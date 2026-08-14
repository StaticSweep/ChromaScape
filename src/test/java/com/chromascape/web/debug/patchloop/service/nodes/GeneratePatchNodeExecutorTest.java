package com.chromascape.web.debug.patchloop.service.nodes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Diagnosis;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.dto.ConfidenceLevel;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopPatchArtifactRepository;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphExecutionContext;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor.ExecutionContext;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Test suite for GeneratePatchNodeExecutor. */
@ExtendWith(MockitoExtension.class)
class GeneratePatchNodeExecutorTest {

  @Mock private PatchLoopPatchArtifactRepository patchArtifactRepository;

  private GeneratePatchNodeExecutor executor;
  private GraphNode node;
  private ExecutionContext context;

  @BeforeEach
  void setUp() {
    executor = new GeneratePatchNodeExecutor(patchArtifactRepository);

    node =
        new GraphNode(
            "generate_patch_1", NodeType.GENERATE_PATCH, "Generate Patch", Collections.emptyMap());

    // Create GraphExecutionContext using builder
    GraphExecutionContext graphContext =
        GraphExecutionContext.builder()
            .executionId("exec-123")
            .iterationId("iter-456")
            .scriptName("TestScript")
            .runtimeData(new java.util.HashMap<>())
            .build();

    // Create ExecutionContext record
    context =
        new ExecutionContext("exec-123", "iter-456", 1, "generate_patch_1", null, graphContext);
  }

  @Test
  @DisplayName("SUCCESS with patchDraft generates patch successfully")
  void shouldGeneratePatchFromPatchDraftOnSuccess() throws Exception {
    // Given: SUCCESS response with patchDraft
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setDiagnosis(createValidDiagnosis());
    response.setPatchDraft(createValidPatchDraft());

    context.context().setRuntimeValue("analysis_1", response);

    // When: generating patch
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should succeed with patchDraft source
    assertTrue(result.success());
    assertEquals("patch-123", result.outputData().get("patchId").asText());
    assertEquals("patchDraft", result.outputData().get("source").asText());
    assertEquals(1, result.outputData().get("filesChanged").asInt());
  }

  @Test
  @DisplayName("SUCCESS without patchDraft returns failure")
  void shouldFailWhenSuccessStatusHasNoPatchDraft() throws Exception {
    // Given: SUCCESS response without patchDraft
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setDiagnosis(createValidDiagnosis());
    response.setPatchDraft(null); // Missing patchDraft

    context.context().setRuntimeValue("analysis_1", response);

    // When: generating patch
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should fail
    assertFalse(result.success());
    assertTrue(result.outputSummary().contains("SUCCESS status requires patchDraft"));
  }

  @Test
  @DisplayName("SUCCESS with empty patchDraft changes returns failure")
  void shouldFailWhenSuccessStatusHasEmptyPatchDraft() throws Exception {
    // Given: SUCCESS response with empty patchDraft
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setDiagnosis(createValidDiagnosis());
    AnalysisV2PatchDraft patchDraft = new AnalysisV2PatchDraft();
    patchDraft.setPatchId("patch-123");
    patchDraft.setChanges(Collections.emptyList()); // Empty changes
    response.setPatchDraft(patchDraft);

    context.context().setRuntimeValue("analysis_1", response);

    // When: generating patch
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should fail
    assertFalse(result.success());
    assertTrue(result.outputSummary().contains("SUCCESS status requires patchDraft with changes"));
  }

  @Test
  @DisplayName("PARTIAL_SUCCESS uses recommendations fallback")
  void shouldUseRecommendationsFallbackOnPartialSuccess() throws Exception {
    // Given: PARTIAL_SUCCESS response without patchDraft but with recommendations
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
    response.setDiagnosis(createValidDiagnosis());
    response.setPatchDraft(null); // No patchDraft for PARTIAL_SUCCESS

    context.context().setRuntimeValue("analysis_1", response);

    // When: generating patch
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should succeed with recommendations source
    assertTrue(result.success());
    assertEquals("recommendations (fallback)", result.outputData().get("source").asText());
  }

  @Test
  @DisplayName("PARTIAL_SUCCESS with no recommendations returns failure")
  void shouldFailWhenPartialSuccessHasNoRecommendations() throws Exception {
    // Given: PARTIAL_SUCCESS response without patchDraft or recommendations
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary("Test diagnosis");
    diagnosis.setRecommendations(Collections.emptyList()); // Empty recommendations
    response.setDiagnosis(diagnosis);
    response.setPatchDraft(null);

    context.context().setRuntimeValue("analysis_1", response);

    // When: generating patch
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should fail
    assertFalse(result.success());
    assertTrue(result.outputSummary().contains("No patchDraft or recommendations available"));
  }

  @Test
  @DisplayName("No analysis response returns failure")
  void shouldFailWhenNoAnalysisResponse() throws Exception {
    // Given: no analysis response in runtime
    // (context has no "analysis_1" value)

    // When: generating patch
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should fail
    assertFalse(result.success());
    assertTrue(result.outputSummary().contains("No V2 analysis response found"));
  }

  // Helper methods

  private AnalysisV2Diagnosis createValidDiagnosis() {
    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary("Null pointer in method X");
    diagnosis.setRootCause("Variable not initialized");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);

    AnalysisV2Diagnosis.FixRecommendation recommendation =
        new AnalysisV2Diagnosis.FixRecommendation();
    recommendation.setTitle("Initialize variable");
    recommendation.setPriority(1);
    recommendation.setExpectedImpact("Fixes NPE");
    recommendation.setConfidence(ConfidenceLevel.HIGH);
    recommendation.setAffectedFiles(Collections.singletonList("src/Example.java"));

    diagnosis.setRecommendations(Collections.singletonList(recommendation));
    return diagnosis;
  }

  private AnalysisV2PatchDraft createValidPatchDraft() {
    AnalysisV2PatchDraft patchDraft = new AnalysisV2PatchDraft();
    patchDraft.setPatchId("patch-123");
    patchDraft.setDescription("Initialize variable X");
    patchDraft.setRationale("Fixes NPE by initializing");
    patchDraft.setConfidence(ConfidenceLevel.HIGH);
    patchDraft.setAffectedFiles(Collections.singletonList("src/Example.java"));

    AnalysisV2PatchDraft.PatchChange change = new AnalysisV2PatchDraft.PatchChange();
    change.setFilePath("src/Example.java");
    change.setLineNumber(42);
    change.setOldText("String x;");
    change.setNewText("String x = \"default\";");

    patchDraft.setChanges(Collections.singletonList(change));
    return patchDraft;
  }
}
