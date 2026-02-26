package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphExecutionContext;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.PatchValidationService;
import com.chromascape.web.debug.patchloop.service.PatchValidationService.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

  public ValidateNodeExecutor(PatchValidationService validationService) {
    this.validationService = validationService;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing VALIDATE node: {}", node.id());

    String executionId = context.executionId();
    int attemptNumber = context.attemptNumber();

    try {
      // Run validation
      ValidationResult result = validationService.validate(executionId, attemptNumber);

      // Store the result
      context.context().setRuntimeValue("validation_" + attemptNumber, result);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("passed", result.passed());
      outputData.put("exitCode", result.exitCode());
      outputData.put("durationMs", result.durationMs());
      outputData.put("outputSummary", result.outputSummary() != null
          ? truncate(result.outputSummary(), 200)
          : "");

      if (result.passed()) {
        return NodeExecutionResult.success(
            node.id(),
            String.format("Validation passed in %d ms", result.durationMs()),
            outputData);
      } else {
        // Validation failed - check if we should retry
        boolean autoRollback = context.context().isAutoRollbackEnabled();

        if (autoRollback) {
          // Trigger rollback
          logger.warn("Validation failed, auto-rollback enabled");
          // TODO: Trigger rollback via PatchApplyService
        }

        return NodeExecutionResult.failure(
            node.id(),
            String.format("Validation failed: exit code %d", result.exitCode()));
      }

    } catch (Exception e) {
      logger.error("Failed to validate patch", e);
      return NodeExecutionResult.failure(node.id(), "Validation failed: " + e.getMessage());
    }
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
