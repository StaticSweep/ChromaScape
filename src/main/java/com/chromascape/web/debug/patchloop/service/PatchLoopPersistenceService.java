package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
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

  /**
   * Saves V2 analysis results in a short transaction.
   *
   * <p>Extracts and persists V2 diagnosis fields including patchDraft reference.
   *
   * @param iteration the iteration to update
   * @param response the V2 analysis response
   * @param responseNode the response JSON
   */
  @Transactional
  public void saveAnalysisResults(
      PatchLoopIteration iteration, AnalysisV2Response response, JsonNode responseNode) {
    iteration.setAnalysisResponseJson(responseNode);
    iteration.setAnalysisId(response.getAnalysisId());

    if (response.getDiagnosis() != null) {
      iteration.setDiagnosisSummary(response.getDiagnosis().getSummary());
      iteration.setRootCause(response.getDiagnosis().getRootCause());
      iteration.setConfidence(
          response.getDiagnosis().getConfidence() != null
              ? response.getDiagnosis().getConfidence().name()
              : null);
      // V2: recommendations optional in diagnosis, preserve count if present
      iteration.setRecommendationCount(
          response.getDiagnosis().getRecommendations() != null
              ? response.getDiagnosis().getRecommendations().size()
              : 0);
    }

    // V2: Store patchDraft metadata in existing diagnosisSummary (as additional info)
    if (response.getPatchDraft() != null) {
      String existingSummary = iteration.getDiagnosisSummary();
      String patchInfo = " [Patch: " + response.getPatchDraft().getPatchId() + "]";
      iteration.setDiagnosisSummary(
          existingSummary != null ? existingSummary + patchInfo : patchInfo.substring(2));
    }

    // V2: impactedModules stored in JSONB response, not separate column

    iterationRepository.save(iteration);
  }

  /**
   * Reads analysis response with V1/V2 compatibility.
   *
   * <p>Detects schema version from JSON and extracts fields accordingly. Returns a stable format
   * for UI consumption regardless of V1 or V2 source.
   *
   * @param responseNode the response JSON from database
   * @return map of extracted fields suitable for UI display
   */
  public java.util.Map<String, Object> readAnalysisResponse(JsonNode responseNode) {
    java.util.Map<String, Object> result = new java.util.HashMap<>();

    if (responseNode == null || responseNode.isNull()) {
      return result;
    }

    String schemaVersion = responseNode.path("schemaVersion").asText(null);

    if (schemaVersion != null && schemaVersion.startsWith("2.")) {
      // V2 path
      return extractV2Fields(responseNode);
    } else {
      // V1 path (default)
      return extractV1Fields(responseNode);
    }
  }

  /** Extracts fields from V1 response JSON. */
  private java.util.Map<String, Object> extractV1Fields(JsonNode responseNode) {
    java.util.Map<String, Object> result = new java.util.HashMap<>();

    JsonNode diagnosis = responseNode.path("diagnosis");
    if (!diagnosis.isMissingNode() && !diagnosis.isNull()) {
      result.put("summary", diagnosis.path("summary").asText());
      result.put("rootCause", diagnosis.path("rootCause").asText());
      result.put("confidence", diagnosis.path("confidence").asText());

      JsonNode recommendations = diagnosis.path("recommendations");
      if (recommendations.isArray()) {
        result.put("recommendationCount", recommendations.size());
      }
    }

    result.put("analysisId", responseNode.path("analysisId").asText());
    result.put("schemaVersion", responseNode.path("schemaVersion").asText("1.0.0"));

    return result;
  }

  /** Extracts fields from V2 response JSON. */
  private java.util.Map<String, Object> extractV2Fields(JsonNode responseNode) {
    java.util.Map<String, Object> result = new java.util.HashMap<>();

    JsonNode diagnosis = responseNode.path("diagnosis");
    if (!diagnosis.isMissingNode() && !diagnosis.isNull()) {
      result.put("summary", diagnosis.path("summary").asText());
      result.put("rootCause", diagnosis.path("rootCause").asText());
      result.put("confidence", diagnosis.path("confidence").asText());

      JsonNode recommendations = diagnosis.path("recommendations");
      if (recommendations.isArray()) {
        result.put("recommendationCount", recommendations.size());
      }
    }

    // V2-specific fields
    JsonNode patchDraft = responseNode.path("patchDraft");
    if (!patchDraft.isMissingNode() && !patchDraft.isNull()) {
      result.put("patchId", patchDraft.path("patchId").asText());
      JsonNode changes = patchDraft.path("changes");
      if (changes.isArray()) {
        result.put("patchChangeCount", changes.size());
      }
    }

    JsonNode impactedModules = responseNode.path("impactedModules");
    if (impactedModules.isArray()) {
      java.util.List<String> modules = new java.util.ArrayList<>();
      impactedModules.forEach(m -> modules.add(m.asText()));
      result.put("impactedModules", modules);
    }

    result.put("analysisId", responseNode.path("analysisId").asText());
    result.put("schemaVersion", responseNode.path("schemaVersion").asText("2.0.0"));

    return result;
  }
}
