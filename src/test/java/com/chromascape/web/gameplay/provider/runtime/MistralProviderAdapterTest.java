package com.chromascape.web.gameplay.provider.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderAdapter.CancelResult;
import com.chromascape.web.gameplay.provider.runtime.ProviderAdapter.ProviderInvocation;
import com.chromascape.web.gameplay.provider.runtime.ProviderAdapter.ProviderTurnResult;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderReadiness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MistralProviderAdapterTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CREDENTIAL = "test-key-123";

  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private String baseUrl;
  private Handler handler;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", this::handle);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void returnsDecisionAndExactUsage() {
    handler = exchange -> respond(exchange, 200, successBody("proposal"));
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.submitted()).isTrue();
    assertThat(result.terminal()).isTrue();
    assertThat(result.failureCode()).isEqualTo(FailureCode.NONE);
    assertThat(result.rawDecision()).isEqualTo("proposal");
    assertThat(result.usage().exact()).isTrue();
    assertThat(result.usage().inputTokens()).isEqualTo(11L);
    assertThat(result.usage().outputTokens()).isEqualTo(7L);
    assertThat(result.events()).contains("http-status:200", "mistral-id:cmpl-1");
  }

  @Test
  void sendsStrictJsonSchemaRequest() throws IOException {
    handler = exchange -> respond(exchange, 200, successBody("proposal"));
    adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576)
        .startTurn(invocation(Instant.now().plusSeconds(30)));

    assertThat(requests).hasSize(1);
    RecordedRequest request = requests.get(0);
    assertThat(request.path()).isEqualTo("/v1/chat/completions");
    assertThat(request.authorization()).isEqualTo("Bearer " + CREDENTIAL);
    JsonNode body = JSON.readTree(request.body());
    assertThat(body.path("model").asText()).isEqualTo("mistral-medium-latest");
    assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
    assertThat(body.path("temperature").asInt()).isZero();
    assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
    assertThat(body.path("response_format").path("json_schema").path("strict").asBoolean())
        .isTrue();
    assertThat(body.path("response_format").path("json_schema").path("schema").isObject()).isTrue();
    assertThat(body.has("tools")).isFalse();
  }

  @Test
  void mapsUnauthorizedToUnavailable() {
    handler = exchange -> respond(exchange, 401, "{\"error\":\"unauthorized\"}");
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.submitted()).isTrue();
    assertThat(result.terminal()).isTrue();
    assertThat(result.failureCode()).isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
  }

  @Test
  void mapsRateLimitToBudgetExhausted() {
    handler = exchange -> respond(exchange, 429, "{\"error\":\"rate limited\"}");
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.terminal()).isTrue();
    assertThat(result.failureCode()).isEqualTo(FailureCode.BUDGET_EXHAUSTED);
  }

  @Test
  void mapsServerErrorToUnknownOutcome() {
    handler = exchange -> respond(exchange, 503, "{\"error\":\"unavailable\"}");
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.submitted()).isTrue();
    assertThat(result.terminal()).isFalse();
    assertThat(result.failureCode()).isEqualTo(FailureCode.UNKNOWN_PROVIDER_OUTCOME);
  }

  @Test
  void mapsTimeoutToUnknownOutcome() {
    handler =
        exchange -> {
          sleep(2_000);
          respondQuietly(exchange, 200, successBody("proposal"));
        };
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().plusMillis(300)));

    assertThat(result.submitted()).isTrue();
    assertThat(result.terminal()).isFalse();
    assertThat(result.failureCode()).isEqualTo(FailureCode.UNKNOWN_PROVIDER_OUTCOME);
  }

  @Test
  void rejectsOversizedBody() {
    handler = exchange -> respond(exchange, 200, successBody("x".repeat(5_000)));
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 4_096).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.terminal()).isTrue();
    assertThat(result.failureCode()).isEqualTo(FailureCode.INVALID_OUTPUT);
    assertThat(result.rawDecision()).isNull();
  }

  @Test
  void rejectsNonStopFinishReason() {
    handler =
        exchange ->
            respond(
                exchange,
                200,
                "{\"id\":\"cmpl-1\",\"model\":\"mistral-medium-latest\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"proposal\"},"
                    + "\"finish_reason\":\"length\"}]}");
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.terminal()).isTrue();
    assertThat(result.failureCode()).isEqualTo(FailureCode.INVALID_OUTPUT);
    assertThat(result.rawDecision()).isNull();
  }

  @Test
  void rejectsNonJsonBody() {
    handler = exchange -> respond(exchange, 200, "not json at all");
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.terminal()).isTrue();
    assertThat(result.failureCode()).isEqualTo(FailureCode.INVALID_OUTPUT);
  }

  @Test
  void missingCredentialDoesNotCallProvider() {
    handler = exchange -> respond(exchange, 200, successBody("proposal"));
    ProviderTurnResult result = adapter(Map.of(), 1_048_576).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.submitted()).isFalse();
    assertThat(result.terminal()).isFalse();
    assertThat(result.failureCode()).isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
    assertThat(result.events()).containsExactly("credential-missing");
    assertThat(requests).isEmpty();
  }

  @Test
  void expiredDeadlineDoesNotCallProvider() {
    handler = exchange -> respond(exchange, 200, successBody("proposal"));
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().minusSeconds(5)));

    assertThat(result.submitted()).isFalse();
    assertThat(result.failureCode()).isEqualTo(FailureCode.DEADLINE_EXPIRED);
    assertThat(requests).isEmpty();
  }

  @Test
  void redactsCredentialEchoedInResponse() {
    handler = exchange -> respond(exchange, 200, successBody(CREDENTIAL));
    ProviderTurnResult result = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576).startTurn(
        invocation(Instant.now().plusSeconds(30)));

    assertThat(result.redactedTranscript()).doesNotContain(CREDENTIAL);
    assertThat(result.redactedTranscript()).contains("[REDACTED]");
  }

  @Test
  void readinessReflectsCredentialPresenceWithoutCallingProvider() {
    MistralProviderAdapter configured = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576);
    MistralProviderAdapter unconfigured = adapter(Map.of(), 1_048_576);

    ProviderReadiness ready = configured.readiness();
    ProviderReadiness missing = unconfigured.readiness();

    assertThat(ready.available()).isTrue();
    assertThat(ready.failureCode()).isEqualTo(FailureCode.NONE);
    assertThat(missing.available()).isFalse();
    assertThat(missing.failureCode()).isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
    assertThat(requests).isEmpty();
  }

  @Test
  void cancelCancelsInflightTurn() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    handler =
        exchange -> {
          received.countDown();
          sleep(3_000);
          respondQuietly(exchange, 200, successBody("proposal"));
        };
    MistralProviderAdapter adapter = adapter(Map.of("MISTRAL_API_KEY", CREDENTIAL), 1_048_576);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ProviderTurnResult> pending =
          executor.submit(() -> adapter.startTurn(invocation(Instant.now().plusSeconds(10))));
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();

      CancelResult cancel = adapter.cancel("mistral:session-1", "mistral:turn-1", "stop");

      assertThat(cancel.failureCode()).isEqualTo(FailureCode.CANCELLED);
      ProviderTurnResult result = pending.get(10, TimeUnit.SECONDS);
      assertThat(result.submitted()).isTrue();
      assertThat(result.terminal()).isFalse();
      assertThat(result.failureCode()).isEqualTo(FailureCode.CANCELLED);
    } finally {
      executor.shutdownNow();
    }
  }

  private MistralProviderAdapter adapter(Map<String, String> environment, int maxResponseBytes) {
    return new MistralProviderAdapter(
        new ProviderRuntimeProperties.ProviderHttp(
            baseUrl, List.of("mistral-*"), List.of("MISTRAL_API_KEY"), maxResponseBytes),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        environment::get,
        Clock.systemUTC());
  }

  private static ProviderInvocation invocation(Instant deadline) {
    return new ProviderInvocation(
        "session-1", "turn-1", "mistral-medium-latest", "decide", deadline, null);
  }

  private static String successBody(String content) {
    return "{\"id\":\"cmpl-1\",\"model\":\"mistral-medium-latest\",\"choices\":[{\"index\":0,"
        + "\"message\":{\"role\":\"assistant\",\"content\":\""
        + content
        + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}";
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(
        new RecordedRequest(
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            body));
    handler.handle(exchange);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    try {
      respond(exchange, status, body);
    } catch (IOException ignored) {
      // The client may already have abandoned the request.
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  @FunctionalInterface
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  private record RecordedRequest(String path, String authorization, String body) {}
}
