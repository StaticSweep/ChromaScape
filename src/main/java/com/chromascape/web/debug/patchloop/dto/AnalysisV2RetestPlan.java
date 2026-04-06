package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Retest plan for V2 analysis responses.
 *
 * <p>Defines how to validate that the patch worked, including test steps, success criteria,
 * rollback plan, and estimated duration.
 *
 * @since 2.0.0
 */
public class AnalysisV2RetestPlan {

  /** Ordered list of test steps to execute. */
  @JsonProperty("testSteps")
  private List<TestStep> testSteps;

  /** Criteria that must be met for the patch to be considered successful. */
  @JsonProperty("successCriteria")
  private List<String> successCriteria;

  /** Plan to rollback changes if the patch fails. */
  @JsonProperty("rollbackPlan")
  private String rollbackPlan;

  /** Estimated time to complete retest (human-readable, e.g., "5 minutes"). */
  @JsonProperty("estimatedDuration")
  private String estimatedDuration;

  public AnalysisV2RetestPlan() {
    this.testSteps = new ArrayList<>();
    this.successCriteria = new ArrayList<>();
  }

  public List<TestStep> getTestSteps() {
    return testSteps != null ? testSteps : new ArrayList<>();
  }

  public void setTestSteps(List<TestStep> testSteps) {
    this.testSteps = testSteps;
  }

  public List<String> getSuccessCriteria() {
    return successCriteria != null ? successCriteria : new ArrayList<>();
  }

  public void setSuccessCriteria(List<String> successCriteria) {
    this.successCriteria = successCriteria;
  }

  public String getRollbackPlan() {
    return rollbackPlan;
  }

  public void setRollbackPlan(String rollbackPlan) {
    this.rollbackPlan = rollbackPlan;
  }

  public String getEstimatedDuration() {
    return estimatedDuration;
  }

  public void setEstimatedDuration(String estimatedDuration) {
    this.estimatedDuration = estimatedDuration;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV2RetestPlan that = (AnalysisV2RetestPlan) o;
    return Objects.equals(testSteps, that.testSteps)
        && Objects.equals(successCriteria, that.successCriteria)
        && Objects.equals(rollbackPlan, that.rollbackPlan)
        && Objects.equals(estimatedDuration, that.estimatedDuration);
  }

  @Override
  public int hashCode() {
    return Objects.hash(testSteps, successCriteria, rollbackPlan, estimatedDuration);
  }

  @Override
  public String toString() {
    return "AnalysisV2RetestPlan{"
        + "testSteps="
        + (testSteps != null ? testSteps.size() : 0)
        + ", successCriteria="
        + (successCriteria != null ? successCriteria.size() : 0)
        + ", rollbackPlan='"
        + rollbackPlan
        + '\''
        + ", estimatedDuration='"
        + estimatedDuration
        + '\''
        + '}';
  }

  /** A single test step in the retest plan. */
  public static class TestStep {

    /** Step number for ordering. */
    @JsonProperty("stepNumber")
    private Integer stepNumber;

    /** Description of the test step. */
    @JsonProperty("description")
    private String description;

    /** Expected result of this step. */
    @JsonProperty("expectedResult")
    private String expectedResult;

    /** Type of test action. */
    @JsonProperty("actionType")
    private ActionType actionType;

    /** Target entity or component for this step (optional). */
    @JsonProperty("target")
    private String target;

    public TestStep() {}

    public TestStep(Integer stepNumber, String description, ActionType actionType) {
      this.stepNumber = stepNumber;
      this.description = description;
      this.actionType = actionType;
    }

    public Integer getStepNumber() {
      return stepNumber;
    }

    public void setStepNumber(Integer stepNumber) {
      this.stepNumber = stepNumber;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public String getExpectedResult() {
      return expectedResult;
    }

    public void setExpectedResult(String expectedResult) {
      this.expectedResult = expectedResult;
    }

    public ActionType getActionType() {
      return actionType;
    }

    public void setActionType(ActionType actionType) {
      this.actionType = actionType;
    }

    public String getTarget() {
      return target;
    }

    public void setTarget(String target) {
      this.target = target;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      TestStep that = (TestStep) o;
      return Objects.equals(stepNumber, that.stepNumber)
          && Objects.equals(description, that.description)
          && Objects.equals(expectedResult, that.expectedResult)
          && actionType == that.actionType
          && Objects.equals(target, that.target);
    }

    @Override
    public int hashCode() {
      return Objects.hash(stepNumber, description, expectedResult, actionType, target);
    }

    @Override
    public String toString() {
      return "TestStep{"
          + "stepNumber="
          + stepNumber
          + ", description='"
          + description
          + '\''
          + ", expectedResult='"
          + expectedResult
          + '\''
          + ", actionType="
          + actionType
          + ", target='"
          + target
          + '\''
          + '}';
    }
  }

  /** Type of test action. */
  public enum ActionType {
    /** Run the script */
    RUN_SCRIPT,

    /** Verify specific behavior */
    VERIFY_BEHAVIOR,

    /** Check logs for expected output */
    CHECK_LOGS,

    /** Inspect game state */
    INSPECT_STATE,

    /** Manual verification step */
    MANUAL_VERIFY
  }
}
