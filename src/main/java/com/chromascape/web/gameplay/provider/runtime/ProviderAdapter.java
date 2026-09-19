package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderReadiness;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderUsage;
import java.time.Instant;
import java.util.List;

/** Thin provider-specific transport boundary. It has no gameplay-delivery capability. */
public interface ProviderAdapter {
  Provider provider();

  ProviderReadiness readiness();

  ProviderTurnResult startTurn(ProviderInvocation invocation);

  ReconciliationResult reconcile(String providerSessionRef, String providerTurnRef);

  CancelResult cancel(String providerSessionRef, String providerTurnRef, String reason);

  record ProviderInvocation(
      String runtimeSessionId,
      String runtimeTurnId,
      String modelId,
      String prompt,
      Instant deadline,
      ProviderToolGateway tools) {}

  record ProviderTurnResult(
      boolean submitted,
      boolean terminal,
      String providerSessionRef,
      String providerTurnRef,
      String rawDecision,
      String redactedTranscript,
      ProviderUsage usage,
      FailureCode failureCode,
      List<String> events) {}

  record ReconciliationResult(
      boolean terminalProven, String rawDecision, ProviderUsage usage, FailureCode failureCode) {}

  record CancelResult(boolean terminalProven, FailureCode failureCode) {}
}
