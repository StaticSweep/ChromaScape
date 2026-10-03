package com.chromascape.web.gameplay.contract.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator.GameplayV2ContractViolation;
import com.chromascape.web.gameplay.provider.v2.ClaudeGameplayV2ProviderAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Catalog-bound sealed-eligibility guarantees for SC-1429 v2: the seal deep-copies its inputs,
 * never exposes its internal mutable node, and binds the canonical catalog digest so catalog
 * substitution is rejected at the adapter boundary.
 *
 * <p>Snapshot-isolation note: the mutation tests below are single-threaded snapshot-isolation
 * tests, not literal concurrent-mutation races. They prove the seal and the adapter copy every
 * mutable {@code JsonNode} at method entry and operate exclusively on the snapshot, so a caller
 * mutation before entry is observed consistently while a mutation after entry (or after return)
 * cannot fabricate sealed state or leak into a returned decision.
 */
class GameplayV2SealedEligibilityTest {
  private static final Instant NOW = Instant.parse("2026-09-03T12:04:00Z");
  private ObjectMapper objectMapper;
  private GameplayV2ContractValidator validator;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    validator = new GameplayV2ContractValidator(objectMapper);
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      fixtures = objectMapper.readTree(input);
    }
  }

  private GameplayV2ValidatedEligibility seal() {
    return GameplayV2ValidatedEligibility.seal(
        validator,
        fixtures.path("catalogSnapshot"),
        fixtures.path("semanticObservation"),
        fixtures.path("planningInput"),
        fixtures.path("eligibilityResult"),
        NOW);
  }

  @Test
  void mutatingTheCallerOriginalAfterSealCannotFabricateTheSealedResult() {
    JsonNode eligibility = fixtures.path("eligibilityResult").deepCopy();
    GameplayV2ValidatedEligibility sealed =
        GameplayV2ValidatedEligibility.seal(
            validator,
            fixtures.path("catalogSnapshot"),
            fixtures.path("semanticObservation"),
            fixtures.path("planningInput"),
            eligibility,
            NOW);
    ((ObjectNode) eligibility.path("entries").path(0)).put("taskKey", "FABRICATED_TASK");
    assertThat(sealed.result().path("entries").path(0).path("taskKey").asText())
        .isEqualTo("FISHING_TRAINING");
  }

  @Test
  void mutatingTheReturnedResultHandleCannotFabricateLaterReads() {
    GameplayV2ValidatedEligibility sealed = seal();
    ((ObjectNode) sealed.result().path("entries").path(0)).put("status", "NO_ELIGIBLE_TASK");
    assertThat(sealed.result().path("entries").path(0).path("status").asText())
        .isEqualTo("ELIGIBLE");
  }

  @Test
  void sealBindsTheCanonicalCatalogDigest() {
    GameplayV2ValidatedEligibility sealed = seal();
    assertThat(sealed.catalogDigest())
        .isEqualTo(GameplayV2EnvelopeRules.catalogDigestOf(fixtures.path("catalogSnapshot")));
  }

  @Test
  void substitutedCatalogIsRejectedAtTheSealedAdapterBoundary() {
    GameplayV2ValidatedEligibility sealed = seal();
    JsonNode substituted = fixtures.path("catalogSnapshot").deepCopy();
    ((ObjectNode) substituted).put("catalogId", "catalog-substituted");
    JsonNode statement = substituted.deepCopy();
    ((ObjectNode) statement).remove("catalogDigest");
    ((ObjectNode) substituted)
        .put("catalogDigest", GameplayV2EligibilityFilter.canonicalDigest(objectMapper, statement));
    assertThat(sealed.checkCatalogBinding(substituted)).isEqualTo("NODE_CATALOG_MISMATCH");
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(
                        sealed, substituted, "claude-fixture-v2", fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_CATALOG_MISMATCH");
  }

  @Test
  void staleCatalogDigestIsRejectedAtTheSealedAdapterBoundary() {
    GameplayV2ValidatedEligibility sealed = seal();
    JsonNode stale = fixtures.path("catalogSnapshot").deepCopy();
    ((ObjectNode) stale.path("tasks").path(0)).put("taskKey", "OTHER_TASK");
    assertThat(sealed.checkCatalogBinding(stale)).isEqualTo("NODE_CATALOG_MISMATCH");
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(sealed, stale, "claude-fixture-v2", fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_CATALOG_MISMATCH");
  }

  @Test
  void unknownClosedSchemaPropertiesAreRejectedAtSealTime() {
    JsonNode catalog = fixtures.path("catalogSnapshot").deepCopy();
    ((ObjectNode) catalog).put("unknownSealProperty", "nope");
    assertThatThrownBy(
            () ->
                GameplayV2ValidatedEligibility.seal(
                    validator,
                    catalog,
                    fixtures.path("semanticObservation"),
                    fixtures.path("planningInput"),
                    fixtures.path("eligibilityResult"),
                    NOW))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("INVALID_IMMUTABLE_TASK_CATALOG_SNAPSHOT");
  }

  @Test
  void sealedAdapterRejectsMissingCatalogAndNullSeal() {
    GameplayV2ValidatedEligibility sealed = seal();
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(sealed, null, "claude-fixture-v2", fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_CATALOG_MISMATCH");
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(
                        (GameplayV2ValidatedEligibility) null,
                        fixtures.path("catalogSnapshot"),
                        "claude-fixture-v2",
                        fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("PROVIDER_BEFORE_ELIGIBILITY");
  }

  @Test
  void validSealStillAdaptsThroughTheSealedBoundary() {
    GameplayV2ValidatedEligibility sealed = seal();
    assertThat(sealed.checkCatalogBinding(fixtures.path("catalogSnapshot"))).isNull();
    assertThat(
            new ClaudeGameplayV2ProviderAdapter(validator)
                .adapt(
                    sealed,
                    fixtures.path("catalogSnapshot"),
                    "claude-fixture-v2",
                    fixtures.path("claudeDecision"))
                .decisionId())
        .isEqualTo(fixtures.path("claudeDecision").path("decisionId").asText());
  }

  @Test
  void mutationAfterSealEntryCannotAffectTheBoundSeal() {
    JsonNode catalog = fixtures.path("catalogSnapshot").deepCopy();
    JsonNode observation = fixtures.path("semanticObservation").deepCopy();
    JsonNode planning = fixtures.path("planningInput").deepCopy();
    JsonNode eligibility = fixtures.path("eligibilityResult").deepCopy();
    GameplayV2ValidatedEligibility sealed =
        GameplayV2ValidatedEligibility.seal(
            validator, catalog, observation, planning, eligibility, NOW);
    ((ObjectNode) catalog).put("catalogId", "catalog-mutated-after-seal-entry");
    ((ObjectNode) observation).put("observationComplete", false);
    ((ObjectNode) planning).put("mode", "MUTATED");
    ((ObjectNode) eligibility.path("entries").path(0)).put("taskKey", "FABRICATED_TASK");
    assertThat(sealed.checkCatalogBinding(catalog)).isEqualTo("NODE_CATALOG_MISMATCH");
    assertThat(sealed.catalogDigest())
        .isEqualTo(GameplayV2EnvelopeRules.catalogDigestOf(fixtures.path("catalogSnapshot")));
    assertThat(sealed.observationDigest())
        .isEqualTo(
            GameplayV2EligibilityFilter.canonicalDigest(
                objectMapper, fixtures.path("semanticObservation")));
    assertThat(sealed.planningInputDigest())
        .isEqualTo(
            GameplayV2EligibilityFilter.canonicalDigest(
                objectMapper, fixtures.path("planningInput")));
    assertThat(sealed.eligibilityResultDigest())
        .isEqualTo(
            GameplayV2EligibilityFilter.canonicalDigest(
                objectMapper, fixtures.path("eligibilityResult")));
    assertThat(sealed.result()).isEqualTo(fixtures.path("eligibilityResult"));
  }

  @Test
  void malformedNonObjectCatalogFailsClosedWithStableCode() {
    GameplayV2ValidatedEligibility sealed = seal();
    JsonNode arrayCatalog = objectMapper.createArrayNode();
    assertThat(sealed.checkCatalogBinding(arrayCatalog)).isEqualTo("NODE_CATALOG_MISMATCH");
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(
                        sealed, arrayCatalog, "claude-fixture-v2", fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_CATALOG_MISMATCH");
    assertThatThrownBy(
            () ->
                GameplayV2ValidatedEligibility.seal(
                    validator,
                    arrayCatalog,
                    fixtures.path("semanticObservation"),
                    fixtures.path("planningInput"),
                    fixtures.path("eligibilityResult"),
                    NOW))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("INVALID_IMMUTABLE_TASK_CATALOG_SNAPSHOT");
  }

  @Test
  void mutatedSnapshotCatalogCannotPassTheSealedAdapterBoundary() {
    GameplayV2ValidatedEligibility sealed = seal();
    JsonNode catalog = fixtures.path("catalogSnapshot").deepCopy();
    JsonNode snapshotBeforeAdapt = catalog.deepCopy();
    assertThatThrownBy(
            () -> {
              ((ObjectNode) catalog).put("catalogId", "catalog-mutated-before-adapt");
              new ClaudeGameplayV2ProviderAdapter(validator)
                  .adapt(sealed, catalog, "claude-fixture-v2", fixtures.path("claudeDecision"));
            })
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_CATALOG_MISMATCH");
    assertThat(sealed.checkCatalogBinding(snapshotBeforeAdapt)).isNull();
  }

  @Test
  void nullProviderOutputFailsClosed() {
    GameplayV2ValidatedEligibility sealed = seal();
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(sealed, fixtures.path("catalogSnapshot"), "claude-fixture-v2", null))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("INVALID_PROVIDER_DECISION");
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(
                        fixtures.path("catalogSnapshot"),
                        fixtures.path("semanticObservation"),
                        fixtures.path("planningInput"),
                        fixtures.path("eligibilityResult"),
                        NOW,
                        "claude-fixture-v2",
                        null))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("INVALID_PROVIDER_DECISION");
  }

  @Test
  void nonObjectProviderOutputFailsClosed() {
    GameplayV2ValidatedEligibility sealed = seal();
    JsonNode arrayOutput = objectMapper.createArrayNode();
    JsonNode textOutput = objectMapper.getNodeFactory().textNode("not-an-object");
    JsonNode nullOutput = objectMapper.getNodeFactory().nullNode();
    for (JsonNode invalid : new JsonNode[] {arrayOutput, textOutput, nullOutput}) {
      assertThatThrownBy(
              () ->
                  new ClaudeGameplayV2ProviderAdapter(validator)
                      .adapt(
                          sealed, fixtures.path("catalogSnapshot"), "claude-fixture-v2", invalid))
          .isInstanceOf(GameplayV2ContractViolation.class)
          .extracting("failureCode")
          .isEqualTo("INVALID_PROVIDER_DECISION");
      assertThatThrownBy(
              () ->
                  new ClaudeGameplayV2ProviderAdapter(validator)
                      .adapt(
                          fixtures.path("catalogSnapshot"),
                          fixtures.path("semanticObservation"),
                          fixtures.path("planningInput"),
                          fixtures.path("eligibilityResult"),
                          NOW,
                          "claude-fixture-v2",
                          invalid))
          .isInstanceOf(GameplayV2ContractViolation.class)
          .extracting("failureCode")
          .isEqualTo("INVALID_PROVIDER_DECISION");
    }
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(
                        sealed,
                        fixtures.path("catalogSnapshot"),
                        "claude-fixture-v2",
                        objectMapper.createObjectNode()))
        .isInstanceOf(GameplayV2ContractViolation.class);
  }

  @Test
  void returnedDecisionDoesNotRetainProviderOutput() {
    GameplayV2ValidatedEligibility sealed = seal();
    JsonNode providerOutput = fixtures.path("claudeDecision").deepCopy();
    var decision =
        new ClaudeGameplayV2ProviderAdapter(validator)
            .adapt(sealed, fixtures.path("catalogSnapshot"), "claude-fixture-v2", providerOutput);
    ((ObjectNode) providerOutput.path("goalPlan").path("nodes").path(0))
        .put("taskKey", "FABRICATED_TASK");
    ((ObjectNode) providerOutput).put("decisionId", "decision-mutated-after-adapt");
    assertThat(decision.goalPlan().path("nodes").path(0).path("taskKey").asText())
        .isEqualTo("FISHING_TRAINING");
    assertThat(decision.decisionId()).isEqualTo("decision-sc1429-002");
    assertThat(decision.goalPlan().path("sourceDecisionId").asText())
        .isEqualTo("decision-sc1429-002");
  }

  @Test
  void providerOutputSnapshotIsUsedForAllChecks() {
    GameplayV2ValidatedEligibility sealed = seal();
    JsonNode providerOutput = fixtures.path("claudeDecision").deepCopy();
    JsonNode catalog = fixtures.path("catalogSnapshot").deepCopy();
    var decision =
        new ClaudeGameplayV2ProviderAdapter(validator)
            .adapt(sealed, catalog, "claude-fixture-v2", providerOutput);
    assertThat(decision.decisionId()).isEqualTo(providerOutput.path("decisionId").asText());
    assertThat(decision.eligibleInputDigest())
        .isEqualTo(providerOutput.path("eligibleInputDigest").asText());
    ((ObjectNode) providerOutput.path("goalPlan")).put("goalPlanId", "goalplan-mutated");
    assertThat(decision.goalPlan().path("goalPlanId").asText()).isEqualTo("goalplan-sc1429-001");
  }
}
