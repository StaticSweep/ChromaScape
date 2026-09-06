package com.chromascape.web.integration.micro6.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates Micro6 catalog v2 payloads and recomputes the canonical hashes that ChromaScape must
 * verify before a catalog enters the cache.
 *
 * <p>A hash mismatch or structural violation is a permanent contract error: the payload is never
 * cached, never merged with last-known-good data, and always reported with explicit reason codes so
 * operators can distinguish a malformed executor from a merely unavailable one.
 */
public final class Micro6CatalogV2Contract {
  private static final Pattern CANONICAL_TASK_KEY = Pattern.compile("^[A-Z][A-Z0-9_]*$");
  private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
  private static final String SCHEMA_STATE_AVAILABLE = "AVAILABLE";
  private static final String SCHEMA_STATE_NOT_REQUESTED = "NOT_REQUESTED";
  private static final String SCHEMA_STATE_NOT_APPLICABLE = "NOT_APPLICABLE";
  private static final String SCHEMA_STATE_ERROR = "ERROR";

  private Micro6CatalogV2Contract() {}

  /** Catalog consumption states surfaced by ChromaScape. */
  public enum CatalogState {
    READY,
    DEGRADED,
    GENERATING,
    STALE,
    UNAVAILABLE
  }

  /** Result of contract validation with recomputed hashes. */
  public static final class ValidationResult {
    private final boolean valid;
    private final List<String> reasons;
    private final String catalogHash;
    private final String schemaBundleHash;

    ValidationResult(
        boolean valid, List<String> reasons, String catalogHash, String schemaBundleHash) {
      this.valid = valid;
      this.reasons = List.copyOf(reasons);
      this.catalogHash = catalogHash;
      this.schemaBundleHash = schemaBundleHash;
    }

    public boolean valid() {
      return valid;
    }

    public List<String> reasons() {
      return reasons;
    }

    public String catalogHash() {
      return catalogHash;
    }

    public String schemaBundleHash() {
      return schemaBundleHash;
    }
  }

  /**
   * Validates a lightweight catalog response. Lightweight payloads carry no schemas, so hash
   * recomputation covers only the canonical catalog hash.
   */
  public static ValidationResult validateLightweight(JsonNode payload) {
    List<String> reasons = new ArrayList<>();
    if (payload == null || !payload.isObject()) {
      return invalid(List.of("catalog_payload_missing"), null, null);
    }

    if (!"2".equals(text(payload.get("version")))
        && !"2".equals(text(payload.get("catalog_version")))) {
      reasons.add("catalog_version_not_2");
    }
    if (!"LIGHTWEIGHT".equalsIgnoreCase(text(payload.get("catalog_mode")))) {
      reasons.add("catalog_mode_not_lightweight");
    }
    String state = text(payload.get("catalog_state"));
    if (!"READY".equalsIgnoreCase(state)
        && !"DEGRADED".equalsIgnoreCase(state)
        && !"UNAVAILABLE".equalsIgnoreCase(state)) {
      reasons.add("catalog_state_invalid:" + state);
    }
    validateStateConsistency(payload, state, reasons);

    JsonNode tasks = payload.get("tasks");
    if (tasks == null || !tasks.isArray() || tasks.isEmpty()) {
      reasons.add("tasks_empty");
    }
    validateTasks(payload, false, reasons);

    if (!payload.has("schema_not_requested_count")) {
      reasons.add("schema_not_requested_count_missing");
    }
    validateAliases(payload, reasons);

    String attestedCatalogHash = text(payload.get("catalog_hash"));
    if (!isSha256(attestedCatalogHash)) {
      reasons.add("catalog_hash_malformed");
    }
    String recomputedCatalogHash = Micro6CanonicalHash.catalogHash(payload, "micro6-executor/v1");
    if (attestedCatalogHash != null && !attestedCatalogHash.equals(recomputedCatalogHash)) {
      reasons.add("catalog_hash_mismatch");
    }

    return new ValidationResult(reasons.isEmpty(), reasons, recomputedCatalogHash, null);
  }

  /**
   * Validates a full catalog response and recomputes {@code catalog_hash}, per-task {@code
   * settings_schema_hash}, and {@code schema_bundle_hash}, comparing each against the attested
   * values. Any mismatch is a permanent contract error.
   */
  public static ValidationResult validateFull(JsonNode payload) {
    List<String> reasons = new ArrayList<>();
    if (payload == null || !payload.isObject()) {
      return invalid(List.of("catalog_payload_missing"), null, null);
    }

    if (!"2".equals(text(payload.get("version")))
        && !"2".equals(text(payload.get("catalog_version")))) {
      reasons.add("catalog_version_not_2");
    }
    if (!"FULL".equalsIgnoreCase(text(payload.get("catalog_mode")))) {
      reasons.add("catalog_mode_not_full");
    }
    String state = text(payload.get("catalog_state"));
    if (!"READY".equalsIgnoreCase(state)
        && !"DEGRADED".equalsIgnoreCase(state)
        && !"UNAVAILABLE".equalsIgnoreCase(state)) {
      reasons.add("catalog_state_invalid:" + state);
    }
    validateStateConsistency(payload, state, reasons);

    JsonNode tasks = payload.get("tasks");
    if (tasks == null || !tasks.isArray() || tasks.isEmpty()) {
      reasons.add("tasks_empty");
    }
    validateTasks(payload, true, reasons);
    validateAliases(payload, reasons);

    // Catalog hash recomputation must match the executor exactly.
    String attestedCatalogHash = text(payload.get("catalog_hash"));
    if (!isSha256(attestedCatalogHash)) {
      reasons.add("catalog_hash_malformed");
    }
    String recomputedCatalogHash = Micro6CanonicalHash.catalogHash(payload, "micro6-executor/v1");
    if (attestedCatalogHash != null && !attestedCatalogHash.equals(recomputedCatalogHash)) {
      reasons.add("catalog_hash_mismatch");
    }

    // Per-task settings schema hashes must match the executor's canonicalization.
    validateSchemaHashes(payload, reasons);

    // CatalogV2Publication emits a bundle only when at least one task has an object args_schema.
    // Full catalogs containing solely NOT_APPLICABLE or ERROR entries therefore legitimately have
    // no schema_bundle_hash; requiring one would reject a producer-valid degraded catalog.
    String attestedBundle = text(payload.get("schema_bundle_hash"));
    String recomputedBundle =
        Micro6CanonicalHash.schemaBundleHash(payload, recomputedCatalogHash);
    if (recomputedBundle != null) {
      if (attestedBundle != null
          && !attestedBundle.equals(recomputedBundle)) {
        reasons.add("schema_bundle_hash_mismatch");
      }
      if (attestedBundle == null || !isSha256(attestedBundle)) {
        reasons.add("schema_bundle_hash_missing");
      }
    } else if (attestedBundle != null) {
      reasons.add("schema_bundle_hash_unexpected");
    }

    return new ValidationResult(
        reasons.isEmpty(), reasons, recomputedCatalogHash, safeSchemaBundleHash(payload));
  }

  private static void validateTasks(JsonNode payload, boolean full, List<String> reasons) {
    JsonNode tasks = payload.get("tasks");
    if (tasks == null || !tasks.isArray()) {
      return;
    }
    Set<String> seenKeys = new HashSet<>();
    for (JsonNode task : tasks) {
      if (task == null || !task.isObject()) {
        reasons.add("task_malformed");
        continue;
      }
      String taskKey = text(task.get("task_key"));
      if (taskKey == null || taskKey.isBlank()) {
        reasons.add("task_missing_key");
        continue;
      }
      if (taskKey.startsWith("DISPLAY_") || taskKey.startsWith("DISPLAY::")) {
        reasons.add("task_display_prefix:" + taskKey);
      }
      if (!CANONICAL_TASK_KEY.matcher(taskKey).matches()) {
        reasons.add("task_invalid_key:" + taskKey);
      }
      if (!seenKeys.add(taskKey)) {
        reasons.add("task_duplicate_key:" + taskKey);
      }
      if (!task.has("display_name")) {
        reasons.add("task_missing_display_name:" + taskKey);
      }
      if (!task.has("category_family")) {
        reasons.add("task_missing_category_family:" + taskKey);
      }
      if (!task.has("runnable") || !task.get("runnable").isBoolean()) {
        reasons.add("task_missing_runnable:" + taskKey);
      }
      validateTaskSchemaState(task, full, taskKey, reasons);
    }
  }

  /**
   * Enforces the explicit per-task schema state contract.
   *
   * <p>{@code AVAILABLE} requires an object {@code args_schema}, a valid schema hash, and a {@code
   * settings_schema_version}. {@code NOT_REQUESTED} must not carry schema content. {@code
   * NOT_APPLICABLE} requires a non-empty {@code schema_not_applicable_reason}. {@code ERROR}
   * requires a non-empty {@code schema_error_reason} and is never usable as {@code READY}. Any other
   * state is invalid. Lightweight mode requires {@code NOT_REQUESTED} for every task.
   */
  private static void validateTaskSchemaState(
      JsonNode task, boolean full, String taskKey, List<String> reasons) {
    if (!full) {
      String state = text(task.get("settings_schema_state"));
      if (!SCHEMA_STATE_NOT_REQUESTED.equals(state)) {
        reasons.add("task_schema_state_not_not_requested:" + taskKey);
        return;
      }
      validateNotRequestedFields(task, taskKey, reasons);
      return;
    }
    String state = text(task.get("settings_schema_state"));
    if (state == null) {
      reasons.add("task_schema_state_missing:" + taskKey);
      return;
    }
    switch (state) {
      case SCHEMA_STATE_AVAILABLE:
        JsonNode argsSchema = task.get("args_schema");
        if (argsSchema == null || !argsSchema.isObject()) {
          reasons.add("task_schema_missing:" + taskKey);
        }
        if (!isSha256(text(task.get("settings_schema_hash")))) {
          reasons.add("task_schema_hash_malformed:" + taskKey);
        }
        if (!task.has("settings_schema_version")) {
          reasons.add("task_schema_version_missing:" + taskKey);
        }
        break;
      case SCHEMA_STATE_NOT_REQUESTED:
        validateNotRequestedFields(task, taskKey, reasons);
        break;
      case SCHEMA_STATE_NOT_APPLICABLE:
        if (isBlank(text(task.get("schema_not_applicable_reason")))) {
          reasons.add("task_schema_not_applicable_reason_missing:" + taskKey);
        }
        break;
      case SCHEMA_STATE_ERROR:
        if (isBlank(text(task.get("schema_error_reason")))) {
          reasons.add("task_schema_error_reason_missing:" + taskKey);
        }
        JsonNode validationErrors = task.get("schema_validation_errors");
        if (validationErrors != null && !validationErrors.isArray()) {
          reasons.add("task_schema_validation_errors_not_array:" + taskKey);
        }
        break;
      default:
        reasons.add("task_schema_state_invalid:" + taskKey + ":" + state);
    }
  }

  /**
   * A {@code NOT_REQUESTED} row must not carry any schema-bearing field: no schema object, hash,
   * version, or reason. The legacy {@code settings_unavailable_reason} is tolerated only while the
   * producer compatibility shim is scheduled for removal.
   */
  private static void validateNotRequestedFields(
      JsonNode task, String taskKey, List<String> reasons) {
    if (task.has("args_schema")) {
      reasons.add("task_schema_not_requested_has_args:" + taskKey);
    }
    if (task.has("settings_schema_hash")) {
      reasons.add("task_schema_not_requested_has_hash:" + taskKey);
    }
    if (task.has("settings_schema_version")) {
      reasons.add("task_schema_not_requested_has_version:" + taskKey);
    }
    if (task.has("schema_error_reason")) {
      reasons.add("task_schema_not_requested_has_error_reason:" + taskKey);
    }
    if (task.has("schema_validation_errors")) {
      reasons.add("task_schema_not_requested_has_validation_errors:" + taskKey);
    }
    if (task.has("schema_not_applicable_reason")) {
      reasons.add("task_schema_not_requested_has_not_applicable_reason:" + taskKey);
    }
  }

  /**
   * {@code catalog_state: READY} requires {@code catalog_valid: true}. A {@code DEGRADED} catalog
   * may allow some schema failures; {@code UNAVAILABLE} must never be treated as usable.
   */
  private static void validateStateConsistency(JsonNode payload, String state, List<String> reasons) {
    JsonNode catalogValid = payload.get("catalog_valid");
    if (catalogValid == null || !catalogValid.isBoolean()) {
      reasons.add("catalog_valid_missing");
      return;
    }
    if ("READY".equalsIgnoreCase(state) && !catalogValid.asBoolean()) {
      reasons.add("catalog_valid_false_for_ready");
    }
  }

  private static void validateAliases(JsonNode payload, List<String> reasons) {
    JsonNode aliases = payload.get("aliases");
    if (aliases == null || !aliases.isArray()) {
      return;
    }
    Set<String> taskKeys = new HashSet<>();
    JsonNode tasks = payload.get("tasks");
    if (tasks != null && tasks.isArray()) {
      tasks.forEach(
          task -> {
            if (task != null && task.has("task_key")) {
              taskKeys.add(text(task.get("task_key")));
            }
          });
    }
    Set<String> seenAliases = new HashSet<>();
    for (JsonNode alias : aliases) {
      if (alias == null || !alias.isObject()) {
        reasons.add("alias_malformed");
        continue;
      }
      String aliasKey = text(alias.get("normalized_alias"));
      if (aliasKey == null || aliasKey.isBlank()) {
        reasons.add("alias_missing_normalized");
        continue;
      }
      if (!seenAliases.add(aliasKey)) {
        reasons.add("alias_duplicate:" + aliasKey);
      }
      String target = text(alias.get("task_key"));
      if (target == null || target.isBlank()) {
        reasons.add("alias_missing_target:" + aliasKey);
      } else if (!taskKeys.contains(target)) {
        reasons.add("alias_target_missing:" + aliasKey + "->" + target);
      }
    }
  }

  private static void validateSchemaHashes(JsonNode payload, List<String> reasons) {
    JsonNode tasks = payload.get("tasks");
    if (tasks == null || !tasks.isArray()) {
      return;
    }
    for (JsonNode task : tasks) {
      if (task == null || !task.isObject()) {
        continue;
      }
      String taskKey = text(task.get("task_key"));
      if (taskKey == null) {
        continue;
      }
      String schemaState = text(task.get("settings_schema_state"));
      if (!SCHEMA_STATE_AVAILABLE.equals(schemaState)) {
        continue;
      }
      JsonNode argsSchema = task.get("args_schema");
      if (argsSchema == null || !argsSchema.isObject()) {
        continue;
      }
      String attested = text(task.get("settings_schema_hash"));
      String recomputed = Micro6CanonicalHash.settingsSchemaHash(argsSchema);
      if (attested != null && recomputed != null && !attested.equals(recomputed)) {
        reasons.add("task_schema_hash_mismatch:" + taskKey);
      }
    }
  }

  /** Classifies a verified catalog payload into a ChromaScape consumption state. */
  public static CatalogState classify(JsonNode payload, ValidationResult validation) {
    if (payload == null || !payload.isObject()) {
      return CatalogState.UNAVAILABLE;
    }
    if (validation == null || !validation.valid()) {
      return CatalogState.UNAVAILABLE;
    }
    // Any task in schema state ERROR is never usable as READY; the catalog degrades.
    if (hasSchemaErrorTasks(payload)) {
      return CatalogState.DEGRADED;
    }
    String state = text(payload.get("catalog_state"));
    if ("READY".equalsIgnoreCase(state)) {
      return CatalogState.READY;
    }
    if ("DEGRADED".equalsIgnoreCase(state)) {
      return CatalogState.DEGRADED;
    }
    return CatalogState.UNAVAILABLE;
  }

  private static boolean hasSchemaErrorTasks(JsonNode payload) {
    JsonNode tasks = payload.get("tasks");
    if (tasks == null || !tasks.isArray()) {
      return false;
    }
    for (JsonNode task : tasks) {
      if (task != null && SCHEMA_STATE_ERROR.equals(text(task.get("settings_schema_state")))) {
        return true;
      }
    }
    return false;
  }

  private static String safeSchemaBundleHash(JsonNode payload) {
    String hash = text(payload.get("schema_bundle_hash"));
    return isSha256(hash) ? hash : null;
  }

  private static boolean isSha256(String value) {
    if (value == null || !value.startsWith("sha256:")) {
      return false;
    }
    return SHA256_HEX.matcher(value.substring("sha256:".length())).matches();
  }

  private static ValidationResult invalid(
      List<String> reasons, String catalogHash, String schemaBundleHash) {
    return new ValidationResult(false, reasons, catalogHash, schemaBundleHash);
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    String value = node.isTextual() ? node.textValue() : node.asText();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
