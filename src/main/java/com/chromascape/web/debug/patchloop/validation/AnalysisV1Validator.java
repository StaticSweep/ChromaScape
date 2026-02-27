package com.chromascape.web.debug.patchloop.validation;

import com.chromascape.web.debug.patchloop.constants.AnalysisV1ErrorCode;
import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;

/**
 * Validator for Analysis V1 contract DTOs.
 *
 * <p>Validates both requests and responses according to the analysis.v1 specification.
 */
public class AnalysisV1Validator {

  /**
   * Validates an analysis request.
   *
   * @param request the request to validate
   * @throws AnalysisException if validation fails
   */
  public static void validateRequest(AnalysisV1Request request) throws AnalysisException {
    if (request == null) {
      throw new AnalysisException(AnalysisV1ErrorCode.INVALID_REQUEST, "Request cannot be null");
    }

    // Validate schema version
    if (request.getSchemaVersion() == null || request.getSchemaVersion().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "schemaVersion is required");
    }

    // Validate schema version compatibility (must be 1.x.x)
    if (!request.getSchemaVersion().startsWith("1.")) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.INVALID_REQUEST,
          "Unsupported schema version: "
              + request.getSchemaVersion()
              + ". This implementation only supports version 1.x.x");
    }

    // Validate prompt
    if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "Prompt is required and cannot be empty");
    }

    // Validate evidence bundle
    if (request.getEvidence() == null) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "Evidence bundle is required");
    }

    // Validate time window
    if (request.getEvidence().getTimeWindow() == null) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "Time window is required in evidence");
    }

    if (!request.getEvidence().getTimeWindow().isValid()) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.INVALID_TIME_WINDOW,
          "Time window is invalid (start must be before end, both non-null)");
    }

    // Validate tick window if present
    if (request.getEvidence().getTickWindow() != null
        && !request.getEvidence().getTickWindow().isValid()) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.INVALID_TIME_WINDOW,
          "Tick window is invalid (startTick must be <= endTick, both non-null)");
    }

    // Validate evidence sufficiency (at least one evidence type)
    if (!request.getEvidence().hasEvidence()) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.INSUFFICIENT_EVIDENCE,
          "Evidence bundle must contain at least one piece of evidence (logs, tick logs, or"
              + " decisions)");
    }
  }

  /**
   * Validates an analysis response.
   *
   * @param response the response to validate
   * @throws AnalysisException if validation fails
   */
  public static void validateResponse(AnalysisV1Response response) throws AnalysisException {
    if (response == null) {
      throw new AnalysisException(AnalysisV1ErrorCode.INVALID_REQUEST, "Response cannot be null");
    }

    // Validate schema version
    if (response.getSchemaVersion() == null || response.getSchemaVersion().trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "schemaVersion is required in response");
    }

    // Validate schema version compatibility (must be 1.x.x)
    if (!response.getSchemaVersion().startsWith("1.")) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.INVALID_REQUEST,
          "Unsupported schema version in response: "
              + response.getSchemaVersion()
              + ". This implementation only supports version 1.x.x");
    }

    // Validate status
    if (response.getStatus() == null) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "Response status is required");
    }

    // Validate diagnosis present if SUCCESS
    if (response.getStatus() == AnalysisStatus.SUCCESS
        || response.getStatus() == AnalysisStatus.PARTIAL_SUCCESS) {
      if (response.getDiagnosis() == null) {
        throw new AnalysisException(
            AnalysisV1ErrorCode.INVALID_REQUEST,
            "Diagnosis section is required when status is SUCCESS or PARTIAL_SUCCESS");
      }

      // Validate diagnosis fields
      if (response.getDiagnosis().getSummary() == null
          || response.getDiagnosis().getSummary().trim().isEmpty()) {
        throw new AnalysisException(
            AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "Diagnosis summary is required");
      }

      if (response.getDiagnosis().getConfidence() == null) {
        throw new AnalysisException(
            AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "Diagnosis confidence is required");
      }
    }

    // Validate error present if FAILURE
    if (response.getStatus() == AnalysisStatus.FAILURE) {
      if (response.getError() == null) {
        throw new AnalysisException(
            AnalysisV1ErrorCode.INVALID_REQUEST,
            "Error information is required when status is FAILURE");
      }

      if (response.getError().getCode() == null || response.getError().getCode().trim().isEmpty()) {
        throw new AnalysisException(
            AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD, "Error code is required in error section");
      }

      if (response.getError().getMessage() == null
          || response.getError().getMessage().trim().isEmpty()) {
        throw new AnalysisException(
            AnalysisV1ErrorCode.MISSING_REQUIRED_FIELD,
            "Error message is required in error section");
      }
    }
  }

  /**
   * Validates evidence sufficiency for analysis.
   *
   * @param request the request with evidence to validate
   * @return true if evidence is sufficient, false otherwise
   */
  public static boolean hasMinimumEvidence(AnalysisV1Request request) {
    if (request == null || request.getEvidence() == null) {
      return false;
    }

    // At least one piece of evidence from any type
    return request.getEvidence().hasEvidence();
  }
}
