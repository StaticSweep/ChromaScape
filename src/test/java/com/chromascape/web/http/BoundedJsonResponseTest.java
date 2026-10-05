package com.chromascape.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedJsonResponseTest {
  @Test
  void stalledBodyIsCancelledByDeadline() throws Exception {
    HttpResponse.ResponseInfo info =
        new HttpResponse.ResponseInfo() {
          public int statusCode() {
            return 200;
          }

          public HttpHeaders headers() {
            return HttpHeaders.of(
                java.util.Map.of("Content-Type", java.util.List.of("application/json")),
                (a, b) -> true);
          }

          public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
          }
        };
    var subscriber = BoundedJsonResponse.handler(100).apply(info);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    subscriber.onSubscribe(
        new java.util.concurrent.Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {
            cancelled.set(true);
          }
        });
    assertThrows(
        java.util.concurrent.ExecutionException.class,
        () ->
            subscriber
                .getBody()
                .toCompletableFuture()
                .get(7, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(cancelled.get());
  }

  @Test
  void rejectsOversizedNonJsonAndRedirectResponses() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          String p = e.getRequestURI().getPath();
          byte[] b = (p.equals("/big") ? "x".repeat(4096) : "{}").getBytes();
          e.getResponseHeaders()
              .set("Content-Type", p.equals("/text") ? "text/plain" : "application/json");
          e.sendResponseHeaders(p.equals("/redirect") ? 302 : 200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.start();
    try {
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      for (String p : java.util.List.of("/big", "/text", "/redirect")) {
        var req =
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + p))
                .timeout(Duration.ofSeconds(2))
                .build();
        assertThrows(
            java.io.IOException.class, () -> client.send(req, BoundedJsonResponse.handler(100)), p);
      }
      assertEquals(
          "{}",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ok"))
                      .build(),
                  BoundedJsonResponse.handler(100))
              .body());
    } finally {
      server.stop(0);
    }
  }
}
