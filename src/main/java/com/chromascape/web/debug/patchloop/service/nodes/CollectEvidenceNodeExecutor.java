package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.EvidenceConfigDto;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEvidenceArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEvidenceArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for COLLECT_EVIDENCE nodes.
 *
 * <p>Collects logs, tick logs, and decisions from the specified time window.
 */
@Component
public class CollectEvidenceNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(CollectEvidenceNodeExecutor.class);

  private final PatchLoopEvidenceService evidenceService;
  private final PatchLoopIterationRepository iterationRepository;
  private final PatchLoopEvidenceArtifactRepository evidenceArtifactRepository;

  public CollectEvidenceNodeExecutor(
      PatchLoopEvidenceService evidenceService,
      PatchLoopIterationRepository iterationRepository,
      PatchLoopEvidenceArtifactRepository evidenceArtifactRepository) {
    this.evidenceService = evidenceService;
    this.iterationRepository = iterationRepository;
    this.evidenceArtifactRepository = evidenceArtifactRepository;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing COLLECT_EVIDENCE node: {}", node.id());

    String executionId = context.executionId();
    String iterationId = context.iterationId();

    try {
      // Get the PatchLoopIteration from the repository
      PatchLoopIteration iteration =
          iterationRepository
              .findByIterationId(iterationId)
              .orElseThrow(
                  () -> new IllegalArgumentException("Iteration not found: " + iterationId));

      // Get evidence config from context or use default
      EvidenceConfigDto config = context.context().getEvidenceConfig();
      if (config == null) {
        config = createDefaultEvidenceConfig();
      }

      // Collect evidence using the actual API
      AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration, config);

      // Check if we have sufficient evidence
      int totalItems =
          (bundle.getLogs() != null ? bundle.getLogs().size() : 0)
              + (bundle.getTickLogs() != null ? bundle.getTickLogs().size() : 0)
              + (bundle.getDecisions() != null ? bundle.getDecisions().size() : 0);

      if (totalItems == 0) {
        logger.warn("No evidence collected for iteration {}", iterationId);
        // Don't fail - just continue with empty evidence
      }

      // Store the evidence in runtime data for later use
      context.context().setRuntimeValue("evidence_" + context.attemptNumber(), bundle);

      // Persist evidence artifact to database
      PatchLoopEvidenceArtifact artifact = new PatchLoopEvidenceArtifact();
      artifact.setExecutionId(executionId);
      artifact.setAttemptNumber(context.attemptNumber());
      artifact.setEvidenceType("COMPREHENSIVE");
      artifact.setCount(totalItems);
      artifact.setSummary(String.format("Collected %d items", totalItems));
      artifact.setTimestamp(LocalDateTime.now());
      evidenceArtifactRepository.save(artifact);

      ObjectNode outputData = JsonNodeFactory.instance.objectNode();
      outputData.put("logCount", bundle.getLogs() != null ? bundle.getLogs().size() : 0);
      outputData.put(
          "tickLogCount", bundle.getTickLogs() != null ? bundle.getTickLogs().size() : 0);
      outputData.put(
          "decisionCount", bundle.getDecisions() != null ? bundle.getDecisions().size() : 0);
      outputData.put("totalItems", totalItems);

      return NodeExecutionResult.success(
          node.id(), String.format("Collected %d evidence items", totalItems), outputData);

    } catch (Exception e) {
      logger.error("Failed to collect evidence", e);
      return NodeExecutionResult.failure(
          node.id(), "Evidence collection failed: " + e.getMessage());
    }
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.COLLECT_EVIDENCE;
  }

  private EvidenceConfigDto createDefaultEvidenceConfig() {
    EvidenceConfigDto config = new EvidenceConfigDto();
    // Set defaults if needed
    return config;
  }
}
