package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopValidationArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopValidationArtifactRepository;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.PatchApplyService;
import com.chromascape.web.debug.patchloop.service.PatchValidationService;
import com.chromascape.web.debug.patchloop.service.PatchValidationService.ValidationResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for VALIDATE nodes.
 *
 * <p>Runs tests and checks to validate a patch.
 */
@Component
public class ValidateNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(ValidateNodeExecutor.class);

  private final PatchValidationService validationService;
  private final PatchLoopValidationArtifactRepository validationArtifactRepository;
  private final PatchApplyService patchApplyService;

  public ValidateNodeExecutor(
      PatchValidationService validationService,
      PatchLoopValidationArtifactRepository validationArtifactRepository,
      PatchApplyService patchApplyService) {
    this.validationService = validationService;
    this.validationArtifactRepository = validationArtifactRepository;
    this.patchApplyService = patchApplyService;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing VALIDATE node: {}", node.id());

    String executionId = context.executionId();
    int attemptNumber = context.attemptNumber();

    try {
      // Get the current patch ID from runtime data early (needed for artifact and rollback)
      String patchId = extractPatchId(context, attemptNumber);

      // Run validation
      ValidationResult result = validationService.validate(executionId, attemptNumber);

      // Store the result
      context.context().setRuntimeValue("validation_" + attemptNumber, result);

      // Persist validation artifact to database
      PatchLoopValidationArtifact artifact = new PatchLoopValidationArtifact();
      artifact.setExecutionId(executionId);
      artifact.setAttemptNumber(attemptNumber);
      artifact.setPatchId(patchId); // Set patch ID from runtime data
      artifact.setPassed(result.passed());
      artifact.setOutput(result.outputSummary());
      artifact.setExitCode(result.exitCode());
      artifact.setDurationMs(result.durationMs());
      artifact.setTimestamp(LocalDateTime.now());
      validationArtifactRepository.save(artifact);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("passed", result.passed());
      outputData.put("exitCode", result.exitCode());
      outputData.put("durationMs", result.durationMs());
      outputData.put(
          "outputSummary",
          result.outputSummary() != null ? truncate(result.outputSummary(), 200) : "");

      if (result.passed()) {
        return NodeExecutionResult.success(
            node.id(),
            String.format("Validation passed in %d ms", result.durationMs()),
            outputData);
      } else {
        // Validation failed - check if we should rollback
        boolean autoRollback = context.context().isAutoRollbackEnabled();

        if (autoRollback && patchId != null) {
          logger.warn("Validation failed, triggering rollback for patch: {}", patchId);
          patchApplyService.rollbackPatch(patchId, "Validation failed with exit code " + result.exitCode());

          // Update the artifact with rollback status (same record)
          artifact.setRolledBack(true);
          artifact.setRollbackReason("Validation failed");
          validationArtifactRepository.save(artifact);
        }

        return NodeExecutionResult.failure(
            node.id(), String.format("Validation failed: exit code %d", result.exitCode()));
      }

    } catch (Exception e) {
      logger.error("Failed to validate patch", e);
      return NodeExecutionResult.failure(node.id(), "Validation failed: " + e.getMessage());
    }
  }

  /**
   * Extracts the patch ID from runtime data for the current attempt.
   */
  private String extractPatchId(ExecutionContext context, int attemptNumber) {
    Object patchObj = context.context().getRuntimeValue("patch_" + attemptNumber);
    if (patchObj == null) {
      return null;
    }

    // Extract patchId from the patch object
    if (patchObj instanceof com.chromascape.web.debug.patchloop.dto.PatchProposal) {
      return ((com.chromascape.web.debug.patchloop.dto.PatchProposal) patchObj).getPatchId();
    } else if (patchObj instanceof String) {
      return (String) patchObj;
    }

    return null;
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.VALIDATE;
  }

  private String truncate(String s, int maxLen) {
    if (s == null) return null;
    return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
  }
}
