package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2Diagnosis;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.dto.PatchProposal;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.entity.PatchApplyStatus;
import com.chromascape.web.debug.patchloop.entity.PatchLoopPatchArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopPatchArtifactRepository;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for GENERATE_PATCH nodes.
 *
 * <p><b>V2 Changes:</b> Uses {@link AnalysisV2PatchDraft} from V2 response as the primary source
 * for patch generation. The patchDraft.changes array provides structured code changes with
 * filePath, oldText, and newText for precise patch application.
 *
 * <p><b>Fallback Behavior:</b> If status is PARTIAL_SUCCESS and patchDraft is incomplete, falls
 * back to deriving from diagnosis.recommendations with explicit warning logged.
 *
 * @since 2.0.0
 */
@Component
public class GeneratePatchNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(GeneratePatchNodeExecutor.class);

  private final PatchLoopPatchArtifactRepository patchArtifactRepository;

  public GeneratePatchNodeExecutor(PatchLoopPatchArtifactRepository patchArtifactRepository) {
    this.patchArtifactRepository = patchArtifactRepository;
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing GENERATE_PATCH node (V2): {}", node.id());

    String executionId = context.executionId();
    int attemptNumber = context.attemptNumber();

    try {
      // Get the V2 analysis response from runtime data
      AnalysisV2Response response =
          (AnalysisV2Response) context.context().getRuntimeValue("analysis_" + attemptNumber);

      if (response == null) {
        return NodeExecutionResult.failure(node.id(), "No V2 analysis response found");
      }

      AnalysisV2PatchDraft patchDraft = response.getPatchDraft();

      // Enforce strict patch source semantics based on status
      com.chromascape.web.debug.patchloop.dto.AnalysisStatus status = response.getStatus();

      // SUCCESS: require patchDraft with changes
      if (status == com.chromascape.web.debug.patchloop.dto.AnalysisStatus.SUCCESS) {
        if (patchDraft == null
            || patchDraft.getChanges() == null
            || patchDraft.getChanges().isEmpty()) {
          return NodeExecutionResult.failure(
              node.id(), "SUCCESS status requires patchDraft with changes");
        }
        // Use patchDraft path
        logger.info(
            "Using patchDraft.changes as primary source ({} changes)",
            patchDraft.getChanges().size());

        PatchProposal patch = generatePatchFromPatchDraft(patchDraft, context);

        // Store the patch for later use
        context.context().setRuntimeValue("patch_" + attemptNumber, patch);

        // Persist patch artifact to database
        persistPatchArtifact(executionId, attemptNumber, patch, patchDraft);

        ObjectNode outputData = JsonNodeFactory.instance.objectNode();
        outputData.put("patchId", patch.getPatchId());
        outputData.put("source", "patchDraft");
        outputData.put(
            "filesChanged", patch.getFilesChanged() != null ? patch.getFilesChanged() : 0);
        outputData.put(
            "linesChanged", patch.getLinesChanged() != null ? patch.getLinesChanged() : 0);
        outputData.put("confidence", patch.getConfidence());
        outputData.put("rationale", patch.getRationale() != null ? patch.getRationale() : "");
        outputData.put("changeCount", patchDraft.getChanges().size());

        return NodeExecutionResult.success(
            node.id(),
            String.format(
                "Patch generated from patchDraft: %d files, %d changes",
                patch.getFilesChanged() != null ? patch.getFilesChanged() : 0,
                patchDraft.getChanges().size()),
            outputData);
      }

      // PARTIAL_SUCCESS or other: derive from recommendations (fallback path)
      AnalysisV2Diagnosis diagnosis = response.getDiagnosis();
      if (diagnosis != null
          && diagnosis.getRecommendations() != null
          && !diagnosis.getRecommendations().isEmpty()) {

        logger.warn(
            "patchDraft not available or incomplete (status: {}). Falling back to recommendations",
            status);

        PatchProposal patch = generatePatchFromRecommendations(diagnosis, context);

        // Store the patch for later use
        context.context().setRuntimeValue("patch_" + attemptNumber, patch);

        // Persist patch artifact to database
        persistPatchArtifact(executionId, attemptNumber, patch, null);

        ObjectNode outputData = JsonNodeFactory.instance.objectNode();
        outputData.put("patchId", patch.getPatchId());
        outputData.put("source", "recommendations (fallback)");
        outputData.put(
            "filesChanged", patch.getFilesChanged() != null ? patch.getFilesChanged() : 0);
        outputData.put(
            "linesChanged", patch.getLinesChanged() != null ? patch.getLinesChanged() : 0);
        outputData.put("confidence", patch.getConfidence());
        outputData.put("rationale", patch.getRationale() != null ? patch.getRationale() : "");

        return NodeExecutionResult.success(
            node.id(),
            String.format(
                "Patch generated from recommendations (fallback): %d files",
                patch.getFilesChanged() != null ? patch.getFilesChanged() : 0),
            outputData);
      }

      return NodeExecutionResult.failure(
          node.id(), "No patchDraft or recommendations available for patch generation");

    } catch (Exception e) {
      logger.error("Failed to generate patch", e);
      return NodeExecutionResult.failure(node.id(), "Patch generation failed: " + e.getMessage());
    }
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.GENERATE_PATCH;
  }

  /** Generates a PatchProposal from the V2 patchDraft (primary source). */
  private PatchProposal generatePatchFromPatchDraft(
      AnalysisV2PatchDraft patchDraft, ExecutionContext context) {

    PatchProposal patch = new PatchProposal();
    patch.setPatchId(patchDraft.getPatchId());
    patch.setExpectedImpact(patchDraft.getDescription());
    patch.setRationale(patchDraft.getRationale());
    patch.setConfidence(
        patchDraft.getConfidence() != null ? patchDraft.getConfidence().toString() : "MEDIUM");
    patch.setAffectedFiles(patchDraft.getAffectedFiles());

    // Build diff text from structured changes
    StringBuilder diffBuilder = new StringBuilder();
    diffBuilder.append("# Generated patch from: ").append(patchDraft.getPatchId()).append("\n");
    diffBuilder.append("# Description: ").append(patchDraft.getDescription()).append("\n");
    diffBuilder
        .append("# Affected files: ")
        .append(String.join(", ", patchDraft.getAffectedFiles()))
        .append("\n\n");

    for (AnalysisV2PatchDraft.PatchChange change : patchDraft.getChanges()) {
      diffBuilder.append("=== ").append(change.getFilePath()).append(" ===\n");
      if (change.getLineNumber() != null) {
        diffBuilder.append("# Line: ").append(change.getLineNumber()).append("\n");
      }
      diffBuilder
          .append("--- oldText ---\n")
          .append(change.getOldText())
          .append("\n\n+++ newText +++\n")
          .append(change.getNewText())
          .append("\n\n");
    }

    patch.setDiffText(diffBuilder.toString());
    patch.setFilesChanged(patchDraft.getAffectedFiles().size());
    patch.setLinesChanged(patchDraft.getChanges().size());

    logger.info(
        "Generated patch from patchDraft: {} changes across {} files",
        patchDraft.getChanges().size(),
        patchDraft.getAffectedFiles().size());

    return patch;
  }

  /** Generates a PatchProposal from diagnosis recommendations (fallback for PARTIAL_SUCCESS). */
  private PatchProposal generatePatchFromRecommendations(
      AnalysisV2Diagnosis diagnosis, ExecutionContext context) {

    // Select highest priority recommendation
    AnalysisV2Diagnosis.FixRecommendation recommendation =
        diagnosis.getRecommendations().stream()
            .min(
                (a, b) -> {
                  int priorityA = a.getPriority() != null ? a.getPriority() : Integer.MAX_VALUE;
                  int priorityB = b.getPriority() != null ? b.getPriority() : Integer.MAX_VALUE;
                  return Integer.compare(priorityA, priorityB);
                })
            .orElse(null);

    if (recommendation == null) {
      // Create empty patch
      PatchProposal patch = new PatchProposal();
      patch.setPatchId("patch_fallback_" + context.executionId() + "_" + context.attemptNumber());
      return patch;
    }

    PatchProposal patch = new PatchProposal();
    patch.setPatchId("patch_fallback_" + context.executionId() + "_" + context.attemptNumber());
    patch.setSourceRecommendation(recommendation.getTitle());
    patch.setExpectedImpact(recommendation.getExpectedImpact());
    patch.setRationale(recommendation.getDescription());
    patch.setConfidence(
        recommendation.getConfidence() != null
            ? recommendation.getConfidence().toString()
            : "MEDIUM");
    patch.setAffectedFiles(recommendation.getAffectedFiles());

    patch.setDiffText(
        "# Generated patch from recommendation (fallback): "
            + recommendation.getTitle()
            + "\n"
            + "# Affected files: "
            + String.join(", ", recommendation.getAffectedFiles())
            + "\n");

    if (recommendation.getAffectedFiles() != null) {
      patch.setFilesChanged(recommendation.getAffectedFiles().size());
    }

    logger.warn(
        "Using fallback patch generation from recommendations: {}", recommendation.getTitle());

    return patch;
  }

  /** Persists a patch artifact to the database. */
  private void persistPatchArtifact(
      String executionId, int attemptNumber, PatchProposal patch, AnalysisV2PatchDraft patchDraft) {

    PatchLoopPatchArtifact artifact = new PatchLoopPatchArtifact();
    artifact.setExecutionId(executionId);
    artifact.setAttemptNumber(attemptNumber);
    artifact.setPatchId(patch.getPatchId());
    artifact.setDiffText(patch.getDiffText());
    artifact.setRationale(patch.getRationale());
    artifact.setFilesChanged(patch.getFilesChanged());
    artifact.setLinesChanged(patch.getLinesChanged());
    artifact.setConfidence(patch.getConfidence());
    artifact.setApplyStatus(PatchApplyStatus.STAGED);
    artifact.setCreatedAt(LocalDateTime.now());

    // Set affected files as JSON
    if (patch.getAffectedFiles() != null && !patch.getAffectedFiles().isEmpty()) {
      ObjectNode affectedFilesJson = JsonNodeFactory.instance.objectNode();
      com.fasterxml.jackson.databind.node.ArrayNode filesArray =
          JsonNodeFactory.instance.arrayNode();
      for (String file : patch.getAffectedFiles()) {
        filesArray.add(file);
      }
      affectedFilesJson.set("files", filesArray);
      artifact.setAffectedFiles(affectedFilesJson);
    }

    // Note: Patch draft metadata (patchId, changes count) is stored in JSONB response
    // and can be retrieved from there for display purposes
    // Additional metadata fields can be added to the entity schema if needed

    patchArtifactRepository.save(artifact);
  }
}
