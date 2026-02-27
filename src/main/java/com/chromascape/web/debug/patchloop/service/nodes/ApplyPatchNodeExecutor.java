package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.PatchProposal;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.entity.PatchApplyStatus;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.PatchApplyService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for APPLY_PATCH nodes.
 *
 * <p>Applies a generated patch to the codebase with safety policy checks.
 */
@Component
public class ApplyPatchNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(ApplyPatchNodeExecutor.class);

  private final PatchApplyService patchApplyService;

  public ApplyPatchNodeExecutor(PatchApplyService patchApplyService) {
    this.patchApplyService = patchApplyService;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing APPLY_PATCH node: {}", node.id());

    String executionId = context.executionId();
    int attemptNumber = context.attemptNumber();

    try {
      // Get the patch from runtime data
      PatchProposal patch =
          (PatchProposal) context.context().getRuntimeValue("patch_" + attemptNumber);

      if (patch == null) {
        return NodeExecutionResult.failure(node.id(), "No patch found to apply");
      }

      // Get the policy config
      GraphExecuteRequest.PatchPolicyConfig policy = context.context().getPolicy();

      // Apply the patch, passing the patchId for consistency
      PatchApplyService.PatchApplyResult result =
          patchApplyService.stageAndApply(
              executionId,
              patch.getDiffText(),
              attemptNumber,
              policy != null ? policy : createDefaultPolicy(),
              patch.getPatchId());

      // Check if application succeeded
      if (result.status() != PatchApplyStatus.APPLIED) {
        return NodeExecutionResult.failure(
            node.id(), "Patch application failed: " + result.errorMessage());
      }

      // Store the result
      context.context().setRuntimeValue("applyResult_" + attemptNumber, result);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("patchId", patch.getPatchId());
      outputData.put("gitBranch", result.gitBranch());
      outputData.put("gitCommit", result.gitCommit());
      outputData.put("filesChanged", result.filesChanged());
      outputData.put("linesChanged", result.linesChanged());

      return NodeExecutionResult.success(
          node.id(),
          String.format(
              "Patch applied: %d files on branch %s", result.filesChanged(), result.gitBranch()),
          outputData);

    } catch (Exception e) {
      logger.error("Failed to apply patch", e);
      return NodeExecutionResult.failure(node.id(), "Patch application failed: " + e.getMessage());
    }
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.APPLY_PATCH;
  }

  private GraphExecuteRequest.PatchPolicyConfig createDefaultPolicy() {
    GraphExecuteRequest.PatchPolicyConfig policy = new GraphExecuteRequest.PatchPolicyConfig();
    policy.setMaxFilesChanged(5);
    policy.setMaxLinesChanged(300);
    policy.setAutoRollbackOnFailure(true);
    return policy;
  }
}
