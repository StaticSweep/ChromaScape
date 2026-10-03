package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnView;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Durable recovery matrix over isolated PostgreSQL. A RESERVED turn stays resumable; submitted
 * turns become NEEDS_RECONCILIATION; terminal turns are untouched and no turn is duplicated.
 */
class ProviderRuntimeRestartIntegrationTest extends AbstractProviderRuntimePostgresTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Autowired private JpaProviderRuntimeStore store;

  @Test
  void restartRecoveryClassifiesNonTerminalStatesWithoutRetryOrDuplication() {
    String sessionId = "restart-" + System.nanoTime();
    SessionRequest session = session(sessionId);
    store.createSession(session);

    TurnRequest reserved = turn(session, "reserved");
    store.reserveTurn(sessionId, reserved);

    TurnRequest submitted = turn(session, "submitted");
    store.reserveTurn(sessionId, submitted);
    store.markSubmitted(submitted.turnId(), "provider-session", "provider-turn", List.of());

    TurnRequest streaming = turn(session, "streaming");
    store.reserveTurn(sessionId, streaming);
    store.markSubmitted(streaming.turnId(), "provider-session", "provider-turn", List.of());
    store.markStreaming(streaming.turnId());

    TurnRequest normalizing = turn(session, "normalizing");
    store.reserveTurn(sessionId, normalizing);
    store.markSubmitted(normalizing.turnId(), "provider-session", "provider-turn", List.of());
    store.markStreaming(normalizing.turnId());
    store.markNormalizing(normalizing.turnId());

    TurnRequest terminal = turn(session, "terminal");
    store.reserveTurn(sessionId, terminal);
    store.markSubmitted(terminal.turnId(), "provider-session", "provider-turn", List.of());
    store.finish(terminal.turnId(), TurnState.ABSTAINED, FailureCode.NONE, null, null, null);

    Set<String> recoverable =
        store.recoverableTurns().stream()
            .filter(turn -> turn.sessionId().equals(sessionId))
            .map(TurnView::turnId)
            .collect(Collectors.toSet());
    assertEquals(
        Set.of(reserved.turnId(), submitted.turnId(), streaming.turnId(), normalizing.turnId()),
        recoverable);

    // Reconstruct exactly what GameplayProviderRuntime.recoverAtStartup does: submitted work is
    // never retried, while RESERVED work may resume.
    for (TurnView turn : store.recoverableTurns()) {
      if (turn.sessionId().equals(sessionId) && turn.state().submitted()) {
        store.markNeedsReconciliation(turn.turnId(), FailureCode.NEEDS_RECONCILIATION);
      }
    }

    List<TurnView> after = store.turnsForSession(sessionId);
    assertEquals(5, after.size(), "recovery must not duplicate turns");
    assertEquals(TurnState.RESERVED, stateOf(after, reserved.turnId()));
    assertEquals(TurnState.NEEDS_RECONCILIATION, stateOf(after, submitted.turnId()));
    assertEquals(TurnState.NEEDS_RECONCILIATION, stateOf(after, streaming.turnId()));
    assertEquals(TurnState.NEEDS_RECONCILIATION, stateOf(after, normalizing.turnId()));
    assertEquals(TurnState.ABSTAINED, stateOf(after, terminal.turnId()));

    List<String> stillRecoverable =
        store.recoverableTurns().stream()
            .filter(turn -> turn.sessionId().equals(sessionId))
            .map(TurnView::turnId)
            .toList();
    assertEquals(List.of(reserved.turnId()), stillRecoverable);
  }

  private TurnState stateOf(List<TurnView> turns, String turnId) {
    return turns.stream()
        .filter(turn -> turn.turnId().equals(turnId))
        .findFirst()
        .orElseThrow()
        .state();
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
        new BudgetLimits(10_000, 10_000, 8, 0, 8, 60_000, 240_000),
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
