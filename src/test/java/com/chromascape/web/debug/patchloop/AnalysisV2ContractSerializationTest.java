package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.debug.patchloop.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Serialization/deserialization tests for V2 contract DTOs.
 *
 * <p>Verifies that V2 request/response objects can be serialized to JSON and deserialized back
 * without data loss.
 */
class AnalysisV2ContractSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void testV2RequestSerialization() throws Exception {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);

    AnalysisV2Target target = new AnalysisV2Target("TestScript");
    target.setRunId("run-123");
    AnalysisV2Target.SessionBounds bounds =
        new AnalysisV2Target.SessionBounds(
            LocalDateTime.of(2026, 2, 28, 10, 30), LocalDateTime.of(2026, 2, 28, 10, 35));
    target.setSessionBounds(bounds);
    request.setTarget(target);

    request.setPrompt("Why did the script fail?");
    request.setContext("Bot ran for 5 minutes then stopped");

    AnalysisV2EvidenceSelector logSelector =
        new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS);
    logSelector.setMaxCount(50);
    request.setEvidenceSelectors(List.of(logSelector));

    // Serialize
    String json = objectMapper.writeValueAsString(request);

    // Verify schema version
    assertTrue(json.contains("\"schemaVersion\":\"2.0.0\""));

    // Deserialize
    AnalysisV2Request deserialized = objectMapper.readValue(json, AnalysisV2Request.class);

    assertEquals(AnalysisV2Request.SCHEMA_VERSION, deserialized.getSchemaVersion());
    assertEquals(PromptType.ISSUE, deserialized.getPromptType());
    assertEquals("TestScript", deserialized.getTarget().getScriptName());
    assertEquals("run-123", deserialized.getTarget().getRunId());
    assertEquals("Why did the script fail?", deserialized.getPrompt());
    assertEquals(1, deserialized.getEvidenceSelectors().size());
    assertEquals(EvidenceType.GENERAL_LOGS, deserialized.getEvidenceSelectors().get(0).getType());
  }

  @Test
  void testV2ResponseSerialization() throws Exception {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setAnalysisId("test-analysis-123");
    response.setStatus(AnalysisStatus.SUCCESS);

    // Set diagnosis
    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary("Test summary");
    diagnosis.setRootCause("Test root cause");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);
    response.setDiagnosis(diagnosis);

    // Set V2 required fields
    response.setImpactedModules(List.of("module1", "module2"));

    AnalysisV2PatchDraft patchDraft = new AnalysisV2PatchDraft();
    patchDraft.setPatchId("patch-123");
    patchDraft.setDescription("Test patch");
    patchDraft.setAffectedFiles(List.of("TestFile.java"));

    AnalysisV2PatchDraft.PatchChange change =
        new AnalysisV2PatchDraft.PatchChange("TestFile.java", "old code", "new code");
    change.setLineNumber(42);
    change.setChangeType(AnalysisV2PatchDraft.ChangeType.MODIFY);
    patchDraft.setChanges(List.of(change));
    patchDraft.setConfidence(ConfidenceLevel.HIGH);
    patchDraft.setRationale("Test rationale");
    response.setPatchDraft(patchDraft);

    AnalysisV2RetestPlan retestPlan = new AnalysisV2RetestPlan();
    AnalysisV2RetestPlan.TestStep step = new AnalysisV2RetestPlan.TestStep();
    step.setStepNumber(1);
    step.setDescription("Run script");
    step.setExpectedResult("Should succeed");
    step.setActionType(AnalysisV2RetestPlan.ActionType.RUN_SCRIPT);
    retestPlan.setTestSteps(List.of(step));
    retestPlan.setSuccessCriteria(List.of("No errors"));
    retestPlan.setRollbackPlan("Revert changes");
    retestPlan.setEstimatedDuration("5 minutes");
    response.setRetestPlan(retestPlan);

    response.setEvidenceReferences(List.of("ref1", "ref2"));

    // Serialize
    String json = objectMapper.writeValueAsString(response);

    // Verify schema version and required fields
    assertTrue(json.contains("\"schemaVersion\":\"2.0.0\""));
    assertTrue(json.contains("\"patchDraft\""));
    assertTrue(json.contains("\"retestPlan\""));
    assertTrue(json.contains("\"impactedModules\""));
    assertTrue(json.contains("\"evidenceReferences\""));

    // Deserialize
    AnalysisV2Response deserialized = objectMapper.readValue(json, AnalysisV2Response.class);

    assertEquals(AnalysisV2Response.SCHEMA_VERSION, deserialized.getSchemaVersion());
    assertEquals("test-analysis-123", deserialized.getAnalysisId());
    assertEquals(AnalysisStatus.SUCCESS, deserialized.getStatus());

    assertNotNull(deserialized.getDiagnosis());
    assertEquals("Test summary", deserialized.getDiagnosis().getSummary());
    assertEquals("Test root cause", deserialized.getDiagnosis().getRootCause());
    assertEquals(ConfidenceLevel.HIGH, deserialized.getDiagnosis().getConfidence());

    assertEquals(2, deserialized.getImpactedModules().size());
    assertEquals("module1", deserialized.getImpactedModules().get(0));

    assertNotNull(deserialized.getPatchDraft());
    assertEquals("patch-123", deserialized.getPatchDraft().getPatchId());
    assertEquals(1, deserialized.getPatchDraft().getChanges().size());
    assertEquals("old code", deserialized.getPatchDraft().getChanges().get(0).getOldText());
    assertEquals("new code", deserialized.getPatchDraft().getChanges().get(0).getNewText());

    assertNotNull(deserialized.getRetestPlan());
    assertEquals(1, deserialized.getRetestPlan().getTestSteps().size());
    assertEquals("Run script", deserialized.getRetestPlan().getTestSteps().get(0).getDescription());

    assertEquals(2, deserialized.getEvidenceReferences().size());
  }

  @Test
  void testV2PatchDraftChangeTypes() throws Exception {
    AnalysisV2PatchDraft.PatchChange modifyChange =
        new AnalysisV2PatchDraft.PatchChange("File.java", "old", "new");
    modifyChange.setLineNumber(10);
    modifyChange.setChangeType(AnalysisV2PatchDraft.ChangeType.MODIFY);

    AnalysisV2PatchDraft.PatchChange insertChange =
        new AnalysisV2PatchDraft.PatchChange("File.java", null, "new code");
    insertChange.setChangeType(AnalysisV2PatchDraft.ChangeType.INSERT);

    AnalysisV2PatchDraft.PatchChange deleteChange =
        new AnalysisV2PatchDraft.PatchChange("File.java", "old code", null);
    deleteChange.setLineNumber(20);
    deleteChange.setChangeType(AnalysisV2PatchDraft.ChangeType.DELETE);

    // Serialize each
    String modifyJson = objectMapper.writeValueAsString(modifyChange);
    String insertJson = objectMapper.writeValueAsString(insertChange);
    String deleteJson = objectMapper.writeValueAsString(deleteChange);

    assertTrue(modifyJson.contains("\"changeType\":\"MODIFY\""));
    assertTrue(insertJson.contains("\"changeType\":\"INSERT\""));
    assertTrue(deleteJson.contains("\"changeType\":\"DELETE\""));

    // Deserialize
    AnalysisV2PatchDraft.PatchChange deserializedModify =
        objectMapper.readValue(modifyJson, AnalysisV2PatchDraft.PatchChange.class);
    assertEquals(AnalysisV2PatchDraft.ChangeType.MODIFY, deserializedModify.getChangeType());
    assertEquals(10, deserializedModify.getLineNumber());
  }

  @Test
  void testV2ResponseWithRecommendations() throws Exception {
    // V2 preserves V1 recommendations for backward compatibility
    AnalysisV2Response response = new AnalysisV2Response();
    response.setAnalysisId("test-rec-123");
    response.setStatus(AnalysisStatus.SUCCESS);

    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary("Test summary");
    diagnosis.setRootCause("Test root cause");
    diagnosis.setConfidence(ConfidenceLevel.MEDIUM);

    AnalysisV2Diagnosis.FixRecommendation recommendation =
        new AnalysisV2Diagnosis.FixRecommendation();
    recommendation.setPriority(1);
    recommendation.setTitle("Fix recommendation");
    recommendation.setDescription("Fix description");
    recommendation.setConfidence(ConfidenceLevel.HIGH);
    recommendation.setAffectedFiles(List.of("File.java"));
    recommendation.setExpectedImpact("Should fix the issue");
    diagnosis.setRecommendations(List.of(recommendation));
    response.setDiagnosis(diagnosis);

    // Serialize and deserialize
    String json = objectMapper.writeValueAsString(response);
    AnalysisV2Response deserialized = objectMapper.readValue(json, AnalysisV2Response.class);

    assertEquals(1, deserialized.getDiagnosis().getRecommendations().size());
    assertEquals(
        "Fix recommendation", deserialized.getDiagnosis().getRecommendations().get(0).getTitle());
  }

  @Test
  void testV2FailureResponse() throws Exception {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setAnalysisId("test-fail-123");
    response.setStatus(AnalysisStatus.FAILURE);

    AnalysisV1Error error = new AnalysisV1Error("API_TIMEOUT", "Request timed out");
    response.setError(error);

    // Serialize and deserialize
    String json = objectMapper.writeValueAsString(response);
    AnalysisV2Response deserialized = objectMapper.readValue(json, AnalysisV2Response.class);

    assertEquals(AnalysisStatus.FAILURE, deserialized.getStatus());
    assertNotNull(deserialized.getError());
    assertEquals("API_TIMEOUT", deserialized.getError().getCode());
    assertEquals("Request timed out", deserialized.getError().getMessage());
  }
}
