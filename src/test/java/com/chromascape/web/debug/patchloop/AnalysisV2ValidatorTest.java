package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.debug.patchloop.constants.AnalysisV2ErrorCode;
import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.validation.AnalysisV2Validator;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation tests for V2 contract DTOs.
 *
 * <p>Verifies strict validation rules for V2 requests and responses.
 */
class AnalysisV2ValidatorTest {

  @Test
  void testValidV2RequestPasses() {
    AnalysisV2Request request = buildValidV2Request();

    assertDoesNotThrow(() -> AnalysisV2Validator.validateRequest(request));
  }

  @Test
  void testNullRequestFails() {
    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(null));
    assertEquals(AnalysisV2ErrorCode.INVALID_REQUEST, ex.getErrorCode());
  }

  @Test
  void testMissingSchemaVersionFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setSchemaVersion(null);
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(new AnalysisV2Target("Test"));
    request.setPrompt("Test prompt");
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("schemaVersion"));
  }

  @Test
  void testInvalidSchemaVersionFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setSchemaVersion("1.0.0"); // V1 version
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(new AnalysisV2Target("Test"));
    request.setPrompt("Test prompt");
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.INVALID_SCHEMA_VERSION, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("2.x.x"));
  }

  @Test
  void testMissingPromptTypeFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(null);
    request.setTarget(new AnalysisV2Target("Test"));
    request.setPrompt("Test prompt");
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("promptType"));
  }

  @Test
  void testMissingTargetFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(null);
    request.setPrompt("Test prompt");
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("target"));
  }

  @Test
  void testMissingTargetScriptNameFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(new AnalysisV2Target()); // No script name
    request.setPrompt("Test prompt");
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("scriptName"));
  }

  @Test
  void testInvalidSessionBoundsFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);
    AnalysisV2Target target = new AnalysisV2Target("Test");
    target.setSessionBounds(
        new AnalysisV2Target.SessionBounds(
            LocalDateTime.of(2026, 2, 28, 10, 35), // End before start
            LocalDateTime.of(2026, 2, 28, 10, 30)));
    request.setTarget(target);
    request.setPrompt("Test prompt");
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.INVALID_TIME_WINDOW, ex.getErrorCode());
  }

  @Test
  void testMissingPromptFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(new AnalysisV2Target("Test"));
    request.setPrompt(null);
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("Prompt"));
  }

  @Test
  void testEmptyPromptFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(new AnalysisV2Target("Test"));
    request.setPrompt("   ");
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
  }

  @Test
  void testMissingEvidenceSelectorsFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(new AnalysisV2Target("Test"));
    request.setPrompt("Test prompt");
    request.setEvidenceSelectors(null);

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("evidenceSelectors"));
  }

  @Test
  void testEmptyEvidenceSelectorsFails() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(new AnalysisV2Target("Test"));
    request.setPrompt("Test prompt");
    request.setEvidenceSelectors(List.of());

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateRequest(request));
    assertEquals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
  }

  @Test
  void testValidSuccessResponsePasses() {
    AnalysisV2Response response = buildValidSuccessV2Response();

    assertDoesNotThrow(() -> AnalysisV2Validator.validateResponse(response));
  }

  @Test
  void testSuccessResponseWithoutDiagnosisFails() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    // No diagnosis set

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateResponse(response));
    assertEquals(AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("diagnosis"));
  }

  @Test
  void testSuccessResponseWithoutImpactedModulesFails() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setDiagnosis(new AnalysisV2Diagnosis("Summary", "Root cause", ConfidenceLevel.HIGH));
    response.setImpactedModules(List.of()); // Empty

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateResponse(response));
    assertEquals(AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("impactedModules"));
  }

  @Test
  void testSuccessResponseWithoutPatchDraftFails() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setDiagnosis(new AnalysisV2Diagnosis("Summary", "Root cause", ConfidenceLevel.HIGH));
    response.setImpactedModules(List.of("module1"));
    // No patchDraft

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateResponse(response));
    assertEquals(AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("patchDraft"));
  }

  @Test
  void testSuccessResponseWithoutRetestPlanFails() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setDiagnosis(new AnalysisV2Diagnosis("Summary", "Root cause", ConfidenceLevel.HIGH));
    response.setImpactedModules(List.of("module1"));
    response.setPatchDraft(new AnalysisV2PatchDraft());
    response.getPatchDraft().setPatchId("patch-1");
    response.getPatchDraft().setDescription("Test");
    response.getPatchDraft().setAffectedFiles(List.of("File.java"));
    AnalysisV2PatchDraft.PatchChange change1 =
        new AnalysisV2PatchDraft.PatchChange("File.java", "old", "new");
    change1.setLineNumber(1);
    response.getPatchDraft().setChanges(List.of(change1));
    // No retestPlan

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateResponse(response));
    assertEquals(AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("retestPlan"));
  }

  @Test
  void testSuccessResponseWithoutEvidenceReferencesFails() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setDiagnosis(new AnalysisV2Diagnosis("Summary", "Root cause", ConfidenceLevel.HIGH));
    response.setImpactedModules(List.of("module1"));
    response.setPatchDraft(new AnalysisV2PatchDraft());
    response.getPatchDraft().setPatchId("patch-1");
    response.getPatchDraft().setDescription("Test");
    response.getPatchDraft().setAffectedFiles(List.of("File.java"));
    AnalysisV2PatchDraft.PatchChange change2 =
        new AnalysisV2PatchDraft.PatchChange("File.java", "old", "new");
    change2.setLineNumber(1);
    response.getPatchDraft().setChanges(List.of(change2));
    response.setRetestPlan(new AnalysisV2RetestPlan());
    AnalysisV2RetestPlan.TestStep step = new AnalysisV2RetestPlan.TestStep();
    step.setStepNumber(1);
    step.setDescription("Step");
    step.setExpectedResult("Result");
    step.setActionType(AnalysisV2RetestPlan.ActionType.RUN_SCRIPT);
    response.getRetestPlan().setTestSteps(List.of(step));
    response.getRetestPlan().setSuccessCriteria(List.of("Criteria"));
    // No evidenceReferences

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateResponse(response));
    assertEquals(AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("evidenceReferences"));
  }

  @Test
  void testFailureResponseWithoutErrorFails() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.FAILURE);
    // No error

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateResponse(response));
    assertEquals(AnalysisV2ErrorCode.MISSING_ERROR_ON_FAILURE, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("Error"));
  }

  @Test
  void testValidFailureResponsePasses() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.FAILURE);
    response.setError(new AnalysisV1Error("API_ERROR", "API call failed"));

    assertDoesNotThrow(() -> AnalysisV2Validator.validateResponse(response));
  }

  @Test
  void testPartialSuccessResponseWithoutDiagnosisFails() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
    // No diagnosis - still required for PARTIAL_SUCCESS

    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> AnalysisV2Validator.validateResponse(response));
    assertEquals(AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS, ex.getErrorCode());
  }

  @Test
  void testValidPartialSuccessResponsePasses() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
    response.setDiagnosis(new AnalysisV2Diagnosis("Summary", "Root cause", ConfidenceLevel.MEDIUM));
    // V2 optional fields (impactedModules, patchDraft, etc.) can be missing

    assertDoesNotThrow(() -> AnalysisV2Validator.validateResponse(response));
  }

  @Test
  void testErrorMessageMapping() {
    assertEquals(
        "Invalid schema version (must be 2.x.x)",
        AnalysisV2Validator.getErrorMessage(AnalysisV2ErrorCode.INVALID_SCHEMA_VERSION));
    assertEquals(
        "Missing required fields for SUCCESS response",
        AnalysisV2Validator.getErrorMessage(AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS));
    assertEquals(
        "Missing error information for FAILURE response",
        AnalysisV2Validator.getErrorMessage(AnalysisV2ErrorCode.MISSING_ERROR_ON_FAILURE));
  }

  // Helper methods

  private AnalysisV2Request buildValidV2Request() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);

    AnalysisV2Target target = new AnalysisV2Target("TestScript");
    target.setRunId("run-123");
    request.setTarget(target);

    request.setPrompt("Test prompt");
    request.setContext("Test context");

    AnalysisV2EvidenceSelector selector = new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS);
    request.setEvidenceSelectors(List.of(selector));

    return request;
  }

  private AnalysisV2Response buildValidSuccessV2Response() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);

    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary("Test summary");
    diagnosis.setRootCause("Test root cause");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);
    response.setDiagnosis(diagnosis);

    response.setImpactedModules(List.of("module1", "module2"));

    AnalysisV2PatchDraft patchDraft = new AnalysisV2PatchDraft();
    patchDraft.setPatchId("patch-1");
    patchDraft.setDescription("Test patch");
    patchDraft.setAffectedFiles(List.of("File.java"));
    AnalysisV2PatchDraft.PatchChange change =
        new AnalysisV2PatchDraft.PatchChange("File.java", "old", "new");
    change.setLineNumber(1);
    patchDraft.setChanges(List.of(change));
    patchDraft.setConfidence(ConfidenceLevel.HIGH);
    patchDraft.setRationale("Test rationale");
    response.setPatchDraft(patchDraft);

    AnalysisV2RetestPlan retestPlan = new AnalysisV2RetestPlan();
    AnalysisV2RetestPlan.TestStep step = new AnalysisV2RetestPlan.TestStep();
    step.setStepNumber(1);
    step.setDescription("Step");
    step.setExpectedResult("Result");
    step.setActionType(AnalysisV2RetestPlan.ActionType.RUN_SCRIPT);
    retestPlan.setTestSteps(List.of(step));
    retestPlan.setSuccessCriteria(List.of("Criteria"));
    retestPlan.setRollbackPlan("Rollback");
    retestPlan.setEstimatedDuration("5 min");
    response.setRetestPlan(retestPlan);

    response.setEvidenceReferences(List.of("ref1", "ref2"));

    return response;
  }
}
