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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** SC-1431 handoff boundary: only committed DECIDED turns are readable and nothing mutates. */
class ProviderDecisionHandoffIntegrationTest extends AbstractProviderRuntimePostgresTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @TempDir Path temporary;

  @Autowired private JpaProviderRuntimeStore store;
  @Autowired private CanonicalGameplayJson canonicalJson;

  @Test
  void handoffExposesOnlyCommittedDecidedTurns() {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts"));
    RedactedTranscriptStore transcripts = new RedactedTranscriptStore(properties, canonicalJson);
    RedactedTranscriptStore.Artifact transcript = transcripts.store("provider transcript");

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
        transcript.reference());
    store.recordTranscript(decided.turnId(), transcript.reference(), transcript.byteLength());

    TurnRequest abstained = turn(session, "abstained");
    reserveAndStream(session, abstained);
    store.finish(abstained.turnId(), TurnState.ABSTAINED, FailureCode.NONE, null, null, null);

    TurnRequest failed = turn(session, "failed");
    reserveAndStream(session, failed);
    store.finish(
        failed.turnId(), TurnState.INVALID_OUTPUT, FailureCode.INVALID_OUTPUT, null, null, null);

    GameplayProviderRuntime runtime =
        new GameplayProviderRuntime(
            properties, null, store, null, null, transcripts, null, List.of());

    ProviderDecisionHandoff.PersistedDecision persisted =
        runtime.findValidatedDecision(session.sessionId(), decided.turnId()).orElseThrow();
    assertEquals(decision.decisionId(), persisted.decision().decisionId());
    assertEquals(canonicalJson.digest(decision), persisted.decisionDigest());
    assertEquals(transcript.reference(), persisted.transcriptRef());
    assertEquals(transcript.reference(), persisted.transcriptSha256());

    assertTrue(runtime.findValidatedDecision(session.sessionId(), abstained.turnId()).isEmpty());
    assertTrue(runtime.findValidatedDecision(session.sessionId(), failed.turnId()).isEmpty());
    assertTrue(runtime.findValidatedDecision(session.sessionId(), "missing-turn").isEmpty());
  }

  /**
   * A v1 decision still loads through the v1 handoff and must never surface through the v2 read:
   * the two contract generations are distinguished by the persisted contractVersion, not by shape.
   */
  @Test
  void aV1DecisionLoadsOnlyThroughTheV1Handoff() {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts-v1"));
    RedactedTranscriptStore transcripts = new RedactedTranscriptStore(properties, canonicalJson);
    String marker = "v1-" + System.nanoTime();
    RedactedTranscriptStore.Artifact transcript = transcripts.store("provider transcript " + marker);

    SessionRequest session = session("handoff-v1-" + System.nanoTime());
    store.createSession(session);
    TurnRequest decided = turn(session, "v1");
    reserveAndStream(session, decided);
    ProviderDecision v1Decision =
        new ProviderDecision(
            GameplayContracts.VERSION,
            Provider.CODEX,
            "gpt-5",
            "decision-" + marker,
            0.91,
            JsonNodeFactory.instance.objectNode().put("kind", "ABSTAIN"),
            List.of("observation:1"));
    store.finish(
        decided.turnId(), TurnState.DECIDED, FailureCode.NONE, v1Decision, null,
        transcript.reference());
    store.recordTranscript(decided.turnId(), transcript.reference(), transcript.byteLength());

    GameplayProviderRuntime runtime =
        new GameplayProviderRuntime(
            properties, null, store, null, null, transcripts, null, List.of());

    assertTrue(runtime.findValidatedDecision(session.sessionId(), decided.turnId()).isPresent());
    assertTrue(runtime.findValidatedV2Decision(session.sessionId(), decided.turnId()).isEmpty());
  }

  /**
   * A v2 decision is exposed only through the v2 read, with its own digests. The v1 view stays
   * empty because deserialising the v2 bytes into the v1 record would fabricate a v1 projection.
   */
  @Test
  void aV2DecisionLoadsOnlyThroughTheV2Handoff() {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts-v2"));
    RedactedTranscriptStore transcripts = new RedactedTranscriptStore(properties, canonicalJson);
    String marker = "v2-" + System.nanoTime();
    RedactedTranscriptStore.Artifact transcript = transcripts.store("provider transcript " + marker);

    SessionRequest session = session("handoff-v2-" + System.nanoTime());
    store.createSession(session);
    TurnRequest decided = turn(session, "v2");
    reserveAndStream(session, decided);

    var decision = JsonNodeFactory.instance.objectNode();
    decision.put("contractVersion", "sc-1429/v2");
    decision.put("kind", "SELECTED");
    decision.put("decisionId", "decision-" + marker);
    decision.put("eligibleInputDigest", DIGEST);
    var goalPlan = decision.putObject("goalPlan");
    goalPlan.put("goalPlanDigest", DIGEST);
    String decisionDigest = canonicalJson.digest(decision);
    String actionDigest = canonicalJson.digest(JsonNodeFactory.instance.objectNode().put("kind", "START_FISHING_TASK"));

    store.finishV2(
        decided.turnId(), TurnState.DECIDED, FailureCode.NONE, decision, decisionDigest,
        actionDigest, null, transcript.reference());
    store.recordTranscript(decided.turnId(), transcript.reference(), transcript.byteLength());

    GameplayProviderRuntime runtime =
        new GameplayProviderRuntime(
            properties, null, store, null, null, transcripts, null, List.of());

    ProviderDecisionHandoff.PersistedV2Decision persisted =
        runtime.findValidatedV2Decision(session.sessionId(), decided.turnId()).orElseThrow();
    assertEquals("decision-" + marker, persisted.decision().path("decisionId").asText());
    assertEquals(decisionDigest, persisted.decisionDigest());
    assertEquals(DIGEST, persisted.goalPlanDigest());
    assertEquals(DIGEST, persisted.eligibleInputDigest());
    assertEquals(transcript.reference(), persisted.transcriptSha256());
    assertTrue(runtime.findValidatedDecision(session.sessionId(), decided.turnId()).isEmpty());
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
