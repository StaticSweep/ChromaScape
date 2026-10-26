package com.chromascape.web.aiplayer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable reviewed expectations. These pins never establish remote readiness or authority. */
@Component
public final class CurrentContractGate {
  public static final String SCHEMA = "current-ai-player-contract/v1";
  public static final Map<String, String> SCHEMAS =
      Map.ofEntries(
          Map.entry("identity", "current-slice-identity/v1"),
          Map.entry("catalog", "2"),
          Map.entry("run", "current-slice-run/v1"),
          Map.entry("runRequest", "current-slice-run-request/v1"),
          Map.entry("capture", "routine-capture-session/v1"),
          Map.entry("graph", "behavior-graph-definition/v1"),
          Map.entry("model", "fishing-model/v1"),
          Map.entry("observation", "routine-tick-batch/v1"),
          Map.entry("semanticAction", "routine-action-batch/v1"),
          Map.entry("recording", "routine-capture-complete/v1"),
          Map.entry("artifact", "certified-candidate/v2"));
  private static final Set<String> OPERATIONS =
      Set.of(
          "capture",
          "semanticLearning",
          "graph",
          "model",
          "replay",
          "shadow",
          "candidate",
          "managedExecution",
          "semanticProducer",
          "video",
          "simulator");
  private final CurrentBackendRuntime runtime;
  private final CurrentClientArtifacts clientArtifacts;
  private final JsonNode manifest;
  private final String configurationCode;

  /** Pin-only constructor for isolated fixture tests. Production always measures its artifact. */
  public CurrentContractGate(String manifestPath) {
    this(manifestPath, null, null);
  }

  public CurrentContractGate(String manifestPath, CurrentBackendRuntime runtime) {
    this(manifestPath, runtime, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CurrentContractGate(
      @Value("${ai-player.contract.manifest:}") String manifestPath,
      CurrentBackendRuntime runtime,
      CurrentClientArtifacts clientArtifacts) {
    this.runtime = runtime;
    this.clientArtifacts = clientArtifacts;
    JsonNode parsed = null;
    String code = "CONTRACT_UNCONFIGURED";
    if (manifestPath != null && !manifestPath.isBlank()) {
      try {
        Path path = Path.of(manifestPath);
        if (Files.size(path) > 65536) throw new IllegalArgumentException("Manifest too large");
        parsed = CanonicalJsonHasher.parse(Files.readAllBytes(path));
        validateManifest(parsed);
        code = null;
      } catch (Exception exception) {
        parsed = null;
        code = "CONTRACT_MANIFEST_INVALID";
      }
    }
    manifest = parsed;
    configurationCode = code;
  }

  public void requireIdentity(CurrentSliceIdentity identity) {
    if (configurationCode != null) refuse(configurationCode);
    if (runtime != null) {
      String reason = runtime.refusal(manifest.path("backend"));
      if (reason != null) refuse(reason);
    }
    if (identity == null || identity.client() == null) refuse("IDENTITY_GROUP_MISSING");
    JsonNode pin = manifest.path("clients").path(identity.client().clientKind());
    if (!pin.isObject()) refuse("CLIENT_CAPABILITY_INVALID");
    if (clientArtifacts != null) {
      String reason = clientArtifacts.refusal(identity.client().clientKind(), pin);
      if (reason != null) refuse(reason);
    }
    if (!pin.path("build_id").asText().equals(identity.client().clientBuildId())
        || !pin.path("artifact_digest").asText().equals(identity.client().artifactDigest())
        || !pin.path("adapter_contract").asText().equals(identity.client().adapterContract())
        || !pin.path("domain").asText().equals(identity.client().domain())
        || !SCHEMAS.get("identity").equals(identity.schema())) refuse("IDENTITY_MISMATCH");
    if (identity.controlAllowed()) refuse("CONTROL_FORBIDDEN");
    if (identity.source() != null
        && (!pin.path("commit_id").asText().equals(identity.source().commitId())
            || !pin.path("build_id").asText().equals(identity.source().buildId())
            || !pin.path("artifact_digest").asText().equals(identity.source().artifactDigest()))) {
      refuse("IDENTITY_MISMATCH");
    }
  }

  public void requireOperation(CurrentSliceIdentity identity, String operation) {
    requireIdentity(identity);
    if (!OPERATIONS.contains(operation)) refuse("CLIENT_CAPABILITY_INVALID");
    if (!manifest
        .path("clients")
        .path(identity.client().clientKind())
        .path("operations")
        .path(operation)
        .asBoolean(false)) {
      refuse("CAPABILITY_UNAVAILABLE");
    }
  }

  public Map<String, Object> diagnostics() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", SCHEMA);
    result.put("status", configurationCode == null ? "PINNED_EXPECTATIONS" : "BLOCKED");
    result.put("reason", configurationCode == null ? "CURRENT_CONTRACT_PINNED" : configurationCode);
    result.put("controlAllowed", false);
    result.put("runtimeReadinessVerified", false);
    result.put("schemas", SCHEMAS);
    if (runtime != null) {
      result.put("observedBackend", runtime.observed());
      String reason =
          configurationCode != null ? configurationCode : runtime.refusal(manifest.path("backend"));
      result.put("backendRuntimeVerified", reason == null);
      if (reason != null) {
        result.put("status", "BLOCKED");
        result.put("reason", reason);
      }
    }
    if (manifest != null) {
      result.put("backend", manifest.get("backend"));
      result.put("clients", manifest.get("clients"));
      result.put("manifestDigest", CanonicalJsonHasher.hash(manifest, null));
      Map<String, Object> statuses = new LinkedHashMap<>();
      Map<String, String> clientReasons = new LinkedHashMap<>();
      if (clientArtifacts != null)
        result.put("observedClientArtifacts", clientArtifacts.observed());
      manifest
          .path("clients")
          .fields()
          .forEachRemaining(
              entry -> {
                String clientReason =
                    clientArtifacts == null
                        ? null
                        : clientArtifacts.refusal(entry.getKey(), entry.getValue());
                if (clientReason != null) clientReasons.put(entry.getKey(), clientReason);
                Map<String, String> operations = new LinkedHashMap<>();
                for (String operation : OPERATIONS) {
                  operations.put(
                      operation,
                      entry.getValue().path("operations").path(operation).asBoolean(false)
                              && clientReason == null
                              && !"BLOCKED".equals(result.get("status"))
                          ? "AVAILABLE_BY_CONTRACT"
                          : "DEGRADED");
                }
                statuses.put(entry.getKey(), operations);
              });
      result.put("operations", statuses);
      result.put("clientReasons", clientReasons);
    }
    return result;
  }

  private static void validateManifest(JsonNode value) {
    exactFields(value, Set.of("schema", "backend", "schemas", "clients", "controlAllowed"));
    if (!SCHEMA.equals(value.path("schema").asText())) invalid();
    disabled(value.path("controlAllowed"));
    build(value.path("backend"), false);
    JsonNode schemas = value.path("schemas");
    exactFields(schemas, SCHEMAS.keySet());
    SCHEMAS.forEach(
        (key, expected) -> {
          if (!schemas.path(key).isTextual() || !expected.equals(schemas.path(key).textValue()))
            invalid();
        });
    JsonNode clients = value.path("clients");
    if (!clients.isObject() || clients.isEmpty()) invalid();
    clients
        .fields()
        .forEachRemaining(
            entry -> {
              String kind = entry.getKey();
              if (!Set.of("MICRO6_MANAGED", "RUNELITE_CAPTURE").contains(kind)) invalid();
              JsonNode pin = entry.getValue();
              build(pin, true);
              disabled(pin.path("controlAllowed"));
              String adapter =
                  kind.equals("MICRO6_MANAGED") ? "micro6-builder/v2" : "runelite-capture/v1";
              String domain = kind.equals("MICRO6_MANAGED") ? "MICRO6" : "RUNELITE";
              if (!adapter.equals(pin.path("adapter_contract").asText())
                  || !domain.equals(pin.path("domain").asText())) invalid();
              JsonNode operations = pin.path("operations");
              if (!operations.isObject()) invalid();
              operations
                  .fields()
                  .forEachRemaining(
                      operation -> {
                        if (!OPERATIONS.contains(operation.getKey())
                            || !operation.getValue().isBoolean()) invalid();
                      });
              if (kind.equals("RUNELITE_CAPTURE")
                  && operations.path("managedExecution").asBoolean(false)) invalid();
              JsonNode endpoints = pin.path("endpoints");
              if (!endpoints.isArray() || endpoints.isEmpty()) invalid();
              List<String> seen = new ArrayList<>();
              endpoints.forEach(
                  endpoint -> {
                    if (!endpoint.isTextual()
                        || !endpoint.textValue().startsWith("/")
                        || endpoint.textValue().contains(":")
                        || endpoint.textValue().startsWith("//")
                        || seen.contains(endpoint.textValue())) invalid();
                    seen.add(endpoint.textValue());
                  });
            });
  }

  private static void build(JsonNode value, boolean client) {
    exactFields(
        value,
        client
            ? Set.of(
                "build_id",
                "commit_id",
                "artifact_digest",
                "adapter_contract",
                "domain",
                "controlAllowed",
                "operations",
                "endpoints")
            : Set.of("build_id", "commit_id", "artifact_digest"));
    if (!value.path("build_id").isTextual()
        || value.path("build_id").textValue().isBlank()
        || !value.path("commit_id").isTextual()
        || !value.path("commit_id").textValue().matches("[0-9a-f]{40}")
        || !value.path("artifact_digest").isTextual()
        || !CanonicalJsonHasher.isSha256(value.path("artifact_digest").textValue())) invalid();
  }

  private static void disabled(JsonNode value) {
    if (!value.isBoolean() || value.booleanValue()) invalid();
  }

  private static void exactFields(JsonNode value, Set<String> fields) {
    if (!value.isObject() || value.size() != fields.size()) invalid();
    value
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!fields.contains(field)) invalid();
            });
  }

  private static void invalid() {
    throw new IllegalArgumentException("Invalid current manifest");
  }

  private static void refuse(String code) {
    throw new CurrentSliceContractException(CurrentSliceFailureCode.valueOf(code), code);
  }
}
