package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PatchLoopPersistenceService}.
 *
 * <p>Verifies that all transactional persistence methods correctly update iteration state and
 * persist to the repository.
 */
@ExtendWith(MockitoExtension.class)
class PatchLoopPersistenceServiceTest {

  @Mock private PatchLoopIterationRepository iterationRepository;

  @Mock private PatchLoopGraphExecutionRepository graphExecutionRepository;

  @InjectMocks private PatchLoopPersistenceService persistenceService;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  void updateIterationStatus_persistsStatusAndUpdatesTimestamp() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setStatus(PatchLoopStatus.QUEUED);
    LocalDateTime beforeUpdate = LocalDateTime.now();

    // When
    persistenceService.updateIterationStatus(iteration, PatchLoopStatus.RUNNING_SCRIPT);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(PatchLoopStatus.RUNNING_SCRIPT, iteration.getStatus());
    assertNotNull(iteration.getLastStateAt());
    assertTrue(
        iteration.getLastStateAt().isAfter(beforeUpdate)
            || iteration.getLastStateAt().isEqual(beforeUpdate));
  }

  @Test
  void updateIterationStatus_transitionsToTerminalState() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);

    // When
    persistenceService.updateIterationStatus(iteration, PatchLoopStatus.FAILED);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(PatchLoopStatus.FAILED, iteration.getStatus());
    assertTrue(iteration.getStatus().isTerminal());
  }

  @Test
  void markIterationFailed_persistsCodeReasonContextAndTerminalState() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);

    // When
    persistenceService.markIterationFailed(
        iteration,
        PatchLoopFailureCode.ANALYSIS_API_TIMEOUT,
        "Claude API timeout",
        "After 120 seconds");

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(PatchLoopFailureCode.ANALYSIS_API_TIMEOUT, iteration.getFailureCode());
    assertEquals("Claude API timeout", iteration.getFailureReason());
    assertEquals("After 120 seconds", iteration.getFailureContext());
    assertTrue(iteration.getStatus().isTerminal());
    assertEquals(PatchLoopStatus.FAILED, iteration.getStatus());
  }

  @Test
  void markIterationFailed_preservesExistingFailureCode() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);
    iteration.setFailureCode(PatchLoopFailureCode.SCRIPT_RUN_TIMEOUT);

    // When
    persistenceService.markIterationFailed(
        iteration, PatchLoopFailureCode.SCRIPT_RUN_TIMEOUT, "Script timed out", "After 5 minutes");

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(PatchLoopFailureCode.SCRIPT_RUN_TIMEOUT, iteration.getFailureCode());
    assertEquals("Script timed out", iteration.getFailureReason());
  }

  @Test
  void saveIteration_persistsIterationState() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("test-iteration-123");
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);

    // When
    persistenceService.saveIteration(iteration);

    // Then
    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository).save(captor.capture());
    assertEquals("test-iteration-123", captor.getValue().getIterationId());
    assertEquals(PatchLoopStatus.RUNNING_SCRIPT, captor.getValue().getStatus());
  }

  @Test
  void saveEvidenceCounts_persistsCountsAndTimeWindow() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    AnalysisV1EvidenceBundle evidence = createEvidenceWithCounts(5, 10, 3);

    LocalDateTime start = LocalDateTime.now().minusMinutes(10);
    LocalDateTime end = LocalDateTime.now();
    TimeWindow timeWindow = new TimeWindow();
    timeWindow.setStart(start);
    timeWindow.setEnd(end);
    evidence.setTimeWindow(timeWindow);

    // When
    persistenceService.saveEvidenceCounts(iteration, evidence);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(5, iteration.getEvidenceLogCount());
    assertEquals(10, iteration.getEvidenceTickLogCount());
    assertEquals(3, iteration.getEvidenceDecisionCount());
    assertEquals(start, iteration.getEvidenceStartTime());
    assertEquals(end, iteration.getEvidenceEndTime());
  }

  @Test
  void saveEvidenceCounts_handlesNullTimeWindow() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    AnalysisV1EvidenceBundle evidence = createEvidenceWithCounts(2, 4, 1);
    evidence.setTimeWindow(null);

    // When
    persistenceService.saveEvidenceCounts(iteration, evidence);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(2, iteration.getEvidenceLogCount());
    assertEquals(4, iteration.getEvidenceTickLogCount());
    assertEquals(1, iteration.getEvidenceDecisionCount());
    assertNull(iteration.getEvidenceStartTime());
    assertNull(iteration.getEvidenceEndTime());
  }

  @Test
  void saveInsufficientEvidence_setsSpecificFailureCodeNotGeneric() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setStatus(PatchLoopStatus.COLLECTING_EVIDENCE);
    AnalysisV1EvidenceBundle evidence = createEmptyEvidence();

    // When
    persistenceService.saveInsufficientEvidence(iteration, evidence);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(PatchLoopFailureCode.INSUFFICIENT_EVIDENCE, iteration.getFailureCode());
    assertEquals("No evidence found in time window", iteration.getFailureReason());
    assertNotNull(iteration.getFailureContext());
    assertTrue(iteration.getFailureContext().contains("Logs: 0"));
    assertTrue(iteration.getFailureContext().contains("Tick Logs: 0"));
    assertTrue(iteration.getFailureContext().contains("Decisions: 0"));
  }

  @Test
  void saveInsufficientEvidence_includesCountsInContext() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    AnalysisV1EvidenceBundle evidence = createEvidenceWithCounts(1, 2, 0);

    // When
    persistenceService.saveInsufficientEvidence(iteration, evidence);

    // Then
    verify(iterationRepository).save(iteration);
    assertTrue(iteration.getFailureContext().contains("Logs: 1"));
    assertTrue(iteration.getFailureContext().contains("Tick Logs: 2"));
    assertTrue(iteration.getFailureContext().contains("Decisions: 0"));
  }

  @Test
  void saveAnalysisRequest_persistsJsonNode() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    ObjectNode requestJson = objectMapper.createObjectNode();
    requestJson.put("prompt", "Why did the script fail?");
    requestJson.put("context", "Test context");

    // When
    persistenceService.saveAnalysisRequest(iteration, requestJson);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(requestJson, iteration.getAnalysisRequestJson());
    assertNotNull(iteration.getAnalysisRequestJson());
  }

  @Test
  void saveAnalysisResults_persistsResponseAndDiagnosisFields() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();

    AnalysisV1Response response = new AnalysisV1Response();
    response.setAnalysisId("analysis-123");

    AnalysisV1Response.DiagnosisSection diagnosis = new AnalysisV1Response.DiagnosisSection();
    diagnosis.setSummary("Script failed due to network timeout");
    diagnosis.setRootCause("Network instability");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);

    AnalysisV1Response.FixRecommendation rec1 = new AnalysisV1Response.FixRecommendation();
    rec1.setTitle("Retry");
    rec1.setDescription("Add retry logic");

    AnalysisV1Response.FixRecommendation rec2 = new AnalysisV1Response.FixRecommendation();
    rec2.setTitle("Timeout");
    rec2.setDescription("Increase timeout");

    diagnosis.setRecommendations(Arrays.asList(rec1, rec2));
    response.setDiagnosis(diagnosis);

    ObjectNode responseNode = objectMapper.createObjectNode();
    responseNode.put("analysisId", "analysis-123");

    // When
    persistenceService.saveAnalysisResults(iteration, response, responseNode);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals("analysis-123", iteration.getAnalysisId());
    assertEquals(responseNode, iteration.getAnalysisResponseJson());
    assertEquals("Script failed due to network timeout", iteration.getDiagnosisSummary());
    assertEquals("Network instability", iteration.getRootCause());
    assertEquals("HIGH", iteration.getConfidence());
    assertEquals(2, iteration.getRecommendationCount());
  }

  @Test
  void saveAnalysisResults_handlesNullDiagnosis() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();

    AnalysisV1Response response = new AnalysisV1Response();
    response.setAnalysisId("analysis-456");
    response.setDiagnosis(null);

    ObjectNode responseNode = objectMapper.createObjectNode();
    responseNode.put("analysisId", "analysis-456");

    // When
    persistenceService.saveAnalysisResults(iteration, response, responseNode);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals("analysis-456", iteration.getAnalysisId());
    assertEquals(responseNode, iteration.getAnalysisResponseJson());
    assertNull(iteration.getDiagnosisSummary());
    assertNull(iteration.getRootCause());
    assertNull(iteration.getConfidence());
    assertNull(iteration.getRecommendationCount());
  }

  @Test
  void saveAnalysisResults_handlesNullConfidence() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();

    AnalysisV1Response response = new AnalysisV1Response();
    response.setAnalysisId("analysis-789");

    AnalysisV1Response.DiagnosisSection diagnosis = new AnalysisV1Response.DiagnosisSection();
    diagnosis.setSummary("Unknown failure");
    diagnosis.setConfidence(null);
    diagnosis.setRecommendations(Collections.emptyList());
    response.setDiagnosis(diagnosis);

    ObjectNode responseNode = objectMapper.createObjectNode();

    // When
    persistenceService.saveAnalysisResults(iteration, response, responseNode);

    // Then
    verify(iterationRepository).save(iteration);
    assertNull(iteration.getConfidence());
    assertEquals(0, iteration.getRecommendationCount());
  }

  @Test
  void saveAnalysisResults_v2_storesPatchIdSeparately_withoutMutatingDiagnosisSummary() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();
    String originalSummary = "Script failed due to network timeout";
    String patchId = "patch-abc123";

    AnalysisV2Response response = new AnalysisV2Response();
    response.setAnalysisId("analysis-v2-123");

    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary(originalSummary);
    diagnosis.setRootCause("Network instability");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);
    diagnosis.setRecommendations(Collections.emptyList());
    response.setDiagnosis(diagnosis);

    AnalysisV2PatchDraft patchDraft = new AnalysisV2PatchDraft();
    patchDraft.setPatchId(patchId);
    patchDraft.setDescription("Add retry logic");
    patchDraft.setAffectedFiles(Arrays.asList("src/NetworkClient.java"));
    response.setPatchDraft(patchDraft);

    ObjectNode responseNode = objectMapper.createObjectNode();
    responseNode.put("analysisId", "analysis-v2-123");

    // When
    persistenceService.saveAnalysisResults(iteration, response, responseNode);

    // Then
    verify(iterationRepository).save(iteration);
    assertEquals(patchId, iteration.getPatchId(), "patchId should be stored separately");

    // Verify diagnosisSummary is NOT mutated (no "[Patch: ...]" appended)
    assertEquals(
        originalSummary,
        iteration.getDiagnosisSummary(),
        "diagnosisSummary should remain unchanged, not polluted with patch info");

    // Verify other fields
    assertEquals("analysis-v2-123", iteration.getAnalysisId());
    assertEquals("Network instability", iteration.getRootCause());
  }

  @Test
  void saveAnalysisResults_v2_patchIdIsNullWhenPatchDraftMissing() {
    // Given
    PatchLoopIteration iteration = new PatchLoopIteration();

    AnalysisV2Response response = new AnalysisV2Response();
    response.setAnalysisId("analysis-v2-456");

    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary("Script failed");
    diagnosis.setConfidence(ConfidenceLevel.MEDIUM);
    diagnosis.setRecommendations(Collections.emptyList());
    response.setDiagnosis(diagnosis);

    // No patchDraft set
    response.setPatchDraft(null);

    ObjectNode responseNode = objectMapper.createObjectNode();

    // When
    persistenceService.saveAnalysisResults(iteration, response, responseNode);

    // Then
    verify(iterationRepository).save(iteration);
    assertNull(iteration.getPatchId(), "patchId should be null when patchDraft is missing");
    assertEquals("Script failed", iteration.getDiagnosisSummary());
  }

  // ==================== markGraphExecutionFailed Tests ====================

  @Test
  void markGraphExecutionFailed_marksBothExecutionAndIterationAsFailed() {
    // Given
    String iterId = "iter-123";
    PatchLoopGraphExecution graphExecution = new PatchLoopGraphExecution();
    graphExecution.setExecutionId("exec-123");
    graphExecution.setIterationId(iterId);
    graphExecution.setExecutionStatus(GraphExecutionStatus.RUNNING);

    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId(iterId);
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);

    when(graphExecutionRepository.findByExecutionId("exec-123"))
        .thenReturn(Optional.of(graphExecution));
    when(iterationRepository.findByIterationId(iterId)).thenReturn(Optional.of(iteration));
    when(graphExecutionRepository.save(any(PatchLoopGraphExecution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // When
    persistenceService.markGraphExecutionFailed(
        "exec-123", PatchLoopFailureCode.INTERNAL_ERROR, "Async submission failed");

    // Then
    verify(graphExecutionRepository).save(graphExecution);
    assertEquals(GraphExecutionStatus.FAILED, graphExecution.getExecutionStatus());
    assertEquals("FAILED", graphExecution.getFinalStatus());
    assertEquals("Async submission failed", graphExecution.getStopReason());
    assertNotNull(graphExecution.getCompletedAt());

    verify(iterationRepository).save(iteration);
    assertEquals(PatchLoopStatus.FAILED, iteration.getStatus());
    assertEquals(PatchLoopFailureCode.INTERNAL_ERROR, iteration.getFailureCode());
  }

  @Test
  void markGraphExecutionFailed_skipsIfExecutionAlreadyTerminal() {
    // Given
    String iterId = "iter-456";
    PatchLoopGraphExecution graphExecution = new PatchLoopGraphExecution();
    graphExecution.setExecutionId("exec-456");
    graphExecution.setIterationId(iterId);
    graphExecution.setExecutionStatus(GraphExecutionStatus.COMPLETED);
    graphExecution.setFinalStatus("COMPLETED");

    when(graphExecutionRepository.findByExecutionId("exec-456"))
        .thenReturn(Optional.of(graphExecution));
    when(iterationRepository.findByIterationId(iterId)).thenReturn(Optional.empty());

    // When
    persistenceService.markGraphExecutionFailed(
        "exec-456", PatchLoopFailureCode.INTERNAL_ERROR, "Should not apply");

    // Then: graph execution should NOT be overwritten
    assertEquals(GraphExecutionStatus.COMPLETED, graphExecution.getExecutionStatus());
    assertEquals("COMPLETED", graphExecution.getFinalStatus());
  }

  @Test
  void markGraphExecutionFailed_handlesExecutionNotFound() {
    // Given
    when(graphExecutionRepository.findByExecutionId("nonexistent")).thenReturn(Optional.empty());

    // When - should not throw
    persistenceService.markGraphExecutionFailed(
        "nonexistent", PatchLoopFailureCode.INTERNAL_ERROR, "Not found");

    // Then: no saves attempted
    verify(graphExecutionRepository, never()).save(any());
    verify(iterationRepository, never()).save(any());
  }

  @Test
  void markGraphExecutionFailed_handlesNullIterationId() {
    // Given
    PatchLoopGraphExecution graphExecution = new PatchLoopGraphExecution();
    graphExecution.setExecutionId("exec-789");
    graphExecution.setIterationId(null); // no iteration linked
    graphExecution.setExecutionStatus(GraphExecutionStatus.CREATED);

    when(graphExecutionRepository.findByExecutionId("exec-789"))
        .thenReturn(Optional.of(graphExecution));
    when(graphExecutionRepository.save(any(PatchLoopGraphExecution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // When - should not throw
    persistenceService.markGraphExecutionFailed(
        "exec-789", PatchLoopFailureCode.INTERNAL_ERROR, "No iteration");

    // Then: graph marked failed, no iteration save attempted
    assertEquals(GraphExecutionStatus.FAILED, graphExecution.getExecutionStatus());
    verify(iterationRepository, never()).save(any());
  }

  // ==================== Helper Methods ====================

  private AnalysisV1EvidenceBundle createEvidenceWithCounts(
      int logCount, int tickLogCount, int decisionCount) {
    AnalysisV1EvidenceBundle evidence = new AnalysisV1EvidenceBundle();

    // Create mock evidence with specified counts
    evidence.setLogs(Collections.nCopies(logCount, createMockLogEvidence()));
    evidence.setTickLogs(Collections.nCopies(tickLogCount, createMockTickLogEvidence()));
    evidence.setDecisions(Collections.nCopies(decisionCount, createMockDecisionEvidence()));

    return evidence;
  }

  private AnalysisV1EvidenceBundle createEmptyEvidence() {
    return createEvidenceWithCounts(0, 0, 0);
  }

  private AnalysisV1EvidenceBundle.LogEvidence createMockLogEvidence() {
    AnalysisV1EvidenceBundle.LogEvidence item =
        new AnalysisV1EvidenceBundle.LogEvidence(
            LocalDateTime.now(), "INFO", "Mock log entry", "TestScript");
    return item;
  }

  private AnalysisV1EvidenceBundle.TickLogEvidence createMockTickLogEvidence() {
    AnalysisV1EvidenceBundle.TickLogEvidence item =
        new AnalysisV1EvidenceBundle.TickLogEvidence(
            1L, LocalDateTime.now(), "TestSubsystem", "INFO", "Mock tick log", "Test details");
    return item;
  }

  private AnalysisV1EvidenceBundle.DecisionEvidence createMockDecisionEvidence() {
    AnalysisV1EvidenceBundle.DecisionEvidence item =
        new AnalysisV1EvidenceBundle.DecisionEvidence(
            LocalDateTime.now(), "CLICK", true, null, "Button", "Click button");
    return item;
  }
}
