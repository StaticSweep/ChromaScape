package com.chromascape.web.debug.patchloop.validation;

import com.chromascape.web.debug.patchloop.constants.AnalysisV2ErrorCode;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Diagnosis;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;

/**
 * Validator for Analysis V2 contract DTOs.
 *
 * <p>Validates both requests and responses according to the analysis.v2 specification. Extends V1
 * validation with strict checks for new V2 required fields.
 *
 * @since 2.0.0
 */
public class AnalysisV2Validator {

  /**
   * Validates an analysis request.
   *
   * @param request the request to validate
   * @throws AnalysisException if validation fails
   */
  public static void validateRequest(AnalysisV2Request request) throws AnalysisException {
    if (request == null) {
      throw new AnalysisException(AnalysisV2ErrorCode.INVALID_REQUEST, "Request cannot be null");
    }

    // Validate schema version
    if (request.getSchemaVersion() == null || request.getSchemaVersion().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "schemaVersion is required");
    }

    // Validate schema version compatibility (must be 2.x.x)
    if (!request.getSchemaVersion().startsWith("2.")) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.INVALID_SCHEMA_VERSION,
          "Unsupported schema version: "
              + request.getSchemaVersion()
              + ". This implementation only supports version 2.x.x");
    }

    // Validate promptType (V2 required)
    if (request.getPromptType() == null) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "promptType is required");
    }

    // Validate target (V2 required)
    if (request.getTarget() == null) {
      throw new AnalysisException(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "target is required");
    }

    if (request.getTarget().getScriptName() == null
        || request.getTarget().getScriptName().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "target.scriptName is required");
    }

    // Validate session bounds if present
    if (request.getTarget().getSessionBounds() != null
        && !request.getTarget().getSessionBounds().isValid()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.INVALID_TIME_WINDOW,
          "Session bounds are invalid (start must be before end, both non-null)");
    }

    // Validate prompt
    if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "Prompt is required and cannot be empty");
    }

    // Validate evidenceSelectors (V2 required)
    if (request.getEvidenceSelectors() == null || request.getEvidenceSelectors().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD,
          "evidenceSelectors is required and must contain at least one selector");
    }

    // Validate each evidence selector
    for (int i = 0; i < request.getEvidenceSelectors().size(); i++) {
      var selector = request.getEvidenceSelectors().get(i);
      if (selector.getType() == null) {
        throw new AnalysisException(
            AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD,
            "evidenceSelectors[" + i + "].type is required");
      }

      // Validate time window if present
      if (selector.getTimeWindow() != null && !selector.getTimeWindow().isValid()) {
        throw new AnalysisException(
            AnalysisV2ErrorCode.INVALID_TIME_WINDOW,
            "evidenceSelectors[" + i + "].timeWindow is invalid");
      }

      // Validate tick window if present
      if (selector.getTickWindow() != null && !selector.getTickWindow().isValid()) {
        throw new AnalysisException(
            AnalysisV2ErrorCode.INVALID_TIME_WINDOW,
            "evidenceSelectors[" + i + "].tickWindow is invalid");
      }
    }
  }

  /**
   * Validates an analysis response.
   *
   * @param response the response to validate
   * @throws AnalysisException if validation fails
   */
  public static void validateResponse(AnalysisV2Response response) throws AnalysisException {
    if (response == null) {
      throw new AnalysisException(AnalysisV2ErrorCode.INVALID_REQUEST, "Response cannot be null");
    }

    // Validate schema version
    if (response.getSchemaVersion() == null || response.getSchemaVersion().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "schemaVersion is required in response");
    }

    // Validate schema version compatibility (must be 2.x.x)
    if (!response.getSchemaVersion().startsWith("2.")) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.INVALID_SCHEMA_VERSION,
          "Unsupported schema version in response: "
              + response.getSchemaVersion()
              + ". This implementation only supports version 2.x.x");
    }

    // Validate status
    if (response.getStatus() == null) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "Response status is required");
    }

    // Validate based on status
    switch (response.getStatus()) {
      case SUCCESS:
        validateSuccessResponse(response);
        break;

      case PARTIAL_SUCCESS:
        validatePartialSuccessResponse(response);
        break;

      case FAILURE:
        validateFailureResponse(response);
        break;
    }
  }

  /** Validates a SUCCESS response (all V2 fields required). */
  private static void validateSuccessResponse(AnalysisV2Response response)
      throws AnalysisException {
    // Diagnosis is required
    if (response.getDiagnosis() == null) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS,
          "diagnosis is required when status is SUCCESS");
    }
    validateDiagnosis(response.getDiagnosis());

    // Impacted modules is required (V2)
    if (response.getImpactedModules() == null || response.getImpactedModules().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS,
          "impactedModules is required and must be non-empty when status is SUCCESS");
    }

    // Patch draft is required (V2)
    if (response.getPatchDraft() == null) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS,
          "patchDraft is required when status is SUCCESS");
    }
    validatePatchDraft(response.getPatchDraft());

    // Retest plan is required (V2)
    if (response.getRetestPlan() == null) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS,
          "retestPlan is required when status is SUCCESS");
    }
    validateRetestPlan(response.getRetestPlan());

    // Evidence references is required (V2)
    if (response.getEvidenceReferences() == null || response.getEvidenceReferences().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS,
          "evidenceReferences is required and must be non-empty when status is SUCCESS");
    }
  }

  /** Validates a PARTIAL_SUCCESS response (some fields optional). */
  private static void validatePartialSuccessResponse(AnalysisV2Response response)
      throws AnalysisException {
    // Diagnosis is required
    if (response.getDiagnosis() == null) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS,
          "diagnosis is required when status is PARTIAL_SUCCESS");
    }
    validateDiagnosis(response.getDiagnosis());

    // For PARTIAL_SUCCESS, impactedModules, patchDraft, retestPlan are optional
    // Log warnings if missing but don't fail validation
  }

  /** Validates a FAILURE response (error field required). */
  private static void validateFailureResponse(AnalysisV2Response response)
      throws AnalysisException {
    if (response.getError() == null) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_ERROR_ON_FAILURE,
          "Error information is required when status is FAILURE");
    }

    if (response.getError().getCode() == null || response.getError().getCode().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "Error code is required in error section");
    }

    if (response.getError().getMessage() == null
        || response.getError().getMessage().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "Error message is required in error section");
    }
  }

  /** Validates a diagnosis object. */
  private static void validateDiagnosis(AnalysisV2Diagnosis diagnosis) throws AnalysisException {
    if (diagnosis.getSummary() == null || diagnosis.getSummary().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "Diagnosis summary is required");
    }

    if (diagnosis.getConfidence() == null) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "Diagnosis confidence is required");
    }
  }

  /** Validates a patch draft object. */
  private static void validatePatchDraft(
      com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft patchDraft)
      throws AnalysisException {
    if (patchDraft.getPatchId() == null || patchDraft.getPatchId().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "patchDraft.patchId is required");
    }

    if (patchDraft.getDescription() == null || patchDraft.getDescription().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD, "patchDraft.description is required");
    }

    if (patchDraft.getAffectedFiles() == null || patchDraft.getAffectedFiles().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD,
          "patchDraft.affectedFiles is required and must be non-empty");
    }

    if (patchDraft.getChanges() == null || patchDraft.getChanges().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD,
          "patchDraft.changes is required and must be non-empty");
    }
  }

  /** Validates a retest plan object. */
  private static void validateRetestPlan(
      com.chromascape.web.debug.patchloop.dto.AnalysisV2RetestPlan retestPlan)
      throws AnalysisException {
    if (retestPlan.getTestSteps() == null || retestPlan.getTestSteps().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD,
          "retestPlan.testSteps is required and must be non-empty");
    }

    if (retestPlan.getSuccessCriteria() == null || retestPlan.getSuccessCriteria().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD,
          "retestPlan.successCriteria is required and must be non-empty");
    }
  }

  /**
   * Validates evidence sufficiency for analysis.
   *
   * @param request the request with evidence selectors to validate
   * @return true if evidence selectors are present, false otherwise
   */
  public static boolean hasMinimumEvidence(AnalysisV2Request request) {
    if (request == null || request.getEvidenceSelectors() == null) {
      return false;
    }

    // At least one evidence selector must be present
    return !request.getEvidenceSelectors().isEmpty();
  }

  /**
   * Returns a human-readable error message for an error code.
   *
   * @param errorCode the error code
   * @return human-readable error message
   */
  public static String getErrorMessage(String errorCode) {
    return switch (errorCode) {
      case AnalysisV2ErrorCode.INVALID_SCHEMA_VERSION -> "Invalid schema version (must be 2.x.x)";
      case AnalysisV2ErrorCode
          .MISSING_SUCCESS_FIELDS -> "Missing required fields for SUCCESS response";
      case AnalysisV2ErrorCode
          .MISSING_ERROR_ON_FAILURE -> "Missing error information for FAILURE response";
      case AnalysisV2ErrorCode.INVALID_RESPONSE_SCHEMA -> "Response structure validation failed";
      case AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD -> "Required field is missing";
      case AnalysisV2ErrorCode.INVALID_TIME_WINDOW -> "Time window validation failed";
      case AnalysisV2ErrorCode.INSUFFICIENT_EVIDENCE -> "Insufficient evidence for analysis";
      case AnalysisV2ErrorCode.API_ERROR -> "API error occurred";
      case AnalysisV2ErrorCode.API_TIMEOUT -> "API request timed out";
      case AnalysisV2ErrorCode.API_RATE_LIMIT -> "API rate limit exceeded";
      case AnalysisV2ErrorCode.API_AUTH_FAILED -> "API authentication failed";
      case AnalysisV2ErrorCode.PARSE_ERROR -> "Failed to parse response";
      case AnalysisV2ErrorCode.EVIDENCE_COLLECTION_FAILED -> "Evidence collection failed";
      case AnalysisV2ErrorCode.INTERNAL_ERROR -> "Internal error occurred";
      case AnalysisV2ErrorCode.INVALID_REQUEST -> "Invalid request";
      default -> "Unknown error: " + errorCode;
    };
  }
}
