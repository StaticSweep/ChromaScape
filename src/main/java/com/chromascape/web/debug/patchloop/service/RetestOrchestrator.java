package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
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

  private final ScriptExecutionService scriptExecutionService;
  private final PatchLoopEvidenceService evidenceService;
  private final PatchLoopIterationRepository iterationRepository;

  public RetestOrchestrator(
      ScriptExecutionService scriptExecutionService,
      PatchLoopEvidenceService evidenceService,
      PatchLoopIterationRepository iterationRepository) {
    this.scriptExecutionService = scriptExecutionService;
    this.evidenceService = evidenceService;
    this.iterationRepository = iterationRepository;
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
      // Run the script again using ScriptExecutionService
      ScriptExecutionService.ScriptExecutionResult execResult =
          scriptExecutionService.runScriptAndWait(scriptName, 300000); // 5 min timeout

      boolean runSuccess = execResult.success();

      // Load iteration to collect evidence
      PatchLoopIteration iteration =
          iterationRepository.findByIterationId(iterationId).orElse(null);
      if (iteration == null) {
        logger.warn("Iteration not found for retest evidence collection: {}", iterationId);
        return new RetestResult(
            false, 0, 0, "Failed to collect evidence: iteration not found", "Iteration not found");
      }

      // Collect new evidence
      AnalysisV1EvidenceBundle newEvidence = evidenceService.collectEvidence(iteration);

      // Compare with baseline
      return compareWithBaseline(baselineData, newEvidence, runSuccess);

    } catch (Exception e) {
      logger.error("Retest failed", e);
      return new RetestResult(
          false, 0, 0, "Retest error: " + e.getMessage(), "Exception during retest");
    }
  }

  /** Compares new evidence with baseline. */
  private RetestResult compareWithBaseline(
      Object baselineData, AnalysisV1EvidenceBundle newEvidence, boolean runSuccess) {

    int improvements = 0;
    int regressions = 0;
    StringBuilder summary = new StringBuilder();

    if (baselineData instanceof AnalysisV1EvidenceBundle baseline) {
      // Compare log counts
      int logDiff = newEvidence.getLogs().size() - baseline.getLogs().size();
      if (logDiff < 0) {
        improvements++;
        summary
            .append("Fewer errors (logs: ")
            .append(newEvidence.getLogs().size())
            .append(" vs ")
            .append(baseline.getLogs().size())
            .append("). ");
      } else if (logDiff > 0) {
        regressions++;
        summary
            .append("More errors (logs: ")
            .append(newEvidence.getLogs().size())
            .append(" vs ")
            .append(baseline.getLogs().size())
            .append("). ");
      }

      // Compare tick counts
      int tickDiff = newEvidence.getTickLogs().size() - baseline.getTickLogs().size();
      if (tickDiff > 0) {
        improvements++;
        summary
            .append("Longer execution (ticks: ")
            .append(newEvidence.getTickLogs().size())
            .append(" vs ")
            .append(baseline.getTickLogs().size())
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
