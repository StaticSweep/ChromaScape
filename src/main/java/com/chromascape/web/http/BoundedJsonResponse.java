package com.chromascape.web.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Completes within the request deadline, rejects redirects/non-JSON, and caps bytes before
 * decoding.
 */
public final class BoundedJsonResponse {
  private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES =
      new java.util.concurrent.ScheduledThreadPoolExecutor(
          1,
          r -> {
            Thread t = new Thread(r, "executor-response-deadlines");
            t.setDaemon(true);
            return t;
          });

  static {
    DEADLINES.setRemoveOnCancelPolicy(true);
  }

  private BoundedJsonResponse() {}

  public static HttpResponse.BodyHandler<String> handler(int maxBytes) {
    if (maxBytes < 1) throw new IllegalArgumentException("positive response limit required");
    return info ->
        new HttpResponse.BodySubscriber<>() {
          final CompletableFuture<String> result = new CompletableFuture<>();
          final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          Flow.Subscription subscription;

          public CompletionStage<String> getBody() {
            return result;
          }

          public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            var deadline =
                DEADLINES.schedule(
                    () -> {
                      s.cancel();
                      result.completeExceptionally(
                          new IllegalStateException("EXECUTOR_RESPONSE_DEADLINE"));
                    },
                    5,
                    java.util.concurrent.TimeUnit.SECONDS);
            result.whenComplete((value, error) -> deadline.cancel(false));
            String type =
                info.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .split(";", 2)[0]
                    .trim()
                    .toLowerCase(java.util.Locale.ROOT);
            if ((info.statusCode() >= 300 && info.statusCode() < 400)
                || !(type.equals("application/json")
                    || type.matches("application/[a-z0-9.+-]+\\+json"))) {
              s.cancel();
              result.completeExceptionally(new IllegalStateException("EXECUTOR_RESPONSE_REJECTED"));
            } else s.request(1);
          }

          public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer b : buffers) {
              if (b.remaining() > maxBytes - bytes.size()) {
                subscription.cancel();
                result.completeExceptionally(
                    new IllegalStateException("EXECUTOR_RESPONSE_TOO_LARGE"));
                return;
              }
              byte[] chunk = new byte[b.remaining()];
              b.get(chunk);
              bytes.writeBytes(chunk);
            }
            subscription.request(1);
          }

          public void onError(Throwable error) {
            result.completeExceptionally(error);
          }

          public void onComplete() {
            result.complete(bytes.toString(StandardCharsets.UTF_8));
          }
        };
  }
}
