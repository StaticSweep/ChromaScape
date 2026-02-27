package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response;
import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Focused transactional persistence service for patch loop iterations.
 *
 * <p>Provides short, atomic database write operations with proper transaction boundaries. Separated
 * from {@link PatchLoopAsyncWorker} to ensure Spring AOP can intercept public method calls and
 * create real transactions.
 *
 * <p><b>Design Principle:</b> This service handles only focused DB writes. Long-running operations
 * (script polling, Claude API calls) remain in the async worker to prevent long-held transactions.
 */
@Service
public class PatchLoopPersistenceService {

  private final PatchLoopIterationRepository iterationRepository;

  public PatchLoopPersistenceService(PatchLoopIterationRepository iterationRepository) {
    this.iterationRepository = iterationRepository;
  }

  /**
   * Updates iteration status in a short transaction.
   *
   * @param iteration the iteration to update
   * @param newStatus the new status to transition to
   */
  @Transactional
  public void updateIterationStatus(PatchLoopIteration iteration, PatchLoopStatus newStatus) {
    iteration.transitionTo(newStatus);
    iterationRepository.save(iteration);
  }

  /**
   * Marks iteration as failed in a short transaction.
   *
   * @param iteration the iteration to mark as failed
   * @param failureCode the failure code
   * @param reason the failure reason
   * @param context additional failure context
   */
  @Transactional
  public void markIterationFailed(
      PatchLoopIteration iteration, String failureCode, String reason, String context) {
    iteration.markFailed(failureCode, reason, context);
    iterationRepository.save(iteration);
  }

  /**
   * Saves iteration in a short transaction.
   *
   * @param iteration the iteration to save
   */
  @Transactional
  public void saveIteration(PatchLoopIteration iteration) {
    iterationRepository.save(iteration);
  }

  /**
   * Saves evidence counts and time window in a short transaction.
   *
   * @param iteration the iteration to update
   * @param evidence the evidence bundle containing counts and time window
   */
  @Transactional
  public void saveEvidenceCounts(PatchLoopIteration iteration, AnalysisV1EvidenceBundle evidence) {
    iteration.setEvidenceLogCount(evidence.getLogs().size());
    iteration.setEvidenceTickLogCount(evidence.getTickLogs().size());
    iteration.setEvidenceDecisionCount(evidence.getDecisions().size());
    if (evidence.getTimeWindow() != null) {
      iteration.setEvidenceStartTime(evidence.getTimeWindow().getStart());
      iteration.setEvidenceEndTime(evidence.getTimeWindow().getEnd());
    }
    iterationRepository.save(iteration);
  }

  /**
   * Saves insufficient evidence state in a short transaction.
   *
   * <p>Sets specific {@link PatchLoopFailureCode#INSUFFICIENT_EVIDENCE} code and detailed context
   * without overwriting with generic failure.
   *
   * @param iteration the iteration to update
   * @param evidence the empty/insufficient evidence bundle
   */
  @Transactional
  public void saveInsufficientEvidence(
      PatchLoopIteration iteration, AnalysisV1EvidenceBundle evidence) {
    iteration.setFailureCode(PatchLoopFailureCode.INSUFFICIENT_EVIDENCE);
    iteration.setFailureReason("No evidence found in time window");
    iteration.setFailureContext(
        "Logs: "
            + evidence.getLogs().size()
            + ", Tick Logs: "
            + evidence.getTickLogs().size()
            + ", Decisions: "
            + evidence.getDecisions().size());
    iterationRepository.save(iteration);
  }

  /**
   * Saves analysis request JSON in a short transaction.
   *
   * @param iteration the iteration to update
   * @param requestNode the analysis request JSON
   */
  @Transactional
  public void saveAnalysisRequest(PatchLoopIteration iteration, JsonNode requestNode) {
    iteration.setAnalysisRequestJson(requestNode);
    iterationRepository.save(iteration);
  }

  /**
   * Saves analysis results in a short transaction.
   *
   * <p>Extracts and persists diagnosis fields from Claude API response.
   *
   * @param iteration the iteration to update
   * @param response the analysis response
   * @param responseNode the response JSON
   */
  @Transactional
  public void saveAnalysisResults(
      PatchLoopIteration iteration, AnalysisV1Response response, JsonNode responseNode) {
    iteration.setAnalysisResponseJson(responseNode);
    iteration.setAnalysisId(response.getAnalysisId());

    if (response.getDiagnosis() != null) {
      iteration.setDiagnosisSummary(response.getDiagnosis().getSummary());
      iteration.setRootCause(response.getDiagnosis().getRootCause());
      iteration.setConfidence(
          response.getDiagnosis().getConfidence() != null
              ? response.getDiagnosis().getConfidence().name()
              : null);
      iteration.setRecommendationCount(response.getDiagnosis().getRecommendations().size());
    }
    iterationRepository.save(iteration);
  }
}
