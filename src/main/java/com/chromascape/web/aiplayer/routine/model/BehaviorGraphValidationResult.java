package com.chromascape.web.aiplayer.routine.model;

import java.util.List;

public record BehaviorGraphValidationResult(
    List<BehaviorGraphValidationIssue> errors, List<BehaviorGraphValidationIssue> warnings) {
  public String state() {
    return errors.isEmpty() ? "VALID" : "INVALID";
  }
}
