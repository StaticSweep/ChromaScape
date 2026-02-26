package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService.EvidenceBundle;
import com.chromascape.web.instance.ScriptControl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrator for retest operations.
 *
 * <p>Runs the script again, collects new evidence, and compares to the baseline.
 */
@Service
public class RetestOrchestrator {

  private static final Logger logger = LoggerFactory.getLogger(RetestOrchestrator.class);

  private final ScriptControl scriptControl;
  private final PatchLoopEvidenceService evidenceService;

  public RetestOrchestrator(
      ScriptControl scriptControl, PatchLoopEvidenceService evidenceService) {
    this.scriptControl = scriptControl;
    this.evidenceService = evidenceService;
  }

  /**
   * Runs a retest and compares against baseline.
   *
   * @param iterationId the iteration ID
   * @param scriptName the script name
   * @param baselineAttempt the baseline attempt number
   * @param currentAttempt the current attempt number
   * @param baselineData the baseline evidence data
   * @return the retest result
   */
  public RetestResult retest(
      String iterationId,
      String scriptName,
      int baselineAttempt,
      int currentAttempt,
      Object baselineData) {

    logger.info(
        "Running retest: script={}, baseline={}, current={}",
        scriptName,
        baselineAttempt,
        currentAttempt);

    try {
      // Run the script again
      ObjectNode runConfig = JsonNodeFactory.instance.objectNode();
      runConfig.put("scriptName", scriptName);
      runConfig.put("iterationId", iterationId);
      runConfig.put("attemptNumber", currentAttempt);

      ObjectNode response = scriptControl.runScript(runConfig);

      String runSessionId = response.has("sessionId") ? response.get("sessionId").asText() : null;

      if (runSessionId == null) {
        return new RetestResult(
            false,
            0,
            0,
            "Failed to start retest: no session ID",
            "Script failed to start");
      }

      // Wait for completion
      boolean completed = scriptControl.waitForCompletion(runSessionId, 300000);

      if (!completed) {
        return new RetestResult(
            false,
            0,
            0,
            "Retest timed out",
            "Script execution timed out");
      }

      boolean runSuccess = scriptControl.getRunSuccess(runSessionId);

      // Collect new evidence
      PatchLoopEvidenceService.EvidenceRequest request =
          new PatchLoopEvidenceService.EvidenceRequest(
              iterationId, new com.chromascape.web.debug.patchloop.dto.EvidenceConfigDto());

      EvidenceBundle newEvidence = evidenceService.collectEvidence(request);

      // Compare with baseline
      return compareWithBaseline(baselineData, newEvidence, runSuccess);

    } catch (Exception e) {
      logger.error("Retest failed", e);
      return new RetestResult(
          false,
          0,
          0,
          "Retest error: " + e.getMessage(),
          "Exception during retest");
    }
  }

  /**
   * Compares new evidence with baseline.
   */
  private RetestResult compareWithBaseline(
      Object baselineData, EvidenceBundle newEvidence, boolean runSuccess) {

    int improvements = 0;
    int regressions = 0;
    StringBuilder summary = new StringBuilder();

    if (baselineData instanceof EvidenceBundle baseline) {
      // Compare log counts
      int logDiff = newEvidence.logCount() - baseline.logCount();
      if (logDiff < 0) {
        improvements++;
        summary.append("Fewer errors (logs: ")
            .append(newEvidence.logCount())
            .append(" vs ")
            .append(baseline.logCount())
            .append("). ");
      } else if (logDiff > 0) {
        regressions++;
        summary.append("More errors (logs: ")
            .append(newEvidence.logCount())
            .append(" vs ")
            .append(baseline.logCount())
            .append("). ");
      }

      // Compare tick counts
      int tickDiff = newEvidence.tickLogCount() - baseline.tickLogCount();
      if (tickDiff > 0) {
        improvements++;
        summary.append("Longer execution (ticks: ")
            .append(newEvidence.tickLogCount())
            .append(" vs ")
            .append(baseline.tickLogCount())
            .append("). ");
      }
    }

    // Check if script ran successfully
    if (runSuccess) {
      improvements++;
      summary.append("Script completed successfully. ");
    } else {
      regressions++;
      summary.append("Script failed during execution. ");
    }

    // Determine overall success
    boolean success = improvements > regressions && runSuccess;

    if (success) {
      summary.append("Overall: IMPROVEMENT");
    } else {
      summary.append("Overall: NO IMPROVEMENT");
    }

    return new RetestResult(
        success,
        improvements,
        regressions,
        summary.toString(),
        runSuccess ? "Script ran successfully" : "Script failed");
  }

  /** Result of a retest operation. */
  public record RetestResult(
      boolean success,
      int improvements,
      int regressions,
      String comparisonSummary,
      String details) {}
}
