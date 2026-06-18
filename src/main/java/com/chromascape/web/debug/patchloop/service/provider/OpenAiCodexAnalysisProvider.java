package com.chromascape.web.debug.patchloop.service.provider;

import com.chromascape.web.debug.patchloop.constants.AnalysisV2ErrorCode;
import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Error;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Diagnosis;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response.AnalysisMetadata;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2RetestPlan;
import com.chromascape.web.debug.patchloop.dto.ConfidenceLevel;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * OpenAI/Codex API provider for V2 analysis.
 *
 * <p>Activated when {@code analysis.provider=openai-codex}.
 */
@Service
@ConditionalOnProperty(name = "analysis.provider", havingValue = "openai-codex")
public class OpenAiCodexAnalysisProvider implements AnalysisProvider {

  private static final Logger logger = LoggerFactory.getLogger(OpenAiCodexAnalysisProvider.class);
  private static final int MAX_PARSE_RETRIES = 2;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String apiKey;
  private final String apiUrl;
  private final String model;
  private final int timeoutSeconds;
  private final int maxTokens;

  public OpenAiCodexAnalysisProvider(
      ObjectMapper objectMapper,
      @Value("${analysis.openai.api-key:}") String apiKey,
      @Value("${analysis.openai.url:https://api.openai.com/v1/chat/completions}") String apiUrl,
      @Value("${analysis.openai.model:gpt-5-mini}") String model,
      @Value("${analysis.openai.timeout-seconds:120}") int timeoutSeconds,
      @Value("${analysis.openai.max-tokens:8192}") int maxTokens) {
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
    AnalysisV2Validator.validateRequest(request);

    String systemPrompt = buildSystemPrompt();
    String userMessage = buildUserMessage(request);

    for (int attempt = 0; attempt <= MAX_PARSE_RETRIES; attempt++) {
      try {
        String responseText = callOpenAiApi(systemPrompt, userMessage, attempt > 0);
        AnalysisV2Response response = parseAnalysisResponse(responseText);

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

        AnalysisV2Validator.validateResponse(response);
        logger.info("[{}] Analysis completed in {}ms", getProviderName(), analysisTime);
        return response;

      } catch (AnalysisException e) {
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
        logger.error(
            "[{}] Analysis failed after {} attempts: {} - {}",
            getProviderName(),
            attempt + 1,
            e.getErrorCode(),
            e.getMessage());
        throw e;
      } catch (Exception e) {
        logger.error("[{}] Unexpected error during analysis", getProviderName(), e);
        return createFailureResponse(
            AnalysisV2ErrorCode.INTERNAL_ERROR, "Unexpected error: " + e.getMessage());
      }
    }

    return createFailureResponse(
        AnalysisV2ErrorCode.PARSE_ERROR,
        "Failed to parse valid response after " + (MAX_PARSE_RETRIES + 1) + " attempts");
  }

  @Override
  public String getProviderName() {
    return "openai-codex";
  }

  @Override
  public boolean isAvailable() {
    return apiKey != null && !apiKey.trim().isEmpty();
  }

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

        CRITICAL: Return ONLY the JSON object. No markdown formatting, no additional text.
        """;
  }

  private String buildUserMessage(AnalysisV2Request request) {
    StringBuilder msg = new StringBuilder();
    msg.append("## Script Failure Analysis Request (V2)\n\n");
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
    msg.append("\nPlease analyze this request and provide a complete V2 response in JSON format.");
    return msg.toString();
  }

  private String addRepairInstruction(String originalMessage, String errorMessage) {
    return originalMessage
        + "\n\nIMPORTANT: Your previous response failed validation. "
        + "Error: "
        + errorMessage
        + "\n\nPlease fix your response to match the exact schema and return ONLY valid JSON.";
  }

  private String callOpenAiApi(String systemPrompt, String userMessage, boolean isRetry)
      throws AnalysisException {
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_AUTH_FAILED,
          "OpenAI API key not configured (set ANALYSIS_OPENAI_API_KEY environment variable)");
    }

    try {
      Map<String, Object> requestBody = new HashMap<>();
      requestBody.put("model", model);
      requestBody.put("max_tokens", maxTokens);
      requestBody.put(
          "messages",
          List.of(
              Map.of("role", "system", "content", systemPrompt),
              Map.of("role", "user", "content", userMessage)));
      requestBody.put("temperature", 0.1);

      String requestJson = objectMapper.writeValueAsString(requestBody);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(apiUrl))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + apiKey)
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestJson))
              .build();

      logger.debug("[{}] Calling OpenAI API: {} (retry: {})", getProviderName(), apiUrl, isRetry);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int statusCode = response.statusCode();
      String responseBody = response.body();

      if (statusCode == 200) {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
          JsonNode contentNode = choices.get(0).path("message").path("content");
          if (contentNode.isTextual()) {
            return contentNode.asText();
          }
          if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
              String text = part.path("text").asText("");
              if (!text.isBlank()) {
                if (sb.length() > 0) {
                  sb.append('\n');
                }
                sb.append(text);
              }
            }
            if (sb.length() > 0) {
              return sb.toString();
            }
          }
        }
        throw new AnalysisException(
            AnalysisV2ErrorCode.PARSE_ERROR, "Unexpected OpenAI response format: " + responseBody);
      }
      if (statusCode == 401 || statusCode == 403) {
        throw new AnalysisException(
            AnalysisV2ErrorCode.API_AUTH_FAILED, "API authentication failed: " + responseBody);
      }
      if (statusCode == 429) {
        throw new AnalysisException(
            AnalysisV2ErrorCode.API_RATE_LIMIT, "API rate limit exceeded: " + responseBody);
      }
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_ERROR, "API error (HTTP " + statusCode + "): " + responseBody);

    } catch (AnalysisException e) {
      throw e;
    } catch (java.net.http.HttpTimeoutException e) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_TIMEOUT,
          "API request timed out after " + timeoutSeconds + " seconds",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_ERROR, "API request interrupted: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new AnalysisException(
          AnalysisV2ErrorCode.API_ERROR, "API request failed: " + e.getMessage(), e);
    }
  }

  private AnalysisV2Response parseAnalysisResponse(String responseText) throws AnalysisException {
    try {
      String jsonText = extractJson(responseText);
      JsonNode root = objectMapper.readTree(jsonText);

      AnalysisV2Response response = new AnalysisV2Response();
      response.setAnalysisId(UUID.randomUUID().toString());
      response.setTimestamp(LocalDateTime.now());

      JsonNode diagnosisNode = root.path("diagnosis");
      if (diagnosisNode.isMissingNode() || diagnosisNode.isNull()) {
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
          response.setStatus(AnalysisStatus.FAILURE);
          AnalysisV1Error error = new AnalysisV1Error();
          error.setCode(errorNode.path("code").asText());
          error.setMessage(errorNode.path("message").asText());
          if (errorNode.has("details")) {
            error.setDetails(errorNode.path("details").toString());
          }
          response.setError(error);
          response.setMetadata(new AnalysisMetadata());
          return response;
        }
        throw new AnalysisException(
            AnalysisV2ErrorCode.INVALID_RESPONSE_SCHEMA, "Missing required 'diagnosis' field");
      }

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

      response.setStatus(
          hasAllSuccessFields ? AnalysisStatus.SUCCESS : AnalysisStatus.PARTIAL_SUCCESS);

      AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
      diagnosis.setSummary(diagnosisNode.path("summary").asText());
      diagnosis.setRootCause(diagnosisNode.path("rootCause").asText());
      try {
        diagnosis.setConfidence(ConfidenceLevel.valueOf(diagnosisNode.path("confidence").asText()));
      } catch (IllegalArgumentException e) {
        diagnosis.setConfidence(ConfidenceLevel.MEDIUM);
      }

      List<AnalysisV2Diagnosis.FixRecommendation> recommendations = new ArrayList<>();
      JsonNode recsNode = diagnosisNode.path("recommendations");
      if (recsNode.isArray()) {
        for (JsonNode recNode : recsNode) {
          AnalysisV2Diagnosis.FixRecommendation rec = new AnalysisV2Diagnosis.FixRecommendation();
          rec.setPriority(recNode.path("priority").asInt(1));
          rec.setTitle(recNode.path("title").asText());
          rec.setDescription(recNode.path("description").asText());
          try {
            rec.setConfidence(ConfidenceLevel.valueOf(recNode.path("confidence").asText()));
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

      List<String> impactedModules = new ArrayList<>();
      JsonNode modulesNode = root.path("impactedModules");
      if (modulesNode.isArray()) {
        modulesNode.forEach(m -> impactedModules.add(m.asText()));
      }
      response.setImpactedModules(impactedModules);

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
        try {
          patchDraft.setConfidence(ConfidenceLevel.valueOf(patchNode.path("confidence").asText()));
        } catch (IllegalArgumentException e) {
          patchDraft.setConfidence(ConfidenceLevel.MEDIUM);
        }
        patchDraft.setRationale(patchNode.path("rationale").asText());
        response.setPatchDraft(patchDraft);
      }

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
          "Failed to parse OpenAI response as JSON: " + e.getMessage(),
          e);
    }
  }

  private String extractJson(String responseText) {
    if (responseText.trim().startsWith("```")) {
      int start = responseText.indexOf('{');
      int end = responseText.lastIndexOf('}');
      if (start >= 0 && end > start) {
        return responseText.substring(start, end + 1);
      }
    }
    return responseText;
  }

  private AnalysisV2Response createFailureResponse(String errorCode, String message) {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setAnalysisId(UUID.randomUUID().toString());
    response.setTimestamp(LocalDateTime.now());
    response.setStatus(AnalysisStatus.FAILURE);
    response.setError(new AnalysisV1Error(errorCode, message));
    AnalysisMetadata metadata = new AnalysisMetadata();
    metadata.setModel(model);
    response.setMetadata(metadata);
    return response;
  }
}
