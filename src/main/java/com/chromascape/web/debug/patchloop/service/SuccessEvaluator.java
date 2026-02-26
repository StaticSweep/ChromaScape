package com.chromascape.web.debug.patchloop.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Evaluates whether the patch loop should stop or continue retrying.
 *
 * <p>Checks success criteria, max attempts, and stop conditions.
 */
@Service
public class SuccessEvaluator {

  private static final Logger logger = LoggerFactory.getLogger(SuccessEvaluator.class);

  /**
   * Evaluates whether the loop should stop.
   *
   * @param executionId the execution ID
   * @param iterationId the iteration ID
   * @param currentAttempt the current attempt number
   * @param maxAttempts the maximum allowed attempts
   * @param successCriteria the success criteria (JSON)
   * @param retestResult the retest result data
   * @return the evaluation result
   */
  public EvaluationResult evaluate(
      String executionId,
      String iterationId,
      int currentAttempt,
      int maxAttempts,
      JsonNode successCriteria,
      Object retestResult) {

    logger.debug(
        "Evaluating success: execution={}, attempt={}/{}",
        executionId,
        currentAttempt,
        maxAttempts);

    // Check if we've reached max attempts
    if (currentAttempt >= maxAttempts) {
      return new EvaluationResult(
          false,
          false,
          "Maximum attempts reached",
          0,
          StopReason.MAX_ATTEMPTS);
    }

    // Check success criteria if provided
    if (successCriteria != null && !successCriteria.isEmpty()) {
      boolean criteriaMet = evaluateSuccessCriteria(successCriteria, retestResult);

      if (criteriaMet) {
        return new EvaluationResult(
            true,
            false,
            "Success criteria met",
            maxAttempts - currentAttempt,
            StopReason.SUCCESS_MET);
      }
    }

    // Check retest result if available
    if (retestResult instanceof RetestOrchestrator.RetestResult retest) {
      if (retest.success()) {
        return new EvaluationResult(
            true,
            false,
            "Retest showed improvement",
            maxAttempts - currentAttempt,
            StopReason.RETEST_SUCCESS);
      }
    }

    // Default: continue retrying
    return new EvaluationResult(
        false,
        true,
        "Continue retrying",
        maxAttempts - currentAttempt,
        StopReason.CONTINUE_RETRY);
  }

  /**
   * Evaluates the success criteria against the retest result.
   */
  private boolean evaluateSuccessCriteria(JsonNode criteria, Object retestResult) {
    // Check for specific criteria types
    if (criteria.has("scriptSuccess")) {
      boolean required = criteria.get("scriptSuccess").asBoolean();
      if (required && retestResult instanceof RetestOrchestrator.RetestResult retest) {
        return retest.success();
      }
    }

    // Check for custom predicate
    if (criteria.has("predicate")) {
      String predicate = criteria.get("predicate").asText();
      // TODO: Implement predicate evaluation (e.g., using a simple DSL)
      logger.debug("Predicate evaluation not yet implemented: {}", predicate);
    }

    // Check for specific state conditions
    if (criteria.has("stateConditions")) {
      JsonNode stateConditions = criteria.get("stateConditions");
      // TODO: Implement state condition checking
      logger.debug("State condition checking not yet implemented");
    }

    return false;
  }

  /** Result of success evaluation. */
  public record EvaluationResult(
      boolean success,
      boolean shouldContinue,
      String stopReason,
      int attemptsRemaining,
      StopReason reason) {}

  /** Reasons for stopping the loop. */
  public enum StopReason {
    /** Success criteria were met */
    SUCCESS_MET,

    /** Retest showed improvement */
    RETEST_SUCCESS,

    /** Maximum attempts reached */
    MAX_ATTEMPTS,

    /** Policy violation */
    POLICY_VIOLATION,

    /** User cancelled */
    USER_CANCELLED,

    /** Timeout exceeded */
    TIMEOUT,

    /** Continue retrying */
    CONTINUE_RETRY,

    /** Repeated identical failure */
    REPEATED_FAILURE
  }
}
