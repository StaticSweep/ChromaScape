package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopAnalysisArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopAnalysisArtifactRepository;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.PatchLoopAnalysisClient;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for ANALYZE nodes.
 *
 * <p><b>V2 Changes:</b> Updated to use V2 request/response contracts. Stores V2 diagnosis in
 * runtime context for use by GENERATE_PATCH node.
 *
 * @since 2.0.0
 */
@Component
public class AnalyzeNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(AnalyzeNodeExecutor.class);

  private final PatchLoopAnalysisClient analysisClient;
  private final PatchLoopAnalysisArtifactRepository analysisArtifactRepository;

  public AnalyzeNodeExecutor(
      PatchLoopAnalysisClient analysisClient,
      PatchLoopAnalysisArtifactRepository analysisArtifactRepository) {
    this.analysisClient = analysisClient;
    this.analysisArtifactRepository = analysisArtifactRepository;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing ANALYZE node (V2): {}", node.id());

    String executionId = context.executionId();
    String iterationId = context.iterationId();
    int attemptNumber = context.attemptNumber();

    try {
      // Build V2 analysis request
      AnalysisV2Request request = buildAnalysisRequest(context);

      // Send to analysis provider via V2 gateway
      AnalysisV2Response response = analysisClient.analyze(request);

      // Check if analysis failed (both SUCCESS and PARTIAL_SUCCESS continue)
      if (response.getStatus() == AnalysisStatus.FAILURE) {
        String error =
            response.getError() != null ? response.getError().getMessage() : "Unknown error";
        return NodeExecutionResult.failure(node.id(), "Analysis failed: " + error);
      }

      // Extract V2 diagnosis (required for SUCCESS and PARTIAL_SUCCESS)
      AnalysisV2Diagnosis diagnosis = response.getDiagnosis();
      if (diagnosis == null) {
        return NodeExecutionResult.failure(node.id(), "No diagnosis returned");
      }

      // Store V2 diagnosis in runtime context for GENERATE_PATCH node
      context.context().setRuntimeValue("analysis_" + attemptNumber, response);
      context.context().setRuntimeValue("diagnosis_" + attemptNumber, diagnosis);

      // Store patch draft reference for GENERATE_PATCH node
      if (response.getPatchDraft() != null) {
        context.context().setRuntimeValue("patchDraft_" + attemptNumber, response.getPatchDraft());
        context
            .context()
            .setRuntimeValue(
                "patchDraftId_" + attemptNumber, response.getPatchDraft().getPatchId());
      }

      // Persist analysis artifact to database (V2 fields)
      PatchLoopAnalysisArtifact artifact = new PatchLoopAnalysisArtifact();
      artifact.setExecutionId(executionId);
      artifact.setAttemptNumber(attemptNumber);
      artifact.setAnalysisId(response.getAnalysisId());
      artifact.setDiagnosisSummary(diagnosis.getSummary());
      artifact.setRootCause(diagnosis.getRootCause());
      artifact.setConfidence(
          diagnosis.getConfidence() != null ? diagnosis.getConfidence().toString() : "UNKNOWN");
      artifact.setRecommendationCount(
          diagnosis.getRecommendations() != null ? diagnosis.getRecommendations().size() : 0);

      // Store V2-specific fields in JSONB response (not separate columns)
      // Note: Patch ID and impacted modules are accessible via response JSON

      artifact.setTimestamp(LocalDateTime.now());
      analysisArtifactRepository.save(artifact);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("analysisId", response.getAnalysisId());
      outputData.put("diagnosisSummary", diagnosis.getSummary());
      outputData.put("rootCause", diagnosis.getRootCause());
      outputData.put(
          "confidence",
          diagnosis.getConfidence() != null ? diagnosis.getConfidence().toString() : "UNKNOWN");
      outputData.put(
          "recommendationCount",
          diagnosis.getRecommendations() != null ? diagnosis.getRecommendations().size() : 0);
      outputData.put("schemaVersion", response.getSchemaVersion());

      if (response.getPatchDraft() != null) {
        outputData.put("patchId", response.getPatchDraft().getPatchId());
        outputData.put(
            "patchChangeCount",
            response.getPatchDraft().getChanges() != null
                ? response.getPatchDraft().getChanges().size()
                : 0);
      }

      // Indicate PARTIAL_SUCCESS in message for transparency
      String statusIndicator =
          response.getStatus() == AnalysisStatus.PARTIAL_SUCCESS ? "(PARTIAL)" : "";

      return NodeExecutionResult.success(
          node.id(),
          String.format(
              "Analysis complete (V2%s): %s, confidence: %s, patch: %s",
              statusIndicator,
              diagnosis.getSummary(),
              diagnosis.getConfidence(),
              response.getPatchDraft() != null ? response.getPatchDraft().getPatchId() : "none"),
          outputData);

    } catch (Exception e) {
      logger.error("Failed to analyze evidence", e);
      return NodeExecutionResult.failure(node.id(), "Analysis failed: " + e.getMessage());
    }
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.ANALYZE;
  }

  private AnalysisV2Request buildAnalysisRequest(ExecutionContext context) {
    // Get evidence from runtime data (still V1 format from collection)
    AnalysisV1EvidenceBundle evidence =
        (AnalysisV1EvidenceBundle)
            context.context().getRuntimeValue("evidence_" + context.attemptNumber());

    if (evidence == null) {
      logger.warn("No evidence found in runtime data for attempt {}", context.attemptNumber());
      evidence = new AnalysisV1EvidenceBundle();
    }

    // Build V2 request from V1 evidence
    AnalysisV2Request request = new AnalysisV2Request();

    // Set prompt type
    request.setPromptType(
        context.context().getGoalPrompt() != null
                && context.context().getGoalPrompt().toLowerCase().contains("feature")
            ? PromptType.FEATURE
            : PromptType.ISSUE);

    // Build target
    AnalysisV2Target target = new AnalysisV2Target();
    target.setScriptName(evidence.getScriptName());
    if (evidence.getTimeWindow() != null) {
      AnalysisV2Target.SessionBounds bounds = new AnalysisV2Target.SessionBounds();
      bounds.setStart(evidence.getTimeWindow().getStart());
      bounds.setEnd(evidence.getTimeWindow().getEnd());
      target.setSessionBounds(bounds);
    }
    request.setTarget(target);

    // Set prompt and context
    request.setPrompt(context.context().getGoalPrompt());
    request.setContext(context.context().getContext());

    // Build evidence selectors from V1 evidence
    java.util.List<AnalysisV2EvidenceSelector> selectors = new java.util.ArrayList<>();

    if (!evidence.getLogs().isEmpty()) {
      AnalysisV2EvidenceSelector logSelector =
          new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS);
      logSelector.setMaxCount(evidence.getLogs().size());
      if (evidence.getTimeWindow() != null) {
        logSelector.setTimeWindow(evidence.getTimeWindow());
      }
      selectors.add(logSelector);
    }

    if (!evidence.getTickLogs().isEmpty()) {
      AnalysisV2EvidenceSelector tickSelector =
          new AnalysisV2EvidenceSelector(EvidenceType.TICK_LOGS);
      tickSelector.setMaxCount(evidence.getTickLogs().size());
      if (evidence.getTickWindow() != null) {
        tickSelector.setTickWindow(evidence.getTickWindow());
      }
      selectors.add(tickSelector);
    }

    if (!evidence.getDecisions().isEmpty()) {
      AnalysisV2EvidenceSelector decisionSelector =
          new AnalysisV2EvidenceSelector(EvidenceType.DECISIONS);
      decisionSelector.setMaxCount(evidence.getDecisions().size());
      if (evidence.getTimeWindow() != null) {
        decisionSelector.setTimeWindow(evidence.getTimeWindow());
      }
      selectors.add(decisionSelector);
    }

    request.setEvidenceSelectors(selectors);

    return request;
  }
}
