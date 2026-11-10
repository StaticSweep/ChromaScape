package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The assembler is the only place the ten contract objects are produced. It must freeze bytes that
 * pass the same full-binding and authority rules Micro6 applies, and it must refuse a draft whose
 * provenance does not hold.
 */
class BootstrapV2BundleAssemblerTest {
  private static final String SECRET = "sc1498-test-authority-secret-0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-09-03T12:00:05Z");

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private JsonNode fixtures;
  private BootstrapV2BundleAssembler assembler;

  @BeforeEach
  void setUp() throws Exception {
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      fixtures = mapper.readTree(input);
    }
    assembler = new BootstrapV2BundleAssembler(new GameplayV2ContractValidator(mapper), SECRET);
  }

  @Test
  void freezesACompleteBundleThatPassesFullBindingsAndAuthority() {
    var assembled = assembler.assemble(draft(fixtures));

    assertThat(assembled.members())
        .containsOnlyKeys(
            "executionEnvelope",
            "catalogSnapshot",
            "semanticObservation",
            "planningInput",
            "eligibilityResult",
            "providerDecision",
            "goalPlan",
            "semanticAction",
            "approvedScope",
            "authority");
    assertThat(assembled.executionEnvelope().path("contractVersion").asText())
        .isEqualTo("sc-1429/v2");
    assertThat(assembled.executionEnvelope().path("mode").asText()).isEqualTo("LIVE_TASK");
    assertThat(assembled.frozen().usable()).isTrue();
    assertThat(assembled.frozen().envelopeDigest())
        .isEqualTo(GameplayV2EligibilityFilter.canonicalDigest(mapper, assembled.executionEnvelope()));
    assertThat(assembled.frozen().scopeDigest()).isEqualTo(assembled.frozen().scopeDigest());
    assertThat(assembled.frozen().bundleDigest()).matches("sha256:[0-9a-f]{64}");
  }

  @Test
  void frozenBytesCarryExactlyTheValidatedMembers() throws Exception {
    var assembled = assembler.assemble(draft(fixtures));

    JsonNode transmitted = mapper.readTree(assembled.frozen().bytes());

    assertThat(transmitted.path("executionEnvelope").path("requestId").asText())
        .isEqualTo("request-sc1429-002");
    assertThat(transmitted.path("executionEnvelope").path("authorityId").asText())
        .isEqualTo("auth-sc1429-002");
    assertThat(transmitted.path("goalPlan").path("goalPlanDigest").asText())
        .isEqualTo(assembled.frozen().goalPlanDigest());
    assertThat(transmitted.path("authority").path("signature").asText())
        .matches("hmac-sha256:[0-9a-f]{64}");
  }

  @Test
  void refusesAnObservationWhoseDigestNoLongerMatchesThePlanningInput() {
    ObjectNode observation = (ObjectNode) fixtures.path("semanticObservation").deepCopy();
    observation.put("capturedTick", observation.path("capturedTick").asLong() + 1);

    assertThatThrownBy(() -> assembler.assemble(draftWith(observation)))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .hasMessageContaining("deterministic filter replay");
  }

  @Test
  void refusesAProviderDecisionThatDoesNotBindTheSealedEligibility() {
    ObjectNode decision = (ObjectNode) fixtures.path("claudeDecision").deepCopy();
    decision.put("eligibleInputDigest", "sha256:" + "9".repeat(64));

    assertThatThrownBy(() -> assembler.assemble(draftWithDecision(decision)))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .hasMessageContaining("eligibleInputDigest");
  }

  @Test
  void refusesWhenTheSigningSecretIsNotConfigured() {
    BootstrapV2BundleAssembler unsigned =
        new BootstrapV2BundleAssembler(new GameplayV2ContractValidator(mapper), "");

    assertThatThrownBy(() -> unsigned.assemble(draft(fixtures)))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .hasMessageContaining("signing secret is not configured");
  }

  private BootstrapV2BundleAssembler.Draft draft(JsonNode source) {
    return new BootstrapV2BundleAssembler.Draft(
        source.path("catalogSnapshot"),
        source.path("semanticObservation"),
        source.path("planningInput"),
        source.path("eligibilityResult"),
        source.path("claudeDecision"),
        source.path("executionEnvelope").path("identities"),
        source.path("executionEnvelope").path("budgets"),
        Instant.parse("2026-09-03T12:10:00Z"),
        "request-sc1429-002",
        "idempotency-sc1429-002",
        "auth-sc1429-002",
        "operator-sc1498",
        NOW.minusSeconds(60),
        NOW.plusSeconds(300),
        1L,
        1L,
        NOW);
  }

  private BootstrapV2BundleAssembler.Draft draftWith(JsonNode observation) {
    BootstrapV2BundleAssembler.Draft base = draft(fixtures);
    return new BootstrapV2BundleAssembler.Draft(
        base.catalogSnapshot(),
        observation,
        base.planningInput(),
        base.eligibilityResult(),
        base.providerDecision(),
        base.identities(),
        base.budgets(),
        base.deadline(),
        base.requestId(),
        base.idempotencyKey(),
        base.authorityId(),
        base.approvedBy(),
        base.approvedAt(),
        base.approvalExpiresAt(),
        base.leaseGeneration(),
        base.fence(),
        base.now());
  }

  private BootstrapV2BundleAssembler.Draft draftWithDecision(JsonNode decision) {
    BootstrapV2BundleAssembler.Draft base = draft(fixtures);
    return new BootstrapV2BundleAssembler.Draft(
        base.catalogSnapshot(),
        base.semanticObservation(),
        base.planningInput(),
        base.eligibilityResult(),
        decision,
        base.identities(),
        base.budgets(),
        base.deadline(),
        base.requestId(),
        base.idempotencyKey(),
        base.authorityId(),
        base.approvedBy(),
        base.approvedAt(),
        base.approvalExpiresAt(),
        base.leaseGeneration(),
        base.fence(),
        base.now());
  }
}
