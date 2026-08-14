package com.chromascape.web.aiplayer.contract;

import java.util.Map;

public class CurrentSliceContractException extends RuntimeException {
  private final CurrentSliceFailureCode code;
  private final Map<String, Object> details;

  public CurrentSliceContractException(CurrentSliceFailureCode code, String message) {
    this(code, message, Map.of());
  }

  public CurrentSliceContractException(CurrentSliceFailureCode code, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public CurrentSliceFailureCode code() { return code; }
  public Map<String, Object> details() { return details; }
}
