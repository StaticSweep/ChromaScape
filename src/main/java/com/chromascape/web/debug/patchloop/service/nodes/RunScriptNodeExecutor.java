package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.ScriptExecutionService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for RUN_SCRIPT nodes.
 *
 * <p>Starts the script using ScriptExecutionService and waits for completion.
 */
@Component
public class RunScriptNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(RunScriptNodeExecutor.class);
  private static final long DEFAULT_TIMEOUT_MS = 300000; // 5 minutes

  private final ScriptExecutionService scriptExecutionService;

  public RunScriptNodeExecutor(ScriptExecutionService scriptExecutionService) {
    this.scriptExecutionService = scriptExecutionService;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info(
        "Executing RUN_SCRIPT node: {} for script: {}",
        node.id(),
        context.context().getScriptName());

    String scriptName = context.context().getScriptName();
    String executionId = context.executionId();
    String iterationId = context.iterationId();
    int attemptNumber = context.attemptNumber();

    try {
      // Get timeout from node config or use default
      long timeoutMs = getTimeoutFromConfig(node, DEFAULT_TIMEOUT_MS);

      logger.info("Starting script: {} with timeout: {}ms", scriptName, timeoutMs);

      // Run the script and wait for completion
      ScriptExecutionService.ScriptExecutionResult result =
          scriptExecutionService.runScriptAndWait(scriptName, timeoutMs);

      if (result.success()) {
        ObjectNode outputData = JsonNodeFactory.instance.objectNode();
        outputData.put("executionId", executionId);
        outputData.put("iterationId", iterationId);
        outputData.put("attemptNumber", attemptNumber);
        outputData.put("success", true);
        outputData.put("scriptName", scriptName);
        outputData.put("durationMs", result.durationMs());

        return NodeExecutionResult.success(
            node.id(),
            String.format("Script completed successfully in %dms", result.durationMs()),
            outputData);
      } else {
        // Check if it was a timeout
        if (result.errorMessage() != null && result.errorMessage().contains("timed out")) {
          return NodeExecutionResult.timeout(node.id());
        }
        return NodeExecutionResult.failure(node.id(), "Script failed: " + result.errorMessage());
      }

    } catch (Exception e) {
      logger.error("Failed to execute script: {}", scriptName, e);
      return NodeExecutionResult.failure(node.id(), "Script execution error: " + e.getMessage());
    }
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.RUN_SCRIPT;
  }

  private long getTimeoutFromConfig(GraphNode node, long defaultTimeout) {
    if (node.config() != null && node.config().containsKey("timeoutMs")) {
      Object timeoutMs = node.config().get("timeoutMs");
      if (timeoutMs instanceof Number n) {
        return n.longValue();
      }
    }
    return defaultTimeout;
  }
}
