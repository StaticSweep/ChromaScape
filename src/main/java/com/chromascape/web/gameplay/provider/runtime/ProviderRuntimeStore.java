package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetUsage;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderUsage;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionView;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnView;
import java.util.List;
import java.util.Optional;

public interface ProviderRuntimeStore {
  SessionView createSession(SessionRequest request);

  Optional<SessionView> findSession(String sessionId);

  SessionRequest sessionRequest(String sessionId);

  TurnReservation reserveTurn(String sessionId, TurnRequest request);

  Optional<TurnView> findTurn(String sessionId, String turnId);

  TurnRequest turnRequest(String turnId);

  void markSubmitted(
      String turnId, String providerSessionRef, String providerTurnRef, List<String> events);

  void markStreaming(String turnId);

  void markNormalizing(String turnId);

  TurnView finish(
      String turnId,
      TurnState state,
      FailureCode failureCode,
      ProviderDecision decision,
      ProviderUsage usage,
      String transcriptRef);

  /**
   * Terminal write for an SC-1429/v2 decision.
   *
   * <p>The v2 decision is not the v1 record: it carries the provider's GoalPlan, the prompt
   * identity, the confidence triple and the eligibility digest it was shown. It is persisted as the
   * exact provider bytes with {@code contractVersion = sc-1429/v2}, so a later draft can be derived
   * from durable provider output rather than from a relabelled v1 projection.
   */
  TurnView finishV2(
      String turnId,
      TurnState state,
      FailureCode failureCode,
      com.fasterxml.jackson.databind.JsonNode decision,
      String decisionDigest,
      String semanticActionDigest,
      ProviderUsage usage,
      String transcriptRef);

  TurnView markNeedsReconciliation(String turnId, FailureCode failureCode);

  /**
   * Read-only lookup of the raw persisted decision row for a turn, whatever its contract version.
   * Returns empty when the turn has no decision row. The JSON is the exact persisted provider
   * bytes; callers must check {@code contractVersion} before interpreting it.
   */
  java.util.Optional<com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels
          .PersistedDecisionRow>
      findDecisionRow(String turnId);

  void recordTranscript(String turnId, String artifactRef, long byteLength);

  ToolCallReservation reserveToolCall(
      String turnId, String toolCallId, String toolName, String argumentsDigest);

  void finishToolCall(String toolCallId, String resultDigest, boolean succeeded);

  int toolCallCount(String turnId);

  List<TurnView> recoverableTurns();

  List<TurnView> turnsForSession(String sessionId);

  BudgetUsage usage(String sessionId);

  record TurnReservation(TurnView turn, boolean created) {}

  record ToolCallReservation(boolean created, String resultDigest, boolean completed) {}
}
