package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphExecutionContext;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.SuccessEvaluator;
import com.chromascape.web.debug.patchloop.service.SuccessEvaluator.EvaluationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for EVALUATE_SUCCESS nodes.
 *
 * <p>Evaluates whether the loop should stop or continue retrying.
 */
@Component
public class EvaluateSuccessNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(EvaluateSuccessNodeExecutor.class);

  private final SuccessEvaluator successEvaluator;

  public EvaluateSuccessNodeExecutor(SuccessEvaluator successEvaluator) {
    this.successEvaluator = successEvaluator;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing EVALUATE_SUCCESS node: {}", node.id());

    String executionId = context.executionId();
    String iterationId = context.iterationId();
    int attemptNumber = context.attemptNumber();
    int maxAttempts = context.context().getMaxAttempts() != null ? context.context().getMaxAttempts() : 3;

    try {
      // Get the retest result
      Object retestObj = context.context().getRuntimeValue("retest_" + attemptNumber);

      // Evaluate success
      EvaluationResult result =
          successEvaluator.evaluate(
              executionId,
              context.iterationId(),
              attemptNumber,
              maxAttempts,
              context.context().getSuccessCriteria(),
              retestObj);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("success", result.success());
      outputData.put("shouldContinue", result.shouldContinue());
      outputData.put("stopReason", result.stopReason() != null ? result.stopReason() : "");
      outputData.put("attemptsRemaining", result.attemptsRemaining());

      if (result.success()) {
        // Success! Stop the loop
        return NodeExecutionResult.success(
            node.id(),
            String.format("Success criteria met: %s", result.stopReason()),
            outputData);
      } else if (result.shouldContinue()) {
        // Should retry - return failure but with retry flag
        return NodeExecutionResult.failureWithRetry(
            node.id(),
            String.format("Continue: %s (%d attempts remaining)",
                result.stopReason(), result.attemptsRemaining()));
      } else {
        // Should stop (max attempts, policy violation, etc.)
        return NodeExecutionResult.failure(
            node.id(),
            String.format("Stop: %s", result.stopReason()));
      }

    } catch (Exception e) {
      logger.error("Failed to evaluate success", e);
      return NodeExecutionResult.failure(node.id(), "Success evaluation failed: " + e.getMessage());
    }
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.EVALUATE_SUCCESS;
  }
}
