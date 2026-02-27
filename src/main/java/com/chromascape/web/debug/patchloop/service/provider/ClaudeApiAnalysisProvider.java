package com.chromascape.web.debug.patchloop.service.provider;

import com.chromascape.web.debug.patchloop.constants.AnalysisV1ErrorCode;
import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response.AnalysisMetadata;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response.DiagnosisSection;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response.FixRecommendation;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.validation.AnalysisV1Validator;
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
 * Claude API provider for bot failure analysis.
 *
 * <p>Uses Java HttpClient to make direct requests to the Anthropic Claude API. This is the default
 * analysis provider when no specific provider is configured.
 *
 * <p>Activated when {@code analysis.provider=claude-api} or when no provider is specified.
 */
@Service
@ConditionalOnProperty(
    name = "analysis.provider",
    havingValue = "claude-api",
    matchIfMissing = true)
public class ClaudeApiAnalysisProvider implements AnalysisProvider {

  private static final Logger logger = LoggerFactory.getLogger(ClaudeApiAnalysisProvider.class);

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
      @Value("${claude.api.max-tokens:4096}") int maxTokens) {
    this.objectMapper = objectMapper;
    this.apiKey = apiKey;
    this.apiUrl = apiUrl;
    this.model = model;
    this.timeoutSeconds = timeoutSeconds;
    this.maxTokens = maxTokens;

    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }

  @Override
  public AnalysisV1Response analyzeEvidence(AnalysisV1Request request) throws AnalysisException {
    logger.info(
        "[{}] Starting evidence analysis for prompt: {}", getProviderName(), request.getPrompt());

    long startTime = System.currentTimeMillis();

    // Validate request
    AnalysisV1Validator.validateRequest(request);

    // Build prompts
    String systemPrompt = buildSystemPrompt();
    String userMessage = buildUserMessage(request);

    try {
      // Call Claude API
      String responseText = callClaudeApi(systemPrompt, userMessage);

      // Parse response
      AnalysisV1Response response = parseAnalysisResponse(responseText, request);

      // Set metadata
      long analysisTime = System.currentTimeMillis() - startTime;
      if (response.getMetadata() == null) {
        response.setMetadata(new AnalysisMetadata());
      }
      response.getMetadata().setModel(model);
      response.getMetadata().setAnalysisTimeMs(analysisTime);
      response.getMetadata().setEvidenceCount(request.getEvidence().getTotalEvidenceCount());

      // Validate response
      AnalysisV1Validator.validateResponse(response);

      logger.info("[{}] Analysis completed in {}ms", getProviderName(), analysisTime);
      return response;

    } catch (AnalysisException e) {
      logger.error(
          "[{}] Analysis failed: {} - {}", getProviderName(), e.getErrorCode(), e.getMessage());
      throw e;
    } catch (Exception e) {
      logger.error("[" + getProviderName() + "] Unexpected error during analysis", e);
      throw new AnalysisException(
          AnalysisV1ErrorCode.INTERNAL_ERROR, "Unexpected error: " + e.getMessage(), e);
    }
  }

  @Override
  public String getProviderName() {
    return "claude-api";
  }

  @Override
  public boolean isAvailable() {
    return apiKey != null && !apiKey.trim().isEmpty();
  }

  /** Builds the system prompt that instructs Claude on how to respond. */
  private String buildSystemPrompt() {
    return """
        You are an expert bot debugging assistant for ChromaScape, a colour-based game automation framework.

        Your task is to analyze bot failure evidence and provide structured diagnosis with actionable recommendations.

        You will receive:
        1. A user's question/prompt about a bot failure
        2. Evidence from the bot run: logs, tick logs, and decision records
        3. Context about the script and failure

        You MUST respond with a valid JSON object matching this schema:
        {
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
        }

        Guidelines:
        - Focus on ROOT CAUSE, not symptoms
        - Provide 1-5 prioritized recommendations (highest priority = 1)
        - Reference specific evidence (logs, decisions, tick data) in your analysis
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

        Return ONLY the JSON object, no additional text.
        """;
  }

  /** Builds the user message with evidence and prompt. */
  private String buildUserMessage(AnalysisV1Request request) {
    StringBuilder msg = new StringBuilder();

    msg.append("## Script Failure Analysis Request\n\n");

    // Script and context
    msg.append(String.format("**Script:** %s\n", request.getEvidence().getScriptName()));
    msg.append(
        String.format(
            "**Time Window:** %s to %s (%.1f seconds)\n",
            request.getEvidence().getTimeWindow().getStart(),
            request.getEvidence().getTimeWindow().getEnd(),
            (double) request.getEvidence().getTimeWindow().getDurationSeconds()));

    if (request.getEvidence().getTickWindow() != null) {
      msg.append(
          String.format(
              "**Tick Window:** %d to %d (%d ticks)\n",
              request.getEvidence().getTickWindow().getStartTick(),
              request.getEvidence().getTickWindow().getEndTick(),
              request.getEvidence().getTickWindow().getTickCount()));
    }

    if (request.getContext() != null && !request.getContext().isEmpty()) {
      msg.append(String.format("\n**Context:** %s\n", request.getContext()));
    }

    msg.append(String.format("\n**Question:** %s\n\n", request.getPrompt()));

    // Evidence summary
    msg.append("## Evidence Summary\n\n");
    msg.append(String.format("- %d general logs\n", request.getEvidence().getLogs().size()));
    msg.append(String.format("- %d tick logs\n", request.getEvidence().getTickLogs().size()));
    msg.append(
        String.format("- %d decision records\n\n", request.getEvidence().getDecisions().size()));

    // Logs
    if (!request.getEvidence().getLogs().isEmpty()) {
      msg.append("### General Logs\n\n");
      for (AnalysisV1EvidenceBundle.LogEvidence log : request.getEvidence().getLogs()) {
        msg.append(
            String.format(
                "[%s] [%s] %s (source: %s)\n",
                log.getTimestamp(), log.getLevel(), log.getMessage(), log.getSource()));
      }
      msg.append("\n");
    }

    // Tick logs
    if (!request.getEvidence().getTickLogs().isEmpty()) {
      msg.append("### Tick Logs\n\n");
      for (AnalysisV1EvidenceBundle.TickLogEvidence log : request.getEvidence().getTickLogs()) {
        msg.append(
            String.format(
                "[Tick %d] [%s] [%s] %s",
                log.getTick(), log.getSubsystem(), log.getSeverity(), log.getMessage()));
        if (log.getDetails() != null && !log.getDetails().isEmpty()) {
          msg.append(String.format(" | Details: %s", log.getDetails()));
        }
        msg.append("\n");
      }
      msg.append("\n");
    }

    // Decisions
    if (!request.getEvidence().getDecisions().isEmpty()) {
      msg.append("### Decision Records\n\n");
      for (AnalysisV1EvidenceBundle.DecisionEvidence dec : request.getEvidence().getDecisions()) {
        msg.append(
            String.format(
                "[%s] %s - %s",
                dec.getTimestamp(),
                dec.getDecisionType(),
                dec.getSuccess() ? "SUCCESS" : "FAILED"));
        if (!dec.getSuccess() && dec.getFailureCode() != null) {
          msg.append(String.format(" (code: %s)", dec.getFailureCode()));
        }
        if (dec.getTargetEntity() != null) {
          msg.append(String.format(" | Target: %s", dec.getTargetEntity()));
        }
        if (dec.getContext() != null && !dec.getContext().isEmpty()) {
          msg.append(String.format(" | Context: %s", dec.getContext()));
        }
        msg.append("\n");
      }
      msg.append("\n");
    }

    msg.append(
        "Please analyze this evidence and provide a structured diagnosis in JSON format as"
            + " specified.");

    return msg.toString();
  }

  /** Calls Claude API and returns the response text. */
  private String callClaudeApi(String systemPrompt, String userMessage) throws AnalysisException {
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.API_AUTH_FAILED,
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

      logger.debug("[{}] Calling Claude API: {}", getProviderName(), apiUrl);

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
              AnalysisV1ErrorCode.PARSE_ERROR, "Unexpected API response format: " + responseBody);
        }
      } else if (statusCode == 401 || statusCode == 403) {
        throw new AnalysisException(
            AnalysisV1ErrorCode.API_AUTH_FAILED, "API authentication failed: " + responseBody);
      } else if (statusCode == 429) {
        throw new AnalysisException(
            AnalysisV1ErrorCode.API_RATE_LIMIT, "API rate limit exceeded: " + responseBody);
      } else {
        throw new AnalysisException(
            AnalysisV1ErrorCode.API_ERROR, "API error (HTTP " + statusCode + "): " + responseBody);
      }

    } catch (AnalysisException e) {
      throw e;
    } catch (java.net.http.HttpTimeoutException e) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.API_TIMEOUT,
          "API request timed out after " + timeoutSeconds + " seconds",
          e);
    } catch (InterruptedException e) {
      // Restore interrupt flag for cooperative cancellation
      Thread.currentThread().interrupt();
      throw new AnalysisException(
          AnalysisV1ErrorCode.API_ERROR, "API request interrupted: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new AnalysisException(
          AnalysisV1ErrorCode.API_ERROR, "API request failed: " + e.getMessage(), e);
    }
  }

  /** Parses the Claude API response and builds AnalysisV1Response. */
  private AnalysisV1Response parseAnalysisResponse(String responseText, AnalysisV1Request request)
      throws AnalysisException {
    try {
      // Parse JSON from Claude's response
      JsonNode diagnosisNode = objectMapper.readTree(responseText);

      // Build response
      AnalysisV1Response response = new AnalysisV1Response();
      response.setAnalysisId(UUID.randomUUID().toString());
      response.setTimestamp(LocalDateTime.now());
      response.setStatus(AnalysisStatus.SUCCESS);

      // Build diagnosis section
      DiagnosisSection diagnosis = new DiagnosisSection();
      diagnosis.setSummary(diagnosisNode.path("summary").asText());
      diagnosis.setRootCause(diagnosisNode.path("rootCause").asText());

      String confidenceStr = diagnosisNode.path("confidence").asText();
      try {
        diagnosis.setConfidence(ConfidenceLevel.valueOf(confidenceStr));
      } catch (IllegalArgumentException e) {
        diagnosis.setConfidence(ConfidenceLevel.MEDIUM);
      }

      // Parse recommendations
      List<FixRecommendation> recommendations = new ArrayList<>();
      JsonNode recsNode = diagnosisNode.path("recommendations");
      if (recsNode.isArray()) {
        for (JsonNode recNode : recsNode) {
          FixRecommendation rec = new FixRecommendation();
          rec.setPriority(recNode.path("priority").asInt(1));
          rec.setTitle(recNode.path("title").asText());
          rec.setDescription(recNode.path("description").asText());

          String recConfStr = recNode.path("confidence").asText();
          try {
            rec.setConfidence(ConfidenceLevel.valueOf(recConfStr));
          } catch (IllegalArgumentException e) {
            rec.setConfidence(ConfidenceLevel.MEDIUM);
          }

          // Parse affected files
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

      return response;

    } catch (JsonProcessingException e) {
      logger.error(
          "[{}] Failed to parse analysis response: {}", getProviderName(), responseText, e);
      throw new AnalysisException(
          AnalysisV1ErrorCode.PARSE_ERROR,
          "Failed to parse Claude response as JSON: " + e.getMessage(),
          e);
    }
  }
}
