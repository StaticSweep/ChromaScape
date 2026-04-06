package com.chromascape.web.debug.patchloop.service.nodes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Error;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Diagnosis;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.dto.ConfidenceLevel;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopAnalysisArtifactRepository;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphExecutionContext;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor.ExecutionContext;
import com.chromascape.web.debug.patchloop.service.PatchLoopAnalysisClient;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Test suite for AnalyzeNodeExecutor. */
@ExtendWith(MockitoExtension.class)
class AnalyzeNodeExecutorTest {

  @Mock private PatchLoopAnalysisClient analysisClient;
  @Mock private PatchLoopAnalysisArtifactRepository analysisArtifactRepository;

  private AnalyzeNodeExecutor executor;
  private GraphNode node;
  private ExecutionContext context;

  @BeforeEach
  void setUp() {
    executor = new AnalyzeNodeExecutor(analysisClient, analysisArtifactRepository);

    node = new GraphNode("analyze_1", NodeType.ANALYZE, "Analyze", Collections.emptyMap());

    // Create GraphExecutionContext using builder
    GraphExecutionContext graphContext =
        GraphExecutionContext.builder()
            .executionId("exec-123")
            .iterationId("iter-456")
            .scriptName("TestScript")
            .goalPrompt("Fix the null pointer exception")
            .context("Test context")
            .runtimeData(new java.util.HashMap<>())
            .build();

    // Add evidence to runtime data
    AnalysisV1EvidenceBundle evidence = new AnalysisV1EvidenceBundle();
    evidence.setScriptName("TestScript");
    graphContext.setRuntimeValue("evidence_1", evidence);

    // Create ExecutionContext record
    context = new ExecutionContext("exec-123", "iter-456", 1, "analyze_1", null, graphContext);
  }

  @Test
  @DisplayName("SUCCESS status continues flow and stores diagnosis")
  void shouldAllowSuccessAndContinue() throws Exception {
    // Given: response with SUCCESS status
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setAnalysisId("test-analysis");
    response.setSchemaVersion("2.0");
    response.setDiagnosis(createValidDiagnosis());
    response.setPatchDraft(createValidPatchDraft());

    when(analysisClient.analyze(any())).thenReturn(response);

    // When: executing analyze node
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should succeed
    assertTrue(result.success());
    assertEquals("analyze_1", result.nodeId());
    assertEquals("test-analysis", result.outputData().get("analysisId").asText());

    // Verify response was stored in runtime
    assertNotNull(context.context().getRuntimeValue("analysis_1"));
    assertNotNull(context.context().getRuntimeValue("diagnosis_1"));
  }

  @Test
  @DisplayName("PARTIAL_SUCCESS status continues flow and stores diagnosis")
  void shouldAllowPartialSuccessAndContinue() throws Exception {
    // Given: response with PARTIAL_SUCCESS status
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
    response.setAnalysisId("test-analysis-partial");
    response.setSchemaVersion("2.0");
    response.setDiagnosis(createValidDiagnosis());
    // No patchDraft for PARTIAL_SUCCESS

    when(analysisClient.analyze(any())).thenReturn(response);

    // When: executing analyze node
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should succeed (not fail)
    assertTrue(result.success());
    assertEquals("analyze_1", result.nodeId());
    assertEquals("test-analysis-partial", result.outputData().get("analysisId").asText());

    // Verify message indicates PARTIAL_SUCCESS
    assertTrue(result.outputSummary().contains("(PARTIAL)"));
  }

  @Test
  @DisplayName("FAILURE status stops flow immediately")
  void shouldFailOnFailureStatus() throws Exception {
    // Given: response with FAILURE status
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.FAILURE);
    AnalysisV1Error error = new AnalysisV1Error();
    error.setCode("ANALYSIS_FAILED");
    error.setMessage("Analysis provider unavailable");
    response.setError(error);

    when(analysisClient.analyze(any())).thenReturn(response);

    // When: executing analyze node
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should fail
    assertFalse(result.success());
    assertTrue(result.outputSummary().contains("Analysis failed"));
    assertTrue(result.outputSummary().contains("Analysis provider unavailable"));
  }

  @Test
  @DisplayName("Missing diagnosis returns failure even for PARTIAL_SUCCESS")
  void shouldFailWhenDiagnosisMissing() throws Exception {
    // Given: PARTIAL_SUCCESS response without diagnosis
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
    response.setAnalysisId("test-analysis");
    response.setDiagnosis(null); // Missing diagnosis

    when(analysisClient.analyze(any())).thenReturn(response);

    // When: executing analyze node
    NodeExecutionResult result = executor.execute(node, context);

    // Then: should fail
    assertFalse(result.success());
    assertTrue(result.outputSummary().contains("No diagnosis returned"));
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
