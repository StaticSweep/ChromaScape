package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetUsage;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderUsage;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionView;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** Transactional current-version persistence for provider sessions and immutable terminal turns. */
@Repository
public class JpaProviderRuntimeStore implements ProviderRuntimeStore {
  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;
  private final CanonicalGameplayJson canonicalJson;
  private final Clock clock;

  @Autowired
  public JpaProviderRuntimeStore(
      EntityManager entityManager, ObjectMapper objectMapper, CanonicalGameplayJson canonicalJson) {
    this(entityManager, objectMapper, canonicalJson, Clock.systemUTC());
  }

  JpaProviderRuntimeStore(
      EntityManager entityManager,
      ObjectMapper objectMapper,
      CanonicalGameplayJson canonicalJson,
      Clock clock) {
    this.entityManager = entityManager;
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.clock = clock;
  }

  @Override
  @Transactional
  public SessionView createSession(SessionRequest request) {
    ProviderSessionEntity existing =
        entityManager.find(ProviderSessionEntity.class, request.sessionId());
    if (existing != null) {
      SessionRequest stored = read(existing.requestJson, SessionRequest.class);
      if (!canonicalJson.digest(stored).equals(canonicalJson.digest(request))) {
        throw conflict("Session identity is already bound to different immutable configuration");
      }
      return sessionView(
          existing, entityManager.find(ProviderBudgetEntity.class, request.sessionId()));
    }
    Instant now = clock.instant();
    ProviderSessionEntity session = new ProviderSessionEntity();
    session.sessionId = request.sessionId();
    session.provider = request.provider().name();
    session.modelId = request.modelId();
    session.state = SessionState.CREATED.name();
    session.taskId = request.taskId();
    session.runId = request.runId();
    session.recordingId = request.recordingId();
    session.promptDigest = request.promptDigest();
    session.toolsetDigest = request.toolsetDigest();
    session.knowledgeDigest = request.knowledgeDigest();
    session.goalPlanDigest = request.goalPlanDigest();
    session.requestJson = write(request);
    session.deadline = request.deadline();
    session.decideOnly = request.decideOnly();
    session.createdAt = now;
    session.updatedAt = now;
    ProviderBudgetEntity budget = new ProviderBudgetEntity();
    budget.sessionId = request.sessionId();
    entityManager.persist(session);
    entityManager.persist(budget);
    entityManager.flush();
    return sessionView(session, budget);
  }

  @Override
  @Transactional
  public Optional<SessionView> findSession(String sessionId) {
    ProviderSessionEntity session = entityManager.find(ProviderSessionEntity.class, sessionId);
    return session == null
        ? Optional.empty()
        : Optional.of(
            sessionView(session, entityManager.find(ProviderBudgetEntity.class, sessionId)));
  }

  @Override
  @Transactional
  public SessionRequest sessionRequest(String sessionId) {
    ProviderSessionEntity session = entityManager.find(ProviderSessionEntity.class, sessionId);
    if (session == null) {
      throw new ProviderRuntimeException("SESSION_NOT_FOUND", "Provider session was not found");
    }
    return read(session.requestJson, SessionRequest.class);
  }

  @Override
  @Transactional
  public TurnReservation reserveTurn(String sessionId, TurnRequest request) {
    ProviderSessionEntity session =
        entityManager.find(ProviderSessionEntity.class, sessionId, LockModeType.PESSIMISTIC_WRITE);
    if (session == null) {
      throw new ProviderRuntimeException("SESSION_NOT_FOUND", "Provider session was not found");
    }
    ProviderTurnEntity existing = findByAnyIdentity(request);
    if (existing != null) {
      if (!existing.sessionId.equals(sessionId)
          || !existing.requestFingerprint.equals(request.requestFingerprint())) {
        throw conflict("Turn, request or idempotency identity has a different fingerprint");
      }
      return new TurnReservation(turnView(existing), false);
    }
    ProviderBudgetEntity budget =
        entityManager.find(ProviderBudgetEntity.class, sessionId, LockModeType.PESSIMISTIC_WRITE);
    SessionRequest sessionRequest = read(session.requestJson, SessionRequest.class);
    BudgetLimits limits = sessionRequest.budgets();
    Instant now = clock.instant();
    long remainingSessionMillis = limits.maxSessionMillis() - budget.elapsedMillis;
    long allowedTurnMillis = Math.min(limits.maxTurnMillis(), remainingSessionMillis);
    boolean exhausted =
        budget.turnsUsed + 1 > limits.maxTurns()
            || budget.providerCallsUsed + 1 > limits.maxProviderCalls()
            || budget.inputTokensUsed + request.estimatedInputTokens() > limits.maxInputTokens()
            || budget.outputTokensUsed + request.estimatedOutputTokens() > limits.maxOutputTokens()
            || allowedTurnMillis <= 0
            || request.deadline().isAfter(now.plusMillis(Math.max(0, allowedTurnMillis)))
            || !request.deadline().isAfter(clock.instant())
            || !session.deadline.isAfter(clock.instant());
    ProviderTurnEntity turn = new ProviderTurnEntity();
    turn.turnId = request.turnId();
    turn.sessionId = sessionId;
    turn.requestId = request.requestId();
    turn.idempotencyKey = request.idempotencyKey();
    turn.requestFingerprint = request.requestFingerprint();
    turn.state = (exhausted ? TurnState.BUDGET_EXHAUSTED : TurnState.RESERVED).name();
    turn.failureCode = (exhausted ? FailureCode.BUDGET_EXHAUSTED : FailureCode.NONE).name();
    turn.observationDigest = request.observationDigest();
    turn.eligibilityDigest = request.eligibilityDigest();
    turn.requestJson = write(request);
    turn.deadline = request.deadline();
    turn.decideOnly = request.decideOnly();
    turn.createdAt = now;
    turn.updatedAt = now;
    entityManager.persist(turn);
    if (!exhausted) {
      budget.turnsUsed++;
      budget.providerCallsUsed++;
      budget.inputTokensUsed += request.estimatedInputTokens();
      budget.outputTokensUsed += request.estimatedOutputTokens();
    }
    refreshSessionState(session);
    appendEvent(turn.turnId, "TURN_" + turn.state, turn.requestFingerprint);
    entityManager.flush();
    return new TurnReservation(turnView(turn), true);
  }

  @Override
  @Transactional
  public Optional<TurnView> findTurn(String sessionId, String turnId) {
    ProviderTurnEntity turn = entityManager.find(ProviderTurnEntity.class, turnId);
    return turn == null || !turn.sessionId.equals(sessionId)
        ? Optional.empty()
        : Optional.of(turnView(turn));
  }

  @Override
  @Transactional
  public TurnRequest turnRequest(String turnId) {
    ProviderTurnEntity turn = entityManager.find(ProviderTurnEntity.class, turnId);
    if (turn == null) {
      throw new ProviderRuntimeException("TURN_NOT_FOUND", "Provider turn was not found");
    }
    return read(turn.requestJson, TurnRequest.class);
  }

  @Override
  @Transactional
  public void markSubmitted(
      String turnId, String providerSessionRef, String providerTurnRef, List<String> events) {
    ProviderTurnEntity turn = mutableTurn(turnId);
    transition(turn, TurnState.SUBMITTED, FailureCode.NONE);
    turn.providerSessionRef = providerSessionRef;
    turn.providerTurnRef = providerTurnRef;
    appendEvent(turnId, "PROVIDER_SUBMITTED", canonicalJson.digest(providerSessionRef));
    for (String event : events == null ? List.<String>of() : events) {
      appendEvent(turnId, "PROVIDER_EVENT", canonicalJson.digest(event));
    }
  }

  @Override
  @Transactional
  public void markNormalizing(String turnId) {
    ProviderTurnEntity turn = mutableTurn(turnId);
    transition(turn, TurnState.NORMALIZING, FailureCode.NONE);
    appendEvent(turnId, "NORMALIZING", canonicalJson.digest(turnId));
  }

  @Override
  @Transactional
  public void markStreaming(String turnId) {
    ProviderTurnEntity turn = mutableTurn(turnId);
    transition(turn, TurnState.STREAMING, FailureCode.NONE);
    appendEvent(turnId, "STREAMING", canonicalJson.digest(turnId));
  }

  @Override
  @Transactional
  public TurnView finish(
      String turnId,
      TurnState state,
      FailureCode failureCode,
      ProviderDecision decision,
      ProviderUsage usage,
      String transcriptRef) {
    if (!state.terminal()) {
      throw new IllegalArgumentException("finish requires a terminal turn state");
    }
    ProviderTurnEntity turn = mutableTurnForFinish(turnId);
    final ProviderSessionEntity session = lockSession(turn.sessionId);
    if (usage != null && exceedsBudget(turn, usage)) {
      state = TurnState.BUDGET_EXHAUSTED;
      failureCode = FailureCode.BUDGET_EXHAUSTED;
      decision = null;
    }
    transition(turn, state, failureCode);
    turn.usageJson = usage == null ? null : write(usage);
    turn.transcriptRef = transcriptRef;
    if (decision != null) {
      ProviderDecisionEntity persisted = new ProviderDecisionEntity();
      persisted.decisionId = decision.decisionId();
      persisted.turnId = turnId;
      persisted.contractVersion = decision.contractVersion();
      persisted.decisionDigest = canonicalJson.digest(decision);
      persisted.semanticActionDigest = canonicalJson.digest(decision.semanticAction());
      persisted.outcome = state.name();
      persisted.decisionJson = write(decision);
      persisted.createdAt = clock.instant();
      turn.decisionDigest = persisted.decisionDigest;
      turn.semanticActionDigest = persisted.semanticActionDigest;
      entityManager.persist(persisted);
    }
    if (usage != null) {
      chargeUsage(turn, usage);
    }
    refreshSessionState(session);
    appendEvent(turnId, "TURN_" + state.name(), canonicalJson.digest(failureCode.name()));
    entityManager.flush();
    return turnView(turn);
  }

  @Override
  @Transactional
  public TurnView finishV2(
      String turnId,
      TurnState state,
      FailureCode failureCode,
      com.fasterxml.jackson.databind.JsonNode decision,
      String decisionDigest,
      String semanticActionDigest,
      ProviderUsage usage,
      String transcriptRef) {
    if (!state.terminal()) {
      throw new IllegalArgumentException("finishV2 requires a terminal turn state");
    }
    ProviderTurnEntity turn = mutableTurnForFinish(turnId);
    final ProviderSessionEntity session = lockSession(turn.sessionId);
    if (usage != null && exceedsBudget(turn, usage)) {
      state = TurnState.BUDGET_EXHAUSTED;
      failureCode = FailureCode.BUDGET_EXHAUSTED;
      decision = null;
    }
    transition(turn, state, failureCode);
    turn.usageJson = usage == null ? null : write(usage);
    turn.transcriptRef = transcriptRef;
    if (decision != null) {
      if (decisionDigest == null
          || !decisionDigest.matches("sha256:[0-9a-f]{64}")
          || semanticActionDigest == null
          || !semanticActionDigest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("A v2 decision requires canonical digests");
      }
      ProviderDecisionEntity persisted = new ProviderDecisionEntity();
      persisted.decisionId = decision.path("decisionId").asText();
      persisted.turnId = turnId;
      persisted.contractVersion = decision.path("contractVersion").asText();
      persisted.decisionDigest = decisionDigest;
      persisted.semanticActionDigest = semanticActionDigest;
      persisted.outcome = state.name();
      persisted.decisionJson = write(decision);
      persisted.createdAt = clock.instant();
      turn.decisionDigest = decisionDigest;
      turn.semanticActionDigest = semanticActionDigest;
      entityManager.persist(persisted);
    }
    if (usage != null) {
      chargeUsage(turn, usage);
    }
    refreshSessionState(session);
    appendEvent(turnId, "TURN_" + state.name(), canonicalJson.digest(failureCode.name()));
    entityManager.flush();
    return turnView(turn);
  }

  @Override
  @Transactional
  public java.util.Optional<ProviderRuntimeModels.PersistedDecisionRow> findDecisionRow(
      String turnId) {
    java.util.List<ProviderDecisionEntity> rows =
        entityManager
            .createQuery(
                "select d from ProviderDecisionEntity d where d.turnId=:turnId",
                ProviderDecisionEntity.class)
            .setParameter("turnId", turnId)
            .setMaxResults(1)
            .getResultList();
    if (rows.isEmpty()) {
      return java.util.Optional.empty();
    }
    ProviderDecisionEntity row = rows.get(0);
    com.fasterxml.jackson.databind.JsonNode decision;
    try {
      decision = objectMapper.readTree(row.decisionJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new ProviderRuntimeException(
          ProviderRuntimeModels.FailureCode.INVALID_EVIDENCE_REFERENCE.name(),
          "Persisted decision row is not valid JSON");
    }
    return java.util.Optional.of(
        new ProviderRuntimeModels.PersistedDecisionRow(
            row.turnId,
            row.contractVersion,
            row.decisionId,
            row.decisionDigest,
            row.semanticActionDigest,
            row.outcome,
            decision));
  }

  @Override
  @Transactional
  public TurnView markNeedsReconciliation(String turnId, FailureCode failureCode) {
    ProviderTurnEntity turn = mutableTurn(turnId);
    ProviderSessionEntity session = lockSession(turn.sessionId);
    transition(turn, TurnState.NEEDS_RECONCILIATION, failureCode);
    refreshSessionState(session);
    appendEvent(turnId, "NEEDS_RECONCILIATION", canonicalJson.digest(failureCode.name()));
    return turnView(turn);
  }

  @Override
  @Transactional
  public void recordTranscript(String turnId, String artifactRef, long byteLength) {
    ProviderTranscriptEntity artifact = new ProviderTranscriptEntity();
    artifact.artifactId = artifactRef;
    artifact.turnId = turnId;
    artifact.sha256 = artifactRef;
    artifact.redactionVersion = "sc-1430-redaction/v1";
    artifact.byteLength = byteLength;
    artifact.createdAt = clock.instant();
    entityManager.persist(artifact);
  }

  @Override
  @Transactional
  public ToolCallReservation reserveToolCall(
      String turnId, String toolCallId, String toolName, String argumentsDigest) {
    if (toolCallId == null || !toolCallId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")) {
      throw new ProviderRuntimeException("IDENTITY_INVALID", "Tool call ID is invalid");
    }
    if (toolName == null || !toolName.matches("[a-z][a-z0-9_]{0,79}")) {
      throw new ProviderRuntimeException("IDENTITY_INVALID", "Tool name is invalid");
    }
    if (!validDigest(argumentsDigest)) {
      throw new ProviderRuntimeException("DIGEST_INVALID", "Tool arguments digest is invalid");
    }
    ProviderTurnEntity turn = entityManager.find(ProviderTurnEntity.class, turnId);
    if (turn == null) {
      throw new ProviderRuntimeException("TURN_NOT_FOUND", "Provider turn was not found");
    }
    TurnState turnState = TurnState.valueOf(turn.state);
    if (turnState != TurnState.SUBMITTED && turnState != TurnState.STREAMING) {
      throw new ProviderRuntimeException(
          "ILLEGAL_STATE_TRANSITION", "Provider tool calls require submitted work");
    }
    ProviderSessionEntity session = lockSession(turn.sessionId);
    ProviderToolCallEntity existing = entityManager.find(ProviderToolCallEntity.class, toolCallId);
    if (existing != null) {
      if (!existing.turnId.equals(turnId)
          || !existing.toolName.equals(toolName)
          || !existing.argumentsDigest.equals(argumentsDigest)) {
        throw conflict("Tool call identity has different immutable arguments");
      }
      return new ToolCallReservation(
          false, existing.resultDigest, "COMPLETED".equals(existing.state));
    }
    ProviderBudgetEntity budget =
        entityManager.find(
            ProviderBudgetEntity.class, turn.sessionId, LockModeType.PESSIMISTIC_WRITE);
    BudgetLimits limits = read(session.requestJson, SessionRequest.class).budgets();
    if (budget.toolCallsUsed + 1 > limits.maxToolCalls()) {
      throw new ProviderRuntimeException(
          FailureCode.BUDGET_EXHAUSTED.name(), "Provider tool-call budget is exhausted");
    }
    ProviderToolCallEntity toolCall = new ProviderToolCallEntity();
    toolCall.toolCallId = toolCallId;
    toolCall.turnId = turnId;
    toolCall.toolName = toolName;
    toolCall.argumentsDigest = argumentsDigest;
    toolCall.state = "RESERVED";
    toolCall.chargedCalls = 1;
    toolCall.createdAt = clock.instant();
    budget.toolCallsUsed++;
    entityManager.persist(toolCall);
    appendEvent(turnId, "TOOL_CALL_RESERVED", argumentsDigest);
    entityManager.flush();
    return new ToolCallReservation(true, null, false);
  }

  @Override
  @Transactional
  public void finishToolCall(String toolCallId, String resultDigest, boolean succeeded) {
    if (!validDigest(resultDigest)) {
      throw new ProviderRuntimeException("DIGEST_INVALID", "Tool result digest is invalid");
    }
    ProviderToolCallEntity toolCall =
        entityManager.find(
            ProviderToolCallEntity.class, toolCallId, LockModeType.PESSIMISTIC_WRITE);
    if (toolCall == null) {
      throw new ProviderRuntimeException("TOOL_CALL_NOT_FOUND", "Provider tool call was not found");
    }
    ProviderTurnEntity turn = entityManager.find(ProviderTurnEntity.class, toolCall.turnId);
    if (turn == null) {
      throw new ProviderRuntimeException("TURN_NOT_FOUND", "Provider turn was not found");
    }
    lockSession(turn.sessionId);
    String requestedState = succeeded ? "COMPLETED" : "FAILED";
    if (!"RESERVED".equals(toolCall.state)) {
      if (requestedState.equals(toolCall.state)
          && java.util.Objects.equals(resultDigest, toolCall.resultDigest)) {
        return;
      }
      throw conflict("Terminal provider tool call is immutable");
    }
    toolCall.resultDigest = resultDigest;
    toolCall.state = requestedState;
    appendEvent(toolCall.turnId, "TOOL_CALL_" + requestedState, resultDigest);
  }

  @Override
  @Transactional
  public int toolCallCount(String turnId) {
    return Math.toIntExact(
        entityManager
            .createQuery(
                "select count(t) from ProviderToolCallEntity t where t.turnId=:turnId", Long.class)
            .setParameter("turnId", turnId)
            .getSingleResult());
  }

  @Override
  @Transactional
  public List<TurnView> recoverableTurns() {
    return entityManager
        .createQuery(
            "select t from ProviderTurnEntity t where t.state in"
                + " ('CREATED','RESERVED','SUBMITTED','STREAMING','NORMALIZING')",
            ProviderTurnEntity.class)
        .getResultList()
        .stream()
        .map(this::turnView)
        .toList();
  }

  @Override
  @Transactional
  public List<TurnView> turnsForSession(String sessionId) {
    return entityManager
        .createQuery(
            "select t from ProviderTurnEntity t where t.sessionId=:sessionId order by t.createdAt",
            ProviderTurnEntity.class)
        .setParameter("sessionId", sessionId)
        .getResultList()
        .stream()
        .map(this::turnView)
        .toList();
  }

  @Override
  @Transactional
  public BudgetUsage usage(String sessionId) {
    ProviderBudgetEntity value = entityManager.find(ProviderBudgetEntity.class, sessionId);
    if (value == null) {
      throw new ProviderRuntimeException("SESSION_NOT_FOUND", "Provider session was not found");
    }
    return budgetUsage(value);
  }

  private ProviderTurnEntity findByAnyIdentity(TurnRequest request) {
    try {
      return entityManager
          .createQuery(
              "select t from ProviderTurnEntity t where t.turnId=:turnId or t.requestId=:requestId"
                  + " or t.idempotencyKey=:idempotencyKey",
              ProviderTurnEntity.class)
          .setParameter("turnId", request.turnId())
          .setParameter("requestId", request.requestId())
          .setParameter("idempotencyKey", request.idempotencyKey())
          .setMaxResults(1)
          .getSingleResult();
    } catch (NoResultException ignored) {
      return null;
    }
  }

  private ProviderTurnEntity mutableTurn(String turnId) {
    ProviderTurnEntity turn =
        entityManager.find(
            ProviderTurnEntity.class, turnId, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    if (turn == null) {
      throw new ProviderRuntimeException("TURN_NOT_FOUND", "Provider turn was not found");
    }
    if (TurnState.valueOf(turn.state).terminal()) {
      throw new ProviderRuntimeException(
          "TERMINAL_IMMUTABLE", "Terminal provider turns are immutable");
    }
    return turn;
  }

  private ProviderTurnEntity mutableTurnForFinish(String turnId) {
    ProviderTurnEntity turn =
        entityManager.find(
            ProviderTurnEntity.class, turnId, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    if (turn == null) {
      throw new ProviderRuntimeException("TURN_NOT_FOUND", "Provider turn was not found");
    }
    TurnState current = TurnState.valueOf(turn.state);
    if (current.terminal() && current != TurnState.NEEDS_RECONCILIATION) {
      throw new ProviderRuntimeException(
          "TERMINAL_IMMUTABLE", "Terminal provider turns are immutable");
    }
    return turn;
  }

  private void transition(ProviderTurnEntity turn, TurnState state, FailureCode failureCode) {
    TurnState current = TurnState.valueOf(turn.state);
    boolean allowed =
        switch (state) {
          case SUBMITTED -> current == TurnState.RESERVED;
          case STREAMING -> current == TurnState.SUBMITTED;
          case NORMALIZING -> current == TurnState.SUBMITTED || current == TurnState.STREAMING;
          default -> state.terminal()
              && (!current.terminal() || current == TurnState.NEEDS_RECONCILIATION);
        };
    if (!allowed) {
      throw new ProviderRuntimeException(
          "ILLEGAL_STATE_TRANSITION", "Illegal provider turn state transition");
    }
    turn.state = state.name();
    turn.failureCode = failureCode.name();
    turn.updatedAt = clock.instant();
  }

  private void chargeUsage(ProviderTurnEntity turn, ProviderUsage usage) {
    ProviderBudgetEntity budget =
        entityManager.find(
            ProviderBudgetEntity.class, turn.sessionId, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    TurnRequest request = read(turn.requestJson, TurnRequest.class);
    if (usage.inputTokens() != null) {
      budget.inputTokensUsed += Math.max(0, usage.inputTokens() - request.estimatedInputTokens());
    }
    if (usage.outputTokens() != null) {
      budget.outputTokensUsed +=
          Math.max(0, usage.outputTokens() - request.estimatedOutputTokens());
    }
    budget.elapsedMillis += usage.elapsedMillis();
  }

  private boolean exceedsBudget(ProviderTurnEntity turn, ProviderUsage usage) {
    ProviderBudgetEntity budget = entityManager.find(ProviderBudgetEntity.class, turn.sessionId);
    ProviderSessionEntity session = entityManager.find(ProviderSessionEntity.class, turn.sessionId);
    BudgetLimits limits = read(session.requestJson, SessionRequest.class).budgets();
    TurnRequest request = read(turn.requestJson, TurnRequest.class);
    long additionalInput =
        usage.inputTokens() == null
            ? 0
            : Math.max(0, usage.inputTokens() - request.estimatedInputTokens());
    long additionalOutput =
        usage.outputTokens() == null
            ? 0
            : Math.max(0, usage.outputTokens() - request.estimatedOutputTokens());
    return budget.inputTokensUsed + additionalInput > limits.maxInputTokens()
        || budget.outputTokensUsed + additionalOutput > limits.maxOutputTokens()
        || usage.toolCalls() > toolCallCount(turn.turnId)
        || budget.elapsedMillis + usage.elapsedMillis() > limits.maxSessionMillis()
        || usage.elapsedMillis() > limits.maxTurnMillis();
  }

  private ProviderSessionEntity lockSession(String sessionId) {
    ProviderSessionEntity session =
        entityManager.find(ProviderSessionEntity.class, sessionId, LockModeType.PESSIMISTIC_WRITE);
    if (session == null) {
      throw new ProviderRuntimeException("SESSION_NOT_FOUND", "Provider session was not found");
    }
    return session;
  }

  private boolean validDigest(String value) {
    return value != null && value.matches("sha256:[a-f0-9]{64}");
  }

  private void refreshSessionState(ProviderSessionEntity session) {
    List<TurnState> turnStates =
        entityManager
            .createQuery(
                "select t.state from ProviderTurnEntity t where t.sessionId=:sessionId",
                String.class)
            .setParameter("sessionId", session.sessionId)
            .getResultList()
            .stream()
            .map(TurnState::valueOf)
            .toList();
    session.state = aggregateSessionState(turnStates).name();
    session.updatedAt = clock.instant();
  }

  static SessionState aggregateSessionState(List<TurnState> turnStates) {
    if (turnStates.isEmpty()) {
      return SessionState.CREATED;
    }
    if (turnStates.contains(TurnState.NEEDS_RECONCILIATION)) {
      return SessionState.NEEDS_RECONCILIATION;
    }
    if (turnStates.stream().anyMatch(state -> !state.terminal())) {
      return SessionState.ACTIVE;
    }
    if (turnStates.stream()
        .anyMatch(
            state ->
                state != TurnState.DECIDED
                    && state != TurnState.ABSTAINED
                    && state != TurnState.CANCELLED)) {
      return SessionState.FAILED;
    }
    if (turnStates.stream()
        .anyMatch(state -> state == TurnState.DECIDED || state == TurnState.ABSTAINED)) {
      return SessionState.COMPLETED;
    }
    return SessionState.CANCELLED;
  }

  private void appendEvent(String turnId, String eventType, String payloadDigest) {
    Long maximum =
        entityManager
            .createQuery(
                "select max(e.sequence) from ProviderEventEntity e where e.turnId=:turnId",
                Long.class)
            .setParameter("turnId", turnId)
            .getSingleResult();
    ProviderEventEntity event = new ProviderEventEntity();
    event.eventId = "event:" + UUID.randomUUID();
    event.turnId = turnId;
    event.sequence = maximum == null ? 1 : maximum + 1;
    event.eventType = eventType;
    event.payloadDigest =
        payloadDigest != null && payloadDigest.matches("sha256:[a-f0-9]{64}")
            ? payloadDigest
            : canonicalJson.digest(payloadDigest == null ? "" : payloadDigest);
    event.occurredAt = clock.instant();
    entityManager.persist(event);
  }

  private SessionView sessionView(ProviderSessionEntity entity, ProviderBudgetEntity budget) {
    SessionRequest request = read(entity.requestJson, SessionRequest.class);
    return new SessionView(
        ProviderRuntimeModels.VERSION,
        entity.sessionId,
        Provider.valueOf(entity.provider),
        entity.modelId,
        SessionState.valueOf(entity.state),
        entity.taskId,
        entity.runId,
        entity.recordingId,
        entity.promptDigest,
        entity.toolsetDigest,
        entity.knowledgeDigest,
        entity.goalPlanDigest,
        request.taskSkillDigests(),
        entity.deadline,
        request.budgets(),
        budgetUsage(budget),
        entity.version,
        entity.createdAt,
        entity.updatedAt,
        entity.decideOnly);
  }

  private TurnView turnView(ProviderTurnEntity entity) {
    ProviderSessionEntity session =
        entityManager.find(ProviderSessionEntity.class, entity.sessionId);
    ProviderDecision decision = null;
    try {
      ProviderDecisionEntity persisted =
          entityManager
              .createQuery(
                  "select d from ProviderDecisionEntity d where d.turnId=:turnId",
                  ProviderDecisionEntity.class)
              .setParameter("turnId", entity.turnId)
              .getSingleResult();
      // A v2 decision is not a v1 record: it carries the GoalPlan and is exposed through the
      // dedicated v2 handoff read. Deserialising it into the v1 type would silently fabricate a
      // v1 projection, so the v1 view stays empty for it.
      if (!"sc-1429/v2".equals(persisted.contractVersion)) {
        decision = read(persisted.decisionJson, ProviderDecision.class);
      }
    } catch (NoResultException ignored) {
      // Non-decision terminal and in-flight turns intentionally have no decision row.
    }
    List<String> events =
        new ArrayList<>(
            entityManager
                .createQuery(
                    "select e.eventId from ProviderEventEntity e where e.turnId=:turnId order by"
                        + " e.sequence",
                    String.class)
                .setParameter("turnId", entity.turnId)
                .getResultList());
    ProviderUsage usage =
        entity.usageJson == null ? null : read(entity.usageJson, ProviderUsage.class);
    return new TurnView(
        ProviderRuntimeModels.VERSION,
        entity.sessionId,
        entity.turnId,
        entity.requestId,
        entity.requestFingerprint,
        Provider.valueOf(session.provider),
        session.modelId,
        TurnState.valueOf(entity.state),
        FailureCode.valueOf(entity.failureCode),
        entity.providerSessionRef,
        entity.providerTurnRef,
        entity.observationDigest,
        entity.eligibilityDigest,
        entity.decisionDigest,
        entity.semanticActionDigest,
        decision,
        usage,
        List.copyOf(events),
        entity.transcriptRef,
        entity.createdAt,
        entity.updatedAt,
        entity.version,
        entity.decideOnly);
  }

  private BudgetUsage budgetUsage(ProviderBudgetEntity entity) {
    return new BudgetUsage(
        entity.inputTokensUsed,
        entity.outputTokensUsed,
        entity.providerCallsUsed,
        entity.toolCallsUsed,
        entity.turnsUsed,
        entity.elapsedMillis);
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to persist provider runtime value", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to read persisted provider runtime value", exception);
    }
  }

  private ProviderRuntimeException conflict(String message) {
    return new ProviderRuntimeException(FailureCode.IDEMPOTENCY_CONFLICT.name(), message);
  }
}
