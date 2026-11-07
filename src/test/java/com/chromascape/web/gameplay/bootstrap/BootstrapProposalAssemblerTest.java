package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService;
import com.chromascape.web.gameplay.bootstrap.BootstrapProposalAssembler.BootstrapProposalException;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff.PersistedDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BootstrapProposalAssemblerTest {
  private static final String SECRET = "authority-test-secret-at-least-32-bytes";

  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private JsonNode fixtures;
  private BootstrapProposalAssembler assembler;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    fixtures =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
    HumanGameplayAuthorityService authorityService =
        new HumanGameplayAuthorityService(
            canonicalJson,
            Clock.fixed(Instant.parse("2026-08-30T12:01:00Z"), ZoneOffset.UTC),
            SECRET);
    assembler = new BootstrapProposalAssembler(new FakeHandoff(), authorityService, canonicalJson);
  }

  @Test
  void persistedDecidedTurnProducesExactScopeAndPreviewFingerprint() {
    ExecutionEnvelope envelope = envelope();
    SnapshotBindings bindings = bindings(envelope);

    var preview =
        assembler.assemble("session-fixture", "turn-fixture", observation(), envelope, bindings);

    assertThat(preview.scopeDigest()).startsWith("sha256:");
    assertThat(preview.previewFingerprint()).startsWith("sha256:");
    assertThat(preview.orchestrationId()).isEqualTo(envelope.requestId());
    // The recording identity is bound by the envelope identities, not invented by the assembler.
    assertThat(preview.recordingId()).isEqualTo("recording-sc1498-001");
    assertThat(preview.decision().provider()).isEqualTo(Provider.CLAUDE);
  }

  @Test
  void unknownPersistedDecisionFailsBeforeAuthorityConsumption() {
    BootstrapProposalAssembler missing =
        new BootstrapProposalAssembler(
            (sessionId, turnId) -> Optional.empty(),
            new HumanGameplayAuthorityService(
                canonicalJson,
                Clock.fixed(Instant.parse("2026-08-30T12:01:00Z"), ZoneOffset.UTC),
                SECRET),
            canonicalJson);

    assertThatThrownBy(
            () ->
                missing.assemble(
                    "missing", "missing", observation(), envelope(), bindings(envelope())))
        .isInstanceOf(BootstrapProposalException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void changedDigestAfterPreviewIsStaleBinding() {
    ExecutionEnvelope envelope = envelope();
    SnapshotBindings bindings = bindings(envelope);
    SnapshotBindings tampered =
        new SnapshotBindings(
            "sha256:" + "f".repeat(64),
            bindings.semanticActionDigest(),
            bindings.catalogDigest(),
            bindings.capabilityDigest(),
            bindings.goalPlanDigest(),
            bindings.taskSkillDigests(),
            bindings.toolSchemaDigest(),
            bindings.knowledgeDigest(),
            bindings.policyDigest(),
            bindings.verifierDigest(),
            bindings.buildId(),
            bindings.expectedObservationRevision(),
            bindings.expectedTaskRevision(),
            bindings.observationDigest(),
            bindings.promptDigest(),
            bindings.providerSessionId(),
            bindings.providerTurnId());

    assertThatThrownBy(
            () ->
                assembler.assemble(
                    "session-fixture", "turn-fixture", observation(), envelope, tampered))
        .isInstanceOf(BootstrapProposalException.class)
        .hasMessageContaining("binding");
  }

  private JsonNode observation() {
    return fixtures.path("observation").deepCopy();
  }

  private ExecutionEnvelope envelope() {
    ObjectNode node = fixtures.path("executionEnvelope").deepCopy();
    node.put("deadline", "2026-08-30T12:10:00Z");
    node.put("semanticActionDigest", canonicalJson.digest(fixtures.path("semanticAction")));
    return objectMapper.convertValue(node, ExecutionEnvelope.class);
  }

  private SnapshotBindings bindings(ExecutionEnvelope envelope) {
    FakeHandoff handoff = new FakeHandoff();
    var persisted = handoff.findValidatedDecision("session-fixture", "turn-fixture").orElseThrow();
    return new SnapshotBindings(
        persisted.decisionDigest(),
        persisted.semanticActionDigest(),
        envelope.catalogDigest(),
        "sha256:" + "c".repeat(64),
        envelope.goalPlanDigest(),
        envelope.taskSkillDigests(),
        envelope.schemaDigest(),
        envelope.knowledgeDigest(),
        envelope.policyDigest(),
        envelope.verifierId(),
        envelope.buildId(),
        envelope.expectedObservationRevision(),
        envelope.expectedTaskRevision(),
        persisted.observationDigest(),
        persisted.promptDigest(),
        persisted.sessionId(),
        persisted.turnId());
  }

  private final class FakeHandoff implements ProviderDecisionHandoff {
    @Override
    public Optional<PersistedDecision> findValidatedDecision(String sessionId, String turnId) {
      if (!"session-fixture".equals(sessionId) || !"turn-fixture".equals(turnId)) {
        return Optional.empty();
      }
      JsonNode action = fixtures.path("semanticAction").deepCopy();
      ExecutionEnvelope envelope = envelope();
      ProviderDecision decision =
          new ProviderDecision(
              "sc-1429/v1",
              Provider.CLAUDE,
              envelope.modelVersion(),
              envelope.decisionId(),
              0.9,
              action,
              List.of("observation:observation-fixture"));
      String transcriptDigest = "sha256:" + "7".repeat(64);
      return Optional.of(
          new PersistedDecision(
              sessionId,
              turnId,
              envelope.requestId(),
              Provider.CLAUDE,
              envelope.modelVersion(),
              canonicalJson.digest(decision),
              canonicalJson.digest(action),
              canonicalJson.digest(observation()),
              canonicalJson.digest(fixtures.path("claudeProposal")),
              canonicalJson.digest(action),
              envelope.knowledgeDigest(),
              envelope.goalPlanDigest(),
              envelope.taskSkillDigests(),
              transcriptDigest,
              transcriptDigest,
              ProviderRuntimeModels.TurnState.DECIDED,
              ProviderRuntimeModels.FailureCode.NONE,
              decision));
    }
  }
}
