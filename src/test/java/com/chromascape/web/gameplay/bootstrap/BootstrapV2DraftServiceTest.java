package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The draft service is the only production entry point that produces a frozen v2 bundle. It must
 * refuse a turn that is not a proven v2 decision, and otherwise return a preview whose digests are
 * the digests of the frozen bytes.
 */
class BootstrapV2DraftServiceTest {
  private static final String SECRET = "sc1498-test-authority-secret-0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-11-10T04:00:30Z");
  private static final Instant DEADLINE = Instant.parse("2026-11-10T04:10:00Z");

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final BootstrapV2SnapshotMapper snapshotMapper = new BootstrapV2SnapshotMapper();
  private BootstrapV2BundleAssembler assembler;
  private JsonNode decision;

  @BeforeEach
  void setUp() {
    assembler = new BootstrapV2BundleAssembler(new GameplayV2ContractValidator(mapper), SECRET);
    decision = null;
  }

  @Test
  void previewsAFrozenBundleForAProvenV2Decision() {
    BootstrapV2DraftService service = service();

    var preview = service.preview(request());

    assertThat(preview.frozen().usable()).isTrue();
    assertThat(preview.frozen().scopeDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(preview.frozen().envelopeDigest())
        .isEqualTo(GameplayV2EligibilityFilter.canonicalDigest(mapper, preview.executionEnvelope()));
    assertThat(preview.recordingId()).isEqualTo("recording-1");
    assertThat(preview.members()).hasSize(10);
    assertThat(preview.approvedScope().path("taskKey").asText()).isEqualTo("FISHING_TRAINING");
  }

  @Test
  void refusesATurnWithoutAProvenV2Decision() {
    BootstrapV2DraftService service =
        new BootstrapV2DraftService(
            new FakeHandoff(Optional.empty()),
            new FakeWire(),
            snapshotMapper,
            assembler,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.preview(request()))
        .isInstanceOfSatisfying(
            BootstrapV2DraftService.DraftException.class,
            exception ->
                assertThat(exception.failureCode()).isEqualTo("PERSISTED_V2_DECISION_MISSING"));
  }

  @Test
  void refusesAnExpiredDeadlineBeforeReadingAnything() {
    BootstrapV2DraftService service = service();
    var expired =
        new BootstrapV2DraftService.DraftRequest(
            "session-1",
            "turn-1",
            identities(),
            budgets(),
            NOW.minusSeconds(1),
            "request-1",
            "idempotency-1",
            "auth-1",
            "operator-1",
            NOW.plusSeconds(300),
            1L,
            1L);

    assertThatThrownBy(() -> service.preview(expired))
        .isInstanceOfSatisfying(
            BootstrapV2DraftService.DraftException.class,
            exception -> assertThat(exception.failureCode()).isEqualTo("DEADLINE_EXPIRED"));
  }

  private BootstrapV2DraftService service() {
    return new BootstrapV2DraftService(
        new FakeHandoff(Optional.empty()) {
          @Override
          public Optional<PersistedV2Decision> findValidatedV2Decision(
              String sessionId, String turnId) {
            return Optional.of(persistedDecision());
          }
        },
        new FakeWire(),
        snapshotMapper,
        assembler,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private BootstrapV2DraftService.DraftRequest request() {
    return new BootstrapV2DraftService.DraftRequest(
        "session-1",
        "turn-1",
        identities(),
        budgets(),
        DEADLINE,
        "request-1",
        "idempotency-1",
        "auth-1",
        "operator-1",
        NOW.plusSeconds(300),
        1L,
        1L);
  }

  /** Builds the decision against the same mapped eligibility the service will compute. */
  private ProviderDecisionHandoff.PersistedV2Decision persistedDecision() {
    if (decision == null) {
      JsonNode catalog = snapshotMapper.catalogSnapshot(wire().catalog(), NOW);
      JsonNode observation =
          snapshotMapper.semanticObservation(
              wire().observe(),
              wire().observation(),
              identities(),
              catalog,
              "sha256:" + "f".repeat(64));
      JsonNode planning =
          snapshotMapper.planningInput(catalog, observation, budgets(), DEADLINE, identities());
      JsonNode eligibility = snapshotMapper.eligibilityResult(catalog, observation, planning, NOW);
      decision = v2Decision(observation, eligibility);
    }
    return new ProviderDecisionHandoff.PersistedV2Decision(
        "session-1",
        "turn-1",
        "request-1",
        "CLAUDE",
        "claude-fixture-v2",
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        decision.path("goalPlan").path("goalPlanDigest").asText(),
        decision.path("eligibleInputDigest").asText(),
        "sha256:" + "3".repeat(64),
        "sha256:" + "3".repeat(64),
        "sha256:" + "f".repeat(64),
        decision);
  }

  private JsonNode v2Decision(JsonNode observation, JsonNode eligibility) {
    ObjectNode goalPlan = mapper.createObjectNode();
    goalPlan.put("contractVersion", "sc-1429/v2");
    goalPlan.put("goalPlanId", "plan-1");
    goalPlan.put("mode", "LIVE_TASK");
    goalPlan.set("identities", identities());
    goalPlan.put("sourceDecisionId", "decision-1");
    goalPlan.put("deadline", DEADLINE.toString());
    goalPlan.put("terminalObjective", "TARGET_LEVEL_REACHED");
    ObjectNode aggregate = goalPlan.putObject("aggregateBudgets");
    aggregate.put("maxActions", 1);
    aggregate.put("maxAttempts", 1);
    aggregate.put("maxElapsedMs", 300000);

    ObjectNode node = mapper.createObjectNode();
    node.put("nodeId", "node-1");
    node.put("ordinal", 0);
    node.put("taskKey", "FISHING_TRAINING");
    ObjectNode ref = mapper.createObjectNode();
    ref.put("taskSkillId", "ts-fishing");
    ref.put("version", 1);
    ref.put("artifactDigest", "sha256:" + "4".repeat(64));
    ref.put("maturity", "VALIDATED");
    ref.set("capabilityRequirements", mapper.createArrayNode().add("FISHING_SEMANTIC_ACTION"));
    ref.set("provenance", mapper.createArrayNode().add("taskskill:ts-fishing@1"));
    node.set("taskSkillRefs", mapper.createArrayNode().add(ref));
    ObjectNode settings = node.putObject("settings");
    settings.put("targetLevel", 2);
    settings.put("areaPolicy", "LUMBRIDGE");
    node.set("prerequisiteNodeIds", mapper.createArrayNode());
    node.set("preconditions", mapper.createArrayNode().add("ACCOUNT_IDLE"));
    node.put("expectedObservationRevision", observation.path("revision").asLong());
    node.put("expectedTaskRevision", observation.path("taskRevision").asLong());
    node.put("expectedTransition", "FISHING_XP_INCREASED");
    node.put("verifierId", "fishing-xp-v1");
    ObjectNode budgets = node.putObject("budgets");
    budgets.put("maxActions", 1);
    budgets.put("maxAttempts", 1);
    budgets.put("maxElapsedMs", 300000);
    node.putNull("fallbackNodeId");
    node.putNull("failureCode");
    node.set("evidenceRefs", mapper.createArrayNode().add("observation-11"));
    goalPlan.set("nodes", mapper.createArrayNode().add(node));

    ObjectNode planCopy = goalPlan.deepCopy();
    planCopy.remove("goalPlanDigest");
    goalPlan.put(
        "goalPlanDigest", GameplayV2EligibilityFilter.canonicalDigest(mapper, planCopy));

    ObjectNode decisionNode = mapper.createObjectNode();
    decisionNode.put("contractVersion", "sc-1429/v2");
    decisionNode.put("kind", "SELECTED");
    decisionNode.put("provider", "CLAUDE");
    decisionNode.put("modelVersion", "claude-fixture-v2");
    decisionNode.put("decisionId", "decision-1");
    decisionNode.put("promptId", "prompt-1");
    decisionNode.put("policyConfidence", 0.9);
    decisionNode.put("adapterObservationConfidence", 1.0);
    decisionNode.put("knowledgeFreshnessConfidence", 1.0);
    decisionNode.put(
        "eligibleInputDigest", GameplayV2EligibilityFilter.canonicalDigest(mapper, eligibility));
    decisionNode.set("goalPlan", goalPlan);
    decisionNode.set("evidenceRefs", mapper.createArrayNode().add("observation-11"));
    return decisionNode;
  }

  private BootstrapV2Wire wire() {
    return new FakeWire();
  }

  private JsonNode identities() {
    ObjectNode identities = mapper.createObjectNode();
    identities.put("accountId", "account-abc");
    identities.put("clientId", "client-abc");
    identities.put("environmentId", "native-windows-local");
    identities.put("runId", "run-1");
    identities.put("taskId", "task-1");
    identities.put("sessionId", "session-1");
    identities.put("recordingId", "recording-1");
    identities.put("sourceId", "source-1");
    return identities;
  }

  private JsonNode budgets() {
    ObjectNode budgets = mapper.createObjectNode();
    budgets.put("maxActions", 1);
    budgets.put("maxAttempts", 1);
    budgets.put("maxElapsedMs", 300000);
    return budgets;
  }

  /** Wire fixture matching {@link BootstrapV2SnapshotMapperTest}. */
  private static final class FakeWire implements BootstrapV2Wire {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Override
    public JsonNode catalog() {
      ObjectNode descriptor = mapper.createObjectNode();
      descriptor.put("taskKey", "FISHING_TRAINING");
      descriptor.set("requiredCapabilities", array("FISHING_SEMANTIC_ACTION"));
      descriptor.set("prerequisites", array("ACCOUNT_IDLE"));
      descriptor.set("preconditions", array("OBSERVATION_CURRENT", "ACCOUNT_IDLE"));
      descriptor.set("expectedTransitions", array("FISHING_XP_INCREASED"));
      descriptor.set("verifierIds", array("fishing-xp-v1"));
      ObjectNode bounds = mapper.createObjectNode();
      bounds.put("maxActions", 3);
      bounds.put("maxAttempts", 3);
      bounds.put("maxElapsedMs", 900000);
      descriptor.set("maxPolicyBounds", bounds);

      ObjectNode task = mapper.createObjectNode();
      task.put("taskKey", "FISHING_TRAINING");
      task.put("eligible", true);
      task.put("schemaDigest", "sha256:" + "b".repeat(64));
      task.set("descriptor", descriptor);

      ObjectNode catalog = mapper.createObjectNode();
      catalog.put("contractVersion", "sc-1429/v2");
      catalog.put("enabled", true);
      catalog.put("executorArtifactId", "micro6-accountbuilder");
      catalog.put("pluginId", "plugin-accountbuilder");
      catalog.put("buildId", "build-abc");
      catalog.put("catalogHash", "sha256:" + "c".repeat(64));
      catalog.put("catalogRevision", 7);
      catalog.put("capabilityDigest",
                com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter
                    .capabilitySnapshotDigest(mapper));
      catalog.put("observationRevision", 11);
      catalog.put("taskRevision", 13);
      catalog.set("tasks", mapper.createArrayNode().add(task));
      return catalog;
    }

    @Override
    public JsonNode observe() {
      ObjectNode observe = mapper.createObjectNode();
      observe.put("contractVersion", "sc-1429/v2");
      observe.put("clientId", "client-abc");
      observe.put("buildId", "build-abc");
      observe.put("catalogHash", "sha256:" + "c".repeat(64));
      observe.put("catalogRevision", 7);
      observe.put("capabilityDigest",
                com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter
                    .capabilitySnapshotDigest(mapper));
      observe.put("observationRevision", 11);
      observe.put("taskRevision", 13);
      return observe;
    }

    @Override
    public JsonNode observation() {
      ObjectNode player = mapper.createObjectNode();
      player.put("worldPoint", "3200,3200,0");
      player.put("animation", 808);
      player.putNull("interactingTarget");
      ObjectNode skills = mapper.createObjectNode();
      skills.put("fishingLevel", 40);
      skills.put("fishingXp", 37224);
      ObjectNode taskState = mapper.createObjectNode();
      taskState.put("revision", 13);
      taskState.put("status", "IDLE");
      taskState.put("taskKey", "FISHING_TRAINING");

      ObjectNode observation = mapper.createObjectNode();
      observation.put("capturedAt", "2026-11-10T04:00:00Z");
      observation.put("capturedTick", 4242);
      observation.put("revision", 11);
      observation.put("accountId", "account-abc");
      observation.put("clientId", "client-abc");
      observation.put("environmentId", "native-windows-local");
      observation.put("buildId", "build-abc");
      observation.set("player", player);
      observation.set("inventory", mapper.createArrayNode());
      observation.set("skills", skills);
      observation.set("eligibleTargets", array("FISHING_TRAINING"));
      observation.set("taskState", taskState);
      return observation;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode array(String... values) {
      var array = mapper.createArrayNode();
      for (String value : values) {
        array.add(value);
      }
      return array;
    }
  }

  private static class FakeHandoff implements ProviderDecisionHandoff {
    private final Optional<PersistedV2Decision> value;

    FakeHandoff(Optional<PersistedV2Decision> value) {
      this.value = value;
    }

    @Override
    public Optional<PersistedDecision> findValidatedDecision(String sessionId, String turnId) {
      return Optional.empty();
    }

    @Override
    public Optional<PersistedV2Decision> findValidatedV2Decision(String sessionId, String turnId) {
      return value;
    }
  }
}
