package com.chromascape.web.debug.patchloop.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for validating patches by running tests and checks.
 *
 * <p>Runs Gradle tasks and collects output for validation results.
 */
@Service
public class PatchValidationService {

  private static final Logger logger = LoggerFactory.getLogger(PatchValidationService.class);

  /** Default validation commands. */
  private static final List<String> DEFAULT_COMMANDS =
      List.of("./gradlew", "test", "--tests", "*Script*");

  /** Default timeout in seconds. */
  private static final int DEFAULT_TIMEOUT_SECONDS = 120;

  /**
   * Validates a patch by running tests.
   *
   * @param executionId the execution ID
   * @param attemptNumber the attempt number
   * @return the validation result
   */
  public ValidationResult validate(String executionId, int attemptNumber) {
    return validate(executionId, attemptNumber, DEFAULT_COMMANDS, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Validates a patch by running specific commands.
   *
   * @param executionId the execution ID
   * @param attemptNumber the attempt number
   * @param commands the commands to run
   * @param timeoutSeconds timeout in seconds
   * @return the validation result
   */
  public ValidationResult validate(
      String executionId, int attemptNumber, List<String> commands, int timeoutSeconds) {

    logger.info("Validating patch for execution {} attempt {}", executionId, attemptNumber);

    long startTime = System.currentTimeMillis();
    StringBuilder output = new StringBuilder();
    StringBuilder errorOutput = new StringBuilder();
    int exitCode = -1;

    try {
      ProcessBuilder pb = new ProcessBuilder(commands);
      pb.redirectErrorStream(true);

      Process process = pb.start();

      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append("\n");
      }

      boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

      if (!completed) {
        process.destroyForcibly();
        exitCode = -1;
        errorOutput.append("Validation timed out after ").append(timeoutSeconds).append(" seconds");
      } else {
        exitCode = process.exitValue();
      }

      long duration = System.currentTimeMillis() - startTime;
      boolean passed = exitCode == 0;

      // Generate output summary
      String summary = generateSummary(output.toString(), passed);

      logger.info(
          "Validation {} in {} ms (exit code: {})",
          passed ? "passed" : "failed",
          duration,
          exitCode);

      return new ValidationResult(
          passed, exitCode, duration, summary, output.toString(), errorOutput.toString());

    } catch (Exception e) {
      logger.error("Validation failed for execution {}", executionId, e);
      long duration = System.currentTimeMillis() - startTime;
      return new ValidationResult(
          false,
          -1,
          duration,
          "Validation error: " + e.getMessage(),
          output.toString(),
          e.getMessage());
    }
  }

  /** Generates a summary of validation output. */
  private String generateSummary(String output, boolean passed) {
    if (passed) {
      int testCount = countMatches(output, "test");
      return String.format("All tests passed (%d tests)", testCount);
    } else {
      // Find failure indicators
      int failedTests = countMatches(output, "FAILED");
      int compilationErrors = countMatches(output, "error:");

      List<String> issues = new ArrayList<>();
      if (failedTests > 0) {
        issues.add(failedTests + " test(s) failed");
      }
      if (compilationErrors > 0) {
        issues.add(compilationErrors + " compilation error(s)");
      }

      if (issues.isEmpty()) {
        return "Validation failed";
      }

      return String.join(", ", issues);
    }
  }

  private int countMatches(String text, String substring) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(substring, index)) != -1) {
      count++;
      index += substring.length();
    }
    return count;
  }

  /** Result of a patch validation. */
  public record ValidationResult(
      boolean passed,
      int exitCode,
      long durationMs,
      String outputSummary,
      String fullOutput,
      String errorOutput) {}
}
