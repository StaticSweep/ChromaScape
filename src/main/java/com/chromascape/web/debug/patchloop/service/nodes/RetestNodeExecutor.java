package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopRetestArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopRetestArtifactRepository;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.RetestOrchestrator;
import com.chromascape.web.debug.patchloop.service.RetestOrchestrator.RetestResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for RETEST nodes.
 *
 * <p>Runs the script again and compares results to the baseline.
 */
@Component
public class RetestNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(RetestNodeExecutor.class);

  private final RetestOrchestrator retestOrchestrator;
  private final PatchLoopRetestArtifactRepository retestArtifactRepository;

  public RetestNodeExecutor(
      RetestOrchestrator retestOrchestrator,
      PatchLoopRetestArtifactRepository retestArtifactRepository) {
    this.retestOrchestrator = retestOrchestrator;
    this.retestArtifactRepository = retestArtifactRepository;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing RETEST node: {}", node.id());

    String executionId = context.executionId();
    String iterationId = context.iterationId();
    int attemptNumber = context.attemptNumber();

    try {
      // Get baseline data from the first attempt
      Object baselineData = context.context().getRuntimeValue("baseline_evidence");
      int baselineAttempt = 1;

      // Run retest
      RetestResult result =
          retestOrchestrator.retest(
              iterationId,
              context.context().getScriptName(),
              baselineAttempt,
              attemptNumber,
              baselineData);

      // Store the result
      context.context().setRuntimeValue("retest_" + attemptNumber, result);

      // Persist retest artifact to database
      PatchLoopRetestArtifact artifact = new PatchLoopRetestArtifact();
      artifact.setExecutionId(executionId);
      artifact.setAttemptNumber(attemptNumber);
      artifact.setBaselineAttemptNumber(baselineAttempt);
      artifact.setSuccess(result.success());
      artifact.setComparisonSummary(result.comparisonSummary());
      artifact.setImprovements(String.valueOf(result.improvements()));
      artifact.setRegressions(String.valueOf(result.regressions()));
      artifact.setTimestamp(LocalDateTime.now());
      retestArtifactRepository.save(artifact);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("success", result.success());
      outputData.put("improvements", result.improvements());
      outputData.put("regressions", result.regressions());
      outputData.put("comparisonSummary", result.comparisonSummary());

      if (result.success()) {
        return NodeExecutionResult.success(
            node.id(),
            String.format("Retest successful: %s", result.comparisonSummary()),
            outputData);
      } else {
        return NodeExecutionResult.failure(
            node.id(), String.format("Retest failed: %s", result.comparisonSummary()));
      }

    } catch (Exception e) {
      logger.error("Failed to run retest", e);
      return NodeExecutionResult.failure(node.id(), "Retest failed: " + e.getMessage());
    }
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.RETEST;
  }
}
