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

package org.apache.pekko.http.impl.engine.client

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ ServerSocketChannel, SocketChannel }
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.event.Logging
import pekko.http.impl.engine.client.PoolMasterActor.{ PoolInterfaceRunning, PoolInterfaceStatus, PoolStatus }
import pekko.http.impl.engine.server.ServerTerminator
import pekko.http.impl.engine.ws.ByteStringSinkProbe
import pekko.http.impl.util._
import pekko.http.scaladsl.Http.{
  HostConnectionPool,
  HostConnectionPoolImpl,
  HttpServerTerminated,
  HttpTerminated,
  OutgoingConnection
}
import pekko.http.scaladsl.model.HttpEntity.{ Chunk, ChunkStreamPart, Chunked, LastChunk }
import pekko.http.scaladsl.model.{ HttpEntity, _ }
import pekko.http.scaladsl.model.headers._
import pekko.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import pekko.http.scaladsl.{ ClientTransport, ConnectionContext, Http }
import pekko.stream.Attributes
import pekko.stream.{ OverflowStrategy, QueueOfferResult }
import pekko.stream.TLSProtocol._
import pekko.stream.scaladsl._
import pekko.stream.testkit.Utils.TE
import pekko.stream.testkit.{ TestPublisher, TestSubscriber }
import pekko.testkit._
import pekko.util.ByteString

import scala.collection.immutable
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

class NewConnectionPoolSpec extends PekkoSpecWithMaterializer("""
    pekko.io.tcp.windows-connection-abort-workaround-enabled = auto
    pekko.io.tcp.trace-logging = off
    pekko.test.single-expect-default = 5000 # timeout for checks, adjust as necessary, set here to 5s
    pekko.scheduler.tick-duration = 1ms     # to make race conditions in Pool idle-timeout more likely
    pekko.http.client.log-unencrypted-network-bytes = 200
                                          """) { testSuite =>

  implicit class WithPoolStatus(val poolId: PoolId) {
    def poolStatus(): Future[Option[PoolInterfaceStatus]] = {
      val statusPromise = Promise[Option[PoolInterfaceStatus]]()
      Http().poolMaster.ref ! PoolStatus(poolId, statusPromise)
      statusPromise.future
    }
  }
  implicit class WithPoolId(val hcp: HostConnectionPool) {
    def poolId: PoolId = hcp.asInstanceOf[HostConnectionPoolImpl].poolId
  }

  // FIXME: Extract into proper util class to be reusable
  lazy val ConnectionResetByPeerMessage: String = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.socket.bind(new InetSocketAddress("127.0.0.1", 0))
    try {
      val clientSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", serverSocket.socket().getLocalPort))
      @volatile var serverSideChannel: SocketChannel = null
      awaitCond {
        serverSideChannel = serverSocket.accept()
        serverSideChannel != null
      }
      serverSideChannel.socket.setSoLinger(true, 0)
      serverSideChannel.close()
      clientSocket.read(ByteBuffer.allocate(1))
      null
    } catch {
      case NonFatal(e) => e.getMessage
    }
  }

  "The host-level client infrastructure" should {

    "complete a simple request/response cycle" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/") -> 42)

      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(response), 42) = responseOut.expectNext()
      response.headers should contain(RawHeader("Req-Host", s"$serverHostName:$serverPort"))
    }

    "open a second connection if the first one is loaded" in new TestSetup {

      case class Request(request: HttpRequest, connNo: Int, promise: Promise[HttpResponse]) {
        def handle(): Unit = promise.success(testServerHandler(connNo)(request))
      }
      override def asyncTestServerHandler(connNr: Int): HttpRequest => Future[HttpResponse] = { req =>
        val p = Promise[HttpResponse]()
        testActor ! Request(req, connNr, p)
        p.future
      }

      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int]()

      responseOutSub.request(2)

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/b") -> 43)

      acceptIncomingConnection()
      acceptIncomingConnection()

      expectMsgType[Request].handle()
      val r1 = responseOut.expectNext()
      expectMsgType[Request].handle()
      val r2 = responseOut.expectNext()

      Seq(r1, r2).foreach {
        case (Success(x), 42) => requestUri(x) should endWith("/a")
        case (Success(x), 43) => requestUri(x) should endWith("/b")
        case x                => fail(x.toString)
      }
      (Seq(r1, r2).map(t => connNr(t._1.get)) should contain).allOf(1, 2)
    }

    "open a second connection if the request on the first one is dispatch but not yet completed" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int]()

      val responseEntityPub = TestPublisher.probe[ByteString]()

      override def testServerHandler(connNr: Int): HttpRequest => HttpResponse = {
        case request @ HttpRequest(_, Uri.Path("/a"), _, _, _) =>
          val entity =
            HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.fromPublisher(responseEntityPub))
          super.testServerHandler(connNr)(request).withEntity(entity)
        case x => super.testServerHandler(connNr)(x)
      }

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(r1), 42) = responseOut.expectNext()
      val responseEntityProbe = TestSubscriber.probe[ByteString]()
      r1.entity.dataBytes.runWith(Sink.fromSubscriber(responseEntityProbe))
      responseEntityProbe.expectSubscription().request(2)
      responseEntityPub.sendNext(ByteString("YEAH"))
      responseEntityProbe.expectNext(ByteString("YEAH"))

      requestIn.sendNext(HttpRequest(uri = "/b") -> 43)
      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(r2), 43) = responseOut.expectNext()
      connNr(r2) shouldEqual 2
    }

    "automatically open a new connection after configured max-connection-lifetime elapsed" in new TestSetup(
      autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int](
        maxConnections = 1,
        minConnections = 1,
        idleTimeout = 10.minutes,
        maxConnectionLifetime = 1.seconds)

      awaitCond({
          requestIn.sendNext(HttpRequest(uri = "/uri") -> 42)
          responseOutSub.request(1)
          val (Success(response), 42) = responseOut.expectNext()
          connNr(response) == 2
        }, max = 2.seconds)
    }

    "not open a second connection if there is an idle one available" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(response1), 42) = responseOut.expectNext()
      connNr(response1) shouldEqual 1

      // prone to race conditions: that the response was delivered does not necessarily mean that the pool infrastructure
      // has actually seen that the response is completely done, especially in the legacy implementation
      Thread.sleep(100)

      requestIn.sendNext(HttpRequest(uri = "/b") -> 43)
      responseOutSub.request(1)
      val (Success(response2), 43) = responseOut.expectNext()
      connNr(response2) shouldEqual 1
    }

    "be able to handle 500 requests against the test server" in new TestSetup {
      val settings = ConnectionPoolSettings(system).withMaxConnections(4).withPipeliningLimit(2)
      val poolFlow = Http().cachedHostConnectionPool[Int](serverHostName, serverPort, settings = settings)

      val N = 500
      val requestIds = Source.fromIterator(() => Iterator.from(1)).take(N)
      val idSum = requestIds.map(id => HttpRequest(uri = s"/r$id") -> id).via(poolFlow).map {
        case (Success(response), id) =>
          requestUri(response) should endWith(s"/r$id")
          id
        case x => fail(x.toString)
      }.runFold(0)(_ + _)

      acceptIncomingConnection()
      acceptIncomingConnection()
      acceptIncomingConnection()
      acceptIncomingConnection()

      Await.result(idSum, 10.seconds.dilated) shouldEqual N * (N + 1) / 2
    }

    "surface connection-level errors" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int](maxRetries = 0)

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash")) sys.error("CRASH BOOM BANG") else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) => requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Failure(x), 43) => x.getMessage should include(ConnectionResetByPeerMessage) }
    }

    "surface connection-level and stream-level errors while receiving response entity" in new TestSetup(
      autoAccept = true) {

      val errorOnConnection1 = Promise[ByteString]()

      val crashingEntity =
        Source.fromIterator(() => Iterator.fill(10)(ByteString("abc")))
          .concat(Source.future(errorOnConnection1.future))
          .log("response-entity-stream")
          .addAttributes(Attributes.logLevels(Logging.InfoLevel, Logging.InfoLevel, Logging.InfoLevel))

      val laterHandler = Promise[(HttpRequest => Future[HttpResponse]) => Unit]()

      override def asyncTestServerHandler(connNr: Int): HttpRequest => Future[HttpResponse] = { req =>
        req.discardEntityBytes()
        if (req.uri.path.toString contains "a")
          Future.successful(HttpResponse(200,
            entity = HttpEntity.CloseDelimited(ContentTypes.`application/octet-stream`, crashingEntity)))
        else {
          val response = Promise[HttpResponse]()
          laterHandler.success(handler => response.completeWith(handler(req)))
          response.future
        }
      }

      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int](maxRetries = 0)

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      responseOutSub.request(2)

      val (Success(response1), _) = responseOut.expectNext()

      val probe1 = ByteStringSinkProbe()
      response1.entity.dataBytes.runWith(probe1.sink)

      probe1.expectBytes(ByteString("abc" * 10))

      // send second request
      requestIn.sendNext(HttpRequest(uri = "/b") -> 43)

      // ensure that server has seen request 2
      val handlerSetter = Await.result(laterHandler.future, 1.second.dilated)

      // now fail the first one, expecting the server-side error message
      EventFilter[RuntimeException](message =
          "Response stream for [GET /a] failed with 'Woops, slipped on a slippery slope.'. Aborting connection.",
        occurrences = 1).intercept {
        errorOnConnection1.failure(new RuntimeException("Woops, slipped on a slippery slope."))
      }

      // expect "reset by peer" on the client side
      probe1.expectError()

      // waiting for error to trigger connection pool failure
      Thread.sleep(2000)

      // now respond to request 2
      handlerSetter(req => Future.successful(HttpResponse()))

      { val (Success(_), _) = responseOut.expectNext() } // extra braces to avoid warning that TestSetup grows fields
    }

    "retry failed requests" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      // must be lazy to prevent initialization problem because `mapServerSideOutboundRawBytes` might be called before the value is initialized
      lazy val remainingResponsesToKill = new AtomicInteger(1)
      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash") && remainingResponsesToKill.decrementAndGet() >= 0)
          sys.error("CRASH BOOM BANG")
        else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) => requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Success(x), 43) => requestUri(x) should endWith("/crash") }
    }

    "respect the configured `maxRetries` value" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, _) = cachedHostConnectionPool[Int](maxRetries = 4)

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      // must be lazy to prevent initialization problem because `mapServerSideOutboundRawBytes` might be called before the value is initialized
      lazy val remainingResponsesToKill = new AtomicInteger(5)
      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash") && remainingResponsesToKill.decrementAndGet() >= 0)
          sys.error("CRASH BOOM BANG")
        else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) => requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Failure(x), 43) => x.getMessage should include(ConnectionResetByPeerMessage) }
      remainingResponsesToKill.get() shouldEqual 0
    }

    "automatically shutdown after configured timeout periods" in new TestSetup() {
      val (_, _, _, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second)
      val gateway = hcp.poolId
      Await.result(gateway.poolStatus(), 1500.millis.dilated).get shouldBe a[PoolInterfaceRunning]
      awaitCond({ Await.result(gateway.poolStatus(), 1500.millis.dilated).isEmpty }, 2000.millis.dilated)
    }
    "automatically shutdown after configured timeout periods but only after streaming response is finished" in new TestSetup() {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](idleTimeout = 200.millis)
      val gateway = hcp.poolId

      lazy val responseEntityPub = TestPublisher.probe[ByteString]()

      override def testServerHandler(connNr: Int): HttpRequest => HttpResponse = {
        case request @ HttpRequest(_, Uri.Path("/a"), _, _, _) =>
          val entity =
            HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.fromPublisher(responseEntityPub))
          super.testServerHandler(connNr)(request).withEntity(entity)
        case x => super.testServerHandler(connNr)(x)
      }
      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(r1), 42) = responseOut.expectNext()
      val responseEntityProbe = ByteStringSinkProbe()
      r1.entity.dataBytes.runWith(responseEntityProbe.sink)
      responseEntityPub.sendNext(ByteString("Hello"))
      responseEntityProbe.expectUtf8EncodedString("Hello")

      Await.result(gateway.poolStatus(), 1500.millis.dilated).get shouldBe a[PoolInterfaceRunning]
      Thread.sleep(500)

      // afterwards it should still be running because a response is still being delivered
      responseEntityPub.sendNext(ByteString("World"))
      responseEntityProbe.expectUtf8EncodedString("World")
      Await.result(gateway.poolStatus(), 1500.millis.dilated).get shouldBe a[PoolInterfaceRunning]

      // now finish response
      responseEntityPub.sendComplete()
      responseEntityProbe.request(1)
      responseEntityProbe.expectComplete()
      awaitCond(Await.result(gateway.poolStatus(), 1500.millis.dilated).isEmpty, 2000.millis.dilated)
    }

    "transparently restart after idle shutdown" in new TestSetup() {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second)

      val gateway = hcp.poolId
      Await.result(gateway.poolStatus(), 1500.millis.dilated).get shouldBe a[PoolInterfaceRunning]
      awaitCond({ Await.result(gateway.poolStatus(), 1500.millis.dilated).isEmpty }, 2000.millis.dilated)

      requestIn.sendNext(HttpRequest(uri = "/") -> 42)

      responseOutSub.request(1)
      acceptIncomingConnection()

      { val (Success(_), 42) = responseOut.expectNext() } // extra braces to avoid warning that TestSetup grows fields
    }

    "don't drop requests during idle-timeout shutdown" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, _) =
        cachedHostConnectionPool[Int](
          idleTimeout = 1.millisecond, // trigger as many idle-timeouts as possible
          maxConnections = 1,
          pipeliningLimit = 1,
          minConnections = 0)

      (1 to 100).foreach { i =>
        responseOutSub.request(1)
        requestIn.sendNext(HttpRequest(uri = s"/$i") -> i)
        responseOut.expectNext()
        // more than the 1 millisescond idle-timeout but it seemed to trigger the bug quite reliably
        Thread.sleep(2)
      }
    }

    "never close hot connections when minConnections key is given and >0 (minConnections = 1)" in new TestSetup() {
      val close: HttpHeader = Connection("close")

      // for lower bound of one connection
      val minConnection = 1
      val (requestIn, responseOut, responseOutSub, hcpMinConnection) =
        cachedHostConnectionPool[Int](idleTimeout = 100.millis, minConnections = minConnection)
      val gatewayConnection = hcpMinConnection.poolId

      acceptIncomingConnection()
      requestIn.sendNext(HttpRequest(uri = "/minimumslots/1", headers = immutable.Seq(close)) -> 42)
      responseOutSub.request(1)
      responseOut.expectNextN(1)

      condHolds(500.millis.dilated) { () =>
        Await.result(gatewayConnection.poolStatus(), 100.millis.dilated).get shouldBe a[PoolInterfaceRunning]
      }
    }

    "never close hot connections when minConnections key is given and >0 (minConnections = 5)" in new TestSetup(
      autoAccept = true) {
      val close: HttpHeader = Connection("close")

      // for lower bound of five connections
      val minConnections = 5
      val (requestIn, responseOut, responseOutSub, hcpMinConnection) = cachedHostConnectionPool[Int](
        idleTimeout = 100.millis,
        minConnections = minConnections,
        maxConnections = minConnections + 10)

      (1 to 30).foreach { _ => // run a few requests
        (0 until minConnections).foreach { i =>
          requestIn.sendNext(HttpRequest(uri = s"/minimumslots/5/$i", headers = immutable.Seq(close)) -> 42)
        }
        responseOutSub.request(minConnections)
        responseOut.expectNextN(minConnections)
      }

      val gatewayConnections = hcpMinConnection.poolId
      condHolds(1000.millis.dilated) { () =>
        val status = gatewayConnections.poolStatus()
        Await.result(status, 100.millis.dilated).get shouldBe a[PoolInterfaceRunning]
      }
    }

    "shutdown if idle and min connection has been set to 0" in new TestSetup() {
      val (_, _, _, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second, minConnections = 0)
      val gateway = hcp.poolId
      Await.result(gateway.poolStatus(), 1500.millis.dilated).get shouldBe a[PoolInterfaceRunning]
      awaitCond({ Await.result(gateway.poolStatus(), 1500.millis.dilated).isEmpty }, 2000.millis.dilated)
    }

    "use the configured ClientTransport" in new ClientTransportTestSetup {
      def issueRequest(request: HttpRequest, settings: ConnectionPoolSettings): Future[HttpResponse] =
        Source.single(request.withUri(request.uri.toRelative))
          .via(Http().outgoingConnectionUsingContext(
            host = request.uri.authority.host.address, port = request.uri.effectivePort,
            settings = settings.connectionSettings, connectionContext = ConnectionContext.noEncryption()))
          .runWith(Sink.head)
    }
  }

  "The single-request client infrastructure" should {
    class LocalTestSetup extends TestSetup(ServerSettings(system).withRawRequestUriHeader(true), autoAccept = true)

    "transform absolute request URIs into relative URIs plus host header" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort/abc?query#fragment")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second.dilated).headers
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/abc?query"))
      responseHeaders should contain(RawHeader("Req-Host", s"$serverHostName:$serverPort"))
    }

    "support absolute request URIs without path component" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second.dilated).headers
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/"))
    }

    "support absolute request URIs with a double slash path component" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort//foo")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second.dilated).headers
      responseHeaders should contain(RawHeader("Req-Uri", s"http://$serverHostName:$serverPort//foo"))
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "//foo"))
    }

    "produce an error if the request does not have an absolute URI" in {
      val request = HttpRequest(uri = "/foo")
      val responseFuture = Http().singleRequest(request)
      val thrown = the[IllegalUriException] thrownBy Await.result(responseFuture, 1.second.dilated)
      (thrown should have).message(
        "Cannot determine request scheme and target endpoint as HttpMethod(GET) request to /foo doesn't have an absolute URI")
    }

    "use the configured ClientTransport" in new ClientTransportTestSetup {
      def issueRequest(request: HttpRequest, settings: ConnectionPoolSettings): Future[HttpResponse] =
        Http().singleRequest(request, settings = settings)
    }

    "support receiving a response before the request is complete" in new LocalTestSetup {
      val sourceQueuePromise = Promise[SourceQueueWithComplete[ChunkStreamPart]]()
      val source = Source.queue(8, OverflowStrategy.fail).mapMaterializedValue(sourceQueuePromise.success)
      val slowEntity = Chunked(ContentTypes.`text/plain(UTF-8)`, source)
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort/abc?query#fragment", entity = slowEntity)
      val responseFuture = Http().singleRequest(request)
      val sourceQueue = Await.result(sourceQueuePromise.future, 3.seconds)
      val response = Await.result(responseFuture, 1.second.dilated)
      response.headers should contain(RawHeader("Req-Host", s"$serverHostName:$serverPort"))

      sourceQueue.offer(HttpEntity.Chunk("lala")).futureValue should be(QueueOfferResult.Enqueued)
      sourceQueue.offer(LastChunk).futureValue should be(QueueOfferResult.Enqueued)
      sourceQueue.complete()
      val bytes = response.entity.dataBytes.runReduce(_ ++ _)
      Await.result(bytes, 3.seconds) should be(ByteString("lala"))
    }

    /*
     * Currently failing the 'outgoing request' part of the connection may also fail the 'incoming reply' part of the connection.
     * In the future we may want to disconnect those and allow the server we connect to to choose how to handle the failure
     * of the request entity.
     */
    "support receiving a response entity even when the request already failed" ignore new TestSetup(
      ServerSettings(system).withRawRequestUriHeader(true), autoAccept = true) {
      val responseSourceQueuePromise = Promise[SourceQueueWithComplete[ChunkStreamPart]]()

      override def testServerHandler(connNr: Int): HttpRequest => HttpResponse = {
        r =>
          HttpResponse(
            headers = responseHeaders(r, connNr),
            entity = HttpEntity.Chunked(ContentTypes.`application/octet-stream`,
              Source.queue(8, OverflowStrategy.fail).mapMaterializedValue(responseSourceQueuePromise.success)))
      }

      val requestSourceQueuePromise = Promise[SourceQueueWithComplete[_]]()
      val requestSource = Source.queue[HttpEntity.ChunkStreamPart](8, OverflowStrategy.fail)
        .mapMaterializedValue(requestSourceQueuePromise.success)
      val slowRequestEntity = Chunked(ContentTypes.`text/plain(UTF-8)`, requestSource)
      val request =
        HttpRequest(uri = s"http://$serverHostName:$serverPort/abc?query#fragment", entity = slowRequestEntity)
      val responseFuture = Http().singleRequest(request)
      val sourceQueue = Await.result(requestSourceQueuePromise.future, 3.seconds)
      val response = Await.result(responseFuture, 1.second.dilated)
      response.headers should contain(RawHeader("Req-Host", s"$serverHostName:$serverPort"))

      response.entity.isChunked should be(true)
      sourceQueue.fail(TE("Request failed though response was already on its way"))

      val responseQueue = Await.result(responseSourceQueuePromise.future, 3.seconds)
      responseQueue.offer(Chunk("lala"))
      responseQueue.offer(LastChunk)

      val bytes = response.entity.dataBytes.runReduce(_ ++ _)
      Await.result(bytes, 3.seconds) should be(ByteString("lala"))
    }
  }

  "The superPool client infrastructure" should {

    "route incoming requests to the right cached host connection pool" in new TestSetup(autoAccept = true) {
      val serverHostName2 = "localhost"
      val binding = Http().newServerAt(serverHostName2, 0).bindSync(testServerHandler(0))
      val serverPort2 = binding.futureValue.localAddress.getPort

      val (requestIn, responseOut, responseOutSub, _) = superPool[Int]()

      requestIn.sendNext(HttpRequest(uri = s"http://$serverHostName:$serverPort/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = s"http://$serverHostName2:$serverPort2/b") -> 43)

      responseOutSub.request(2)
      Seq(responseOut.expectNext(), responseOut.expectNext()).foreach {
        case (Success(x), 42) => requestUri(x) shouldEqual s"http://$serverHostName:$serverPort/a"
        case (Success(x), 43) => requestUri(x) shouldEqual s"http://$serverHostName2:$serverPort2/b"
        case x                => fail(x.toString)
      }
    }

    "use the configured ClientTransport" in new ClientTransportTestSetup {
      def issueRequest(request: HttpRequest, settings: ConnectionPoolSettings): Future[HttpResponse] =
        Source.single(request)
          .map((_, ()))
          .via(Http().superPool[Unit](settings = settings))
          .map(_._1.get)
          .runWith(Sink.head)
    }
  }

  "be able to handle 500 `Connection: close` requests against the test server" in new TestSetup {
    val settings = ConnectionPoolSettings(system).withMaxConnections(4)
    val poolFlow = Http().cachedHostConnectionPool[Int](serverHostName, serverPort, settings = settings)

    val N = 500
    val requestIds = Source.fromIterator(() => Iterator.from(1)).take(N)
    val idSum =
      requestIds.map(id => HttpRequest(uri = s"/r$id").withHeaders(Connection("close")) -> id).via(poolFlow).map {
        case (Success(response), id) =>
          requestUri(response) should endWith(s"/r$id")
          id
        case x => fail(x.toString)
      }.runFold(0)(_ + _)

    (1 to N).foreach(_ => acceptIncomingConnection())

    Await.result(idSum, 10.seconds.dilated) shouldEqual N * (N + 1) / 2
  }

  "be able to handle 500 requests with connection termination" in new TestSetup(autoAccept = true) {
    def closeHeader(): List[Connection] =
      if (util.Random.nextInt(8) == 0) Connection("close") :: Nil
      else Nil

    override def testServerHandler(connNr: Int): HttpRequest => HttpResponse = { r =>
      val idx = r.uri.path.tail.head.toString
      HttpResponse()
        .withHeaders(RawHeader("Req-Idx", idx) +: responseHeaders(r, connNr))
        .withDefaultHeaders(closeHeader())
    }

    for (pipeliningLimit <- Iterator.from(1).map(math.pow(2, _).toInt).take(4)) {
      val settings = ConnectionPoolSettings(system).withMaxConnections(4).withPipeliningLimit(
        pipeliningLimit).withMaxOpenRequests(4 * pipeliningLimit)
      val poolFlow = Http().cachedHostConnectionPool[Int](serverHostName, serverPort, settings = settings)

      def method() =
        if (util.Random.nextInt(2) == 0) HttpMethods.POST else HttpMethods.GET

      def request(i: Int) =
        HttpRequest(method = method(), headers = closeHeader(), uri = s"/$i") -> i

      try {
        val N = 200
        val (_, idSum) =
          Source.fromIterator(() => Iterator.from(1)).take(N)
            .map(request)
            .viaMat(poolFlow)(Keep.right)
            .map {
              case (Success(response), id) =>
                requestUri(response) should endWith(s"/$id")
                id
              case x => fail(x.toString)
            }.toMat(Sink.fold(0)(_ + _))(Keep.both).run()

        Await.result(idSum, 35.seconds.dilated) shouldEqual N * (N + 1) / 2
      } catch {
        case thr: Throwable =>
          throw new RuntimeException(s"Failed at pipeliningLimit=$pipeliningLimit, poolFlow=$poolFlow", thr)
      }
    }
  }

  class TestSetup(
      serverSettings: ServerSettings = ServerSettings(system),
      autoAccept: Boolean = false) {

    def asyncTestServerHandler(connNr: Int): HttpRequest => Future[HttpResponse] = {
      val handler = testServerHandler(connNr)
      req => Future.successful(handler(req))
    }

    def testServerHandler(connNr: Int): HttpRequest => HttpResponse = {
      r => HttpResponse(headers = responseHeaders(r, connNr), entity = r.entity)
    }

    def responseHeaders(r: HttpRequest, connNr: Int) =
      ConnNrHeader(connNr) +: RawHeader("Req-Uri", r.uri.toString) +: r.headers.map(h =>
        RawHeader("Req-" + h.name, h.value))

    def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString = bytes

    val incomingConnectionCounter = new AtomicInteger
    val incomingConnections = TestSubscriber.manualProbe[Http.IncomingConnection]()
    val (incomingConnectionsSub, serverHostName: String, serverPort: Int) = {
      val rawBytesInjection = BidiFlow.fromFlows(
        Flow[SslTlsOutbound].collect[ByteString] { case SendBytes(x) => mapServerSideOutboundRawBytes(x) }
          .recover { case NoErrorComplete => ByteString.empty },
        Flow[ByteString].map(SessionBytes(null, _)))
      val sink = if (autoAccept) Sink.foreach[Http.IncomingConnection](handleConnection)
      else Sink.fromSubscriber(incomingConnections)

      val binding =
        Tcp(system)
          .bind("localhost", 0, idleTimeout = serverSettings.timeouts.idleTimeout)
          .map { c =>
            val layer = Http().serverLayer(serverSettings, log = log)
            val flow = layer.atop(rawBytesInjection).join(c.flow)
              .mapMaterializedValue(_ =>
                new ServerTerminator {
                  // this is simply a mock, since we do not use termination in these tests anyway
                  def terminate(deadline: FiniteDuration)(implicit ec: ExecutionContext): Future[HttpTerminated] =
                    Future.successful(HttpServerTerminated)
                })
            Http.IncomingConnection(c.localAddress, c.remoteAddress, flow)
          }
          .to(sink)
          .run()
          .awaitResult(3.seconds)

      val sub = if (autoAccept) null else incomingConnections.expectSubscription()
      (sub, binding.localAddress.getHostString, binding.localAddress.getPort)
    }

    def acceptIncomingConnection(): Unit = {
      incomingConnectionsSub.request(1)
      val conn = incomingConnections.expectNext()
      handleConnection(conn)
    }

    private def handleConnection(c: Http.IncomingConnection) =
      c.handleWithAsyncHandler(asyncTestServerHandler(incomingConnectionCounter.incrementAndGet()))

    def cachedHostConnectionPool[T](
        maxConnections: Int = 2,
        minConnections: Int = 0,
        maxRetries: Int = 2,
        maxOpenRequests: Int = 8,
        pipeliningLimit: Int = 1,
        idleTimeout: FiniteDuration = 5.seconds,
        maxConnectionLifetime: Duration = Duration.Inf,
        ccSettings: ClientConnectionSettings = ClientConnectionSettings(system)) = {

      val settings =
        ConnectionPoolSettings(system)
          .withMaxConnections(maxConnections)
          .withMinConnections(minConnections)
          .withMaxRetries(maxRetries)
          .withMaxOpenRequests(maxOpenRequests)
          .withPipeliningLimit(pipeliningLimit)
          .withIdleTimeout(idleTimeout.dilated)
          .withMaxConnectionLifetime(maxConnectionLifetime)
          .withConnectionSettings(ccSettings)

      flowTestBench(
        Http().cachedHostConnectionPool[T](serverHostName, serverPort, settings))
    }

    def superPool[T](
        maxConnections: Int = 2,
        minConnections: Int = 0,
        maxRetries: Int = 2,
        maxOpenRequests: Int = 8,
        pipeliningLimit: Int = 1,
        idleTimeout: FiniteDuration = 5.seconds,
        ccSettings: ClientConnectionSettings = ClientConnectionSettings(system)) = {

      val settings =
        ConnectionPoolSettings(system)
          .withMaxConnections(maxConnections)
          .withMinConnections(minConnections)
          .withMaxRetries(maxRetries)
          .withMaxOpenRequests(maxOpenRequests)
          .withPipeliningLimit(pipeliningLimit)
          .withIdleTimeout(idleTimeout.dilated)
          .withConnectionSettings(ccSettings)
      flowTestBench(Http().superPool[T](settings = settings))
    }

    def flowTestBench[T, Mat](poolFlow: Flow[(HttpRequest, T), (Try[HttpResponse], T), Mat]) = {
      val requestIn = TestPublisher.probe[(HttpRequest, T)]()
      val responseOut = TestSubscriber.manualProbe[(Try[HttpResponse], T)]()
      val hcp = Source.fromPublisher(requestIn).viaMat(poolFlow)(Keep.right).to(Sink.fromSubscriber(responseOut)).run()
      val responseOutSub = responseOut.expectSubscription()
      (requestIn, responseOut, responseOutSub, hcp)
    }

    def connNr(r: HttpResponse): Int = r.headers.find(_.is("conn-nr")).get.value.toInt
    def requestUri(r: HttpResponse): String = r.headers.find(_.is("req-uri")).get.value

    /**
     * Makes sure the given condition "f" holds in the timer period of "in".
     * The given condition function should throw if not met.
     * Note: Execution of "condHolds" will take at least "in" time, so for big "in" it might drain the ime budget for tests.
     */
    def condHolds[T](in: FiniteDuration)(f: () => T): T = {
      val end = System.nanoTime.nanos + in

      var lastR = f()
      while (System.nanoTime.nanos < end) {
        lastR = f()
        Thread.sleep(50)
      }
      lastR
    }
  }

  case class ConnNrHeader(nr: Int) extends CustomHeader {
    def renderInRequests = false
    def renderInResponses = true
    def name = "Conn-Nr"
    def value = nr.toString
  }

  implicit class MustContain[T](specimen: Seq[T]) {
    def mustContainLike(pf: PartialFunction[T, Unit]): Unit =
      specimen.collectFirst(pf).getOrElse(fail(s"None of [${specimen.mkString(", ")}] matched partial function"))
  }

  object NoErrorComplete extends SingletonException

  abstract class ClientTransportTestSetup {
    def issueRequest(request: HttpRequest, settings: ConnectionPoolSettings): Future[HttpResponse]

    class CustomTransport extends ClientTransport {
      val in = ByteStringSinkProbe()
      val out = TestPublisher.probe[ByteString]()
      val promise = Promise[(String, Int, ClientConnectionSettings)]()

      def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(
          implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {
        promise.success((host, port, settings))
        Flow.fromSinkAndSource(in.sink, Source.fromPublisher(out))
          .mapMaterializedValue(_ =>
            Future.successful(Http.OutgoingConnection(InetSocketAddress.createUnresolved("local", 12345),
              InetSocketAddress.createUnresolved(host, port))))
      }
    }

    import system.dispatcher

    val CustomIdleTimeout = 23.hours

    val transport = new CustomTransport
    val poolSettings =
      ConnectionPoolSettings(system)
        .withConnectionSettings(
          ClientConnectionSettings(system).withIdleTimeout(CustomIdleTimeout).withTransport(transport))

    val responseFuture = issueRequest(HttpRequest(uri = "http://example.org/test"), settings = poolSettings)

    val (host, port, settings) = transport.promise.future.awaitResult(10.seconds)
    host should ===("example.org")
    port should ===(80)
    settings.idleTimeout should ===(CustomIdleTimeout)

    transport.in.ensureSubscription()
    transport.in.expectUtf8EncodedString("GET /test HTTP/1.1\r\n")
    transport.out.sendNext(ByteString("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"))

    val response = responseFuture.awaitResult(10.seconds)
    response.status should ===(StatusCodes.OK)
    transport.out.sendNext(ByteString("Hello World!"))
    transport.out.sendComplete()

    response.entity.dataBytes.utf8String.awaitResult(10.seconds) should ===("Hello World!")
  }
}
