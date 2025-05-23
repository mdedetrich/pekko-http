/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.http.impl.engine.client.pool

import java.time.Instant
import java.util

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.Cancellable
import pekko.annotation.InternalApi
import pekko.dispatch.ExecutionContexts
import pekko.event.LoggingAdapter
import pekko.http.impl.engine.client.PoolFlow.{ RequestContext, ResponseContext }
import pekko.http.impl.engine.client.pool.SlotState._
import pekko.http.impl.util.{ RichHttpRequest, StageLoggingWithOverride, StreamUtils }
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{ headers, HttpEntity, HttpRequest, HttpResponse }
import pekko.http.scaladsl.settings.ConnectionPoolSettings
import pekko.util.OptionVal
import pekko.stream._
import pekko.stream.scaladsl.{ Flow, Keep, Sink, Source }
import pekko.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Failure, Random, Success, Try }

/**
 * Internal API
 *
 * New host connection pool implementation.
 *
 * Backpressure logic of the external interface:
 *
 *  * pool pulls if there's a free slot
 *  * pool buffers outgoing response in a slot and registers them for becoming dispatchable. When a response is pulled
 *    a waiting slot is notified and the response is then dispatched.
 *
 * The implementation is split up into this class which does all the stream-based wiring. It contains a vector of
 * slots that contain the mutable slot state for every slot.
 *
 * The actual state machine logic is handled in separate [[SlotState]] subclasses that interface with the logic through
 * the clean [[SlotContext]] interface.
 */
@InternalApi
private[client] object NewHostConnectionPool {
  def apply(
      connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]],
      settings: ConnectionPoolSettings, log: LoggingAdapter): Flow[RequestContext, ResponseContext, NotUsed] =
    Flow.fromGraph(new HostConnectionPoolStage(connectionFlow, settings, log))

  private final class HostConnectionPoolStage(
      connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]],
      _settings: ConnectionPoolSettings, _log: LoggingAdapter)
      extends GraphStage[FlowShape[RequestContext, ResponseContext]] {
    val requestsIn = Inlet[RequestContext]("HostConnectionPoolStage.requestsIn")
    val responsesOut = Outlet[ResponseContext]("HostConnectionPoolStage.responsesOut")

    override val shape = FlowShape(requestsIn, responsesOut)
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with StageLoggingWithOverride with InHandler with OutHandler { logic =>
        override def logOverride: LoggingAdapter = _log

        setHandlers(requestsIn, responsesOut, this)

        private[this] var lastTimeoutId = 0L

        val slots = Vector.tabulate(_settings.maxConnections)(new Slot(_))
        val slotsWaitingForDispatch: util.Deque[Slot] = new util.ArrayDeque[Slot]
        // To find idle slots fast we need a datastructure which supports
        //   * quick add and remove
        //   * ordering by slot id.
        //
        // The second requirement is a somewhat arbitrary historic decision. But it actually also makes sense because it makes the pool behavior
        // more deterministic: Done like that, the lower-numbered slots will be preferred over higher-numbered ones which will make it more likely
        // that higher-numbered slots will idle out if they are not used, so that the dynamic pool size will adapt itself automatically.
        // The downside is that there's less distribution over different slots/connections when the pool is not fully saturated.
        val idleSlots: util.TreeSet[Slot] = {
          val res = new util.TreeSet[Slot]((o1: Slot, o2: Slot) => java.lang.Integer.compare(o1.slotId, o2.slotId))
          res.addAll(slots.asJava)
          res
        } // fast set to track idle slots
        val retryBuffer: util.Deque[RequestContext] = new util.ArrayDeque[RequestContext]
        var _connectionEmbargo: FiniteDuration = Duration.Zero
        def baseEmbargo: FiniteDuration = _settings.baseConnectionBackoff
        def maxBaseEmbargo: FiniteDuration = _settings.maxConnectionBackoff / 2 // because we'll add a random component of the same size to the base

        override def preStart(): Unit = {
          pull(requestsIn)
          slots.foreach(_.initialize())
        }

        def onPush(): Unit = {
          val nextRequest = grab(requestsIn)
          if (hasIdleSlots) {
            dispatchRequest(nextRequest)
            pullIfNeeded()
          } else // embargo might change state from unconnected -> embargoed losing an idle slot between the pull and the push here
            retryBuffer.addFirst(nextRequest)
        }
        def onPull(): Unit =
          if (!slotsWaitingForDispatch.isEmpty)
            slotsWaitingForDispatch.pollFirst().onResponseDispatchable()
        // else push when next slot becomes dispatchable

        def pullIfNeeded(): Unit =
          if (hasIdleSlots)
            if (!retryBuffer.isEmpty) {
              log.debug("Dispatching request from retryBuffer")
              dispatchRequest(retryBuffer.pollFirst())
            } else if (!hasBeenPulled(requestsIn))
              pull(requestsIn)

        def hasIdleSlots: Boolean = {
          if (log.isDebugEnabled) { // somewhat sneaky way of enabling extra assertions in "debug-mode"
            // Helps debugging if you suspect that idleSlots are not consistent with actual state any more
            val idle = idleSlots.asScala.map(_.slotId).toSet
            val idleAll = slots.filter(_.isIdle).map(_.slotId).toSet
            require(idle == idleAll, s"Managed idle [${idle.mkString(", ")}] != real idle [${idleAll.mkString(", ")}]")
          }
          !idleSlots.isEmpty
        }

        def dispatchResponseResult(req: RequestContext, result: Try[HttpResponse]): Unit =
          if (result.isFailure && req.canBeRetried) {
            log.debug("Request [{}] has {} retries left, retrying...", req.request.debugString, req.retriesLeft)
            retryBuffer.addLast(req.copy(retriesLeft = req.retriesLeft - 1))
          } else
            push(responsesOut, ResponseContext(req, result))

        def dispatchRequest(req: RequestContext): Unit = {
          val slot = idleSlots.first()
          idleSlots.remove(slot)

          slot.debug(s"Dispatching request [${req.request.debugString}]")
          slot.onNewRequest(req)
        }

        def numConnectedSlots: Int = slots.count(_.isConnected)

        def onConnectionAttemptFailed(atPreviousEmbargoLevel: FiniteDuration): Unit = {
          val oldValue = _connectionEmbargo
          _connectionEmbargo match {
            case Duration.Zero            => _connectionEmbargo = baseEmbargo
            case `atPreviousEmbargoLevel` => _connectionEmbargo = (_connectionEmbargo * 2) min maxBaseEmbargo
            case _                        =>
            // don't increase if the embargo level has already changed since the start of the connection attempt
          }
          if (_connectionEmbargo != oldValue) {
            log.debug(
              s"Connection attempt failed. Backing off new connection attempts for at least ${_connectionEmbargo}.")
            slots.foreach(_.onNewConnectionEmbargo(_connectionEmbargo))
          }
        }
        def onConnectionAttemptSucceeded(): Unit = _connectionEmbargo = Duration.Zero
        def currentEmbargo: FiniteDuration = _connectionEmbargo

        class Event[T](val name: String, val transition: (SlotState, Slot, T) => SlotState) {
          def preApply(t: T): Event[Unit] = new Event(name, (state, slot, _) => transition(state, slot, t))
          override def toString: String = s"Event($name)"
        }
        object Event {
          val onPreConnect = event0("onPreConnect", _.onPreConnect(_))
          val onConnectionAttemptSucceeded =
            event[Http.OutgoingConnection]("onConnectionAttemptSucceeded", _.onConnectionAttemptSucceeded(_, _))
          val onConnectionAttemptFailed =
            event[Throwable]("onConnectionAttemptFailed", _.onConnectionAttemptFailed(_, _))

          val onNewConnectionEmbargo = event[FiniteDuration]("onNewConnectionEmbargo", _.onNewConnectionEmbargo(_, _))

          val onNewRequest = event[RequestContext]("onNewRequest", _.onNewRequest(_, _))

          val onRequestDispatched = event0("onRequestDispatched", _.onRequestDispatched(_))
          val onRequestEntityCompleted = event0("onRequestEntityCompleted", _.onRequestEntityCompleted(_))
          val onRequestEntityFailed = event[Throwable]("onRequestEntityFailed", _.onRequestEntityFailed(_, _))

          val onResponseReceived = event[HttpResponse]("onResponseReceived", _.onResponseReceived(_, _))
          val onResponseDispatchable = event0("onResponseDispatchable", _.onResponseDispatchable(_))
          val onResponseEntitySubscribed = event0("onResponseEntitySubscribed", _.onResponseEntitySubscribed(_))
          val onResponseEntityCompleted = event0("onResponseEntityCompleted", _.onResponseEntityCompleted(_))
          val onResponseEntityFailed = event[Throwable]("onResponseEntityFailed", _.onResponseEntityFailed(_, _))

          val onConnectionCompleted = event0("onConnectionCompleted", _.onConnectionCompleted(_))
          val onConnectionFailed = event[Throwable]("onConnectionFailed", _.onConnectionFailed(_, _))

          val onTimeout = event0("onTimeout", _.onTimeout(_))

          private def event0(name: String, transition: (SlotState, Slot) => SlotState): Event[Unit] =
            new Event(name, (state, slot, _) => transition(state, slot))
          private def event[T](name: String, transition: (SlotState, Slot, T) => SlotState): Event[T] =
            new Event[T](name, transition)
        }

        protected trait StateHandling {
          private[this] var _state: SlotState = Unconnected
          private[this] var _changedIntoThisStateNanos: Long = System.nanoTime()

          def changedIntoThisStateNanos: Long = _changedIntoThisStateNanos
          def state: SlotState = _state
          def state_=(newState: SlotState): Unit = {
            _state = newState
            _changedIntoThisStateNanos = System.nanoTime()
          }
        }

        final class Slot(val slotId: Int) extends SlotContext with StateHandling {
          private[this] var currentTimeoutId: Long = -1
          private[this] var currentTimeout: Cancellable = _
          private[this] var disconnectAt: Long = Long.MaxValue
          private[this] var isEnqueuedForResponseDispatch: Boolean = false

          private[this] var connection: SlotConnection = _
          def isIdle: Boolean = state.isIdle
          def isConnected: Boolean = state.isConnected
          def shutdown(): Unit = {
            // if the connection is idle, we just complete it regularly, otherwise, we forcibly tear it down
            // with an error (which will be logged in OutgoingConnectionBlueprint, see `mapError` there).
            val reason =
              if (isIdle) None
              else Some(new IllegalStateException("Pool slot was shut down") with NoStackTrace)

            closeConnection(reason)

            state.onShutdown(this)
          }

          def initialize(): Unit =
            if (slotId < settings.minConnections)
              updateState(Event.onPreConnect)

          def onConnectionAttemptSucceeded(outgoing: Http.OutgoingConnection): Unit =
            updateState(Event.onConnectionAttemptSucceeded, outgoing)

          def onConnectionAttemptFailed(cause: Throwable): Unit =
            updateState(Event.onConnectionAttemptFailed, cause)

          def onNewConnectionEmbargo(embargo: FiniteDuration): Unit =
            updateState(Event.onNewConnectionEmbargo, embargo)

          def onNewRequest(req: RequestContext): Unit =
            updateState(Event.onNewRequest, req)

          def onRequestEntityCompleted(): Unit =
            updateState(Event.onRequestEntityCompleted)
          def onRequestEntityFailed(cause: Throwable): Unit =
            updateState(Event.onRequestEntityFailed, cause)

          def onResponseReceived(response: HttpResponse): Unit =
            updateState(Event.onResponseReceived, response)

          def onResponseDispatchable(): Unit = {
            isEnqueuedForResponseDispatch = false
            updateState(Event.onResponseDispatchable)
          }

          def onResponseEntitySubscribed(): Unit =
            updateState(Event.onResponseEntitySubscribed)
          def onResponseEntityCompleted(): Unit =
            updateState(Event.onResponseEntityCompleted)
          def onResponseEntityFailed(cause: Throwable): Unit =
            updateState(Event.onResponseEntityFailed, cause)

          def onConnectionCompleted(): Unit =
            updateState(Event.onConnectionCompleted)
          def onConnectionFailed(cause: Throwable): Unit =
            updateState(Event.onConnectionFailed, cause)

          protected def updateState(event: Event[Unit]): Unit = updateState(event, ())
          protected def updateState[T](event: Event[T], arg: T): Unit = {
            def runOneTransition[U](event: Event[U], arg: U): OptionVal[Event[Unit]] =
              try {
                cancelCurrentTimeout()

                val previousState = state
                val timeInState = System.nanoTime() - changedIntoThisStateNanos

                debug(s"Before event [${event.name}] In state [${state.name}] for [${timeInState / 1000000} ms]")
                state = event.transition(state, this, arg)
                require(state != Unconnected, "Slot must not change to Unconnected state") // Use ToBeClosed or Failed instead from state impls
                debug(s"After event [${event.name}] State change [${previousState.name}] -> [${state.name}]")

                state.stateTimeout match {
                  case d: FiniteDuration =>
                    val myTimeoutId = createNewTimeoutId()
                    currentTimeoutId = myTimeoutId
                    currentTimeout =
                      materializer.scheduleOnce(d,
                        safeRunnable {
                          if (myTimeoutId == currentTimeoutId) { // timeout may race with state changes, ignore if timeout isn't current any more
                            debug(s"Slot timeout after $d")
                            updateState(Event.onTimeout)
                          }
                        })
                  case _ => // no timeout set, nothing to do
                }

                if (connection != null && state.isInstanceOf[ShouldCloseConnectionState]) {
                  debug(s"State change from [${previousState.name}] to [$state]. Closing the existing connection.")
                  closeConnection(state.asInstanceOf[ShouldCloseConnectionState].failure)
                  state = Unconnected
                }

                if (!previousState.isIdle && state.isIdle && !(state == Unconnected && currentEmbargo != Duration.Zero)) {
                  debug("Slot became idle... Trying to pull")
                  idleSlots.add(this)
                  pullIfNeeded()
                } else if (previousState.isIdle && !state.isIdle)
                  idleSlots.remove(this)

                state match {
                  case PushingRequestToConnection(ctx) =>
                    connection.pushRequest(ctx.request)
                    OptionVal.Some(Event.onRequestDispatched)

                  case _: WaitingForResponseDispatch =>
                    if (isAvailable(responsesOut)) OptionVal.Some(Event.onResponseDispatchable)
                    else {
                      if (!isEnqueuedForResponseDispatch) {
                        isEnqueuedForResponseDispatch = true
                        logic.slotsWaitingForDispatch.addLast(this)
                      }
                      OptionVal.None
                    }

                  case WaitingForResponseEntitySubscription(req, res, _, _) if hasNoEntityStream(res) =>
                    // the connection cannot drive these for a strict entity so we have to loop ourselves
                    OptionVal.Some(Event.onResponseEntitySubscribed)
                  case WaitingForEndOfResponseEntity(req, res, _) if hasNoEntityStream(res) =>
                    // the connection cannot drive these for a strict entity so we have to loop ourselves
                    OptionVal.Some(Event.onResponseEntityCompleted)
                  case Unconnected if currentEmbargo != Duration.Zero =>
                    OptionVal.Some(Event.onNewConnectionEmbargo.preApply(currentEmbargo))
                  // numConnectedSlots might be slow for big numbers of connections, so avoid calling if minConnections feature is disabled
                  case s
                      if !s.isConnected && s.isIdle && settings.minConnections > 0 && numConnectedSlots < settings.minConnections =>
                    debug(s"Preconnecting because number of connected slots fell down to $numConnectedSlots")
                    OptionVal.Some(Event.onPreConnect)
                  case _ => OptionVal.None
                }

                // put additional bookkeeping here (like keeping track of idle connections)
              } catch {
                case NonFatal(ex) =>
                  error(
                    ex,
                    "Slot execution failed. That's probably a bug. Please file a bug at https://github.com/apache/pekko-http/issues. Slot is restarted.")

                  try {
                    cancelCurrentTimeout()
                    closeConnection(Some(ex))
                    state.onShutdown(this)
                    OptionVal.None
                  } catch {
                    case NonFatal(ex) =>
                      error(ex, "Shutting down slot after error failed.")
                  }
                  logic.slotsWaitingForDispatch.remove(this)
                  idleSlots.add(this)
                  state = Unconnected
                  OptionVal.Some(Event.onPreConnect)
              }

            /** Run a loop of state transitions */
            /* @tailrec (does not work for some reason?) */
            def loop[U](event: Event[U], arg: U, remainingIterations: Int): Unit =
              if (remainingIterations > 0)
                runOneTransition(event, arg) match {
                  case OptionVal.None       => // no more changes
                  case OptionVal.Some(next) => loop(next, (), remainingIterations - 1)
                }
              else
                throw new IllegalStateException(
                  "State transition loop exceeded maximum number of loops. The pool will shutdown itself. " +
                  "That's probably a bug. Please file a bug at https://github.com/apache/pekko-http/issues. ")

            loop(event, arg, 10)
          }

          override def log: LoggingAdapter = _log
          override def prefixString: String = s"[$slotId (${state.productPrefix})]"

          def error(cause: Throwable, msg: String): Unit =
            if (log.isErrorEnabled)
              log.error(cause, s"[{} ({})] $msg", slotId, state.productPrefix)

          def settings: ConnectionPoolSettings = _settings

          private lazy val keepAliveDurationFuzziness: () => Long = {
            val random = new Random()
            val max = math.max(settings.maxConnectionLifetime.toMillis / 10, 2)
            () => random.nextLong() % max
          }
          def openConnection(): Unit = {
            if (connection ne null)
              throw new IllegalStateException("Cannot open connection when slot still has an open connection")

            connection = logic.openConnection(this)
            if (settings.maxConnectionLifetime.isFinite) {
              disconnectAt =
                Instant.now().toEpochMilli + settings.maxConnectionLifetime.toMillis + keepAliveDurationFuzziness()
            }
          }

          def closeConnection(failure: Option[Throwable]): Unit =
            if (connection ne null) {
              connection.close(failure)
              connection = null
            }
          def isCurrentConnection(conn: SlotConnection): Boolean = connection eq conn
          def isConnectionClosed: Boolean = (connection eq null) || connection.isClosed

          def dispatchResponseResult(req: RequestContext, result: Try[HttpResponse]): Unit =
            logic.dispatchResponseResult(req, result)

          def willCloseAfter(res: HttpResponse): Boolean = {
            logic.willClose(res) || keepAliveTimeApplies()
          }

          def keepAliveTimeApplies(): Boolean = if (settings.maxConnectionLifetime.isFinite) {
            Instant.now().toEpochMilli > disconnectAt
          } else false

          private[this] def cancelCurrentTimeout(): Unit =
            if (currentTimeout ne null) {
              currentTimeout.cancel()
              currentTimeout = null
              currentTimeoutId = -1
            }
        }
        private def hasNoEntityStream(response: HttpResponse): Boolean =
          response.entity match {
            case _: HttpEntity.Strict        => true
            case HttpEntity.Default(_, _, e) => e == Source.empty
            case e                           => e.isKnownEmpty
          }

        final class SlotConnection(
            _slot: Slot,
            requestOut: SubSourceOutlet[HttpRequest],
            responseIn: SubSinkInlet[HttpResponse]) extends InHandler with OutHandler { connection =>
          var ongoingResponseEntity: Option[HttpEntity] = None
          var ongoingResponseEntityKillSwitch: Option[KillSwitch] = None
          var connectionEstablished: Boolean = false

          /** Will only be executed if this connection is still the current connection for its slot */
          def withSlot(f: Slot => Unit): Unit =
            if (_slot.isCurrentConnection(this)) f(_slot)

          def pushRequest(request: HttpRequest): Unit = {
            val newRequest =
              request.entity match {
                case _: HttpEntity.Strict => request
                case e =>
                  val (newEntity, entityComplete) = HttpEntity.captureTermination(request.entity)
                  entityComplete.onComplete(safely {
                    case Success(_)     => withSlot(_.onRequestEntityCompleted())
                    case Failure(cause) => withSlot(_.onRequestEntityFailed(cause))
                  })(ExecutionContexts.parasitic)
                  request.withEntity(newEntity)
              }

            emitRequest(newRequest)
          }

          /**
           * If regular is true connection is closed, otherwise, it is aborted.
           *
           * A connection should be closed regularly after a request/response with `Connection: close` has been completed.
           * A connection should be aborted after failures.
           */
          def close(failure: Option[Throwable]): Unit = {
            failure match {
              case None          => requestOut.complete()
              case Some(failure) => requestOut.fail(failure)
            }

            responseIn.cancel()

            val exception =
              failure.getOrElse(new IllegalStateException("Connection was closed while response was still in-flight"))
            ongoingResponseEntity.foreach(_.dataBytes.runWith(Sink.cancelled)(subFusingMaterializer))
            ongoingResponseEntityKillSwitch.foreach(_.abort(exception))
          }
          def isClosed: Boolean = requestOut.isClosed || responseIn.isClosed

          def onPush(): Unit = {
            val response = responseIn.grab()

            withSlot(_.debug("Received response")) // FIXME: add abbreviated info

            // if hasNoEntityStream == true, we don't expect onResponseEntity... events in the state machine above
            if (hasNoEntityStream(response)) withSlot(_.onResponseReceived(response))
            else {
              val (newEntity, StreamUtils.StreamControl(entitySubscribed, entityComplete, entityKillSwitch)) =
                StreamUtils.transformEntityStream(response.entity, StreamUtils.CaptureMaterializationAndTerminationOp)

              ongoingResponseEntity = Some(response.entity)
              ongoingResponseEntityKillSwitch = entityKillSwitch

              entitySubscribed.onComplete(safely {
                case Success(_) =>
                  withSlot(_.onResponseEntitySubscribed())

                  entityComplete.onComplete {
                    safely { res =>
                      res match {
                        case Success(_)     => withSlot(_.onResponseEntityCompleted())
                        case Failure(cause) => withSlot(_.onResponseEntityFailed(cause))
                      }
                      ongoingResponseEntity = None
                      ongoingResponseEntityKillSwitch = None
                    }
                  }(ExecutionContexts.parasitic)
                case Failure(_) => throw new IllegalStateException("Should never fail")
              })(ExecutionContexts.parasitic)

              withSlot(_.onResponseReceived(response.withEntity(newEntity)))
            }

            if (!responseIn.isClosed) responseIn.pull()
          }

          override def onUpstreamFinish(): Unit =
            withSlot { slot =>
              slot.debug("Connection completed")
              slot.onConnectionCompleted()
            }
          override def onUpstreamFailure(ex: Throwable): Unit =
            withSlot { slot =>
              if (connectionEstablished) {
                slot.debug("Connection failed")
                slot.onConnectionFailed(ex)
              }
              // otherwise, rely on connection.onComplete to fail below
              // (connection error is sent through matValue future and through the stream)
            }

          def onPull(): Unit = () // emitRequests makes sure not to push too early

          override def onDownstreamFinish(cause: Throwable): Unit =
            withSlot { slot =>
              slot.debug("Connection cancelled")
              // Let's use StreamTcpException for now.
              // FIXME: after moving to Akka 2.6.x only, we can use cancelation cause propagation which would probably also report
              // a StreamTcpException here
              slot.onConnectionFailed(new StreamTcpException(
                "Connection was cancelled (caused by a failure of the underlying HTTP connection)"))
              responseIn.cancel()
            }

          /** Helper that makes sure requestOut is pulled before pushing */
          private def emitRequest(request: HttpRequest): Unit =
            if (requestOut.isAvailable) requestOut.push(request)
            else
              requestOut.setHandler(new OutHandler {
                def onPull(): Unit = {
                  requestOut.push(request)
                  // Implicit assumption is that `connection` was the previous handler. We would just use the
                  // previous handler if there was a way to get at it... SubSourceOutlet.getHandler seems to be missing.
                  requestOut.setHandler(connection)
                }

                override def onDownstreamFinish(cause: Throwable): Unit = connection.onDownstreamFinish(cause)
              })
        }
        def openConnection(slot: Slot): SlotConnection = {
          val currentEmbargoLevel = currentEmbargo

          val requestOut = new SubSourceOutlet[HttpRequest](s"PoolSlot[${slot.slotId}].requestOut")
          val responseIn = new SubSinkInlet[HttpResponse](s"PoolSlot[${slot.slotId}].responseIn")
          responseIn.pull()

          slot.debug("Establishing connection")
          val connection =
            Source.fromGraph(requestOut.source)
              .viaMat(connectionFlow)(Keep.right)
              .to(responseIn.sink)
              .run()(subFusingMaterializer)

          val slotCon = new SlotConnection(slot, requestOut, responseIn)
          requestOut.setHandler(slotCon)
          responseIn.setHandler(slotCon)

          connection.onComplete(safely {
            case Success(outgoingConnection) =>
              slotCon.withSlot { sl =>
                slotCon.connectionEstablished = true
                slot.debug("Connection attempt succeeded")
                onConnectionAttemptSucceeded()
                sl.onConnectionAttemptSucceeded(outgoingConnection)
              }
            case Failure(cause) =>
              slotCon.withSlot { sl =>
                slot.debug(s"Connection attempt failed with ${cause.getMessage}")
                onConnectionAttemptFailed(currentEmbargoLevel)
                sl.onConnectionAttemptFailed(cause)
              }
          })(ExecutionContexts.parasitic)

          slotCon
        }

        override def onUpstreamFinish(): Unit = {
          log.debug("Pool upstream was completed")
          super.onUpstreamFinish()
        }
        override def onUpstreamFailure(ex: Throwable): Unit = {
          log.debug("Pool upstream failed with {}", ex)
          super.onUpstreamFailure(ex)
        }
        override def onDownstreamFinish(cause: Throwable): Unit = {
          log.debug("Pool downstream cancelled")
          super.onDownstreamFinish(cause)
        }
        override def postStop(): Unit = {
          slots.foreach(_.shutdown())
          log.debug(s"Pool stopped")
        }

        private def willClose(response: HttpResponse): Boolean =
          response.header[headers.Connection].exists(_.hasClose)

        private val safeCallback = getAsyncCallback[() => Unit](f => f())
        private def safely[T, U](f: T => Unit): T => Unit = t => safeCallback.invoke(() => f(t))
        private def safeRunnable(body: => Unit): Runnable =
          new Runnable {
            def run(): Unit = safeCallback.invoke(() => body)
          }
        private def createNewTimeoutId(): Long = {
          lastTimeoutId += 1
          lastTimeoutId
        }
      }
  }
}
