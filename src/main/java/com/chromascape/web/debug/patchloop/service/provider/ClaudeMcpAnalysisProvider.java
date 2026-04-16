package com.chromascape.web.debug.patchloop.service.provider;

import com.chromascape.web.debug.patchloop.constants.AnalysisV2ErrorCode;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpAnalyzeRequest;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpAnalyzeResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpConfigProperties;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpHealthResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportException;
import com.chromascape.web.debug.patchloop.validation.AnalysisV2Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Claude MCP (Model Context Protocol) provider for V2 analysis.
 *
 * <p>This provider uses the MCP protocol to communicate with Claude instead of direct API calls.
 * MCP provides a standardized protocol for LLM interactions with better tool integration.
 *
 * <p><b>V2 Changes:</b> Updated to use V2 request/response contracts.
 *
 * <p>Activated when {@code analysis.provider=codex-mcp} or {@code analysis.provider=claude-mcp}
 * (legacy alias).
 *
 * <p>The provider delegates HTTP communication to {@link McpTransportClient} which handles retry
 * logic with exponential backoff, timeout management, and deterministic error type mapping.
 *
 * @since 2.0.0
 */
@Service
@ConditionalOnProperty(name = "analysis.provider", havingValue = "codex-mcp", matchIfMissing = true)
public class ClaudeMcpAnalysisProvider implements AnalysisProvider {

  private static final Logger logger = LoggerFactory.getLogger(ClaudeMcpAnalysisProvider.class);

  private final McpTransportClient transport;
  private final McpConfigProperties config;
  private final String providerName;

  /**
   * Creates a new Claude MCP analysis provider.
   *
   * @param transport the MCP transport client
   * @param config the MCP configuration properties
   */
  @Autowired
  public ClaudeMcpAnalysisProvider(
      McpTransportClient transport,
      McpConfigProperties config,
      @Value("${analysis.provider:codex-mcp}") String configuredProviderName) {
    this.transport = transport;
    this.config = config;
    this.providerName = normalizeProviderName(configuredProviderName);

    logger.info(
        "ClaudeMcpAnalysisProvider initialized (V2) - provider: {}, serverUrl: {}, timeout: {}s,"
            + " maxRetries: {}",
        this.providerName,
        config.getServerUrl(),
        config.getTimeoutSeconds(),
        config.getMaxRetries());
  }

  public ClaudeMcpAnalysisProvider(McpTransportClient transport, McpConfigProperties config) {
    this(transport, config, "codex-mcp");
  }

  private String normalizeProviderName(String configuredProviderName) {
    if ("claude-mcp".equals(configuredProviderName)) {
      return "claude-mcp";
    }
    return "codex-mcp";
  }

  @Override
  public AnalysisV2Response analyzeEvidence(AnalysisV2Request request) throws AnalysisException {
    // Validate incoming request
    AnalysisV2Validator.validateRequest(request);

    logger.debug("[{}] MCP analyzeEvidence() called", getProviderName());

    try {
      // Step 1: Build MCP request from V2 request
      McpAnalyzeRequest mcpRequest = buildMcpRequest(request);

      // Step 2: Call MCP server (transport handles timeout and retry logic)
      McpAnalyzeResponse mcpResponse = transport.analyze(mcpRequest);

      // Step 3: Check for server-side error in response wrapper FIRST
      // (wrapper-level error takes precedence over null response)
      if (mcpResponse.getError() != null && !mcpResponse.getError().isBlank()) {
        logger.error(
            "[{}] MCP server returned error in response: {}",
            getProviderName(),
            mcpResponse.getError());
        throw new AnalysisException(
            AnalysisV2ErrorCode.API_ERROR, "MCP server error: " + mcpResponse.getError());
      }

      // Step 4: Extract V2 response from MCP response wrapper
      AnalysisV2Response response = mcpResponse.getAnalysisResponse();

      // Step 5: Validate response is not null
      if (response == null) {
        logger.error("[{}] MCP server returned null analysisResponse", getProviderName());
        throw new AnalysisException(
            AnalysisV2ErrorCode.INVALID_RESPONSE_SCHEMA,
            "MCP server returned null analysisResponse");
      }

      // Step 6: Validate response before returning
      AnalysisV2Validator.validateResponse(response);

      logger.debug(
          "[{}] MCP analyzeEvidence() completed - analysisId: {}, status: {}",
          getProviderName(),
          response.getAnalysisId(),
          response.getStatus());

      return response;

    } catch (McpTransportException e) {
      // Step 7: Map MCP transport errors to V2 error codes
      String errorCode = mapTransportErrorToV2ErrorCode(e.getErrorType());
      logger.warn(
          "[{}] MCP transport error: {} -> {}", getProviderName(), e.getErrorType(), errorCode);

      throw new AnalysisException(errorCode, "MCP analysis failed: " + e.getMessage(), e);
    }
  }

  /**
   * Builds an MCP analyze request from a V2 analysis request.
   *
   * @param request the V2 analysis request
   * @return the MCP analyze request
   */
  private McpAnalyzeRequest buildMcpRequest(AnalysisV2Request request) {
    // Build request options with timeout from config
    McpAnalyzeRequest.McpRequestOptions options =
        new McpAnalyzeRequest.McpRequestOptions(config.getTimeoutSeconds(), null);

    return new McpAnalyzeRequest(request, options);
  }

  /**
   * Maps MCP transport error types to V2 error codes.
   *
   * <p>This mapping ensures deterministic error handling across the analysis pipeline.
   *
   * @param errorType the MCP transport error type
   * @return the corresponding V2 error code
   */
  private String mapTransportErrorToV2ErrorCode(McpTransportException.McpErrorType errorType) {

    return switch (errorType) {
      case TIMEOUT -> AnalysisV2ErrorCode.API_TIMEOUT;
      case AUTH_FAILED -> AnalysisV2ErrorCode.API_AUTH_FAILED;
      case RATE_LIMIT -> AnalysisV2ErrorCode.API_RATE_LIMIT;
      case HTTP_ERROR -> AnalysisV2ErrorCode.API_ERROR;
      case INVALID_RESPONSE -> AnalysisV2ErrorCode.PARSE_ERROR;
      case SERIALIZATION_ERROR -> AnalysisV2ErrorCode.PARSE_ERROR;
      case CONFIGURATION_ERROR -> AnalysisV2ErrorCode.INVALID_REQUEST;
      case CONNECTION_REFUSED -> AnalysisV2ErrorCode.API_ERROR;
      case UNKNOWN -> AnalysisV2ErrorCode.INTERNAL_ERROR;
    };
  }

  @Override
  public String getProviderName() {
    return providerName;
  }

  @Override
  public boolean isAvailable() {
    try {
      McpHealthResponse health = transport.healthCheck();
      boolean isHealthy = health.isHealthy();

      if (isHealthy) {
        logger.debug(
            "[{}] MCP server is available - version: {}",
            getProviderName(),
            health.getServerVersion());
      } else {
        logger.warn("[{}] MCP server returned unhealthy status", getProviderName());
      }

      return isHealthy;

    } catch (McpTransportException e) {
      logger.warn(
          "[{}] MCP server unavailable: {} - {}",
          getProviderName(),
          e.getErrorType(),
          e.getMessage());
      return false;
    }
  }
}
