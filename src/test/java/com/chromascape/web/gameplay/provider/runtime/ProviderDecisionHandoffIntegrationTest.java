package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** SC-1431 handoff boundary: only committed DECIDED turns are readable and nothing mutates. */
class ProviderDecisionHandoffIntegrationTest extends AbstractProviderRuntimePostgresTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Autowired private JpaProviderRuntimeStore store;
  @Autowired private CanonicalGameplayJson canonicalJson;

  @Test
  void handoffExposesOnlyCommittedDecidedTurns() {
    SessionRequest session = session("handoff-" + System.nanoTime());
    store.createSession(session);
    TurnRequest decided = turn(session, "decided");
    reserveAndStream(session, decided);
    ProviderDecision decision = decision();
    store.finish(
        decided.turnId(),
        TurnState.DECIDED,
        FailureCode.NONE,
        decision,
        null,
        "sha256:" + "f".repeat(64));
    store.recordTranscript(decided.turnId(), "sha256:" + "f".repeat(64), 12L);

    TurnRequest abstained = turn(session, "abstained");
    reserveAndStream(session, abstained);
    store.finish(abstained.turnId(), TurnState.ABSTAINED, FailureCode.NONE, null, null, null);

    TurnRequest failed = turn(session, "failed");
    reserveAndStream(session, failed);
    store.finish(
        failed.turnId(), TurnState.INVALID_OUTPUT, FailureCode.INVALID_OUTPUT, null, null, null);

    GameplayProviderRuntime runtime =
        new GameplayProviderRuntime(
            new ProviderRuntimeProperties(), null, store, null, null, null, null, List.of());

    ProviderDecisionHandoff.PersistedDecision persisted =
        runtime.findValidatedDecision(session.sessionId(), decided.turnId()).orElseThrow();
    assertEquals(decision.decisionId(), persisted.decision().decisionId());
    assertEquals(canonicalJson.digest(decision), persisted.decisionDigest());
    assertEquals("sha256:" + "f".repeat(64), persisted.transcriptRef());

    assertTrue(runtime.findValidatedDecision(session.sessionId(), abstained.turnId()).isEmpty());
    assertTrue(runtime.findValidatedDecision(session.sessionId(), failed.turnId()).isEmpty());
    assertTrue(runtime.findValidatedDecision(session.sessionId(), "missing-turn").isEmpty());
  }

  private ProviderDecision decision() {
    return new ProviderDecision(
        GameplayContracts.VERSION,
        Provider.CODEX,
        "gpt-5",
        "decision-handoff-1",
        0.91,
        JsonNodeFactory.instance.objectNode().put("kind", "ABSTAIN"),
        List.of("observation:1"));
  }

  private void reserveAndStream(SessionRequest session, TurnRequest turn) {
    store.reserveTurn(session.sessionId(), turn);
    store.markSubmitted(turn.turnId(), "provider-session", "provider-turn", List.of());
    store.markStreaming(turn.turnId());
  }

  private SessionRequest session(String id) {
    return new SessionRequest(
        id,
        Provider.CODEX,
        "gpt-5",
        "task-test",
        "run-test",
        "recording-test",
        DIGEST,
        DIGEST,
        DIGEST,
        DIGEST,
        List.of(DIGEST),
        Instant.now().plusSeconds(300),
        new BudgetLimits(10_000, 10_000, 4, 0, 4, 60_000, 240_000),
        true);
  }

  private TurnRequest turn(SessionRequest session, String suffix) {
    return new TurnRequest(
        session.sessionId() + "-turn-" + suffix,
        session.sessionId() + "-request-" + suffix,
        session.sessionId() + "-idempotency-" + suffix,
        DIGEST,
        JsonNodeFactory.instance.objectNode(),
        DIGEST,
        JsonNodeFactory.instance.arrayNode(),
        DIGEST,
        JsonNodeFactory.instance.objectNode(),
        "source-test",
        10,
        10,
        Instant.now().plusSeconds(60),
        true);
  }
}
