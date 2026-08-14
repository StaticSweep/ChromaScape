package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract.CatalogState;
import com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract.ValidationResult;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class Micro6CatalogV2ContractTest {

  @Test
  void validLightweightCatalogIsAccepted() {
    ValidationResult result =
        Micro6CatalogV2Contract.validateLightweight(TestMicro6Catalog.lightweight());
    assertThat(result.valid()).as("reasons: %s", result.reasons()).isTrue();
    assertThat(result.catalogHash()).isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    assertThat(Micro6CatalogV2Contract.classify(TestMicro6Catalog.lightweight(), result))
        .isEqualTo(CatalogState.READY);
  }

  @Test
  void validFullCatalogIsAccepted() {
    ValidationResult result = Micro6CatalogV2Contract.validateFull(TestMicro6Catalog.full());
    assertThat(result.valid()).as("reasons: %s", result.reasons()).isTrue();
    assertThat(result.catalogHash()).isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    assertThat(result.schemaBundleHash()).isEqualTo(TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
  }

  @Test
  void lightweightRequiresNotRequestedSchemaState() {
    ObjectNode payload = TestMicro6Catalog.lightweight();
    ((ObjectNode) payload.path("tasks").get(0)).put("settings_schema_state", "AVAILABLE");
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).contains("task_schema_state_not_not_requested:FISHING_TRAINING");
  }

  @Test
  void duplicateTaskKeysFailValidation() {
    ObjectNode payload = TestMicro6Catalog.lightweight();
    ArrayNode tasks = (ArrayNode) payload.path("tasks");
    tasks.add(tasks.get(0).deepCopy());
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).anyMatch(r -> r.contains("task_duplicate_key:FISHING_TRAINING"));
  }

  @Test
  void displayPrefixedRowsFailValidation() {
    ObjectNode payload = TestMicro6Catalog.lightweight();
    ObjectNode display = TestMicro6Catalog.task(false).deepCopy();
    display.put("task_key", "DISPLAY_FISHING_TRAINING");
    ((ArrayNode) payload.path("tasks")).add(display);
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).anyMatch(r -> r.contains("DISPLAY_"));
  }

  @Test
  void missingAliasTargetFailsValidation() {
    ObjectNode payload = TestMicro6Catalog.lightweight();
    ObjectNode alias = (ObjectNode) payload.path("aliases").get(0).deepCopy();
    alias.put("task_key", "FISHING_TRAINING_V2_MISSING");
    ((ArrayNode) payload.path("aliases")).add(alias);
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).anyMatch(r -> r.contains("alias_target_missing"));
  }

  @Test
  void emptyTasksNeverPassAsValid() {
    ObjectNode payload = TestMicro6Catalog.lightweight();
    payload.set("tasks", new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode());
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(payload);
    assertThat(result.valid()).isFalse();
    assertThat(Micro6CatalogV2Contract.classify(payload, result))
        .isEqualTo(CatalogState.UNAVAILABLE);
  }

  @Test
  void catalogHashMismatchFailsFullValidation() {
    ObjectNode payload = TestMicro6Catalog.full();
    ((ObjectNode) payload.path("tasks").get(0)).put("display_name", "Altered Name");
    // Keep the attested (now stale) hash so the mismatch is provable.
    payload.put("catalog_hash", TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).contains("catalog_hash_mismatch");
  }

  @Test
  void schemaBundleHashMismatchFailsFullValidation() {
    ObjectNode payload = TestMicro6Catalog.full();
    payload.put("schema_bundle_hash", "sha256:" + "a".repeat(64));
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).contains("schema_bundle_hash_mismatch");
  }

  @Test
  void perTaskSchemaHashMismatchFailsFullValidation() {
    ObjectNode payload = TestMicro6Catalog.full();
    ((ObjectNode) payload.path("tasks").get(0))
        .put("settings_schema_hash", "sha256:" + "b".repeat(64));
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).anyMatch(r -> r.contains("task_schema_hash_mismatch"));
  }

  @Test
  void missingSchemaBundleHashFailsReadyValidation() {
    ObjectNode payload = TestMicro6Catalog.full();
    payload.remove("schema_bundle_hash");
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).contains("schema_bundle_hash_missing");
  }

  @Test
  void degradedCatalogIsAcceptedAsDegraded() {
    // A DEGRADED catalog is still selectable and cacheable: its complete degraded bundle (covering
    // available schemas, non-applicable states, and schema errors) must verify.
    ObjectNode payload = TestMicro6Catalog.full();
    payload.put("catalog_state", "DEGRADED");
    payload.put("schema_bundle_hash", TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).as("reasons: %s", result.reasons()).isTrue();
    assertThat(Micro6CatalogV2Contract.classify(payload, result)).isEqualTo(CatalogState.DEGRADED);
  }

  @Test
  void degradedCatalogWithoutAttestedBundleFailsValidation() {
    ObjectNode payload = TestMicro6Catalog.full();
    payload.put("catalog_state", "DEGRADED");
    payload.remove("schema_bundle_hash");
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).contains("schema_bundle_hash_missing");
  }

  @Test
  void malformedPayloadIsRejected() {
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(null);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).contains("catalog_payload_missing");
  }

  @Test
  void taskSchemaStateForFullMustBeAvailableWithSchema() {
    ObjectNode payload = TestMicro6Catalog.full();
    ObjectNode task = (ObjectNode) payload.path("tasks").get(0);
    task.put("settings_schema_state", "AVAILABLE");
    task.remove("args_schema");
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).anyMatch(r -> r.contains("task_schema_missing"));
  }

  @Test
  void aliasesResolveToCanonicalFishingTraining() {
    ValidationResult result =
        Micro6CatalogV2Contract.validateLightweight(TestMicro6Catalog.lightweight());
    assertThat(result.valid()).isTrue();
    List<String> reasons = result.reasons();
    assertThat(reasons).doesNotContain("alias_target_missing:FISH_TRAINING->FISHING_TRAINING");
  }

  @Test
  void notApplicableSchemaStateRequiresReason() {
    ObjectNode payload = TestMicro6Catalog.full();
    ObjectNode task = (ObjectNode) payload.path("tasks").get(0);
    task.put("settings_schema_state", "NOT_APPLICABLE");
    task.remove("args_schema");
    task.remove("settings_schema_hash");
    task.remove("settings_schema_version");
    TestMicro6Catalog.rehashBundle(payload);

    ValidationResult missing = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(missing.valid()).isFalse();
    assertThat(missing.reasons())
        .anyMatch(r -> r.contains("task_schema_not_applicable_reason_missing:FISHING_TRAINING"));

    task.put("schema_not_applicable_reason", "settings are managed by the host");
    TestMicro6Catalog.rehashBundle(payload);
    ValidationResult withReason = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(withReason.valid()).as("reasons: %s", withReason.reasons()).isTrue();
  }

  @Test
  void errorSchemaStateRequiresReasonAndNeverClassifiesAsReady() {
    ObjectNode payload = TestMicro6Catalog.full();
    ObjectNode task = (ObjectNode) payload.path("tasks").get(0);
    task.put("settings_schema_state", "ERROR");
    task.remove("args_schema");
    task.remove("settings_schema_hash");
    task.remove("settings_schema_version");
    TestMicro6Catalog.rehashBundle(payload);

    ValidationResult missing = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(missing.valid()).isFalse();
    assertThat(missing.reasons())
        .anyMatch(r -> r.contains("task_schema_error_reason_missing:FISHING_TRAINING"));

    task.put("schema_error_reason", "schema compilation failed");
    TestMicro6Catalog.rehashBundle(payload);
    ValidationResult withReason = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(withReason.valid()).as("reasons: %s", withReason.reasons()).isTrue();
    // Even a catalog_state READY payload with an ERROR task is never usable as READY.
    assertThat(Micro6CatalogV2Contract.classify(payload, withReason))
        .isEqualTo(CatalogState.DEGRADED);
  }

  @Test
  void unknownSchemaStateIsInvalid() {
    ObjectNode payload = TestMicro6Catalog.full();
    ((ObjectNode) payload.path("tasks").get(0)).put("settings_schema_state", "WAT");
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_state_invalid:FISHING_TRAINING:WAT"));
  }

  @Test
  void missingSchemaStateIsInvalidInFullMode() {
    ObjectNode payload = TestMicro6Catalog.full();
    ((ObjectNode) payload.path("tasks").get(0)).remove("settings_schema_state");
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_state_missing:FISHING_TRAINING"));
  }

  @Test
  void notRequestedSchemaStateMustNotCarrySchemaContentInFullMode() {
    ObjectNode payload = TestMicro6Catalog.full();
    ObjectNode task = (ObjectNode) payload.path("tasks").get(0);
    task.put("settings_schema_state", "NOT_REQUESTED");
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_not_requested_has_args:FISHING_TRAINING"));
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_not_requested_has_hash:FISHING_TRAINING"));
  }

  @Test
  void notRequestedSchemaStateMustNotCarrySchemaContentInLightweightMode() {
    ObjectNode payload = TestMicro6Catalog.lightweight();
    ObjectNode task = (ObjectNode) payload.path("tasks").get(0);
    task.put("settings_schema_state", "NOT_REQUESTED");
    task.set("args_schema", TestMicro6Catalog.schema());
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_not_requested_has_args:FISHING_TRAINING"));
  }

  @Test
  void notRequestedForbidsSchemaVersionAndReasonFields() {
    ObjectNode payload = TestMicro6Catalog.lightweight();
    ObjectNode task = (ObjectNode) payload.path("tasks").get(0);
    task.put("settings_schema_state", "NOT_REQUESTED");
    task.put("settings_schema_version", "1");
    task.put("schema_error_reason", "stale_error");
    task.set("schema_validation_errors", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode());
    task.put("schema_not_applicable_reason", "stale_reason");
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_not_requested_has_version:FISHING_TRAINING"));
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_not_requested_has_error_reason:FISHING_TRAINING"));
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_not_requested_has_validation_errors:FISHING_TRAINING"));
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_not_requested_has_not_applicable_reason:FISHING_TRAINING"));
  }

  @Test
  void errorSchemaStateRejectsNonArrayValidationErrors() {
    ObjectNode payload = TestMicro6Catalog.full();
    ObjectNode task = (ObjectNode) payload.path("tasks").get(0);
    task.put("settings_schema_state", "ERROR");
    task.remove("args_schema");
    task.remove("settings_schema_hash");
    task.remove("settings_schema_version");
    task.put("schema_error_reason", "schema_validation_failed");
    task.put("schema_validation_errors", "not-an-array");
    TestMicro6Catalog.rehashBundle(payload);
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons())
        .anyMatch(r -> r.contains("task_schema_validation_errors_not_array:FISHING_TRAINING"));
  }

  @Test
  void readyCatalogWithCatalogValidFalseIsRejected() {
    ObjectNode payload = TestMicro6Catalog.full();
    payload.put("catalog_valid", false);
    ValidationResult result = Micro6CatalogV2Contract.validateFull(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).contains("catalog_valid_false_for_ready");
  }

  @Test
  void readyCatalogWithCatalogValidFalseIsRejectedInLightweightMode() {
    ObjectNode payload = TestMicro6Catalog.lightweight();
    payload.put("catalog_valid", false);
    ValidationResult result = Micro6CatalogV2Contract.validateLightweight(payload);
    assertThat(result.valid()).isFalse();
    assertThat(result.reasons()).contains("catalog_valid_false_for_ready");
  }
}
