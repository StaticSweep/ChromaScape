package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response;
import com.chromascape.web.debug.patchloop.dto.PatchProposal;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphExecutionContext;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for GENERATE_PATCH nodes.
 *
 * <p>Generates a code patch based on the analysis results.
 */
@Component
public class GeneratePatchNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(GeneratePatchNodeExecutor.class);

  public GeneratePatchNodeExecutor() {
    // Dependencies injected via constructor if needed
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing GENERATE_PATCH node: {}", node.id());

    String executionId = context.executionId();
    int attemptNumber = context.attemptNumber();

    try {
      // Get the analysis results from runtime data
      AnalysisV1Response.DiagnosisSection diagnosis =
          (AnalysisV1Response.DiagnosisSection)
              context.context().getRuntimeValue("analysis_" + attemptNumber);

      if (diagnosis == null) {
        return NodeExecutionResult.failure(node.id(), "No analysis results found");
      }

      // Check if there are any recommendations
      if (diagnosis.getRecommendations() == null || diagnosis.getRecommendations().isEmpty()) {
        return NodeExecutionResult.failure(node.id(), "No recommendations to generate patch from");
      }

      // Select the highest priority recommendation
      AnalysisV1Response.FixRecommendation recommendation =
          diagnosis.getRecommendations().stream()
              .min((a, b) -> {
                int priorityA = a.getPriority() != null ? a.getPriority() : Integer.MAX_VALUE;
                int priorityB = b.getPriority() != null ? b.getPriority() : Integer.MAX_VALUE;
                return Integer.compare(priorityA, priorityB);
              })
              .orElse(null);

      if (recommendation == null) {
        return NodeExecutionResult.failure(node.id(), "No valid recommendation found");
      }

      // Generate the patch
      // In a real implementation, this would call the AI provider with "generate_patch" mode
      // For now, create a placeholder patch
      PatchProposal patch = generatePatchFromRecommendation(recommendation, context);

      // Store the patch for later use
      context.context().setRuntimeValue("patch_" + attemptNumber, patch);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("patchId", patch.getPatchId());
      outputData.put("filesChanged", patch.getFilesChanged() != null ? patch.getFilesChanged() : 0);
      outputData.put("linesChanged", patch.getLinesChanged() != null ? patch.getLinesChanged() : 0);
      outputData.put("confidence", patch.getConfidence());
      outputData.put("rationale", patch.getRationale() != null ? patch.getRationale() : "");

      return NodeExecutionResult.success(
          node.id(),
          String.format("Patch generated: %d files, %d lines",
              patch.getFilesChanged() != null ? patch.getFilesChanged() : 0,
              patch.getLinesChanged() != null ? patch.getLinesChanged() : 0),
          outputData);

    } catch (Exception e) {
      logger.error("Failed to generate patch", e);
      return NodeExecutionResult.failure(node.id(), "Patch generation failed: " + e.getMessage());
    }
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.GENERATE_PATCH;
  }

  private PatchProposal generatePatchFromRecommendation(
      AnalysisV1Response.FixRecommendation recommendation, ExecutionContext context) {

    PatchProposal patch = new PatchProposal();
    patch.setPatchId("patch_" + context.executionId() + "_" + context.attemptNumber());
    patch.setSourceRecommendation(recommendation.getTitle());
    patch.setExpectedImpact(recommendation.getExpectedImpact());
    patch.setRationale(recommendation.getDescription());
    patch.setConfidence(recommendation.getConfidence() != null
        ? recommendation.getConfidence().toString()
        : "MEDIUM");
    patch.setAffectedFiles(recommendation.getAffectedFiles());

    // In a real implementation, call the AI provider to generate the actual diff
    // For now, set a placeholder
    patch.setDiffText("# Generated patch from recommendation: " + recommendation.getTitle() + "\n"
        + "# Affected files: " + String.join(", ", recommendation.getAffectedFiles()) + "\n");

    if (recommendation.getAffectedFiles() != null) {
      patch.setFilesChanged(recommendation.getAffectedFiles().size());
    }

    return patch;
  }
}
