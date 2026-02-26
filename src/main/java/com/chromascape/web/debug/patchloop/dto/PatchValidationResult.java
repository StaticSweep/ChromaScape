package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Result of validating a patch (running tests, checks).
 *
 * <p>Contains the validation status, output, and any errors or warnings.
 */
public class PatchValidationResult {

  /** Whether validation passed */
  @JsonProperty("passed")
  private boolean passed;

  /** Validation output (test results, etc.) */
  @JsonProperty("output")
  private String output;

  /** Error output if validation failed */
  @JsonProperty("errorOutput")
  private String errorOutput;

  /** Exit code from validation command */
  @JsonProperty("exitCode")
  private Integer exitCode;

  /** List of test failures */
  @JsonProperty("failures")
  private List<TestFailure> failures;

  /** List of warnings */
  @JsonProperty("warnings")
  private List<String> warnings;

  /** Time taken for validation (milliseconds) */
  @JsonProperty("durationMs")
  private Long durationMs;

  /** Validation timestamp */
  @JsonProperty("timestamp")
  private String timestamp;

  /** Patch ID that was validated */
  @JsonProperty("patchId")
  private String patchId;

  public PatchValidationResult() {
    this.failures = new ArrayList<>();
    this.warnings = new ArrayList<>();
  }

  public boolean isPassed() {
    return passed;
  }

  public void setPassed(boolean passed) {
    this.passed = passed;
  }

  public String getOutput() {
    return output;
  }

  public void setOutput(String output) {
    this.output = output;
  }

  public String getErrorOutput() {
    return errorOutput;
  }

  public void setErrorOutput(String errorOutput) {
    this.errorOutput = errorOutput;
  }

  public Integer getExitCode() {
    return exitCode;
  }

  public void setExitCode(Integer exitCode) {
    this.exitCode = exitCode;
  }

  public List<TestFailure> getFailures() {
    return failures;
  }

  public void setFailures(List<TestFailure> failures) {
    this.failures = failures;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<String> warnings) {
    this.warnings = warnings;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  public String getPatchId() {
    return patchId;
  }

  public void setPatchId(String patchId) {
    this.patchId = patchId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchValidationResult that = (PatchValidationResult) o;
    return passed == that.passed && Objects.equals(patchId, that.patchId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(patchId, passed);
  }

  @Override
  public String toString() {
    return "PatchValidationResult{"
        + "patchId='"
        + patchId
        + '\''
        + ", passed="
        + passed
        + ", exitCode="
        + exitCode
        + ", durationMs="
        + durationMs
        + '}';
  }

  /** A single test failure. */
  public static class TestFailure {

    @JsonProperty("testName")
    private String testName;

    @JsonProperty("className")
    private String className;

    @JsonProperty("errorMessage")
    private String errorMessage;

    @JsonProperty("stackTrace")
    private String stackTrace;

    public String getTestName() {
      return testName;
    }

    public void setTestName(String testName) {
      this.testName = testName;
    }

    public String getClassName() {
      return className;
    }

    public void setClassName(String className) {
      this.className = className;
    }

    public String getErrorMessage() {
      return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
      this.errorMessage = errorMessage;
    }

    public String getStackTrace() {
      return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
      this.stackTrace = stackTrace;
    }
  }
}
