package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Calls the server-side Micro6 gameplay gate; it has no raw input or direct client surface. */
@Component
public final class Micro6AccountBuilderGameplayGateway implements AccountBuilderGameplayGateway {
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final URI baseUri;
  private final Duration pollInterval;

  public Micro6AccountBuilderGameplayGateway(
      ObjectMapper objectMapper,
      @Value("${gameplay.bootstrap.micro6-url:http://127.0.0.1:8098}") String baseUrl) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    this.pollInterval = Duration.ofMillis(250);
  }

  @Override
  public DispatchResult dispatch(ExecutionEnvelope envelope, HumanAuthority authority) {
    JsonNode accepted =
        post(
            "api/v1/gameplay/dispatch",
            Map.of("envelope", envelope, "authority", authority),
            envelope.idempotencyKey());
    if (!accepted.path("success").asBoolean(false)) {
      return resultFromResponse(accepted, false);
    }
    int entryId = accepted.path("entry_id").asInt(0);
    if (entryId <= 0) {
      throw new IllegalStateException("Micro6 accepted gameplay dispatch without entry_id");
    }
    Instant budgetDeadline =
        Instant.now()
            .plusMillis(Math.max(1_000L, envelope.budgets().path("maxElapsedMs").asLong()));
    Instant terminalDeadline =
        budgetDeadline.isBefore(envelope.deadline()) ? budgetDeadline : envelope.deadline();
    while (Instant.now().isBefore(terminalDeadline)) {
      JsonNode status = get("api/v1/status/" + entryId);
      String state = status.path("status").asText();
      if (isTerminal(state)) {
        return terminalResult(entryId, state, status);
      }
      try {
        Thread.sleep(pollInterval.toMillis());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Micro6 terminal reconciliation interrupted", exception);
      }
    }
    JsonNode finalStatus = get("api/v1/status/" + entryId);
    String finalState = finalStatus.path("status").asText();
    if (isTerminal(finalState)) {
      return terminalResult(entryId, finalState, finalStatus);
    }
    return new DispatchResult(
        Outcome.UNCERTAIN_OUTCOME,
        "TERMINAL_RECONCILIATION_TIMEOUT",
        true,
        false,
        List.of("micro6:entry:" + entryId));
  }

  private boolean isTerminal(String state) {
    return List.of("COMPLETED", "FAILED", "CANCELLED", "STOPPED").contains(state);
  }

  @Override
  public DispatchResult stop(String requestId, String reason, boolean killSwitch) {
    return resultFromResponse(
        post(
            "api/v1/gameplay/stop",
            Map.of("requestId", requestId, "reason", reason, "killSwitch", killSwitch),
            requestId + ":stop"),
        true);
  }

  private JsonNode post(String path, Object body, String idempotencyKey) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", idempotencyKey)
              .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode json = objectMapper.readTree(response.body());
      if (json.isObject()) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) json)
            .put("_httpSuccess", response.statusCode() >= 200 && response.statusCode() < 300);
      }
      return json;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Micro6 dispatch interrupted", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("Micro6 dispatch could not be reconciled", exception);
    }
  }

  private JsonNode get(String path) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Micro6 status returned HTTP " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Micro6 status reconciliation interrupted", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("Micro6 status could not be reconciled", exception);
    }
  }

  private DispatchResult terminalResult(int entryId, String state, JsonNode status) {
    Outcome outcome;
    if ("COMPLETED".equals(state)) {
      outcome =
          switch (status.path("gameplay_verification_outcome").asText("UNVERIFIABLE")) {
            case "CONFIRMED" -> Outcome.CONFIRMED;
            case "REFUTED" -> Outcome.REFUTED;
            default -> Outcome.UNVERIFIABLE;
          };
    } else if ("CANCELLED".equals(state) || "STOPPED".equals(state)) {
      outcome = Outcome.STOPPED;
    } else {
      outcome = Outcome.FAILED;
    }
    return new DispatchResult(
        outcome,
        firstText(status, "failureCode", "error_code"),
        true,
        true,
        List.of("micro6:entry:" + entryId, "micro6:status:" + state.toLowerCase()));
  }

  private DispatchResult resultFromResponse(JsonNode json, boolean deliveryAcknowledged) {
    String value =
        json.path("outcome")
            .asText(json.path("_httpSuccess").asBoolean(false) ? "DELIVERED" : "FAILED");
    Outcome outcome;
    try {
      outcome = Outcome.valueOf(value);
    } catch (IllegalArgumentException exception) {
      outcome = Outcome.FAILED;
    }
    List<String> evidence = new ArrayList<>();
    json.path("evidenceRefs").forEach(item -> evidence.add(item.asText()));
    return new DispatchResult(
        outcome,
        firstText(json, "failureCode", "error_code"),
        json.path("deliveryAcknowledged").asBoolean(deliveryAcknowledged),
        json.path("terminalReconciled").asBoolean(true),
        List.copyOf(evidence));
  }

  private String firstText(JsonNode node, String first, String second) {
    String value = node.path(first).asText(null);
    return value != null ? value : node.path(second).asText(null);
  }
}
