package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff.PersistedDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderDecisionOrchestrationSourceTest {
  private static final String DECISION_DIGEST = "sha256:" + "1".repeat(64);
  private static final String ACTION_DIGEST = "sha256:" + "2".repeat(64);
  private static final String TOOL_DIGEST = "sha256:" + "3".repeat(64);
  private static final String KNOWLEDGE_DIGEST = "sha256:" + "4".repeat(64);
  private static final String PLAN_DIGEST = "sha256:" + "5".repeat(64);
  private static final String SKILL_DIGEST = "sha256:" + "6".repeat(64);
  private static final String TRANSCRIPT = "sha256:" + "7".repeat(64);

  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalGameplayJson canonicalJson = new CanonicalGameplayJson(mapper);
  private final com.fasterxml.jackson.databind.node.ObjectNode observationNode =
      mapper.createObjectNode().put("revision", 2);

  private String observationDigest() {
    return canonicalJson.digest(observationNode);
  }

  private PersistedDecision persisted(TurnState state) {
    return new PersistedDecision(
        "session-codex-1",
        "turn-codex-1",
        "request-1",
        Provider.CODEX,
        "gpt-test",
        DECISION_DIGEST,
        ACTION_DIGEST,
        observationDigest(),
        "sha256:" + "d".repeat(64),
        TOOL_DIGEST,
        KNOWLEDGE_DIGEST,
        PLAN_DIGEST,
        List.of(SKILL_DIGEST),
        TRANSCRIPT,
        TRANSCRIPT,
        state,
        com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
        decision());
  }

  private ProviderDecision decision() {
    return new ProviderDecision(
        "sc-1429/v1",
        Provider.CODEX,
        "gpt-test",
        "decision-1",
        0.9,
        mapper.createObjectNode().put("kind", "START_FISHING_TASK"),
        List.of());
  }

  private OrchestrationRequest request() {
    var node = mapper.createObjectNode();
    ExecutionEnvelope envelope =
        new ExecutionEnvelope(
            "sc-1429/v1",
            Provider.CODEX,
            "gpt-test",
            "decision-1",
            "request-1",
            "idempotency-1",
            PLAN_DIGEST,
            List.of(SKILL_DIGEST),
            "FISHING_TRAINING",
            node,
            node,
            "sha256:" + "c".repeat(64),
            TOOL_DIGEST,
            "sha256:" + "e".repeat(64),
            KNOWLEDGE_DIGEST,
            ACTION_DIGEST,
            "CONFIRMED",
            "verifier-1",
            "build-1",
            2,
            3,
            "authority-1",
            1,
            7,
            mapper.createObjectNode(),
            java.time.Instant.now().plusSeconds(600),
            "RUN");
    SnapshotBindings bindings =
        new SnapshotBindings(
            DECISION_DIGEST,
            ACTION_DIGEST,
            "sha256:" + "c".repeat(64),
            "sha256:" + "d".repeat(64),
            PLAN_DIGEST,
            List.of(SKILL_DIGEST),
            TOOL_DIGEST,
            KNOWLEDGE_DIGEST,
            "sha256:" + "e".repeat(64),
            "verifier-1",
            "build-1",
            2,
            3,
            observationDigest(),
            "sha256:" + "d".repeat(64));
    return new OrchestrationRequest(
        "orchestration-1",
        "request-1",
        "idempotency-1",
        "sha256:" + "f".repeat(64),
        AccountBuilderOrchestrationModels.Mode.LIVE_TASK,
        "PROVIDER:CODEX",
        decision(),
        envelope,
        bindings,
        null,
        observationNode,
        envelope.deadline());
  }

  private ProviderDecisionOrchestrationSource source(PersistedDecision persisted) {
    return new ProviderDecisionOrchestrationSource(
        (sessionId, turnId) -> persisted == null ? Optional.empty() : Optional.of(persisted),
        canonicalJson);
  }

  @Test
  void missingTurnFailsClosed() {
    assertThatThrownBy(() -> source(null).bind("session-codex-1", "turn-codex-1", request()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
  }

  @Test
  void nonterminalTurnIsRejected() {
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.NORMALIZING))
                    .bind("session-codex-1", "turn-codex-1", request()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.ABSTAINED))
                    .bind("session-codex-1", "turn-codex-1", request()))
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.CANCELLED))
                    .bind("session-codex-1", "turn-codex-1", request()))
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.NEEDS_RECONCILIATION))
                    .bind("session-codex-1", "turn-codex-1", request()))
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
  }

  @Test
  void persistedCodexDecisionWithMatchingProvenanceBindsExactlyOnce() {
    OrchestrationRequest original = request();
    OrchestrationRequest bound =
        source(persisted(TurnState.DECIDED)).bind("session-codex-1", "turn-codex-1", original);
    assertThat(bound.sourceType()).isEqualTo("PROVIDER:CODEX");
    assertThat(bound.decision().provider()).isEqualTo(Provider.CODEX);
    assertThat(bound.envelope()).isSameAs(original.envelope());
    assertThat(bound.requestId()).isEqualTo("request-1");
  }

  @Test
  void tamperedOrMissingTranscriptFailsClosed() {
    PersistedDecision tampered =
        new PersistedDecision(
            "session-codex-1",
            "turn-codex-1",
            "request-1",
            Provider.CODEX,
            "gpt-test",
            DECISION_DIGEST,
            ACTION_DIGEST,
            observationDigest(),
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
            TOOL_DIGEST,
            KNOWLEDGE_DIGEST,
            PLAN_DIGEST,
            List.of(SKILL_DIGEST),
            TRANSCRIPT,
            "sha256:" + "0".repeat(64),
            TurnState.DECIDED,
            com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
            decision());
    assertThatThrownBy(() -> source(tampered).bind("session-codex-1", "turn-codex-1", request()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
  }

  @Test
  void incompleteProvenanceFailsClosed() {
    PersistedDecision missingPrompt =
        new PersistedDecision(
            "session-codex-1",
            "turn-codex-1",
            "request-1",
            Provider.CODEX,
            "gpt-test",
            DECISION_DIGEST,
            ACTION_DIGEST,
            observationDigest(),
            null,
            TOOL_DIGEST,
            KNOWLEDGE_DIGEST,
            PLAN_DIGEST,
            List.of(SKILL_DIGEST),
            TRANSCRIPT,
            TRANSCRIPT,
            TurnState.DECIDED,
            com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
            decision());
    assertThatThrownBy(
            () -> source(missingPrompt).bind("session-codex-1", "turn-codex-1", request()))
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);

    PersistedDecision emptySkills =
        new PersistedDecision(
            "session-codex-1",
            "turn-codex-1",
            "request-1",
            Provider.CODEX,
            "gpt-test",
            DECISION_DIGEST,
            ACTION_DIGEST,
            observationDigest(),
            "sha256:" + "d".repeat(64),
            TOOL_DIGEST,
            KNOWLEDGE_DIGEST,
            PLAN_DIGEST,
            List.of(),
            TRANSCRIPT,
            TRANSCRIPT,
            TurnState.DECIDED,
            com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
            decision());
    assertThatThrownBy(() -> source(emptySkills).bind("session-codex-1", "turn-codex-1", request()))
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
  }

  @Test
  void bindingWithoutPersistedPromptProvenanceIsStaleBinding() {
    OrchestrationRequest original = request();
    SnapshotBindings withoutPrompt =
        new SnapshotBindings(
            original.bindings().decisionDigest(),
            original.bindings().semanticActionDigest(),
            original.bindings().catalogDigest(),
            original.bindings().capabilityDigest(),
            original.bindings().goalPlanDigest(),
            original.bindings().taskSkillDigests(),
            original.bindings().toolSchemaDigest(),
            original.bindings().knowledgeDigest(),
            original.bindings().policyDigest(),
            original.bindings().verifierDigest(),
            original.bindings().buildId(),
            original.bindings().expectedObservationRevision(),
            original.bindings().expectedTaskRevision(),
            original.bindings().observationDigest(),
            null);
    OrchestrationRequest withoutPromptRequest =
        new OrchestrationRequest(
            original.orchestrationId(),
            original.requestId(),
            original.idempotencyKey(),
            original.requestFingerprint(),
            original.mode(),
            original.sourceType(),
            original.decision(),
            original.envelope(),
            withoutPrompt,
            original.authority(),
            original.observation(),
            original.deadline());
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.DECIDED))
                    .bind("session-codex-1", "turn-codex-1", withoutPromptRequest))
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
  }

  @Test
  void providerOrModelMismatchIsStaleBinding() {
    PersistedDecision otherModel =
        new PersistedDecision(
            "session-codex-1",
            "turn-codex-1",
            "request-1",
            Provider.CODEX,
            "gpt-other",
            DECISION_DIGEST,
            ACTION_DIGEST,
            observationDigest(),
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
            TOOL_DIGEST,
            KNOWLEDGE_DIGEST,
            PLAN_DIGEST,
            List.of(SKILL_DIGEST),
            TRANSCRIPT,
            TRANSCRIPT,
            TurnState.DECIDED,
            com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
            decision());
    assertThatThrownBy(() -> source(otherModel).bind("session-codex-1", "turn-codex-1", request()))
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
  }

  @Test
  void requestIdMismatchIsStaleBinding() {
    PersistedDecision otherRequest =
        new PersistedDecision(
            "session-codex-1",
            "turn-codex-1",
            "request-2",
            Provider.CODEX,
            "gpt-test",
            DECISION_DIGEST,
            ACTION_DIGEST,
            observationDigest(),
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
            TOOL_DIGEST,
            KNOWLEDGE_DIGEST,
            PLAN_DIGEST,
            List.of(SKILL_DIGEST),
            TRANSCRIPT,
            TRANSCRIPT,
            TurnState.DECIDED,
            com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
            decision());
    assertThatThrownBy(
            () -> source(otherRequest).bind("session-codex-1", "turn-codex-1", request()))
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
  }

  @Test
  void decisionDigestMismatchIsStaleBinding() {
    PersistedDecision changed =
        new PersistedDecision(
            "session-codex-1",
            "turn-codex-1",
            "request-1",
            Provider.CODEX,
            "gpt-test",
            "sha256:" + "9".repeat(64),
            ACTION_DIGEST,
            observationDigest(),
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
            TOOL_DIGEST,
            KNOWLEDGE_DIGEST,
            PLAN_DIGEST,
            List.of(SKILL_DIGEST),
            TRANSCRIPT,
            TRANSCRIPT,
            TurnState.DECIDED,
            com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
            decision());
    assertThatThrownBy(() -> source(changed).bind("session-codex-1", "turn-codex-1", request()))
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
  }

  @Test
  void planSkillToolAndKnowledgeMismatchIsStaleBinding() {
    var node = mapper.createObjectNode();
    ExecutionEnvelope envelope =
        new ExecutionEnvelope(
            "sc-1429/v1",
            Provider.CODEX,
            "gpt-test",
            "decision-1",
            "request-1",
            "idempotency-1",
            "sha256:" + "b".repeat(64),
            List.of(SKILL_DIGEST),
            "FISHING_TRAINING",
            node,
            node,
            "sha256:" + "c".repeat(64),
            TOOL_DIGEST,
            "sha256:" + "e".repeat(64),
            KNOWLEDGE_DIGEST,
            ACTION_DIGEST,
            "CONFIRMED",
            "verifier-1",
            "build-1",
            2,
            3,
            "authority-1",
            1,
            7,
            mapper.createObjectNode(),
            java.time.Instant.now().plusSeconds(600),
            "RUN");
    OrchestrationRequest drifted =
        new OrchestrationRequest(
            "orchestration-1",
            "request-1",
            "idempotency-1",
            "sha256:" + "f".repeat(64),
            AccountBuilderOrchestrationModels.Mode.LIVE_TASK,
            "PROVIDER:CODEX",
            decision(),
            envelope,
            request().bindings(),
            null,
            request().observation(),
            envelope.deadline());
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.DECIDED))
                    .bind("session-codex-1", "turn-codex-1", drifted))
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
  }
}
