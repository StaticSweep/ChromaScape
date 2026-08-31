package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetUsage;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderReadiness;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderUsage;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionView;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameplayProviderRuntimeTest {
  @TempDir java.nio.file.Path temporary;

  @Test
  void identicalRetryInvokesProviderOnceAndPublishesOnlyPersistedDecision() throws Exception {
    Fixture fixture = fixture(validResult());
    fixture.runtime.createSession(fixture.session);

    var first = fixture.runtime.submitTurn(fixture.session.sessionId(), fixture.turn);
    var retry = fixture.runtime.submitTurn(fixture.session.sessionId(), fixture.turn);
    TurnView terminal =
        awaitTerminal(fixture.runtime, fixture.session.sessionId(), fixture.turn.turnId());

    assertTrue(first.created());
    assertFalse(retry.created());
    assertEquals(TurnState.DECIDED, terminal.state());
    assertEquals(1, fixture.adapter.calls.get());
    assertTrue(
        fixture
            .runtime
            .findValidatedDecision(fixture.session.sessionId(), fixture.turn.turnId())
            .isPresent());
    fixture.runtime.close();
  }

  @Test
  void submittedUnknownOutcomeNeverRetriesOrSwitchesProvider() throws Exception {
    ProviderAdapter.ProviderTurnResult uncertain =
        new ProviderAdapter.ProviderTurnResult(
            true,
            false,
            "claude:session-runtime",
            "claude:turn-runtime",
            null,
            "bounded transcript",
            new ProviderUsage(null, null, 0, 25, false),
            FailureCode.UNKNOWN_PROVIDER_OUTCOME,
            List.of());
    Fixture fixture = fixture(uncertain);
    fixture.runtime.createSession(fixture.session);
    fixture.runtime.submitTurn(fixture.session.sessionId(), fixture.turn);

    TurnView terminal =
        awaitTerminal(fixture.runtime, fixture.session.sessionId(), fixture.turn.turnId());
    fixture.runtime.recoverAtStartup();

    assertEquals(TurnState.NEEDS_RECONCILIATION, terminal.state());
    assertEquals(1, fixture.adapter.calls.get());
    assertTrue(
        fixture
            .runtime
            .findValidatedDecision(fixture.session.sessionId(), fixture.turn.turnId())
            .isEmpty());
    fixture.runtime.close();
  }

  private Fixture fixture(ProviderAdapter.ProviderTurnResult result) throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CanonicalGameplayJson canonical = new CanonicalGameplayJson(mapper);
    JsonNode fixtures;
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json")) {
      fixtures = mapper.readTree(input);
    }
    String digest = "sha256:" + "a".repeat(64);
    SessionRequest session =
        new SessionRequest(
            "session-runtime",
            Provider.CLAUDE,
            "claude-test",
            "task-runtime",
            "run-runtime",
            "recording-runtime",
            digest,
            digest,
            digest,
            digest,
            List.of(digest),
            Instant.now().plusSeconds(120),
            new BudgetLimits(10_000, 10_000, 2, 0, 2, 60_000, 120_000),
            true);
    JsonNode observation = fixtures.path("observation");
    JsonNode actions = mapper.createArrayNode().add(fixtures.path("semanticAction"));
    TurnRequest preliminary =
        new TurnRequest(
            "turn-runtime",
            "request-runtime",
            "idempotency-runtime",
            digest,
            observation,
            canonical.digest(observation),
            actions,
            canonical.digest(actions),
            observation.path("identities"),
            "source-sc1429",
            100,
            100,
            Instant.now().plusSeconds(60),
            true);
    ProviderRuntimeValidator validator =
        new ProviderRuntimeValidator(new GameplayContractValidator(mapper), canonical);
    TurnRequest turn =
        new TurnRequest(
            preliminary.turnId(),
            preliminary.requestId(),
            preliminary.idempotencyKey(),
            validator.fingerprint(session, preliminary),
            preliminary.observation(),
            preliminary.observationDigest(),
            preliminary.eligibleActions(),
            preliminary.eligibilityDigest(),
            preliminary.identities(),
            preliminary.sourceId(),
            preliminary.estimatedInputTokens(),
            preliminary.estimatedOutputTokens(),
            preliminary.deadline(),
            true);
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setEnabled(true);
    properties.setOperatorKey("operator-key-that-is-at-least-32-characters");
    properties.setTranscriptRoot(temporary.resolve("transcripts"));
    properties.setWorkRoot(temporary.resolve("work"));
    FakeAdapter adapter = new FakeAdapter(result);
    InMemoryStore store = new InMemoryStore(canonical);
    GameplayProviderRuntime runtime =
        new GameplayProviderRuntime(
            properties,
            validator,
            store,
            new ProviderPromptBuilder(mapper),
            new ProviderOutputNormalizer(mapper, new GameplayContractValidator(mapper), canonical),
            new RedactedTranscriptStore(properties, canonical),
            List.of(adapter));
    adapter.rawDecision = mapper.writeValueAsString(fixtures.path("claudeProposal"));
    return new Fixture(runtime, adapter, session, turn);
  }

  private ProviderAdapter.ProviderTurnResult validResult() {
    return new ProviderAdapter.ProviderTurnResult(
        true,
        true,
        "claude:session-runtime",
        "claude:turn-runtime",
        "USE_FIXTURE",
        "bounded transcript",
        new ProviderUsage(100L, 100L, 0, 25, true),
        FailureCode.NONE,
        List.of("terminal"));
  }

  private TurnView awaitTerminal(GameplayProviderRuntime runtime, String sessionId, String turnId)
      throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      TurnView value = runtime.turn(sessionId, turnId);
      if (value.state().terminal()) {
        return value;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Provider turn did not become terminal");
  }

  private record Fixture(
      GameplayProviderRuntime runtime,
      FakeAdapter adapter,
      SessionRequest session,
      TurnRequest turn) {}

  private static final class FakeAdapter implements ProviderAdapter {
    private final AtomicInteger calls = new AtomicInteger();
    private final ProviderTurnResult configured;
    private String rawDecision;

    private FakeAdapter(ProviderTurnResult configured) {
      this.configured = configured;
    }

    @Override
    public Provider provider() {
      return Provider.CLAUDE;
    }

    @Override
    public ProviderReadiness readiness() {
      return new ProviderReadiness(true, "fake-claude", "test", FailureCode.NONE);
    }

    @Override
    public ProviderTurnResult startTurn(ProviderInvocation invocation) {
      calls.incrementAndGet();
      return new ProviderTurnResult(
          configured.submitted(),
          configured.terminal(),
          configured.providerSessionRef(),
          configured.providerTurnRef(),
          "USE_FIXTURE".equals(configured.rawDecision()) ? rawDecision : configured.rawDecision(),
          configured.redactedTranscript(),
          configured.usage(),
          configured.failureCode(),
          configured.events());
    }

    @Override
    public ReconciliationResult reconcile(String providerSessionRef, String providerTurnRef) {
      return new ReconciliationResult(false, null, null, FailureCode.NEEDS_RECONCILIATION);
    }

    @Override
    public CancelResult cancel(String providerSessionRef, String providerTurnRef, String reason) {
      return new CancelResult(false, FailureCode.NEEDS_RECONCILIATION);
    }
  }

  private static final class InMemoryStore implements ProviderRuntimeStore {
    private final CanonicalGameplayJson canonical;
    private final Map<String, SessionRequest> sessionRequests = new LinkedHashMap<>();
    private final Map<String, SessionView> sessions = new LinkedHashMap<>();
    private final Map<String, TurnRequest> turnRequests = new LinkedHashMap<>();
    private final Map<String, TurnView> turns = new LinkedHashMap<>();

    private InMemoryStore(CanonicalGameplayJson canonical) {
      this.canonical = canonical;
    }

    @Override
    public synchronized SessionView createSession(SessionRequest request) {
      sessionRequests.putIfAbsent(request.sessionId(), request);
      return sessions.computeIfAbsent(
          request.sessionId(),
          ignored ->
              new SessionView(
                  ProviderRuntimeModels.VERSION,
                  request.sessionId(),
                  request.provider(),
                  request.modelId(),
                  SessionState.CREATED,
                  request.taskId(),
                  request.runId(),
                  request.recordingId(),
                  request.promptDigest(),
                  request.toolsetDigest(),
                  request.knowledgeDigest(),
                  request.goalPlanDigest(),
                  request.taskSkillDigests(),
                  request.deadline(),
                  request.budgets(),
                  BudgetUsage.zero(),
                  0,
                  Instant.now(),
                  Instant.now(),
                  true));
    }

    @Override
    public synchronized Optional<SessionView> findSession(String sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public synchronized SessionRequest sessionRequest(String sessionId) {
      return sessionRequests.get(sessionId);
    }

    @Override
    public synchronized TurnReservation reserveTurn(String sessionId, TurnRequest request) {
      TurnView existing = turns.get(request.turnId());
      if (existing != null) {
        if (!existing.requestFingerprint().equals(request.requestFingerprint())) {
          throw new ProviderRuntimeException(
              FailureCode.IDEMPOTENCY_CONFLICT.name(), "fingerprint conflict");
        }
        return new TurnReservation(existing, false);
      }
      turnRequests.put(request.turnId(), request);
      TurnView value =
          view(sessionId, request, TurnState.RESERVED, FailureCode.NONE, null, null, null);
      turns.put(request.turnId(), value);
      return new TurnReservation(value, true);
    }

    @Override
    public synchronized Optional<TurnView> findTurn(String sessionId, String turnId) {
      return Optional.ofNullable(turns.get(turnId))
          .filter(value -> value.sessionId().equals(sessionId));
    }

    @Override
    public synchronized TurnRequest turnRequest(String turnId) {
      return turnRequests.get(turnId);
    }

    @Override
    public synchronized void markSubmitted(
        String turnId, String providerSessionRef, String providerTurnRef, List<String> events) {
      replace(
          turnId,
          TurnState.SUBMITTED,
          FailureCode.NONE,
          null,
          null,
          null,
          providerSessionRef,
          providerTurnRef);
    }

    @Override
    public synchronized void markNormalizing(String turnId) {
      replace(turnId, TurnState.NORMALIZING, FailureCode.NONE, null, null, null, null, null);
    }

    @Override
    public synchronized TurnView finish(
        String turnId,
        TurnState state,
        FailureCode failureCode,
        ProviderDecision decision,
        ProviderUsage usage,
        String transcriptRef) {
      replace(turnId, state, failureCode, decision, usage, transcriptRef, null, null);
      return turns.get(turnId);
    }

    @Override
    public synchronized TurnView markNeedsReconciliation(String turnId, FailureCode failureCode) {
      replace(turnId, TurnState.NEEDS_RECONCILIATION, failureCode, null, null, null, null, null);
      return turns.get(turnId);
    }

    @Override
    public void recordTranscript(String turnId, String artifactRef, long byteLength) {}

    @Override
    public synchronized List<TurnView> recoverableTurns() {
      return turns.values().stream().filter(value -> !value.state().terminal()).toList();
    }

    @Override
    public synchronized List<TurnView> turnsForSession(String sessionId) {
      return turns.values().stream().filter(value -> value.sessionId().equals(sessionId)).toList();
    }

    @Override
    public BudgetUsage usage(String sessionId) {
      return BudgetUsage.zero();
    }

    private TurnView view(
        String sessionId,
        TurnRequest request,
        TurnState state,
        FailureCode failure,
        ProviderDecision decision,
        ProviderUsage usage,
        String transcriptRef) {
      SessionRequest session = sessionRequests.get(sessionId);
      return new TurnView(
          ProviderRuntimeModels.VERSION,
          sessionId,
          request.turnId(),
          request.requestId(),
          request.requestFingerprint(),
          session.provider(),
          session.modelId(),
          state,
          failure,
          null,
          null,
          request.observationDigest(),
          request.eligibilityDigest(),
          decision == null ? null : canonical.digest(decision),
          decision == null ? null : canonical.digest(decision.semanticAction()),
          decision,
          usage,
          List.of(),
          transcriptRef,
          Instant.now(),
          Instant.now(),
          0,
          true);
    }

    private void replace(
        String turnId,
        TurnState state,
        FailureCode failure,
        ProviderDecision decision,
        ProviderUsage usage,
        String transcriptRef,
        String providerSessionRef,
        String providerTurnRef) {
      TurnView old = turns.get(turnId);
      List<String> events = new ArrayList<>(old.eventRefs());
      events.add("event:" + state.name());
      turns.put(
          turnId,
          new TurnView(
              old.contractVersion(),
              old.sessionId(),
              old.turnId(),
              old.requestId(),
              old.requestFingerprint(),
              old.provider(),
              old.modelId(),
              state,
              failure,
              providerSessionRef == null ? old.providerSessionRef() : providerSessionRef,
              providerTurnRef == null ? old.providerTurnRef() : providerTurnRef,
              old.observationDigest(),
              old.eligibilityDigest(),
              decision == null ? old.decisionDigest() : canonical.digest(decision),
              decision == null
                  ? old.semanticActionDigest()
                  : canonical.digest(decision.semanticAction()),
              decision == null ? old.decision() : decision,
              usage == null ? old.usage() : usage,
              List.copyOf(events),
              transcriptRef == null ? old.transcriptRef() : transcriptRef,
              old.createdAt(),
              Instant.now(),
              old.version() + 1,
              true));
    }
  }
}
