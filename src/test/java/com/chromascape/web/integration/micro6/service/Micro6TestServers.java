package com.chromascape.web.integration.micro6.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test doubles for the assembled integration test: a fake authenticated leeuwbotapi runtime
 * registry and a fake Micro6 executor HTTP server.
 */
final class Micro6TestServers {

  static final class FakeMicro6Executor {
    private final HttpServer server;
    final String url;
    volatile int lightweightStatus = 200;
    volatile String lightweightBody = "{}";
    volatile int fullStatus = 200;
    volatile String fullBody = "{}";
    volatile boolean dropConnections = false;
    volatile long lightweightDelayMs = 0;
    volatile long fullDelayMs = 0;
    volatile int lightweightCalls = 0;
    volatile int fullCalls = 0;

    FakeMicro6Executor() throws IOException {
      server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext(
          "/api/v1/catalog",
          exchange -> {
            String mode =
                exchange.getRequestURI().getQuery() == null
                    ? ""
                    : exchange.getRequestURI().getQuery();
            if (dropConnections) {
              exchange.close();
              return;
            }
            boolean full = mode.contains("mode=full");
            long delay = full ? fullDelayMs : lightweightDelayMs;
            if (delay > 0) {
              try {
                Thread.sleep(delay);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            if (full) {
              fullCalls++;
              write(exchange, fullStatus, fullBody);
            } else {
              lightweightCalls++;
              write(exchange, lightweightStatus, lightweightBody);
            }
          });
      server.start();
      url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }
  }

  static final class FakeLeeuwbotApi {
    private final HttpServer server;
    final String url;
    final Map<String, FakeMicro6Executor> executorsByRef = new ConcurrentHashMap<>();
    final Map<String, String> executorInstanceByRef = new ConcurrentHashMap<>();
    final Map<String, String> stateByRef = new ConcurrentHashMap<>();
    final Map<String, String> catalogHashByRef = new ConcurrentHashMap<>();
    final Map<String, String> schemaBundleHashByRef = new ConcurrentHashMap<>();
    final Map<String, String> catalogVersionByRef = new ConcurrentHashMap<>();
    final Map<String, String> catalogStateByRef = new ConcurrentHashMap<>();
    final Map<String, String> schemaBundleStateByRef = new ConcurrentHashMap<>();
    final Map<String, String> sessionIdByRef = new ConcurrentHashMap<>();
    final Map<String, String> machineUuidByRef = new ConcurrentHashMap<>();
    final Map<String, Runnable> beforeCatalogHook = new ConcurrentHashMap<>();
    final Map<String, Runnable> beforeResolveHook = new ConcurrentHashMap<>();
    final Map<String, String> executorHeaderByRef = new ConcurrentHashMap<>();
    // Response-header overrides that decouple the stamped headers from the runtime attestation, so
    // tests can exercise mid-fetch drift (the response carrying a stale identity the runtime no
    // longer attests). When unset, headers fall back to the runtime attestation / payload.
    final Map<String, String> catalogHashHeaderByRef = new ConcurrentHashMap<>();
    final Map<String, String> schemaBundleHashHeaderByRef = new ConcurrentHashMap<>();
    final Map<String, String> catalogStateHeaderByRef = new ConcurrentHashMap<>();
    final Map<String, String> schemaBundleStateHeaderByRef = new ConcurrentHashMap<>();
    // Per-endpoint controls to omit catalog_state / schema_bundle_state from the runtime resolve
    // JSON, so tests can model a registry runtime missing lifecycle metadata while the catalog
    // proxy response headers remain independently valid.
    final Set<String> omitRuntimeCatalogStateByRef = ConcurrentHashMap.newKeySet();
    final Set<String> omitRuntimeSchemaBundleStateByRef = ConcurrentHashMap.newKeySet();
    final ObjectMapper mapper = new ObjectMapper();
    volatile boolean rewriteCatalogIdentity = true;
    volatile boolean omitIdentityHeaders = false;

    FakeLeeuwbotApi() throws IOException {
      server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext(
          "/api/v1/launcher/runtimes/micro6/",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/v1/launcher/runtimes/micro6/";
            String tail = path.substring(prefix.length());
            String endpointRef = tail;
            if (tail.endsWith("/catalog")) {
              endpointRef = tail.substring(0, tail.length() - "/catalog".length());
              handleCatalog(exchange, endpointRef);
              return;
            }
            handleResolve(exchange, endpointRef);
          });
      server.createContext(
          "/api/v1/launcher/runtimes/micro6",
          exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/launcher/runtimes/micro6")) {
              StringBuilder json = new StringBuilder("{\"runtimes\":[");
              boolean first = true;
              for (Map.Entry<String, FakeMicro6Executor> entry : executorsByRef.entrySet()) {
                if (!first) {
                  json.append(",");
                }
                first = false;
                json.append(runtimeJson(entry.getKey(), entry.getValue()));
              }
              json.append("]}");
              write(exchange, 200, json.toString());
            }
          });
      server.start();
      url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void register(String endpointRef, String executorInstanceId, FakeMicro6Executor executor) {
      executorsByRef.put(endpointRef, executor);
      executorInstanceByRef.put(endpointRef, executorInstanceId);
      stateByRef.put(endpointRef, "ONLINE");
    }

    void setState(String endpointRef, String state) {
      stateByRef.put(endpointRef, state);
    }

    void stop() {
      server.stop(0);
    }

    private void handleResolve(com.sun.net.httpserver.HttpExchange exchange, String endpointRef) {
      Runnable resolveHook = beforeResolveHook.get(endpointRef);
      if (resolveHook != null) {
        resolveHook.run();
      }
      FakeMicro6Executor executor = executorsByRef.get(endpointRef);
      if (executor == null) {
        write(
            exchange,
            404,
            "{\"error\":\"unknown endpoint reference\",\"reason_code\":\"ENDPOINT_REF_UNKNOWN\"}");
        return;
      }
      write(exchange, 200, runtimeJson(endpointRef, executor));
    }

    private void handleCatalog(com.sun.net.httpserver.HttpExchange exchange, String endpointRef) {
      FakeMicro6Executor executor = executorsByRef.get(endpointRef);
      if (executor == null) {
        write(
            exchange,
            404,
            "{\"error\":\"unknown endpoint reference\",\"reason_code\":\"ENDPOINT_REF_UNKNOWN\"}");
        return;
      }
      Runnable hook = beforeCatalogHook.get(endpointRef);
      if (hook != null) {
        hook.run();
      }
      // Faithful proxy: the registry fetches the executor's catalog server-side. A dropped
      // connection or unreachable executor surfaces as 502, mirroring the real proxy.
      String query = exchange.getRequestURI().getQuery();
      boolean full = query != null && query.contains("mode=full");
      String catalogUrl = executor.url + "/api/v1/catalog?mode=" + (full ? "full" : "lightweight");
      try {
        java.net.http.HttpClient client =
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2000)).build();
        java.net.http.HttpRequest request =
            java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(catalogUrl))
                .timeout(Duration.ofMillis(2000))
                .GET()
                .build();
        java.net.http.HttpResponse<String> response =
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        // The catalog payload must carry the same executor identity the registry resolved, or the
        // consumer rejects it as an identity conflict. Mirrors the real executor/registry contract.
        if (rewriteCatalogIdentity && body != null && !body.isEmpty()) {
          try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed != null && parsed.isObject()) {
              ((ObjectNode) parsed)
                  .put("executor_instance_id", executorInstanceByRef.get(endpointRef));
              body = mapper.writeValueAsString(parsed);
            }
          } catch (Exception ignored) {
            // leave the body untouched if it is not JSON
          }
        }
        // The registry stamps the resolved runtime identity onto the response headers exactly like
        // the real backend proxy. Header overrides (when set) take precedence so tests can decouple
        // the response from the runtime attestation; otherwise the runtime's attested metadata
        // (catalogHashByRef / schemaBundleHashByRef) is used first, falling back to the fetched
        // payload when the runtime has no attestation.
        String catalogHash =
            firstNonNull(
                catalogHashHeaderByRef.get(endpointRef), catalogHashByRef.get(endpointRef));
        String schemaBundleHash =
            firstNonNull(
                schemaBundleHashHeaderByRef.get(endpointRef),
                schemaBundleHashByRef.get(endpointRef));
        String catalogState =
            firstNonNull(
                catalogStateHeaderByRef.get(endpointRef), catalogStateByRef.get(endpointRef));
        String schemaBundleState =
            firstNonNull(
                schemaBundleStateHeaderByRef.get(endpointRef),
                schemaBundleStateByRef.get(endpointRef));
        try {
          JsonNode parsed = mapper.readTree(body);
          if (parsed != null && parsed.isObject()) {
            if ((catalogHash == null || catalogHash.isEmpty()) && parsed.has("catalog_hash")) {
              catalogHash = parsed.get("catalog_hash").asText();
            }
            if ((schemaBundleHash == null || schemaBundleHash.isEmpty())
                && parsed.has("schema_bundle_hash")) {
              schemaBundleHash = parsed.get("schema_bundle_hash").asText();
            }
            if (catalogState == null && parsed.has("catalog_state")) {
              catalogState = parsed.get("catalog_state").asText();
            }
            if (schemaBundleState == null && parsed.has("schema_bundle_state")) {
              schemaBundleState = parsed.get("schema_bundle_state").asText();
            }
          }
        } catch (Exception ignored) {
          // identity headers default to empty when the body is not JSON
        }
        if (catalogState == null) {
          catalogState = "READY";
        }
        if (schemaBundleState == null) {
          // A GENERATING catalog is always attested with a GENERATING schema bundle (mirroring the
          // health-generating contract); otherwise the bundle state follows the bundle hash.
          if ("GENERATING".equals(catalogState)) {
            schemaBundleState = "GENERATING";
          } else {
            schemaBundleState =
                schemaBundleHash == null || schemaBundleHash.isEmpty()
                    ? "NOT_AVAILABLE"
                    : "AVAILABLE";
          }
        }
        // A non-AVAILABLE schema state must never carry a bundle hash (contract matrix).
        if (!"AVAILABLE".equals(schemaBundleState)) {
          schemaBundleHash = "";
        }
        exchange.getResponseHeaders().set("X-Micro6-Machine-Uuid", machineUuidFor(endpointRef));
        exchange
            .getResponseHeaders()
            .set(
                "X-Micro6-Executor-Instance-Id",
                executorHeaderByRef.getOrDefault(
                    endpointRef, executorInstanceByRef.get(endpointRef)));
        exchange.getResponseHeaders().set("X-Micro6-Session-Id", sessionIdFor(endpointRef));
        exchange
            .getResponseHeaders()
            .set("X-Micro6-Catalog-Version", catalogVersionFor(endpointRef));
        if (catalogHash != null && !catalogHash.isEmpty()) {
          exchange.getResponseHeaders().set("X-Micro6-Catalog-Hash", catalogHash);
        }
        if (schemaBundleHash != null && !schemaBundleHash.isEmpty()) {
          exchange.getResponseHeaders().set("X-Micro6-Schema-Bundle-Hash", schemaBundleHash);
        }
        exchange.getResponseHeaders().set("X-Micro6-Catalog-State", catalogState);
        exchange.getResponseHeaders().set("X-Micro6-Schema-Bundle-State", schemaBundleState);
        exchange
            .getResponseHeaders()
            .set("X-Micro6-Observed-At", java.time.Instant.now().toString());
        if (omitIdentityHeaders) {
          exchange.getResponseHeaders().remove("X-Micro6-Machine-Uuid");
          exchange.getResponseHeaders().remove("X-Micro6-Executor-Instance-Id");
          exchange.getResponseHeaders().remove("X-Micro6-Session-Id");
          exchange.getResponseHeaders().remove("X-Micro6-Catalog-Version");
          exchange.getResponseHeaders().remove("X-Micro6-Catalog-Hash");
          exchange.getResponseHeaders().remove("X-Micro6-Schema-Bundle-Hash");
          exchange.getResponseHeaders().remove("X-Micro6-Catalog-State");
          exchange.getResponseHeaders().remove("X-Micro6-Schema-Bundle-State");
          exchange.getResponseHeaders().remove("X-Micro6-Observed-At");
        }
        write(exchange, response.statusCode(), body);
      } catch (Exception e) {
        write(
            exchange,
            502,
            "{\"error\":\"executor_unreachable\",\"reason_code\":\"EXECUTOR_UNREACHABLE\"}");
      }
    }

    private String machineUuidFor(String endpointRef) {
      return machineUuidByRef.getOrDefault(endpointRef, "machine-" + endpointRef.hashCode());
    }

    private String sessionIdFor(String endpointRef) {
      return sessionIdByRef.getOrDefault(endpointRef, "session-" + endpointRef.hashCode());
    }

    private String catalogVersionFor(String endpointRef) {
      return catalogVersionByRef.getOrDefault(endpointRef, "2");
    }

    private String runtimeJson(String endpointRef, FakeMicro6Executor executor) {
      String state = stateByRef.getOrDefault(endpointRef, "ONLINE");
      String catalogHash = catalogHashByRef.get(endpointRef);
      String schemaBundleHash = schemaBundleHashByRef.get(endpointRef);
      String catalogVersion = catalogVersionByRef.getOrDefault(endpointRef, "2");
      String catalogState = catalogStateByRef.get(endpointRef);
      String schemaBundleState = schemaBundleStateByRef.get(endpointRef);
      String sessionId = sessionIdFor(endpointRef);
      // The launcher heartbeat metadata is stable: once an executor attests a catalog/bundle hash
      // it
      // keeps it across subsequent resolves even if a later /catalog response stops carrying it.
      if (catalogHash == null) {
        try {
          JsonNode parsed = mapper.readTree(executor.fullBody);
          if (parsed != null && parsed.isObject() && parsed.has("catalog_hash")) {
            catalogHashByRef.putIfAbsent(endpointRef, parsed.get("catalog_hash").asText());
            catalogHash = catalogHashByRef.get(endpointRef);
          }
        } catch (Exception ignored) {
          // fall through to null
        }
        if (catalogHash == null) {
          try {
            JsonNode parsed = mapper.readTree(executor.lightweightBody);
            if (parsed != null && parsed.isObject() && parsed.has("catalog_hash")) {
              catalogHashByRef.putIfAbsent(endpointRef, parsed.get("catalog_hash").asText());
              catalogHash = catalogHashByRef.get(endpointRef);
            }
          } catch (Exception ignored) {
            // fall through to null
          }
        }
      }
      if (schemaBundleHash == null
          && (schemaBundleState == null || "AVAILABLE".equals(schemaBundleState))) {
        try {
          JsonNode parsed = mapper.readTree(executor.fullBody);
          if (parsed != null && parsed.isObject() && parsed.has("schema_bundle_hash")) {
            schemaBundleHashByRef.putIfAbsent(
                endpointRef, parsed.get("schema_bundle_hash").asText());
            schemaBundleHash = schemaBundleHashByRef.get(endpointRef);
          }
        } catch (Exception ignored) {
          // fall through to null
        }
      }
      if (catalogState == null) {
        catalogState = "READY";
      }
      if (schemaBundleState == null) {
        if ("GENERATING".equals(catalogState)) {
          schemaBundleState = "GENERATING";
        } else {
          schemaBundleState = schemaBundleHash == null ? "NOT_AVAILABLE" : "AVAILABLE";
        }
      }
      StringBuilder json =
          new StringBuilder("{")
              .append("\"machine_uuid\":\"")
              .append(machineUuidFor(endpointRef))
              .append("\",")
              .append("\"session_id\":\"")
              .append(sessionId)
              .append("\",")
              .append("\"account_hash\":\"0123456789abcdef\",")
              .append("\"client_type\":\"micro6\",")
              .append("\"executor_instance_id\":\"")
              .append(executorInstanceByRef.get(endpointRef))
              .append("\",")
              .append("\"catalog_version\":\"")
              .append(catalogVersion)
              .append("\",");
      if (catalogHash != null) {
        json.append("\"catalog_hash\":\"").append(catalogHash).append("\",");
      }
      if (schemaBundleHash != null) {
        json.append("\"schema_bundle_hash\":\"").append(schemaBundleHash).append("\",");
      }
      if (catalogState != null && !omitRuntimeCatalogStateByRef.contains(endpointRef)) {
        json.append("\"catalog_state\":\"").append(catalogState).append("\",");
      }
      if (schemaBundleState != null && !omitRuntimeSchemaBundleStateByRef.contains(endpointRef)) {
        json.append("\"schema_bundle_state\":\"").append(schemaBundleState).append("\",");
      }
      json.append("\"runtime_state\":\"")
          .append(state)
          .append("\",")
          .append("\"endpoint_ref\":\"")
          .append(endpointRef)
          .append("\",")
          .append("\"advertised_base_url\":\"")
          .append(executor.url)
          .append("\"")
          .append("}");
      return json.toString();
    }
  }

  private static String firstNonNull(String primary, String fallback) {
    return primary != null ? primary : fallback;
  }

  private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    } catch (IOException e) {
      exchange.close();
    }
  }
}
