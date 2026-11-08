package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderReadiness;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Direct HTTPS adapter for the Mistral chat-completions API.
 *
 * <p>Mistral is not a CLI process, so this adapter implements {@link ProviderAdapter} directly
 * rather than extending {@link AbstractProcessProviderAdapter}. It submits at most one request per
 * turn, never retries, and never logs the credential or the request headers.
 */
@Component
public final class MistralProviderAdapter implements ProviderAdapter {
  private static final Logger LOG = LoggerFactory.getLogger(MistralProviderAdapter.class);
  private static final String REF_PREFIX = "mistral:";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ProviderRuntimeProperties.ProviderHttp configuration;
  private final HttpClient client;
  private final Function<String, String> environment;
  private final Clock clock;
  private final ConcurrentHashMap<String, CompletableFuture<?>> inFlight =
      new ConcurrentHashMap<>();
  private final Set<String> cancelledTurns = ConcurrentHashMap.newKeySet();

  /** Creates the production adapter from the validated Mistral runtime properties. */
  @Autowired
  public MistralProviderAdapter(ProviderRuntimeProperties properties) {
    this(
        properties.getMistral(),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        System::getenv,
        Clock.systemUTC());
  }

  MistralProviderAdapter(
      ProviderRuntimeProperties.ProviderHttp configuration,
      HttpClient client,
      Function<String, String> environment,
      Clock clock) {
    this.configuration = configuration;
    this.client = client;
    this.environment = environment;
    this.clock = clock;
  }

  @Override
  public Provider provider() {
    return Provider.MISTRAL;
  }

  @Override
  public ProviderReadiness readiness() {
    if (credential() == null) {
      return new ProviderReadiness(
          false, configuration.getBaseUrl(), null, FailureCode.PROVIDER_UNAVAILABLE);
    }
    return new ProviderReadiness(true, configuration.getBaseUrl(), "http", FailureCode.NONE);
  }

  @Override
  public ProviderTurnResult startTurn(ProviderInvocation invocation) {
    String sessionRef = REF_PREFIX + invocation.runtimeSessionId();
    String turnRef = REF_PREFIX + invocation.runtimeTurnId();
    String credential = credential();
    if (credential == null) {
      LOG.warn("Mistral credential is not configured for turn {}", turnRef);
      return result(
          sessionRef,
          turnRef,
          false,
          false,
          null,
          null,
          new ProviderUsage(null, null, 0, 0, false),
          FailureCode.PROVIDER_UNAVAILABLE,
          List.of("credential-missing"));
    }
    if (!invocation.deadline().isAfter(clock.instant())) {
      return result(
          sessionRef,
          turnRef,
          false,
          false,
          null,
          null,
          new ProviderUsage(null, null, 0, 0, false),
          FailureCode.DEADLINE_EXPIRED,
          List.of("deadline-expired"));
    }
    String requestBody;
    try {
      requestBody = requestBody(invocation);
    } catch (IOException exception) {
      return result(
          sessionRef,
          turnRef,
          false,
          false,
          null,
          null,
          new ProviderUsage(null, null, 0, 0, false),
          FailureCode.INTERNAL_FAILURE,
          List.of("request-build-failed"));
    }
    HttpRequest request;
    Duration remaining = Duration.between(clock.instant(), invocation.deadline());
    if (remaining.isZero() || remaining.isNegative()) {
      return result(
          sessionRef,
          turnRef,
          false,
          false,
          null,
          null,
          new ProviderUsage(null, null, 0, 0, false),
          FailureCode.DEADLINE_EXPIRED,
          List.of("deadline-expired"));
    }
    try {
      request =
          HttpRequest.newBuilder(URI.create(configuration.getBaseUrl() + "/chat/completions"))
              .timeout(remaining)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .header("Authorization", "Bearer " + credential)
              .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
              .build();
    } catch (IllegalArgumentException exception) {
      LOG.warn("Mistral base URL is unusable for turn {}", turnRef);
      return result(
          sessionRef,
          turnRef,
          false,
          false,
          null,
          null,
          new ProviderUsage(null, null, 0, 0, false),
          FailureCode.PROVIDER_UNAVAILABLE,
          List.of("http-error:IllegalArgumentException"));
    }
    long startedNanos = System.nanoTime();
    CompletableFuture<HttpResponse<InputStream>> future =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    inFlight.put(invocation.runtimeTurnId(), future);
    try {
      long waitMillis =
          Math.max(1L, Duration.between(clock.instant(), invocation.deadline()).toMillis());
      HttpResponse<InputStream> response = future.get(waitMillis, TimeUnit.MILLISECONDS);
      return mapResponse(sessionRef, turnRef, requestBody, credential, response, startedNanos);
    } catch (CancellationException exception) {
      LOG.warn("Mistral turn {} was cancelled", turnRef);
      return failure(
          sessionRef,
          turnRef,
          requestBody,
          credential,
          startedNanos,
          FailureCode.CANCELLED,
          true,
          exception);
    } catch (TimeoutException exception) {
      LOG.warn("Mistral turn {} timed out", turnRef);
      return failure(
          sessionRef,
          turnRef,
          requestBody,
          credential,
          startedNanos,
          outcomeCode(invocation.runtimeTurnId(), future),
          true,
          exception);
    } catch (ExecutionException exception) {
      LOG.warn("Mistral turn {} failed with {}", turnRef, exception.getClass().getSimpleName());
      return failure(
          sessionRef,
          turnRef,
          requestBody,
          credential,
          startedNanos,
          outcomeCode(invocation.runtimeTurnId(), future),
          true,
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      LOG.warn("Mistral turn {} was interrupted", turnRef);
      return failure(
          sessionRef,
          turnRef,
          requestBody,
          credential,
          startedNanos,
          outcomeCode(invocation.runtimeTurnId(), future),
          true,
          exception);
    } finally {
      inFlight.remove(invocation.runtimeTurnId());
      cancelledTurns.remove(invocation.runtimeTurnId());
    }
  }

  private FailureCode outcomeCode(String turnId, CompletableFuture<?> future) {
    return cancelledTurns.contains(turnId) || future.isCancelled()
        ? FailureCode.CANCELLED
        : FailureCode.UNKNOWN_PROVIDER_OUTCOME;
  }

  @Override
  public ReconciliationResult reconcile(String providerSessionRef, String providerTurnRef) {
    return new ReconciliationResult(false, null, null, FailureCode.NEEDS_RECONCILIATION);
  }

  @Override
  public CancelResult cancel(String providerSessionRef, String providerTurnRef, String reason) {
    if (providerTurnRef != null && providerTurnRef.startsWith(REF_PREFIX)) {
      String turnId = providerTurnRef.substring(REF_PREFIX.length());
      CompletableFuture<?> future = inFlight.get(turnId);
      if (future != null) {
        cancelledTurns.add(turnId);
        future.cancel(true);
        return new CancelResult(false, FailureCode.CANCELLED);
      }
    }
    return new CancelResult(false, FailureCode.NEEDS_RECONCILIATION);
  }

  private ProviderTurnResult mapResponse(
      String sessionRef,
      String turnRef,
      String requestBody,
      String credential,
      HttpResponse<InputStream> response,
      long startedNanos) {
    int status = response.statusCode();
    int maxBytes = configuration.getMaxResponseBytes();
    byte[] data;
    try (InputStream stream = response.body()) {
      data = stream.readNBytes(maxBytes + 1);
    } catch (IOException exception) {
      LOG.warn("Mistral turn {} response could not be read", turnRef);
      return failure(
          sessionRef,
          turnRef,
          requestBody,
          credential,
          startedNanos,
          FailureCode.UNKNOWN_PROVIDER_OUTCOME,
          true,
          exception);
    }
    String bodyText = new String(data, 0, Math.min(data.length, maxBytes), StandardCharsets.UTF_8);
    String transcript = transcript(requestBody, status, bodyText, credential);
    long elapsedMillis = elapsedMillis(startedNanos);
    if (status == 401 || status == 403) {
      return result(
          sessionRef,
          turnRef,
          true,
          true,
          null,
          transcript,
          new ProviderUsage(null, null, 0, elapsedMillis, false),
          FailureCode.PROVIDER_UNAVAILABLE,
          events(status, null));
    }
    if (status == 429) {
      return result(
          sessionRef,
          turnRef,
          true,
          true,
          null,
          transcript,
          new ProviderUsage(null, null, 0, elapsedMillis, false),
          FailureCode.BUDGET_EXHAUSTED,
          events(status, null));
    }
    if (status >= 500) {
      return result(
          sessionRef,
          turnRef,
          true,
          false,
          null,
          transcript,
          new ProviderUsage(null, null, 0, elapsedMillis, false),
          FailureCode.UNKNOWN_PROVIDER_OUTCOME,
          events(status, null));
    }
    if (status != 200) {
      return result(
          sessionRef,
          turnRef,
          true,
          true,
          null,
          transcript,
          new ProviderUsage(null, null, 0, elapsedMillis, false),
          FailureCode.PROVIDER_UNAVAILABLE,
          events(status, null));
    }
    if (data.length > maxBytes) {
      return result(
          sessionRef,
          turnRef,
          true,
          true,
          null,
          transcript,
          new ProviderUsage(null, null, 0, elapsedMillis, false),
          FailureCode.INVALID_OUTPUT,
          events(status, null));
    }
    JsonNode root;
    try {
      root = JSON.readTree(bodyText);
    } catch (IOException exception) {
      return result(
          sessionRef,
          turnRef,
          true,
          true,
          null,
          transcript,
          new ProviderUsage(null, null, 0, elapsedMillis, false),
          FailureCode.INVALID_OUTPUT,
          events(status, null));
    }
    ProviderUsage usage = usage(root, elapsedMillis);
    JsonNode choice = root.path("choices").path(0);
    JsonNode content = choice.path("message").path("content");
    String finishReason = choice.path("finish_reason").asText(null);
    if (!content.isTextual() || !"stop".equals(finishReason)) {
      return result(
          sessionRef,
          turnRef,
          true,
          true,
          null,
          transcript,
          usage,
          FailureCode.INVALID_OUTPUT,
          events(status, root));
    }
    return result(
        sessionRef,
        turnRef,
        true,
        true,
        content.asText(),
        transcript,
        usage,
        FailureCode.NONE,
        events(status, root));
  }

  private ProviderTurnResult failure(
      String sessionRef,
      String turnRef,
      String requestBody,
      String credential,
      long startedNanos,
      FailureCode failureCode,
      boolean submitted,
      Exception exception) {
    return result(
        sessionRef,
        turnRef,
        submitted,
        false,
        null,
        errorTranscript(requestBody, exception.getClass().getSimpleName(), credential),
        new ProviderUsage(null, null, 0, elapsedMillis(startedNanos), false),
        failureCode,
        List.of("http-error:" + exception.getClass().getSimpleName()));
  }

  private ProviderTurnResult result(
      String sessionRef,
      String turnRef,
      boolean submitted,
      boolean terminal,
      String rawDecision,
      String transcript,
      ProviderUsage usage,
      FailureCode failureCode,
      List<String> events) {
    return new ProviderTurnResult(
        submitted,
        terminal,
        sessionRef,
        turnRef,
        rawDecision,
        transcript,
        usage,
        failureCode,
        events);
  }

  private String requestBody(ProviderInvocation invocation) throws IOException {
    ObjectNode body = JSON.createObjectNode();
    body.put("model", invocation.modelId());
    ArrayNode messages = body.putArray("messages");
    ObjectNode message = messages.addObject();
    message.put("role", "user");
    message.put("content", invocation.prompt());
    body.put("temperature", 0);
    ObjectNode responseFormat = body.putObject("response_format");
    responseFormat.put("type", "json_schema");
    ObjectNode jsonSchema = responseFormat.putObject("json_schema");
    jsonSchema.put("name", "gameplay_provider_proposal");
    jsonSchema.put("strict", true);
    jsonSchema.set("schema", JSON.readTree(ProviderProposalSchema.JSON));
    return JSON.writeValueAsString(body);
  }

  private ProviderUsage usage(JsonNode root, long elapsedMillis) {
    JsonNode usage = root.path("usage");
    JsonNode promptTokens = usage.path("prompt_tokens");
    JsonNode completionTokens = usage.path("completion_tokens");
    if (promptTokens.isNumber() && completionTokens.isNumber()) {
      return new ProviderUsage(
          promptTokens.asLong(), completionTokens.asLong(), 0, elapsedMillis, true);
    }
    return new ProviderUsage(null, null, 0, elapsedMillis, false);
  }

  private List<String> events(int status, JsonNode root) {
    String id = root == null ? "none" : root.path("id").asText("none");
    String model = root == null ? "none" : root.path("model").asText("none");
    return List.of("http-status:" + status, "mistral-id:" + id, "mistral-model:" + model);
  }

  private String transcript(String requestBody, int status, String bodyText, String credential) {
    return redact(
        "[request]\n" + requestBody + "\n[response " + status + "]\n" + bodyText, credential);
  }

  private String errorTranscript(String requestBody, String kind, String credential) {
    return redact("[request]\n" + requestBody + "\n[error " + kind + "]", credential);
  }

  private static String redact(String value, String credential) {
    if (credential == null || credential.isEmpty()) {
      return value;
    }
    return value.replace(credential, "[REDACTED]");
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  private String credential() {
    for (String name : configuration.getCredentialEnvironmentNames()) {
      String value = environment.apply(name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
