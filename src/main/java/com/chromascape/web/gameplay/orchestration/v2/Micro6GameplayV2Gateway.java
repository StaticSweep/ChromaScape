package com.chromascape.web.gameplay.orchestration.v2;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2Transport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Additive SC-1429/v2 transport for SC-1449. Speaks only the closed six-operation vocabulary
 * ({@code CATALOG}, {@code OBSERVE}, {@code RESERVE}, {@code START}, {@code STATUS}, {@code STOP})
 * on the additive {@code /api/v2/gameplay/} routes. The v1 combined {@code
 * /api/v1/gameplay/dispatch} semantics are never reused here: reservation and start are
 * independently evidenced. The reserve body is transmitted exactly as validated — the gateway never
 * re-serializes the bundle — and every response is enriched with the truthful HTTP status so the
 * live gate can verify receipts before persisting success. The live gate calls this only after its
 * persistence transaction commits, so no HTTP call ever runs inside that transaction.
 */
@Component
public class Micro6GameplayV2Gateway {
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final URI baseUri;
  private final V2Transport delegate;

  @Autowired
  public Micro6GameplayV2Gateway(
      ObjectMapper objectMapper,
      @Value("${gameplay.bootstrap.micro6-url:http://127.0.0.1:8098}") String baseUrl) {
    this(
        objectMapper,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
        URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/"));
  }

  Micro6GameplayV2Gateway(ObjectMapper objectMapper, HttpClient httpClient, URI baseUri) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.baseUri = baseUri;
    this.delegate = null;
  }

  /** Test seam: wraps any transport as this gateway type without HTTP. */
  static Micro6GameplayV2Gateway wrapping(ObjectMapper objectMapper, V2Transport transport) {
    return new Micro6GameplayV2Gateway(objectMapper, transport);
  }

  private Micro6GameplayV2Gateway(ObjectMapper objectMapper, V2Transport transport) {
    this.objectMapper = objectMapper;
    this.httpClient = null;
    this.baseUri = null;
    this.delegate = transport;
  }

  /** Reserves the complete bundle on Micro6 without starting gameplay. */
  public JsonNode reserve(byte[] bundleBytes, String idempotencyKey) {
    if (delegate != null) {
      return delegate.reserve(bundleBytes, idempotencyKey);
    }
    return post("api/v2/gameplay/reserve", bundleBytes, idempotencyKey);
  }

  /** Starts the previously reserved request. */
  public JsonNode start(JsonNode envelope, String idempotencyKey) {
    if (delegate != null) {
      return delegate.start(envelope, idempotencyKey);
    }
    return post("api/v2/gameplay/start", Map.of("envelope", envelope), idempotencyKey);
  }

  /** Fetches Micro6 state for independent reconciliation. */
  public JsonNode status(String requestId) {
    if (delegate != null) {
      return delegate.status(requestId);
    }
    return get("api/v2/gameplay/status/" + requestId);
  }

  /** Stops the request; a kill switch stops the active task and records evidence. */
  public JsonNode stop(String requestId, String reason, boolean killSwitch) {
    if (delegate != null) {
      return delegate.stop(requestId, reason, killSwitch);
    }
    return post(
        "api/v2/gameplay/stop",
        Map.of("requestId", requestId, "reason", reason, "killSwitch", killSwitch),
        requestId + ":stop");
  }

  /** Fetches the Micro6 catalog attestation. */
  public JsonNode catalog() {
    if (delegate != null) {
      return delegate.catalog();
    }
    return get("api/v2/gameplay/catalog");
  }

  /** Fetches the Micro6 observation attestation. */
  public JsonNode observe(String clientId) {
    if (delegate != null) {
      return delegate.observe(clientId);
    }
    return get("api/v2/gameplay/observe?clientId=" + clientId);
  }

  /** Test seam: exposes this gateway as the live gate's transport boundary. */
  V2Transport asTransport() {
    return new V2Transport() {
      @Override
      public JsonNode reserve(byte[] bundleBytes, String idempotencyKey) {
        return Micro6GameplayV2Gateway.this.reserve(bundleBytes, idempotencyKey);
      }

      @Override
      public JsonNode start(JsonNode envelope, String idempotencyKey) {
        return Micro6GameplayV2Gateway.this.start(envelope, idempotencyKey);
      }

      @Override
      public JsonNode status(String requestId) {
        return Micro6GameplayV2Gateway.this.status(requestId);
      }

      @Override
      public JsonNode stop(String requestId, String reason, boolean killSwitch) {
        return Micro6GameplayV2Gateway.this.stop(requestId, reason, killSwitch);
      }

      @Override
      public JsonNode catalog() {
        return Micro6GameplayV2Gateway.this.catalog();
      }

      @Override
      public JsonNode observe(String clientId) {
        return Micro6GameplayV2Gateway.this.observe(clientId);
      }
    };
  }

  private JsonNode post(String path, Object body, String idempotencyKey) {
    byte[] payload;
    try {
      payload = body instanceof byte[] bytes ? bytes : objectMapper.writeValueAsBytes(body);
    } catch (Exception exception) {
      throw new GameplayV2DispatchModels.V2TransportException(
          "Micro6 v2 request could not be serialized", exception);
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", idempotencyKey)
              .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return enrich(response);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new GameplayV2DispatchModels.V2TransportException(
          "Micro6 v2 delivery interrupted", exception);
    } catch (Exception exception) {
      throw new GameplayV2DispatchModels.V2TransportException(
          "Micro6 v2 delivery could not be reconciled", exception);
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
        throw new GameplayV2DispatchModels.V2TransportException(
            "Micro6 v2 status returned HTTP " + response.statusCode(), null);
      }
      // Receipt verification requires the truthful HTTP stamp on every response, not only on the
      // POST routes. Without it a status receipt can never be verified, which would make remote
      // reconciliation permanently impossible and leave every uncertain row stuck.
      return enrich(response);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new GameplayV2DispatchModels.V2TransportException(
          "Micro6 v2 status reconciliation interrupted", exception);
    } catch (GameplayV2DispatchModels.V2TransportException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new GameplayV2DispatchModels.V2TransportException(
          "Micro6 v2 status could not be reconciled", exception);
    }
  }

  /** Stamps the truthful HTTP outcome onto the parsed body; never fabricates success. */
  private JsonNode enrich(HttpResponse<String> response) throws java.io.IOException {
    JsonNode json;
    try {
      json = objectMapper.readTree(response.body());
    } catch (Exception exception) {
      json = objectMapper.createObjectNode();
    }
    if (json.isObject()) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) json)
          .put("_httpSuccess", response.statusCode() >= 200 && response.statusCode() < 300);
      ((com.fasterxml.jackson.databind.node.ObjectNode) json)
          .put("_httpStatus", response.statusCode());
    }
    return json;
  }
}
