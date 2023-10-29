/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.http.javadsl.model;

import static org.apache.pekko.http.impl.util.Util.convertArray;
import static org.apache.pekko.http.impl.util.Util.convertMapToScala;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.apache.pekko.http.ccompat.MapHelpers;
import org.apache.pekko.stream.javadsl.Source;
import scala.collection.immutable.List;
import scala.collection.immutable.Nil$;

/** Constructors for Multipart instances */
public final class Multiparts {
  /**
   * Constructor for `multipart/form-data` content as defined in http://tools.ietf.org/html/rfc2388.
   * All parts must have distinct names. (This is not verified!)
   */
  public static Multipart.FormData createFormDataFromParts(Multipart.FormData.BodyPart... parts) {
    return org.apache.pekko.http.scaladsl.model.Multipart.FormData$.MODULE$.createNonStrict(
        convertArray(parts));
  }
  /**
   * Constructor for `multipart/form-data` content as defined in http://tools.ietf.org/html/rfc2388.
   * All parts must have distinct names. (This is not verified!)
   */
  public static Multipart.FormData createFormDataFromSourceParts(
      Source<Multipart.FormData.BodyPart, ? extends Object> parts) {
    return org.apache.pekko.http.scaladsl.model.Multipart.FormData$.MODULE$.createSource(
        parts.asScala());
  }

  /**
   * Constructor for `multipart/form-data` content as defined in http://tools.ietf.org/html/rfc2388.
   * All parts must have distinct names. (This is not verified!)
   */
  public static Multipart.FormData.Strict createStrictFormDataFromParts(
      Multipart.FormData.BodyPart.Strict... parts) {
    return org.apache.pekko.http.scaladsl.model.Multipart.FormData$.MODULE$.createStrict(
        convertArray(parts));
  }

  /**
   * Constructor for `multipart/form-data` content as defined in http://tools.ietf.org/html/rfc2388.
   * All parts must have distinct names. (This is not verified!)
   */
  public static Multipart.FormData.Strict createFormDataFromFields(
      Map<String, HttpEntity.Strict> fields) {
    return org.apache.pekko.http.scaladsl.model.Multipart.FormData$.MODULE$.createStrict(
        toScalaMap(fields));
  }

  /**
   * Creates a FormData instance that contains a single part backed by the given file.
   *
   * <p>To create an instance with several parts or for multiple files, use
   * `Multiparts.createFormDataFromParts(Multiparts.createFormDataPartFromPath("field1", ...),
   * Multiparts.createFormDataPartFromPath("field2", ...)`
   */
  public static Multipart.FormData createFormDataFromPath(
      String name, ContentType contentType, Path path, int chunkSize) {
    return org.apache.pekko.http.scaladsl.model.Multipart.FormData$.MODULE$.fromPath(
        name, (org.apache.pekko.http.scaladsl.model.ContentType) contentType, path, chunkSize);
  }

  /**
   * Creates a FormData instance that contains a single part backed by the given file.
   *
   * <p>To create an instance with several parts or for multiple files, use
   * `Multiparts.createFormDataFromParts(Multiparts.createFormDataPartFromPath("field1", ...),
   * Multiparts.createFormDataPartFromPath("field2", ...)`
   */
  public static Multipart.FormData createFormDataFromPath(
      String name, ContentType contentType, Path path) {
    return org.apache.pekko.http.scaladsl.model.Multipart.FormData$.MODULE$.fromPath(
        name, (org.apache.pekko.http.scaladsl.model.ContentType) contentType, path, -1);
  }

  /** Creates a BodyPart backed by a file that will be streamed using a FileSource. */
  public static Multipart.FormData.BodyPart createFormDataPartFromPath(
      String name, ContentType contentType, Path path, int chunkSize) {
    return org.apache.pekko.http.scaladsl.model.Multipart$FormData$BodyPart$.MODULE$.fromPath(
        name, (org.apache.pekko.http.scaladsl.model.ContentType) contentType, path, chunkSize);
  }

  /** Creates a BodyPart backed by a file that will be streamed using a FileSource. */
  public static Multipart.FormData.BodyPart createFormDataPartFromPath(
      String name, ContentType contentType, Path path) {
    return org.apache.pekko.http.scaladsl.model.Multipart$FormData$BodyPart$.MODULE$.fromPath(
        name, (org.apache.pekko.http.scaladsl.model.ContentType) contentType, path, -1);
  }

  /** Creates a BodyPart. */
  public static Multipart.FormData.BodyPart createFormDataBodyPart(
      String name, BodyPartEntity entity) {
    List nil = Nil$.MODULE$;
    Map<String, String> additionalDispositionParams = Collections.emptyMap();
    return org.apache.pekko.http.scaladsl.model.Multipart$FormData$BodyPart$Builder$.MODULE$.create(
        name,
        (org.apache.pekko.http.scaladsl.model.BodyPartEntity) entity,
        convertMapToScala(additionalDispositionParams),
        nil);
  }

  /** Creates a BodyPart. */
  public static Multipart.FormData.BodyPart createFormDataBodyPart(
      String name, BodyPartEntity entity, Map<String, String> additionalDispositionParams) {
    List nil = Nil$.MODULE$;
    return org.apache.pekko.http.scaladsl.model.Multipart$FormData$BodyPart$Builder$.MODULE$.create(
        name,
        (org.apache.pekko.http.scaladsl.model.BodyPartEntity) entity,
        convertMapToScala(additionalDispositionParams),
        nil);
  }

  /** Creates a BodyPart. */
  public static Multipart.FormData.BodyPart createFormDataBodyPart(
      String name,
      BodyPartEntity entity,
      Map<String, String> additionalDispositionParams,
      java.util.List<HttpHeader> headers) {
    return org.apache.pekko.http.scaladsl.model.Multipart$FormData$BodyPart$Builder$.MODULE$.create(
        name,
        (org.apache.pekko.http.scaladsl.model.BodyPartEntity) entity,
        convertMapToScala(additionalDispositionParams),
        toScalaSeq(headers));
  }

  /** Creates a BodyPart.Strict. */
  public static Multipart.FormData.BodyPart.Strict createFormDataBodyPartStrict(
      String name, HttpEntity.Strict entity) {
    List nil = Nil$.MODULE$;
    Map<String, String> additionalDispositionParams = Collections.emptyMap();
    return org.apache.pekko.http.scaladsl.model.Multipart$FormData$BodyPart$StrictBuilder$.MODULE$
        .createStrict(
            name,
            (org.apache.pekko.http.scaladsl.model.HttpEntity.Strict) entity,
            convertMapToScala(additionalDispositionParams),
            nil);
  }

  /** Creates a BodyPart.Strict. */
  public static Multipart.FormData.BodyPart.Strict createFormDataBodyPartStrict(
      String name, HttpEntity.Strict entity, Map<String, String> additionalDispositionParams) {
    List nil = Nil$.MODULE$;
    return org.apache.pekko.http.scaladsl.model.Multipart$FormData$BodyPart$StrictBuilder$.MODULE$
        .createStrict(
            name,
            (org.apache.pekko.http.scaladsl.model.HttpEntity.Strict) entity,
            convertMapToScala(additionalDispositionParams),
            nil);
  }

  /** Creates a BodyPart.Strict. */
  public static Multipart.FormData.BodyPart.Strict createFormDataBodyPartStrict(
      String name,
      HttpEntity.Strict entity,
      Map<String, String> additionalDispositionParams,
      java.util.List<HttpHeader> headers) {
    return org.apache.pekko.http.scaladsl.model.Multipart$FormData$BodyPart$StrictBuilder$.MODULE$
        .createStrict(
            name,
            (org.apache.pekko.http.scaladsl.model.HttpEntity.Strict) entity,
            convertMapToScala(additionalDispositionParams),
            toScalaSeq(headers));
  }

  private static scala.collection.immutable.Map<String, HttpEntity.Strict> toScalaMap(
      Map<String, HttpEntity.Strict> map) {
    return MapHelpers.convertMapToScala(map);
  }

  private static scala.collection.Iterable<HttpHeader> toScalaSeq(
      java.util.List<HttpHeader> _headers) {
    return scala.collection.JavaConverters.collectionAsScalaIterableConverter(_headers).asScala();
  }
}
