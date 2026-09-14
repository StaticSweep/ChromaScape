package com.chromascape.web.gameplay.contract.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.EligibilityStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameplayV2EligibilityFilterTest {
  private static final Instant NOW = Instant.parse("2026-09-03T12:00:05Z");
  private ObjectMapper objectMapper;
  private GameplayV2ContractValidator validator;
  private GameplayV2EligibilityFilter filter;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    validator = new GameplayV2ContractValidator(objectMapper);
    filter = new GameplayV2EligibilityFilter(objectMapper);
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      fixtures = objectMapper.readTree(input);
    }
  }

  @Test
  void deterministicFilterReproducesTheGoldenEligibilityResult() {
    JsonNode result =
        filter.filter(
            fixtures.path("catalogSnapshot"),
            fixtures.path("semanticObservation"),
            fixtures.path("planningInput"),
            NOW);
    validator.validateWithRules("eligibilityResult", result);
    assertThat(result).isEqualTo(fixtures.path("eligibilityResult"));
    assertThat(GameplayV2EligibilityFilter.canonicalDigest(objectMapper, result))
        .isEqualTo(fixtures.at("/expectedDigests/eligibilityResult").asText());
    assertThat(GameplayV2EligibilityFilter.eligibleTaskKeys(result))
        .containsExactly("FISHING_TRAINING");
  }

  @Test
  void staleObservationIsRejectedDeterministically() {
    JsonNode stale = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) stale.path("freshness"))
        .put("status", "STALE");
    assertThat(statusOf(filter.filter(catalog(), stale, planningInputFor(stale), NOW)))
        .isEqualTo(EligibilityStatus.STALE_OBSERVATION);

    JsonNode aged = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) aged.path("freshness")).put("maxAgeMs", 1000);
    assertThat(statusOf(filter.filter(catalog(), aged, planningInputFor(aged), NOW)))
        .isEqualTo(EligibilityStatus.STALE_OBSERVATION);
  }

  @Test
  void observationFreshnessRejectsFutureAndHonorsBoundaries() {
    assertThat(statusOf(filterAt(NOW.plusMillis(1))))
        .isEqualTo(EligibilityStatus.STALE_OBSERVATION);
    assertThat(statusOf(filterAt(NOW))).isEqualTo(EligibilityStatus.ELIGIBLE);
    assertThat(statusOf(filterAt(NOW.minusMillis(300000)))).isEqualTo(EligibilityStatus.ELIGIBLE);
    assertThat(statusOf(filterAt(NOW.minusMillis(300001))))
        .isEqualTo(EligibilityStatus.STALE_OBSERVATION);
  }

  @Test
  void simulatorDetectionIsStableUnderTurkishLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      JsonNode observation = fixtures.path("semanticObservation").deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) observation.path("identities"))
          .put("environmentId", "simulation");
      JsonNode planningInput = planningInputFor(observation);
      ((com.fasterxml.jackson.databind.node.ObjectNode) planningInput.path("identities"))
          .put("environmentId", "simulation");
      assertThat(statusOf(filter.filter(catalog(), observation, planningInput, NOW)))
          .isEqualTo(EligibilityStatus.POLICY_DENIED);

      JsonNode invalid = objectMapper.createObjectNode();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> validator.validateWithRules("providerDecision", invalid))
          .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
          .extracting("failureCode")
          .isEqualTo("INVALID_PROVIDER_DECISION");
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void identityMismatchesFailClosedBeforeTaskChecks() {
    JsonNode mismatched = fixtures.path("planningInput").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) mismatched)
        .put(
            "capabilitySnapshotDigest",
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    assertThat(statusOf(filter.filter(catalog(), observation(), mismatched, NOW)))
        .isEqualTo(EligibilityStatus.IDENTITY_MISMATCH);
  }

  @Test
  void policyBudgetAndPrerequisiteReasonsAreClosed() {
    JsonNode overBudget = fixtures.path("planningInput").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) overBudget.path("budgets"))
        .put("maxActions", 20);
    assertThat(statusOf(filter.filter(catalog(), observation(), overBudget, NOW)))
        .isEqualTo(EligibilityStatus.BUDGET_EXHAUSTED);

    JsonNode running = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) running.path("taskState"))
        .put("status", "RUNNING");
    assertThat(statusOf(filter.filter(catalog(), running, planningInputFor(running), NOW)))
        .isEqualTo(EligibilityStatus.PREREQUISITE_UNMET);

    JsonNode unlisted = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) unlisted.path("eligibleTargets")).removeAll();
    assertThat(statusOf(filter.filter(catalog(), unlisted, planningInputFor(unlisted), NOW)))
        .isEqualTo(EligibilityStatus.NO_ELIGIBLE_TASK);
  }

  @Test
  void incompleteAndLowConfidenceObservationsAreStale() {
    JsonNode incomplete = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) incomplete).put("observationComplete", false);
    assertThat(statusOf(filter.filter(catalog(), incomplete, planningInputFor(incomplete), NOW)))
        .isEqualTo(EligibilityStatus.STALE_OBSERVATION);

    JsonNode lowConfidence = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) lowConfidence)
        .put("adapterObservationConfidence", 0.2);
    assertThat(
            statusOf(filter.filter(catalog(), lowConfidence, planningInputFor(lowConfidence), NOW)))
        .isEqualTo(EligibilityStatus.STALE_OBSERVATION);
  }

  @Test
  void catalogRevisionIdentitiesDeadlineAndContradictionsFailClosed() {
    JsonNode revised = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) revised).put("catalogRevision", 99);
    assertThat(statusOf(filter.filter(catalog(), revised, planningInputFor(revised), NOW)))
        .isEqualTo(EligibilityStatus.IDENTITY_MISMATCH);

    JsonNode moved = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) moved.path("identities"))
        .put("accountId", "other-account");
    assertThat(statusOf(filter.filter(catalog(), moved, planningInputFor(moved), NOW)))
        .isEqualTo(EligibilityStatus.IDENTITY_MISMATCH);

    JsonNode expired = fixtures.path("planningInput").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) expired)
        .put("deadline", "2026-08-30T00:00:00Z");
    assertThat(statusOf(filter.filter(catalog(), observation(), expired, NOW)))
        .isEqualTo(EligibilityStatus.POLICY_DENIED);

    JsonNode contradictory = fixtures.path("planningInput").deepCopy();
    com.fasterxml.jackson.databind.node.ObjectNode rejected = objectMapper.createObjectNode();
    rejected.put("taskKey", "FISHING_TRAINING");
    rejected.put("reason", "NO_ELIGIBLE_TASK");
    ((com.fasterxml.jackson.databind.node.ArrayNode) contradictory.path("rejectedEntries"))
        .add(rejected);
    assertThat(statusOf(filter.filter(catalog(), observation(), contradictory, NOW)))
        .isEqualTo(EligibilityStatus.IDENTITY_MISMATCH);
  }

  @Test
  void unsupportedCapabilitiesAndCatalogPreconditionsAreEvaluated() {
    JsonNode catalog = fixtures.path("catalogSnapshot").deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode)
            catalog.path("tasks").path(0).path("requiredCapabilities"))
        .add("FLYING_SEMANTIC_ACTION");
    assertThat(statusOf(filter.filter(catalog, observation(), planningInput(), NOW)))
        .isEqualTo(EligibilityStatus.UNSUPPORTED_CAPABILITY);

    JsonNode stalePrecondition = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) stalePrecondition.path("freshness"))
        .put("status", "STALE");
    // STALE freshness already maps to STALE_OBSERVATION globally; a bare
    // incomplete flag with CURRENT freshness exercises the precondition path.
    JsonNode incompleteCurrent = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) incompleteCurrent)
        .put("observationComplete", false);
    assertThat(
            statusOf(
                filter.filter(
                    catalog(), incompleteCurrent, planningInputFor(incompleteCurrent), NOW)))
        .isEqualTo(EligibilityStatus.STALE_OBSERVATION);
  }

  private JsonNode planningInputFor(JsonNode observation) {
    JsonNode planningInput = fixtures.path("planningInput").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) planningInput)
        .put(
            "observationDigest",
            GameplayV2EligibilityFilter.canonicalDigest(objectMapper, observation));
    return planningInput;
  }

  private JsonNode filterAt(Instant capturedAt) {
    JsonNode observation = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) observation)
        .put("capturedAt", capturedAt.toString());
    return filter.filter(catalog(), observation, planningInputFor(observation), NOW);
  }

  private JsonNode catalog() {
    return fixtures.path("catalogSnapshot");
  }

  private JsonNode observation() {
    return fixtures.path("semanticObservation");
  }

  private JsonNode planningInput() {
    return planningInputFor(fixtures.path("semanticObservation"));
  }

  private EligibilityStatus statusOf(JsonNode result) {
    return result.path("entries").path(0).path("status").isTextual()
        ? GameplayV2Contracts.EligibilityStatus.valueOf(
            result.path("entries").path(0).path("status").asText())
        : null;
  }
}
