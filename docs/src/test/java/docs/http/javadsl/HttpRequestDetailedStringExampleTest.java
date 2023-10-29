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

package docs.http.javadsl;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.apache.pekko.http.javadsl.model.*;
import org.apache.pekko.http.javadsl.model.headers.Authorization;
import org.junit.Test;

public class HttpRequestDetailedStringExampleTest {

  // Custom string representation which includes headers
  public String toDetailedString(HttpRequest request) {

    HttpMethod method = request.method();
    Uri uri = request.getUri();
    Iterable<HttpHeader> headers = request.getHeaders();
    RequestEntity entity = request.entity();
    HttpProtocol protocol = request.protocol();

    return String.format("HttpRequest(%s, %s, %s, %s, %s)", method, uri, headers, entity, protocol);
  }

  @Test
  public void headersInCustomStringRepresentation() {

    // An HTTP header containing Personal Identifying Information
    Authorization piiHeader = Authorization.basic("user", "password");

    // An HTTP entity containing Personal Identifying Information
    HttpEntity.Strict piiBody = HttpEntities.create("This body contains information about [user]");

    HttpRequest httpRequestWithHeadersAndBody =
        HttpRequest.create().withEntity(piiBody).withHeaders(Arrays.asList(piiHeader));

    // Our custom string representation includes body and headers string representations...
    assertTrue(toDetailedString(httpRequestWithHeadersAndBody).contains(piiHeader.toString()));
    assertTrue(toDetailedString(httpRequestWithHeadersAndBody).contains(piiBody.toString()));

    // ... while default `toString` doesn't.
    assertFalse(httpRequestWithHeadersAndBody.toString().contains(piiHeader.unsafeToString()));
    assertFalse(httpRequestWithHeadersAndBody.toString().contains(piiBody.getData().utf8String()));
  }
}
