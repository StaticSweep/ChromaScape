package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;

public final class AccountBuilderOrchestrationException extends RuntimeException {
  private final FailureCode failureCode;

  public AccountBuilderOrchestrationException(FailureCode failureCode, String message) {
    super(message);
    this.failureCode = failureCode;
  }

  public FailureCode failureCode() {
    return failureCode;
  }
}
