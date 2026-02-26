package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphExecutionContext;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.PatchLoopAnalysisClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for ANALYZE nodes.
 *
 * <p>Sends collected evidence to the AI analysis provider for diagnosis.
 */
@Component
public class AnalyzeNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(AnalyzeNodeExecutor.class);

  private final PatchLoopAnalysisClient analysisClient;

  public AnalyzeNodeExecutor(PatchLoopAnalysisClient analysisClient) {
    this.analysisClient = analysisClient;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing ANALYZE node: {}", node.id());

    String executionId = context.executionId();
    String iterationId = context.iterationId();
    int attemptNumber = context.attemptNumber();

    try {
      // Build analysis request
      AnalysisV1Request request = buildAnalysisRequest(context);

      // Send to analysis provider - use the correct API method
      AnalysisV1Response response = analysisClient.analyzeEvidence(request);

      // Check if analysis succeeded
      if (response.getStatus() != com.chromascape.web.debug.patchloop.dto.AnalysisStatus.SUCCESS) {
        String error = response.getError() != null ? response.getError().getMessage() : "Unknown error";
        return NodeExecutionResult.failure(node.id(), "Analysis failed: " + error);
      }

      // Extract diagnosis
      AnalysisV1Response.DiagnosisSection diagnosis = response.getDiagnosis();
      if (diagnosis == null) {
        return NodeExecutionResult.failure(node.id(), "No diagnosis returned");
      }

      // Store the analysis results for later use
      context.context().setRuntimeValue("analysis_" + attemptNumber, diagnosis);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("analysisId", response.getAnalysisId());
      outputData.put("diagnosisSummary", diagnosis.getSummary());
      outputData.put("rootCause", diagnosis.getRootCause());
      outputData.put("confidence", diagnosis.getConfidence() != null ? diagnosis.getConfidence().toString() : "UNKNOWN");
      outputData.put("recommendationCount", diagnosis.getRecommendations() != null ? diagnosis.getRecommendations().size() : 0);

      return NodeExecutionResult.success(
          node.id(),
          String.format("Analysis complete: %s, confidence: %s",
              diagnosis.getSummary(),
              diagnosis.getConfidence()),
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

  private AnalysisV1Request buildAnalysisRequest(ExecutionContext context) {
    // Get evidence from runtime data
    AnalysisV1EvidenceBundle evidence =
        (AnalysisV1EvidenceBundle)
            context.context().getRuntimeValue("evidence_" + context.attemptNumber());

    if (evidence == null) {
      logger.warn("No evidence found in runtime data for attempt {}", context.attemptNumber());
      evidence = new AnalysisV1EvidenceBundle();
    }

    AnalysisV1Request request = new AnalysisV1Request();
    request.setEvidence(evidence);
    request.setScriptName(context.context().getScriptName());
    request.setPrompt(context.context().getGoalPrompt());
    request.setContext(context.context().getContext());

    return request;
  }
}
