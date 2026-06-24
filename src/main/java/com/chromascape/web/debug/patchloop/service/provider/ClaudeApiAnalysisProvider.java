package com.chromascape.web.debug.patchloop.service.provider;

import com.chromascape.web.debug.patchloop.constants.AnalysisV2ErrorCode;
import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response.AnalysisMetadata;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.validation.AnalysisV2Validator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Claude API provider for V2 analysis.
 *
 * <p><b>V2 Changes:</b>
 *
 * <ul>
 *   <li>Updated to use V2 request/response contracts
 *   <li>Implements parse-hardening pipeline with up to 2 retries on validation failure
 *   <li>Generates V2-specific prompt demanding patchDraft, retestPlan, impactedModules, and
 *       evidenceReferences
 *   <li>Maps V1 evidence bundle to V2 request format for backward compatibility
 * </ul>
 *
 * <p>Activated when {@code analysis.provider=claude-api} or when no provider is specified.
 *
 * @since 2.0.0
 */
@Service
@ConditionalOnProperty(
    name = "analysis.provider",
    havingValue = "claude-api",
    matchIfMissing = true)
public class ClaudeApiAnalysisProvider implements AnalysisProvider {

  private static final Logger logger = LoggerFactory.getLogger(ClaudeApiAnalysisProvider.class);

  /** Maximum retries for parse repair on validation failure. */
  private static final int MAX_PARSE_RETRIES = 2;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String apiKey;
  private final String apiUrl;
  private final String model;
  private final int timeoutSeconds;
  private final int maxTokens;

  public ClaudeApiAnalysisProvider(
      ObjectMapper objectMapper,
      @Value("${claude.api.key:}") String apiKey,
      @Value("${claude.api.url:https://api.anthropic.com/v1/messages}") String apiUrl,
      @Value("${claude.api.model:claude-opus-4-6}") String model,
      @Value("${claude.api.timeout-seconds:120}") int timeoutSeconds,
      @Value("${claude.api.max-tokens:8192}") int maxTokens) {
    this.objectMapper = objectMapper;
    this.apiKey = apiKey;
    this.apiUrl = apiUrl;
    this.model = model;
    this.timeoutSeconds = timeoutSeconds;
    this.maxTokens = maxTokens;

    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }

  @Override
  public AnalysisV2Response analyzeEvidence(AnalysisV2Request request) throws AnalysisException {
    logger.info(
        "[{}] Starting V2 evidence analysis for prompt: {}",
        getProviderName(),
        request.getPrompt());

    long startTime = System.currentTimeMillis();

    // Validate request
    AnalysisV2Validator.validateRequest(request);

    // Build prompts
    String systemPrompt = buildSystemPrompt();
    String userMessage = buildUserMessage(request);

    // Parse-hardening pipeline: retry with repair prompt on validation failure
    for (int attempt = 0; attempt <= MAX_PARSE_RETRIES; attempt++) {
      try {
        // Call Claude API
        String responseText = callClaudeApi(systemPrompt, userMessage, attempt > 0);

        // Parse response
        AnalysisV2Response response = parseAnalysisResponse(responseText, request);

        // Set metadata
        long analysisTime = System.currentTimeMillis() - startTime;
        if (response.getMetadata() == null) {
          response.setMetadata(new AnalysisMetadata());
        }
        response.getMetadata().setModel(model);
        response.getMetadata().setAnalysisTimeMs(analysisTime);
        response
            .getMetadata()
            .setEvidenceCount(
                request.getEvidenceSelectors() != null ? request.getEvidenceSelectors().size() : 0);

        // Validate response
        AnalysisV2Validator.validateResponse(response);

        logger.info("[{}] Analysis completed in {}ms", getProviderName(), analysisTime);
        return response;

      } catch (AnalysisException e) {
        // If validation failed and we have retries left, add repair instruction
        if (e.getErrorCode().equals(AnalysisV2ErrorCode.INVALID_RESPONSE_SCHEMA)
            || e.getErrorCode().equals(AnalysisV2ErrorCode.MISSING_SUCCESS_FIELDS)
            || e.getErrorCode().equals(AnalysisV2ErrorCode.PARSE_ERROR)
            || e.getErrorCode().equals(AnalysisV2ErrorCode.MISSING_REQUIRED_FIELD)
            || e.getErrorCode().equals(AnalysisV2ErrorCode.MISSING_ERROR_ON_FAILURE)) {
          if (attempt < MAX_PARSE_RETRIES) {
            logger.warn(
                "[{}] Validation attempt {} failed: {}. Retrying with repair prompt...",
                getProviderName(),
                attempt,
                e.getMessage());
            userMessage = addRepairInstruction(userMessage, e.getMessage());
            continue;
          }
        }

        // Re-throw if no retries left or different error
        logger.error(
            "[{}] Analysis failed after {} attempts: {} - {}",
            getProviderName(),
            attempt + 1,
            e.getErrorCode(),
            e.getMessage());
        throw e;

      } catch (Exception e) {
        logger.error("[" + getProviderName() + "] Unexpected error during analysis", e);
        // Return deterministic FAILURE response
        return createFailureResponse(
            AnalysisV2ErrorCode.INTERNAL_ERROR, "Unexpected error: " + e.getMessage());
      }
    }

    // Should not reach here, but return FAILURE if exhausted retries
    return createFailureResponse(
        AnalysisV2ErrorCode.PARSE_ERROR,
        "Failed to parse valid response after " + (MAX_PARSE_RETRIES + 1) + " attempts");
  }

  @Override
  public String getProviderName() {
    return "claude-api";
  }

  @Override
  public boolean isAvailable() {
    return apiKey != null && !apiKey.trim().isEmpty();
  }

  /** Builds the V2 system prompt with explicit V2 schema requirements. */
  private String buildSystemPrompt() {
    return """
        You are an expert bot debugging assistant for ChromaScape, a colour-based game automation framework.

        Your task is to analyze bot failure evidence and provide structured diagnosis with a complete patch draft and retest plan.

        You MUST respond with a valid JSON object matching this exact schema:
        {
          "diagnosis": {
            "summary": "1-2 sentence summary of the root cause",
            "rootCause": "Detailed explanation of why the failure occurred",
            "confidence": "HIGH" | "MEDIUM" | "LOW",
            "recommendations": [
              {
                "priority": 1,
                "title": "Short recommendation title",
                "description": "Detailed description of the fix",
                "confidence": "HIGH" | "MEDIUM" | "LOW",
                "affectedFiles": ["file/path1.java", "file/path2.java"],
                "expectedImpact": "What this fix will accomplish"
              }
            ]
          },
          "impactedModules": ["module1", "module2"],
          "patchDraft": {
            "patchId": "unique-patch-id",
            "description": "Human-readable description of the patch",
            "affectedFiles": ["file/path1.java", "file/path2.java"],
            "changes": [
              {
                "filePath": "file/path1.java",
                "oldText": "exact code to replace",
                "newText": "exact new code",
                "lineNumber": 123,
                "changeType": "MODIFY" | "INSERT" | "DELETE" | "REPLACE"
              }
            ],
            "confidence": "HIGH" | "MEDIUM" | "LOW",
            "rationale": "Why this patch will fix the issue"
          },
          "retestPlan": {
            "testSteps": [
              {
                "stepNumber": 1,
                "description": "What to do",
                "expectedResult": "What should happen",
                "actionType": "RUN_SCRIPT" | "VERIFY_BEHAVIOR" | "CHECK_LOGS" | "INSPECT_STATE" | "MANUAL_VERIFY",
                "target": "target entity (optional)"
              }
            ],
            "successCriteria": ["criteria1", "criteria2"],
            "rollbackPlan": "How to rollback if patch fails",
            "estimatedDuration": "5 minutes"
          },
          "evidenceReferences": ["ref1", "ref2"]
        }

        Guidelines:
        - Focus on ROOT CAUSE, not symptoms
        - Provide concrete, copy-pasteable code changes in patchDraft.changes
        - Include at least one PatchChange with exact oldText/newText
        - Reference specific evidence (logs, decisions, tick data) in evidenceReferences
        - Consider colour detection failures, state machine issues, timing problems, and coordinate drift
        - Be concise but thorough
        - Assign confidence levels honestly based on evidence quality

        Common ChromaScape issues:
        - Colour definitions becoming outdated (game graphics change)
        - Viewport not focused on correct area
        - State machine getting stuck in loops
        - Timing issues (bot actions too fast/slow)
        - Mouse coordinate drift over time
        - OCR failures due to font/rendering changes

        CRITICAL: Return ONLY the JSON object. No markdown formatting, no additional text.
        """;
  }

  /** Builds the V2 user message from request. */
  private String buildUserMessage(AnalysisV2Request request) {
    StringBuilder msg = new StringBuilder();

    msg.append("## Script Failure Analysis Request (V2)\n\n");

    // Target info
    msg.append(String.format("**Script:** %s\n", request.getTarget().getScriptName()));
    if (request.getTarget().getRunId() != null) {
      msg.append(String.format("**Run ID:** %s\n", request.getTarget().getRunId()));
    }
    if (request.getTarget().getSessionBounds() != null) {
      msg.append(
          String.format(
              "**Session:** %s to %s (%.1f seconds)\n",
              request.getTarget().getSessionBounds().getStart(),
              request.getTarget().getSessionBounds().getEnd(),
              (double) request.getTarget().getSessionBounds().getDurationSeconds()));
    }

    if (request.getContext() != null && !request.getContext().isEmpty()) {
      msg.append(String.format("\n**Context:** %s\n", request.getContext()));
    }

    msg.append(String.format("\n**Question:** %s\n\n", request.getPrompt()));

    // Evidence selectors summary
    msg.append("## Evidence Selectors\n\n");
    for (var selector : request.getEvidenceSelectors()) {
      msg.append(String.format("- %s (required: %s)", selector.getType(), selector.getRequired()));
      if (selector.getTimeWindow() != null) {
        msg.append(
            String.format(
                " time: %s to %s",
                selector.getTimeWindow().getStart(), selector.getTimeWindow().getEnd()));
      }
      if (selector.getTickWindow() != null) {
        msg.append(
            String.format(
                " ticks: %d to %d",
                selector.getTickWindow().getStartTick(), selector.getTickWindow().getEndTick()));
      }
      msg.append("\n");
    }
    msg.append("\n");

    appendEvidenceBundle(msg, request.getEvidenceBundle());

    msg.append(
        "Please analyze this request and provide a complete V2 response in JSON format as specified.");

    return msg.toString();
  }

  /** Adds repair instruction to the user message after validation failure. */
  private String addRepairInstruction(String originalMessage, String errorMessage) {
    return originalMessage
        + "\n\n"
        + "IMPORTANT: Your previous response failed validation. "
        + "Error: "
        + errorMessage
        + "\n\n"
        + "Please fix your response to match the exact schema specified above. "
        + "Ensure all REQUIRED fields for SUCCESS status are present:\n"
        + "- diagnosis (with summary, rootCause, confidence)\n"
        + "- impactedModules (non-empty array)\n"
        + "- patchDraft (with patchId, description, affectedFiles, changes)\n"
        + "- retestPlan (with testSteps, successCriteria)\n"
        + "- evidenceReferences (non-empty array)\n\n"
        + "Return ONLY the valid JSON object.";
  }

  private void appendEvidenceBundle(StringBuilder msg, AnalysisV1EvidenceBundle evidenceBundle) {
    if (evidenceBundle == null || !evidenceBundle.hasEvidence()) {
      return;
    }

    msg.append("## Selected Evidence\n\n");

    if (!evidenceBundle.getLogs().isEmpty()) {
      msg.append("### Logs\n");
      for (var log : evidenceBundle.getLogs()) {
        msg.append(
            String.format(
                "- [%s] %s %s :: %s%n",
                safe(log.getLevel()),
                safe(log.getTimestamp()),
                safe(log.getSource()),
                safe(log.getMessage())));
      }
      msg.append('\n');
    }

    if (!evidenceBundle.getTickLogs().isEmpty()) {
      msg.append("### Tick Logs\n");
      for (var tickLog : evidenceBundle.getTickLogs()) {
        msg.append(
            String.format(
                "- [tick %s] %s %s/%s :: %s",
                safe(tickLog.getTick()),
                safe(tickLog.getTimestamp()),
                safe(tickLog.getSubsystem()),
                safe(tickLog.getSeverity()),
                safe(tickLog.getMessage())));
        if (tickLog.getDetails() != null && !tickLog.getDetails().isBlank()) {
          msg.append(String.format(" | details=%s", tickLog.getDetails()));
        }
        msg.append('\n');
      }
      msg.append('\n');
    }

    if (!evidenceBundle.getDecisions().isEmpty()) {
      msg.append("### Decisions\n");
      for (var decision : evidenceBundle.getDecisions()) {
        msg.append(
            String.format(
                "- [%s] %s success=%s failure=%s target=%s context=%s%n",
                safe(decision.getDecisionType()),
                safe(decision.getTimestamp()),
                safe(decision.getSuccess()),
                safe(decision.getFailureCode()),
                safe(decision.getTargetEntity()),
                safe(decision.getContext())));
      }
      msg.append('\n');
    }
  }

  private String safe(Object value) {
    return value == null ? "-" : value.toString();
  }

  /** Calls Claude API and returns the response text. */
  private String callClaudeApi(String systemPrompt, String userMessage, boolean isRetry)
      throws AnalysisException {
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_AUTH_FAILED,
          "Claude API key not configured (set CLAUDE_API_KEY environment variable)");
    }

    try {
      // Build request body
      Map<String, Object> requestBody = new HashMap<>();
      requestBody.put("model", model);
      requestBody.put("max_tokens", maxTokens);
      requestBody.put("system", systemPrompt);
      requestBody.put("messages", List.of(Map.of("role", "user", "content", userMessage)));

      String requestJson = objectMapper.writeValueAsString(requestBody);

      // Build HTTP request
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(apiUrl))
              .header("Content-Type", "application/json")
              .header("x-api-key", apiKey)
              .header("anthropic-version", "2023-06-01")
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestJson))
              .build();

      logger.debug("[{}] Calling Claude API: {} (retry: {})", getProviderName(), apiUrl, isRetry);

      // Send request
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      // Handle response
      int statusCode = response.statusCode();
      String responseBody = response.body();

      if (statusCode == 200) {
        // Parse successful response
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("content");
        if (content.isArray() && content.size() > 0) {
          return content.get(0).path("text").asText();
        } else {
          throw new AnalysisException(
              AnalysisV2ErrorCode.PARSE_ERROR, "Unexpected API response format: " + responseBody);
        }
      } else if (statusCode == 401 || statusCode == 403) {
        throw new AnalysisException(
            AnalysisV2ErrorCode.API_AUTH_FAILED, "API authentication failed: " + responseBody);
      } else if (statusCode == 429) {
        throw new AnalysisException(
            AnalysisV2ErrorCode.API_RATE_LIMIT, "API rate limit exceeded: " + responseBody);
      } else {
        throw new AnalysisException(
            AnalysisV2ErrorCode.API_ERROR, "API error (HTTP " + statusCode + "): " + responseBody);
      }

    } catch (AnalysisException e) {
      throw e;
    } catch (java.net.http.HttpTimeoutException e) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_TIMEOUT,
          "API request timed out after " + timeoutSeconds + " seconds",
          e);
    } catch (InterruptedException e) {
      // Restore interrupt flag for cooperative cancellation
      Thread.currentThread().interrupt();
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_ERROR, "API request interrupted: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_ERROR, "API request failed: " + e.getMessage(), e);
    }
  }

  /** Parses the Claude API response and builds AnalysisV2Response. */
  private AnalysisV2Response parseAnalysisResponse(String responseText, AnalysisV2Request request)
      throws AnalysisException {
    try {
      // Extract JSON from potential markdown code blocks
      String jsonText = extractJson(responseText);

      // Parse JSON from Claude's response
      JsonNode root = objectMapper.readTree(jsonText);

      // Build response
      AnalysisV2Response response = new AnalysisV2Response();
      response.setAnalysisId(UUID.randomUUID().toString());
      response.setTimestamp(LocalDateTime.now());

      // Determine status - check if we have minimum viable diagnosis
      JsonNode diagnosisNode = root.path("diagnosis");
      if (diagnosisNode.isMissingNode() || diagnosisNode.isNull()) {
        // Check error path
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
          response.setStatus(AnalysisStatus.FAILURE);

          // Populate error object properly (V2 contract requirement)
          AnalysisV1Error error = new AnalysisV1Error();
          error.setCode(errorNode.path("code").asText());
          error.setMessage(errorNode.path("message").asText());
          if (errorNode.has("details")) {
            error.setDetails(errorNode.path("details").toString());
          }
          response.setError(error);

          response.setMetadata(new AnalysisMetadata());
          return response;
        } else {
          throw new AnalysisException(
              AnalysisV2ErrorCode.INVALID_RESPONSE_SCHEMA, "Missing required 'diagnosis' field");
        }
      }

      // We have diagnosis - check if we have all SUCCESS requirements
      JsonNode impactedModulesNode = root.path("impactedModules");
      JsonNode patchDraftNode = root.path("patchDraft");
      JsonNode retestPlanNode = root.path("retestPlan");
      JsonNode evidenceRefsNode = root.path("evidenceReferences");

      boolean hasAllSuccessFields =
          !impactedModulesNode.isMissingNode()
              && !impactedModulesNode.isNull()
              && impactedModulesNode.isArray()
              && impactedModulesNode.size() > 0
              && !patchDraftNode.isMissingNode()
              && !patchDraftNode.isNull()
              && !retestPlanNode.isMissingNode()
              && !retestPlanNode.isNull()
              && !evidenceRefsNode.isMissingNode()
              && !evidenceRefsNode.isNull()
              && evidenceRefsNode.isArray()
              && evidenceRefsNode.size() > 0;

      if (hasAllSuccessFields) {
        response.setStatus(AnalysisStatus.SUCCESS);
      } else {
        // Emit PARTIAL_SUCCESS when diagnosis exists but some success fields are missing
        response.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
      }

      // Parse diagnosis (continues for both SUCCESS and PARTIAL_SUCCESS)
      AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
      diagnosis.setSummary(diagnosisNode.path("summary").asText());
      diagnosis.setRootCause(diagnosisNode.path("rootCause").asText());

      String confidenceStr = diagnosisNode.path("confidence").asText();
      try {
        diagnosis.setConfidence(ConfidenceLevel.valueOf(confidenceStr));
      } catch (IllegalArgumentException e) {
        diagnosis.setConfidence(ConfidenceLevel.MEDIUM);
      }

      // Parse recommendations (optional in V2)
      List<AnalysisV2Diagnosis.FixRecommendation> recommendations = new ArrayList<>();
      JsonNode recsNode = diagnosisNode.path("recommendations");
      if (recsNode.isArray()) {
        for (JsonNode recNode : recsNode) {
          AnalysisV2Diagnosis.FixRecommendation rec = new AnalysisV2Diagnosis.FixRecommendation();
          rec.setPriority(recNode.path("priority").asInt(1));
          rec.setTitle(recNode.path("title").asText());
          rec.setDescription(recNode.path("description").asText());

          String recConfStr = recNode.path("confidence").asText();
          try {
            rec.setConfidence(ConfidenceLevel.valueOf(recConfStr));
          } catch (IllegalArgumentException e) {
            rec.setConfidence(ConfidenceLevel.MEDIUM);
          }

          List<String> affectedFiles = new ArrayList<>();
          JsonNode filesNode = recNode.path("affectedFiles");
          if (filesNode.isArray()) {
            filesNode.forEach(f -> affectedFiles.add(f.asText()));
          }
          rec.setAffectedFiles(affectedFiles);

          rec.setExpectedImpact(recNode.path("expectedImpact").asText());
          recommendations.add(rec);
        }
      }
      diagnosis.setRecommendations(recommendations);
      response.setDiagnosis(diagnosis);

      // Parse impactedModules
      List<String> impactedModules = new ArrayList<>();
      JsonNode modulesNode = root.path("impactedModules");
      if (modulesNode.isArray()) {
        modulesNode.forEach(m -> impactedModules.add(m.asText()));
      }
      response.setImpactedModules(impactedModules);

      // Parse patchDraft
      JsonNode patchNode = root.path("patchDraft");
      if (!patchNode.isMissingNode() && !patchNode.isNull()) {
        AnalysisV2PatchDraft patchDraft = new AnalysisV2PatchDraft();
        patchDraft.setPatchId(patchNode.path("patchId").asText());
        patchDraft.setDescription(patchNode.path("description").asText());

        List<String> affectedFiles = new ArrayList<>();
        JsonNode patchFilesNode = patchNode.path("affectedFiles");
        if (patchFilesNode.isArray()) {
          patchFilesNode.forEach(f -> affectedFiles.add(f.asText()));
        }
        patchDraft.setAffectedFiles(affectedFiles);

        List<AnalysisV2PatchDraft.PatchChange> changes = new ArrayList<>();
        JsonNode changesNode = patchNode.path("changes");
        if (changesNode.isArray()) {
          for (JsonNode changeNode : changesNode) {
            AnalysisV2PatchDraft.PatchChange change = new AnalysisV2PatchDraft.PatchChange();
            change.setFilePath(changeNode.path("filePath").asText());
            change.setOldText(changeNode.path("oldText").asText());
            change.setNewText(changeNode.path("newText").asText());
            if (changeNode.has("lineNumber")) {
              change.setLineNumber(changeNode.path("lineNumber").asInt());
            }
            if (changeNode.has("changeType")) {
              try {
                change.setChangeType(
                    AnalysisV2PatchDraft.ChangeType.valueOf(
                        changeNode.path("changeType").asText()));
              } catch (IllegalArgumentException e) {
                change.setChangeType(AnalysisV2PatchDraft.ChangeType.MODIFY);
              }
            }
            changes.add(change);
          }
        }
        patchDraft.setChanges(changes);

        String patchConfStr = patchNode.path("confidence").asText();
        try {
          patchDraft.setConfidence(ConfidenceLevel.valueOf(patchConfStr));
        } catch (IllegalArgumentException e) {
          patchDraft.setConfidence(ConfidenceLevel.MEDIUM);
        }

        patchDraft.setRationale(patchNode.path("rationale").asText());
        response.setPatchDraft(patchDraft);
      }

      // Parse retestPlan
      JsonNode retestNode = root.path("retestPlan");
      if (!retestNode.isMissingNode() && !retestNode.isNull()) {
        AnalysisV2RetestPlan retestPlan = new AnalysisV2RetestPlan();

        List<AnalysisV2RetestPlan.TestStep> testSteps = new ArrayList<>();
        JsonNode stepsNode = retestNode.path("testSteps");
        if (stepsNode.isArray()) {
          for (JsonNode stepNode : stepsNode) {
            AnalysisV2RetestPlan.TestStep step = new AnalysisV2RetestPlan.TestStep();
            step.setStepNumber(stepNode.path("stepNumber").asInt());
            step.setDescription(stepNode.path("description").asText());
            step.setExpectedResult(stepNode.path("expectedResult").asText());
            if (stepNode.has("actionType")) {
              try {
                step.setActionType(
                    AnalysisV2RetestPlan.ActionType.valueOf(stepNode.path("actionType").asText()));
              } catch (IllegalArgumentException e) {
                step.setActionType(AnalysisV2RetestPlan.ActionType.VERIFY_BEHAVIOR);
              }
            }
            step.setTarget(stepNode.path("target").asText());
            testSteps.add(step);
          }
        }
        retestPlan.setTestSteps(testSteps);

        List<String> successCriteria = new ArrayList<>();
        JsonNode criteriaNode = retestNode.path("successCriteria");
        if (criteriaNode.isArray()) {
          criteriaNode.forEach(c -> successCriteria.add(c.asText()));
        }
        retestPlan.setSuccessCriteria(successCriteria);

        retestPlan.setRollbackPlan(retestNode.path("rollbackPlan").asText());
        retestPlan.setEstimatedDuration(retestNode.path("estimatedDuration").asText());
        response.setRetestPlan(retestPlan);
      }

      // Parse evidenceReferences
      List<String> evidenceReferences = new ArrayList<>();
      JsonNode refsNode = root.path("evidenceReferences");
      if (refsNode.isArray()) {
        refsNode.forEach(r -> evidenceReferences.add(r.asText()));
      }
      response.setEvidenceReferences(evidenceReferences);

      return response;

    } catch (JsonProcessingException e) {
      logger.error(
          "[{}] Failed to parse analysis response: {}", getProviderName(), responseText, e);
      throw new AnalysisException(
          AnalysisV2ErrorCode.PARSE_ERROR,
          "Failed to parse Claude response as JSON: " + e.getMessage(),
          e);
    }
  }

  /** Extracts JSON from potential markdown code blocks. */
  private String extractJson(String responseText) {
    // Check if wrapped in markdown code blocks
    if (responseText.trim().startsWith("```")) {
      int start = responseText.indexOf('{');
      int end = responseText.lastIndexOf('}');
      if (start >= 0 && end > start) {
        return responseText.substring(start, end + 1);
      }
    }
    return responseText;
  }

  /** Creates a deterministic FAILURE response. */
  private AnalysisV2Response createFailureResponse(String errorCode, String message) {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setAnalysisId(UUID.randomUUID().toString());
    response.setTimestamp(LocalDateTime.now());
    response.setStatus(AnalysisStatus.FAILURE);

    AnalysisV1Error error = new AnalysisV1Error(errorCode, message);
    response.setError(error);

    AnalysisMetadata metadata = new AnalysisMetadata();
    metadata.setModel(model);
    response.setMetadata(metadata);

    return response;
  }
}
