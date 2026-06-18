package com.chromascape.web.integration.microbot.adapter;

import com.chromascape.web.integration.adapter.BotRuntimeAdapter;
import com.chromascape.web.integration.adapter.RuntimeAction;
import com.chromascape.web.integration.adapter.RuntimeEventEnvelope;
import com.chromascape.web.integration.adapter.RuntimeStateSnapshot;
import com.chromascape.web.integration.microbot.config.MicrobotAgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter that connects to a Microbot Agent Server via HTTP.
 *
 * <p>Provides state snapshots, event forwarding, and action dispatch through the Agent Server's
 * REST API.
 */
@Component
@ConditionalOnProperty(name = "microbot.agent-server.enabled", havingValue = "true")
public class MicrobotAgentServerAdapter implements BotRuntimeAdapter {

  private static final Logger logger = LoggerFactory.getLogger(MicrobotAgentServerAdapter.class);

  private final MicrobotAgentConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final List<RuntimeEventListener> listeners = new CopyOnWriteArrayList<>();

  public MicrobotAgentServerAdapter(MicrobotAgentConfig config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.getTimeoutMs())).build();
    logger.info("Microbot Agent Server adapter initialized: {}", config.getBaseUrl());
  }

  @Override
  public String getAdapterId() {
    return "microbot-agent";
  }

  @Override
  public String getDisplayName() {
    return "Microbot Agent Server";
  }

  @Override
  public boolean isAvailable() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.getBaseUrl() + "/state"))
              .timeout(Duration.ofMillis(config.getTimeoutMs()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    } catch (Exception e) {
      logger.debug("Microbot Agent Server health check failed: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public RuntimeStateSnapshot fetchStateSnapshot() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.getBaseUrl() + "/state"))
              .timeout(Duration.ofMillis(config.getTimeoutMs()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        logger.warn("State snapshot request failed with status: {}", response.statusCode());
        return new RuntimeStateSnapshot(
            getAdapterId(), LocalDateTime.now(), "UNKNOWN", null, null, null);
      }

      JsonNode root = objectMapper.readTree(response.body());
      Map<String, Object> metadata = new HashMap<>();
      root.fieldNames().forEachRemaining(name -> metadata.put(name, root.get(name)));

      return new RuntimeStateSnapshot(
          getAdapterId(),
          LocalDateTime.now(),
          root.path("gameState").asText("UNKNOWN"),
          null,
          null,
          metadata);

    } catch (Exception e) {
      logger.error("Failed to fetch state snapshot: {}", e.getMessage());
      return new RuntimeStateSnapshot(
          getAdapterId(), LocalDateTime.now(), "ERROR", null, null, null);
    }
  }

  @Override
  public RuntimeAction dispatchAction(RuntimeAction action) {
    try {
      String actionType = action.getActionType() != null ? action.getActionType() : "";
      Map<String, Object> params =
          action.getParameters() != null ? action.getParameters() : Map.of();
      HttpResponse<String> response;
      switch (actionType) {
        case "script.start":
          response = postJson("/scripts/start", params);
          break;
        case "script.stop":
          response = postJson("/scripts/stop", params);
          break;
        case "script.status":
          response = get("/scripts/status" + toQuery(params));
          break;
        default:
          action.setAccepted(false);
          action.setRejectionReason(
              "Unsupported actionType. Supported: script.start, script.stop, script.status");
          return action;
      }

      action.setAccepted(response.statusCode() >= 200 && response.statusCode() < 300);
      if (!action.isAccepted()) {
        action.setRejectionReason("HTTP " + response.statusCode() + ": " + response.body());
      }
    } catch (Exception e) {
      action.setAccepted(false);
      action.setRejectionReason(e.getMessage());
    }
    return action;
  }

  @Override
  public void addEventListener(RuntimeEventListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeEventListener(RuntimeEventListener listener) {
    listeners.remove(listener);
  }

  /** Publishes a canonical event to all registered listeners. */
  public void publishEvent(String eventType, Map<String, Object> payload) {
    RuntimeEventEnvelope envelope =
        new RuntimeEventEnvelope(
            UUID.randomUUID().toString(), getAdapterId(), eventType, LocalDateTime.now(), payload);
    for (RuntimeEventListener listener : listeners) {
      try {
        listener.onEvent(envelope);
      } catch (Exception e) {
        logger.warn("Event listener failed: {}", e.getMessage());
      }
    }
  }

  private HttpResponse<String> postJson(String path, Map<String, Object> body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrl() + path))
            .timeout(Duration.ofMillis(config.getTimeoutMs()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrl() + path))
            .timeout(Duration.ofMillis(config.getTimeoutMs()))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String toQuery(Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return "";
    }
    StringJoiner joiner = new StringJoiner("&", "?", "");
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      if (entry.getValue() != null) {
        joiner.add(entry.getKey() + "=" + entry.getValue().toString());
      }
    }
    return joiner.length() > 1 ? joiner.toString() : "";
  }
}
