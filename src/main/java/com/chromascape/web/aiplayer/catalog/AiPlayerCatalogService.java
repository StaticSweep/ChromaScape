package com.chromascape.web.aiplayer.catalog;

import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Read-only catalog consumption and operator-owned profile creation; never dispatches a task. */
@Service
public class AiPlayerCatalogService {
  private final Micro6ExecutorCatalogService upstream;
  private final CatalogStore store;
  private final ObjectMapper json;

  /** Creates this component with its explicit dependencies or bounded failure details. */
  public AiPlayerCatalogService(
      Micro6ExecutorCatalogService upstream, CatalogStore store, ObjectMapper json) {
    this.upstream = upstream;
    this.store = store;
    this.json = json;
  }

  /** Returns lightweight metadata for an authenticated endpoint reference. */
  public ObjectNode catalog(String endpoint, boolean refresh) {
    return acquire(endpoint, "lightweight", refresh);
  }

  /** Loads the selected task schema and binds it to the expected catalog identity. */
  public ObjectNode detail(String endpoint, String taskKey, String expectedHash, boolean refresh) {
    CatalogNormalizer.canonical(taskKey);
    ObjectNode catalog = acquire(endpoint, "full", refresh);
    if (!Set.of("READY", "STALE", "DEGRADED").contains(catalog.path("state").asText())) {
      throw new CatalogProblem(503, "CATALOG_UNAVAILABLE", "Task settings are unavailable.");
    }
    if (expectedHash != null && !expectedHash.equals(catalog.path("catalog_hash").asText())) {
      throw new CatalogProblem(
          409, "CATALOG_CHANGED", "Catalog changed. Refresh and reselect the task.");
    }
    JsonNode selected = null;
    for (JsonNode task : catalog.path("tasks")) {
      if (task.path("task_key").asText().equals(taskKey)) {
        selected = task;
      }
    }
    if (selected == null) {
      throw new CatalogProblem(404, "TASK_NOT_FOUND", "Canonical task is absent.");
    }
    ObjectNode out = json.createObjectNode();
    out.put("state", catalog.path("state").asText());
    out.put("catalog_hash", catalog.path("catalog_hash").asText());
    out.put("catalog_version", catalog.path("catalog_version").asText());
    out.set("task", selected.deepCopy());
    out.set("catalog_snapshot", catalog.deepCopy());
    String schemaState = selected.path("settings_schema_state").asText();
    if (schemaState.equals("AVAILABLE")) {
      try {
        TaskSettingsValidator.schema(selected.path("args_schema"));
        if (!Micro6CanonicalHash.settingsSchemaHash(selected.path("args_schema"))
            .equals(selected.path("settings_schema_hash").asText())) {
          throw new CatalogProblem(422, "INVALID_SCHEMA", "Settings schema hash is inconsistent.");
        }
        out.put("schema_state", "AVAILABLE");
      } catch (CatalogProblem e) {
        out.put("schema_state", "INVALID");
        out.put("schema_reason", e.getMessage());
      }
    } else {
      out.put("schema_state", "UNAVAILABLE");
      out.put("schema_reason", "Micro6 did not provide an applicable settings schema.");
    }
    out.set("source_summary", source(selected, catalog));
    out.set("method_facts", facts(selected.path("method_facts")));
    return out;
  }

  /** Validates the current schema and settings before appending an immutable version. */
  public ObjectNode save(JsonNode request) {
    Set<String> fields =
        Set.of(
            "profileId", "name", "endpointRef", "taskKey", "catalogHash", "schemaHash", "settings");
    if (request == null || !request.isObject() || request.toString().length() > 100000) {
      throw new CatalogProblem(422, "INVALID_PROFILE", "Profile request is missing or too large.");
    }
    request
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!fields.contains(key)) {
                throw new CatalogProblem(
                    422,
                    "INVALID_PROFILE",
                    "Unknown profile field.",
                    Map.of(key, "Unknown field."));
              }
            });
    Map<String, String> typeErrors = new java.util.LinkedHashMap<>();
    for (String field : List.of("name", "endpointRef", "taskKey", "catalogHash", "schemaHash")) {
      if (!request.path(field).isTextual()) {
        typeErrors.put(field, "Expected a string.");
      }
    }
    if (request.hasNonNull("profileId") && !request.path("profileId").isTextual()) {
      typeErrors.put("profileId", "Expected a string or null.");
    }
    if (!typeErrors.isEmpty()) {
      throw new CatalogProblem(422, "INVALID_PROFILE", "Correct the profile fields.", typeErrors);
    }
    String name = request.path("name").asText().trim();
    if (name.isEmpty() || name.length() > 120) {
      throw new CatalogProblem(
          422,
          "INVALID_PROFILE",
          "Enter a name of 1–120 characters.",
          Map.of("name", "Enter a profile name."));
    }
    ObjectNode detail =
        detail(
            request.path("endpointRef").asText(),
            request.path("taskKey").asText(),
            request.path("catalogHash").asText(),
            true);
    if (!detail.path("state").asText().equals("READY")) {
      throw new CatalogProblem(
          409, "CATALOG_STALE", "Refresh a current catalog before saving a new version.");
    }
    if (!detail.path("schema_state").asText().equals("AVAILABLE")) {
      throw new CatalogProblem(
          422, "SCHEMA_UNAVAILABLE", "Settings cannot be saved without a valid schema.");
    }
    JsonNode task = detail.path("task");
    if (!request.path("schemaHash").asText().equals(task.path("settings_schema_hash").asText())) {
      throw new CatalogProblem(409, "SCHEMA_CHANGED", "Schema changed. Reload task settings.");
    }
    ObjectNode values =
        TaskSettingsValidator.validate(task.path("args_schema"), request.get("settings"));
    return store.append(
        request.hasNonNull("profileId") ? request.path("profileId").asText() : null,
        name,
        detail,
        values);
  }

  private ObjectNode acquire(String endpoint, String mode, boolean refresh) {
    if (endpoint == null || !endpoint.matches("[A-Za-z0-9_.:-]{1,200}")) {
      throw new CatalogProblem(
          422, "INVALID_ENDPOINT_REF", "Choose an authenticated catalog endpoint reference.");
    }
    JsonNode reply = upstream.getCatalogForExecutor(endpoint, mode, refresh);
    JsonNode raw = reply.path("catalog");
    String state = reply.path("catalog_state").asText("UNAVAILABLE");
    boolean transientFailure = transientFailure(reply);
    if (raw.isObject()
        && Set.of("READY", "STALE", "DEGRADED").contains(state)
        && (!state.equals("STALE") || transientFailure)) {
      try {
        ObjectNode normalized = CatalogNormalizer.normalize(raw, mode.equals("full"));
        normalized.put("state", state);
        normalized.put("endpoint_ref", endpoint);
        normalized.put("mode", mode);
        normalized.set("reasons", reply.path("reasons").deepCopy());
        normalized.put(
            "captured_at",
            reply
                .path("captured_at")
                .asText(state.equals("STALE") ? "unknown" : java.time.Instant.now().toString()));
        if (state.equals("READY")) {
          store.cache(endpoint, mode, normalized);
        }
        return normalized;
      } catch (CatalogProblem problem) {
        if (problem.code().equals("PROFILE_STORAGE_UNAVAILABLE")) {
          throw problem;
        }
        ObjectNode invalid = json.createObjectNode();
        invalid.put("state", "INVALID");
        invalid.put("reason", problem.getMessage());
        invalid.putArray("tasks");
        return invalid;
      }
    }
    // Only named transient failures permit stale metadata. Authorization/identity/hash errors
    // never expose a persisted cache under a possibly reassigned endpoint reference.
    String reason = reply.path("reason_code").asText();
    if (transientFailure) {
      ObjectNode cached = store.cached(endpoint, mode);
      if (cached != null) {
        cached.put("state", "STALE");
        cached.put("reason", "Micro6 is unavailable; showing the saved last-known-good catalog.");
        return cached;
      }
    }
    ObjectNode empty = json.createObjectNode();
    empty.put("state", state.equals("GENERATING") ? "LOADING" : "UNAVAILABLE");
    empty.put("reason", reason.isBlank() ? "No catalog is available." : reason);
    empty.putArray("tasks");
    return empty;
  }

  private boolean transientFailure(JsonNode reply) {
    String reason = reply.path("reason_code").asText();
    String detail = reply.path("detailed_reason_code").asText();
    if (reason.equals("EXECUTOR_ERROR")) {
      return detail.matches("CATALOG_HTTP_5[0-9]{2}");
    }
    if (reason.equals("REGISTRY_FAILURE") && !detail.isBlank()) {
      return detail.equals("registry_unreachable") || detail.matches("registry_http_5[0-9]{2}");
    }
    return Set.of(
            "REGISTRY_FAILURE",
            "RUNTIME_NOT_SELECTABLE",
            "EXECUTOR_UNAVAILABLE",
            "RUNTIME_OFFLINE",
            "CATALOG_UNAVAILABLE",
            "FETCH_FAILED",
            "CATALOG_FETCH_FAILED",
            "RUNTIME_UNAVAILABLE",
            "CATALOG_NOT_READY",
            "SCHEMA_BUNDLE_NOT_READY",
            "EXECUTOR_UNREACHABLE")
        .contains(reason);
  }

  private ObjectNode source(JsonNode task, JsonNode catalog) {
    ObjectNode out = json.createObjectNode();
    JsonNode summary = task.path("source_summary");
    for (String key :
        List.of(
            "entry_class",
            "module",
            "commit_id",
            "tree_id",
            "build_id",
            "artifact_digest",
            "source_snapshot_id",
            "source_manifest_hash",
            "availability",
            "components",
            "settings_read_sites",
            "recording_id",
            "tick")) {
      JsonNode value = summary.get(key);
      if (value != null) {
        out.set(key, value.deepCopy());
      } else if (task.has(key)) {
        out.set(key, task.get(key).deepCopy());
      } else if (catalog.has(key)) {
        out.set(key, catalog.get(key).deepCopy());
      }
    }
    for (String key :
        List.of(
            "entry_class",
            "module",
            "commit_id",
            "tree_id",
            "build_id",
            "artifact_digest",
            "source_snapshot_id",
            "source_manifest_hash")) {
      if (!out.has(key)) {
        out.putNull(key);
      }
    }
    if (!out.has("availability")) {
      out.put("availability", "UNKNOWN");
    }
    out.put("content_authority", "SC-810 exact task/build/snapshot; summary only");
    boolean exact =
        out.path("source_manifest_hash").asText().matches("sha256:[a-f0-9]{64}")
            && out.path("source_snapshot_id")
                .asText()
                .equals("srcsnap_" + out.path("source_manifest_hash").asText().substring(7, 31));
    out.put("mapping_support", exact ? "REQUIRES_SC811_EVIDENCE" : "UNKNOWN");
    return out;
  }

  private ObjectNode facts(JsonNode facts) {
    if (!facts.isObject()) {
      ObjectNode unknown = json.createObjectNode();
      unknown.put("state", "UNKNOWN");
      return unknown;
    }
    if (facts.toString().length() > 32768
        || !facts.path("version").isValueNode()
        || !facts.path("citations").isArray()
        || facts.path("citations").isEmpty()
        || !facts.hasNonNull("observed_at")
        || !facts.hasNonNull("uncertainty")) {
      throw new CatalogProblem(
          422,
          "INVALID_METHOD_FACTS",
          "Method facts require bounded version, citations, freshness and uncertainty.");
    }
    for (JsonNode citation : facts.path("citations")) {
      String uri = citation.isTextual() ? citation.asText() : citation.path("url").asText();
      if (!uri.matches("https://[^\\s]{1,2000}")) {
        throw new CatalogProblem(
            422, "INVALID_METHOD_FACTS", "Method facts require HTTPS source citations.");
      }
    }
    ObjectNode out = ((ObjectNode) facts).deepCopy();
    boolean stale = facts.path("stale").asBoolean();
    try {
      java.time.Instant observed = java.time.Instant.parse(facts.path("observed_at").asText());
      if (observed.isAfter(java.time.Instant.now())) {
        throw new IllegalArgumentException("Future observation");
      }
      if (facts.hasNonNull("valid_until")) {
        java.time.Instant until = java.time.Instant.parse(facts.path("valid_until").asText());
        if (until.isBefore(observed)) {
          throw new IllegalArgumentException("Invalid freshness interval");
        }
        stale |= until.isBefore(java.time.Instant.now());
      }
    } catch (Exception e) {
      throw new CatalogProblem(
          422, "INVALID_METHOD_FACTS", "Method facts require valid UTC freshness timestamps.");
    }
    out.put("state", stale ? "UNKNOWN_STALE" : "CITED");
    return out;
  }
}
