package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.debug.patchloop.constants.AnalysisV1ErrorCode;
import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.validation.AnalysisV1Validator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Test suite for AnalysisV1Validator. */
class AnalysisV1ValidatorTest {

  @Nested
  @DisplayName("Request Validation")
  class RequestValidationTests {

    @Test
    @DisplayName("Valid request passes validation")
    void testValidRequestPasses() {
      // Arrange
      AnalysisV1Request request = createValidRequest();

      // Act & Assert
      assertDoesNotThrow(() -> AnalysisV1Validator.validateRequest(request));
    }

    @Test
    @DisplayName("Null request fails validation")
    void testNullRequestFails() {
      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(null));

      assertEquals(AnalysisV1ErrorCode.INVALID_REQUEST, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("cannot be null"));
    }

    @Test
    @DisplayName("Missing schema version fails validation")
    void testMissingSchemaVersionFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.setSchemaVersion(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("schemaVersion"));
    }

    @Test
    @DisplayName("Empty schema version fails validation")
    void testEmptySchemaVersionFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.setSchemaVersion("");

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("schemaVersion"));
    }

    @Test
    @DisplayName("Unsupported schema version (2.0.0) fails validation")
    void testUnsupportedSchemaVersionFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.setSchemaVersion("2.0.0");

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.INVALID_REQUEST, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Unsupported schema version"));
      assertTrue(ex.getMessage().contains("2.0.0"));
    }

    @Test
    @DisplayName("Schema version 1.1.0 passes validation (forward compatible)")
    void testForwardCompatibleSchemaVersionPasses() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.setSchemaVersion("1.1.0");

      // Act & Assert
      assertDoesNotThrow(() -> AnalysisV1Validator.validateRequest(request));
    }

    @Test
    @DisplayName("Missing prompt fails validation")
    void testMissingPromptFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.setPrompt(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Prompt"));
    }

    @Test
    @DisplayName("Empty prompt fails validation")
    void testEmptyPromptFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.setPrompt("   ");

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Prompt"));
    }

    @Test
    @DisplayName("Missing evidence bundle fails validation")
    void testMissingEvidenceFails() {
      // Arrange
      AnalysisV1Request request = new AnalysisV1Request();
      request.setPrompt("Test prompt");
      request.setEvidence(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Evidence bundle"));
    }

    @Test
    @DisplayName("Missing time window fails validation")
    void testMissingTimeWindowFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.getEvidence().setTimeWindow(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Time window"));
    }

    @Test
    @DisplayName("Invalid time window (end before start) fails validation")
    void testInvalidTimeWindowFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      LocalDateTime now = LocalDateTime.now();
      request.getEvidence().setTimeWindow(new TimeWindow(now, now.minusMinutes(5)));

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.INVALID_TIME_WINDOW, ex.getErrorCode());
    }

    @Test
    @DisplayName("Invalid tick window (startTick > endTick) fails validation")
    void testInvalidTickWindowFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.getEvidence().setTickWindow(new TickWindow(100L, 50L)); // Start > End

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.INVALID_TIME_WINDOW, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Tick window"));
    }

    @Test
    @DisplayName("No evidence (empty logs, tick logs, decisions) fails validation")
    void testInsufficientEvidenceFails() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.getEvidence().setLogs(Collections.emptyList());
      request.getEvidence().setTickLogs(Collections.emptyList());
      request.getEvidence().setDecisions(Collections.emptyList());

      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateRequest(request));

      assertEquals(AnalysisV1ErrorCode.INSUFFICIENT_EVIDENCE, ex.getErrorCode());
    }
  }

  @Nested
  @DisplayName("Response Validation")
  class ResponseValidationTests {

    @Test
    @DisplayName("Valid SUCCESS response passes validation")
    void testValidSuccessResponsePasses() {
      // Arrange
      AnalysisV1Response response = createSuccessResponse();

      // Act & Assert
      assertDoesNotThrow(() -> AnalysisV1Validator.validateResponse(response));
    }

    @Test
    @DisplayName("Valid FAILURE response passes validation")
    void testValidFailureResponsePasses() {
      // Arrange
      AnalysisV1Response response = createFailureResponse();

      // Act & Assert
      assertDoesNotThrow(() -> AnalysisV1Validator.validateResponse(response));
    }

    @Test
    @DisplayName("Null response fails validation")
    void testNullResponseFails() {
      // Act & Assert
      AnalysisException ex =
          assertThrows(AnalysisException.class, () -> AnalysisV1Validator.validateResponse(null));

      assertEquals(AnalysisV1ErrorCode.INVALID_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("Missing schema version in response fails validation")
    void testMissingSchemaVersionInResponseFails() {
      // Arrange
      AnalysisV1Response response = createSuccessResponse();
      response.setSchemaVersion(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("schemaVersion"));
    }

    @Test
    @DisplayName("Unsupported schema version (2.0.0) in response fails validation")
    void testUnsupportedSchemaVersionInResponseFails() {
      // Arrange
      AnalysisV1Response response = createSuccessResponse();
      response.setSchemaVersion("2.0.0");

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.INVALID_REQUEST, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Unsupported schema version"));
      assertTrue(ex.getMessage().contains("2.0.0"));
    }

    @Test
    @DisplayName("Schema version 1.1.0 in response passes validation (forward compatible)")
    void testForwardCompatibleSchemaVersionInResponsePasses() {
      // Arrange
      AnalysisV1Response response = createSuccessResponse();
      response.setSchemaVersion("1.1.0");

      // Act & Assert
      assertDoesNotThrow(() -> AnalysisV1Validator.validateResponse(response));
    }

    @Test
    @DisplayName("Schema version 1.0.0 in response passes validation (base version)")
    void testBaseSchemaVersionInResponsePasses() {
      // Arrange
      AnalysisV1Response response = createSuccessResponse();
      response.setSchemaVersion("1.0.0");

      // Act & Assert
      assertDoesNotThrow(() -> AnalysisV1Validator.validateResponse(response));
    }

    @Test
    @DisplayName("Missing status fails validation")
    void testMissingStatusFails() {
      // Arrange
      AnalysisV1Response response = createSuccessResponse();
      response.setStatus(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("status"));
    }

    @Test
    @DisplayName("SUCCESS status without diagnosis fails validation")
    void testSuccessWithoutDiagnosisFails() {
      // Arrange
      AnalysisV1Response response = new AnalysisV1Response();
      response.setStatus(AnalysisStatus.SUCCESS);
      response.setDiagnosis(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.INVALID_REQUEST, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Diagnosis section is required"));
    }

    @Test
    @DisplayName("SUCCESS with diagnosis but missing summary fails validation")
    void testSuccessWithoutSummaryFails() {
      // Arrange
      AnalysisV1Response response = createSuccessResponse();
      response.getDiagnosis().setSummary(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("summary"));
    }

    @Test
    @DisplayName("SUCCESS with diagnosis but missing confidence fails validation")
    void testSuccessWithoutConfidenceFails() {
      // Arrange
      AnalysisV1Response response = createSuccessResponse();
      response.getDiagnosis().setConfidence(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("confidence"));
    }

    @Test
    @DisplayName("FAILURE status without error fails validation")
    void testFailureWithoutErrorFails() {
      // Arrange
      AnalysisV1Response response = new AnalysisV1Response();
      response.setStatus(AnalysisStatus.FAILURE);
      response.setError(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.INVALID_REQUEST, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Error information is required"));
    }

    @Test
    @DisplayName("FAILURE with error but missing code fails validation")
    void testFailureWithoutErrorCodeFails() {
      // Arrange
      AnalysisV1Response response = createFailureResponse();
      response.getError().setCode(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Error code"));
    }

    @Test
    @DisplayName("FAILURE with error but missing message fails validation")
    void testFailureWithoutErrorMessageFails() {
      // Arrange
      AnalysisV1Response response = createFailureResponse();
      response.getError().setMessage(null);

      // Act & Assert
      AnalysisException ex =
          assertThrows(
              AnalysisException.class, () -> AnalysisV1Validator.validateResponse(response));

      assertEquals(AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, ex.getErrorCode());
      assertTrue(ex.getMessage().contains("Error message"));
    }
  }

  @Nested
  @DisplayName("Evidence Sufficiency")
  class EvidenceSufficiencyTests {

    @Test
    @DisplayName("Request with logs has minimum evidence")
    void testWithLogsHasMinimumEvidence() {
      // Arrange
      AnalysisV1Request request = createValidRequest();

      // Act & Assert
      assertTrue(AnalysisV1Validator.hasMinimumEvidence(request));
    }

    @Test
    @DisplayName("Request with no evidence lacks minimum evidence")
    void testWithNoEvidenceLacksMinimum() {
      // Arrange
      AnalysisV1Request request = createValidRequest();
      request.getEvidence().setLogs(Collections.emptyList());
      request.getEvidence().setTickLogs(Collections.emptyList());
      request.getEvidence().setDecisions(Collections.emptyList());

      // Act & Assert
      assertFalse(AnalysisV1Validator.hasMinimumEvidence(request));
    }

    @Test
    @DisplayName("Null request lacks minimum evidence")
    void testNullRequestLacksMinimum() {
      // Act & Assert
      assertFalse(AnalysisV1Validator.hasMinimumEvidence(null));
    }

    @Test
    @DisplayName("Request with null evidence lacks minimum evidence")
    void testNullEvidenceLacksMinimum() {
      // Arrange
      AnalysisV1Request request = new AnalysisV1Request();
      request.setEvidence(null);

      // Act & Assert
      assertFalse(AnalysisV1Validator.hasMinimumEvidence(request));
    }
  }

  // ========== Helper Methods ==========

  private AnalysisV1Request createValidRequest() {
    AnalysisV1Request request = new AnalysisV1Request();
    request.setPrompt("Why did the bot fail?");

    AnalysisV1EvidenceBundle evidence = new AnalysisV1EvidenceBundle();
    LocalDateTime now = LocalDateTime.now();
    evidence.setTimeWindow(new TimeWindow(now.minusMinutes(5), now));

    // Add at least one piece of evidence
    evidence.setLogs(new ArrayList<>());
    evidence
        .getLogs()
        .add(
            new AnalysisV1EvidenceBundle.LogEvidence(
                now, "ERROR", "Something went wrong", "TestScript"));

    request.setEvidence(evidence);
    return request;
  }

  private AnalysisV1Response createSuccessResponse() {
    AnalysisV1Response response = new AnalysisV1Response();
    response.setAnalysisId("test-analysis-123");
    response.setStatus(AnalysisStatus.SUCCESS);

    AnalysisV1Response.DiagnosisSection diagnosis = new AnalysisV1Response.DiagnosisSection();
    diagnosis.setSummary("The bot failed due to X");
    diagnosis.setRootCause("Root cause is Y");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);

    response.setDiagnosis(diagnosis);
    return response;
  }

  private AnalysisV1Response createFailureResponse() {
    AnalysisV1Response response = new AnalysisV1Response();
    response.setAnalysisId("test-analysis-456");
    response.setStatus(AnalysisStatus.FAILURE);

    AnalysisV1Error error = new AnalysisV1Error();
    error.setCode(AnalysisV1ErrorCode.INSUFFICIENT_EVIDENCE);
    error.setMessage("Not enough evidence to analyze");

    response.setError(error);
    return response;
  }
}
