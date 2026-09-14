package com.chromascape.web.gameplay.provider.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.DecisionKind;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.ProviderDecision;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameplayV2ProviderEquivalenceTest {
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

  private ProviderDecision adaptClaude(JsonNode eligibility, JsonNode output) {
    return new ClaudeGameplayV2ProviderAdapter(validator)
        .adapt(
            fixtures.path("catalogSnapshot"),
            fixtures.path("semanticObservation"),
            fixtures.path("planningInput"),
            eligibility,
            NOW,
            "claude-fixture-v2",
            output);
  }

  private ProviderDecision adaptCodex(JsonNode eligibility, JsonNode output) {
    return new CodexGameplayV2ProviderAdapter(validator)
        .adapt(
            fixtures.path("catalogSnapshot"),
            fixtures.path("semanticObservation"),
            fixtures.path("planningInput"),
            eligibility,
            NOW,
            "codex-fixture-v2",
            output);
  }

  @Test
  void equivalentEligibleInputsNormalizeToTheSameProviderNeutralDecision() {
    ProviderDecision claude =
        adaptClaude(fixtures.path("eligibilityResult"), fixtures.path("claudeDecision"));
    ProviderDecision codex =
        adaptCodex(fixtures.path("eligibilityResult"), fixtures.path("codexDecision"));

    assertThat(claude.decisionId()).isEqualTo(codex.decisionId());
    assertThat(claude.promptId()).isNotEqualTo(codex.promptId());
    assertThat(claude.modelVersion()).isNotEqualTo(codex.modelVersion());
    assertThat(claude.policyConfidence()).isEqualTo(codex.policyConfidence());
    assertThat(claude.adapterObservationConfidence())
        .isEqualTo(codex.adapterObservationConfidence());
    assertThat(claude.knowledgeFreshnessConfidence())
        .isEqualTo(codex.knowledgeFreshnessConfidence());
    assertThat(claude.eligibleInputDigest()).isEqualTo(codex.eligibleInputDigest());
    assertThat(claude.goalPlan()).isEqualTo(codex.goalPlan());
    assertThat(claude.goalPlan().path("goalPlanDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("goalPlan").asText());
    assertThat(claude.provider()).isNotEqualTo(codex.provider());
    assertThat(claude.kind()).isEqualTo(DecisionKind.SELECTED);
    assertThat(codex.kind()).isEqualTo(DecisionKind.SELECTED);
    assertThat(claude.abstentionReason()).isNull();
  }

  @Test
  void adaptersFailClosedBeforeEligibility() {
    // A genuinely empty eligible set (stale observation replayed through the real filter)
    // reaches the adapter and is refused before any provider output is touched.
    JsonNode staleObservation = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) staleObservation)
        .put("observationComplete", false);
    JsonNode stalePlanning = planningInputFor(staleObservation);
    JsonNode genuineEmpty =
        new GameplayV2EligibilityFilter(objectMapper)
            .filter(fixtures.path("catalogSnapshot"), staleObservation, stalePlanning, NOW);
    assertThat(GameplayV2EligibilityFilter.eligibleTaskKeys(genuineEmpty)).isEmpty();
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(
                        fixtures.path("catalogSnapshot"),
                        staleObservation,
                        stalePlanning,
                        genuineEmpty,
                        NOW,
                        "claude-fixture-v2",
                        fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("PROVIDER_BEFORE_ELIGIBILITY");
  }

  @Test
  void adaptersRejectFabricatedEligibilityAtTheBoundary() {
    // Structurally plausible but unreplayed: the entries were retargeted without changing the
    // inputs they claim to derive from. The adapter must replay and reject, even though the
    // provider output itself is well-formed.
    JsonNode fabricated = fixtures.path("eligibilityResult").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) fabricated.path("entries").path(0))
        .put("taskKey", "SOMETHING_ELSE");
    assertThatThrownBy(
            () ->
                new CodexGameplayV2ProviderAdapter(validator)
                    .adapt(
                        fixtures.path("catalogSnapshot"),
                        fixtures.path("semanticObservation"),
                        fixtures.path("planningInput"),
                        fabricated,
                        NOW,
                        "codex-fixture-v2",
                        fixtures.path("codexDecision")))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("IDENTITY_MISMATCH");
  }

  @Test
  void adaptersRejectFutureEligibilityEvaluation() {
    JsonNode future = fixtures.path("eligibilityResult").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) future)
        .put("evaluatedAt", NOW.plusSeconds(600).toString());
    assertThatThrownBy(() -> adaptClaude(future, fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("IDENTITY_MISMATCH");
  }

  @Test
  void unreplayedAdapterOverloadFailsClosed() {
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(
                        fixtures.path("eligibilityResult"),
                        fixtures.path("catalogSnapshot"),
                        "claude-fixture-v2",
                        fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("PROVIDER_BEFORE_ELIGIBILITY");
  }

  @Test
  void abstainedDecisionCarriesNoGoalPlan() {
    JsonNode abstained = fixtures.path("abstainDecision");
    validator.validateWithRules("providerDecision", abstained);
    assertThat(abstained.path("goalPlan").isMissingNode()).isTrue();
    assertThat(abstained.path("kind").asText()).isEqualTo("ABSTAINED");
    ProviderDecision adapted = adaptClaude(fixtures.path("eligibilityResult"), abstained);
    assertThat(adapted.kind()).isEqualTo(DecisionKind.ABSTAINED);
    assertThat(adapted.goalPlan()).isNull();
    assertThat(adapted.abstentionReason()).isNotNull();
    assertThat(adapted.abstentionReason().name()).isEqualTo(abstained.path("reason").asText());
  }

  @Test
  void adaptersRejectMismatchedProviderIdentity() {
    com.fasterxml.jackson.databind.node.ObjectNode mislabeled =
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                fixtures.path("claudeDecision").deepCopy())
            .put("provider", "CODEX");
    assertThatThrownBy(() -> adaptClaude(fixtures.path("eligibilityResult"), mislabeled))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("PROVIDER_MISMATCH");
  }

  @Test
  void adaptersRejectInvalidAndMismatchedModelVersions() {
    String[] invalidVersions =
        new String[] {null, "", " leading", "trailing ", "bad/value", "x".repeat(129)};
    for (String invalid : invalidVersions) {
      assertThatThrownBy(() -> adaptClaudeWithModel(invalid, fixtures.path("claudeDecision")))
          .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
          .extracting("failureCode")
          .isEqualTo("INVALID_PROVIDER_DECISION");
      assertThatThrownBy(() -> adaptCodexWithModel(invalid, fixtures.path("codexDecision")))
          .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
          .extracting("failureCode")
          .isEqualTo("INVALID_PROVIDER_DECISION");
    }

    assertThatThrownBy(
            () -> adaptClaudeWithModel("other-valid-model", fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("PROVIDER_MISMATCH");
    assertThatThrownBy(
            () -> adaptCodexWithModel("other-valid-model", fixtures.path("codexDecision")))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("PROVIDER_MISMATCH");
  }

  @Test
  void adaptersRejectUnboundEligibilityProvenance() {
    com.fasterxml.jackson.databind.node.ObjectNode rebound =
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                fixtures.path("claudeDecision").deepCopy())
            .put(
                "eligibleInputDigest",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    assertThatThrownBy(() -> adaptClaude(fixtures.path("eligibilityResult"), rebound))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("IDENTITY_MISMATCH");
  }

  @Test
  void adaptersRejectCyclicGoalPlansRecursively() {
    JsonNode cyclic = fixtures.path("claudeDecision").deepCopy();
    com.fasterxml.jackson.databind.node.ObjectNode plan =
        (com.fasterxml.jackson.databind.node.ObjectNode) cyclic.path("goalPlan");
    com.fasterxml.jackson.databind.node.ObjectNode last =
        (com.fasterxml.jackson.databind.node.ObjectNode) plan.path("nodes").path(2);
    last.put("fallbackNodeId", "node-0");
    assertThatThrownBy(() -> adaptClaude(fixtures.path("eligibilityResult"), cyclic))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("GOAL_PLAN_CYCLIC");
  }

  @Test
  void uncataloguedAdapterFailsClosed() {
    assertThatThrownBy(
            () ->
                new ClaudeGameplayV2ProviderAdapter(validator)
                    .adapt(
                        fixtures.path("eligibilityResult"),
                        "claude-fixture-v2",
                        fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_CATALOG_MISMATCH");
  }

  @Test
  void directProviderDecisionValidationRejectsNestedInvalidPlans() {
    JsonNode cyclic = fixtures.path("claudeDecision").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            ((com.fasterxml.jackson.databind.node.ObjectNode) cyclic.path("goalPlan"))
                .path("nodes")
                .path(2))
        .put("fallbackNodeId", "node-0");
    assertThatThrownBy(() -> validator.validateWithRules("providerDecision", cyclic))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("GOAL_PLAN_CYCLIC");
  }

  @Test
  void catalogBoundAdapterRejectsAllowlistViolations() {
    JsonNode catalogOffender = fixtures.path("claudeDecision").deepCopy();
    com.fasterxml.jackson.databind.node.ObjectNode offenderNode =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            ((com.fasterxml.jackson.databind.node.ObjectNode) catalogOffender.path("goalPlan"))
                .path("nodes")
                .path(0);
    offenderNode.put("verifierId", "unknown-verifier");
    // Re-sign the mutated plan so the failure is purely the catalog allowlist,
    // not the stale digest.
    com.fasterxml.jackson.databind.node.ObjectNode offenderPlan =
        (com.fasterxml.jackson.databind.node.ObjectNode) catalogOffender.path("goalPlan");
    com.fasterxml.jackson.databind.node.ObjectNode planCopy = offenderPlan.deepCopy();
    planCopy.remove("goalPlanDigest");
    offenderPlan.put(
        "goalPlanDigest",
        com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter.canonicalDigest(
            objectMapper, planCopy));
    assertThatThrownBy(() -> adaptClaude(fixtures.path("eligibilityResult"), catalogOffender))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_CATALOG_MISMATCH");
  }

  /** The real flow builds planning input from the observation, so the harness rebinding follows. */
  private JsonNode planningInputFor(JsonNode observation) {
    JsonNode planningInput = fixtures.path("planningInput").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) planningInput)
        .put(
            "observationDigest",
            GameplayV2EligibilityFilter.canonicalDigest(objectMapper, observation));
    return planningInput;
  }

  private ProviderDecision adaptClaudeWithModel(String modelVersion, JsonNode output) {
    return new ClaudeGameplayV2ProviderAdapter(validator)
        .adapt(
            fixtures.path("catalogSnapshot"),
            fixtures.path("semanticObservation"),
            fixtures.path("planningInput"),
            fixtures.path("eligibilityResult"),
            NOW,
            modelVersion,
            output);
  }

  private ProviderDecision adaptCodexWithModel(String modelVersion, JsonNode output) {
    return new CodexGameplayV2ProviderAdapter(validator)
        .adapt(
            fixtures.path("catalogSnapshot"),
            fixtures.path("semanticObservation"),
            fixtures.path("planningInput"),
            fixtures.path("eligibilityResult"),
            NOW,
            modelVersion,
            output);
  }
}
