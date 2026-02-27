package com.chromascape.web.debug.patchloop.service.provider;

import com.chromascape.web.debug.patchloop.constants.AnalysisV1ErrorCode;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpConfigProperties;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpHealthResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Claude MCP (Model Context Protocol) provider for bot failure analysis.
 *
 * <p>This provider uses the MCP protocol to communicate with Claude instead of direct API calls.
 * MCP provides a standardized protocol for LLM interactions with better tool integration.
 *
 * <p>Activated when {@code analysis.provider=claude-mcp}.
 *
 * <p><strong>Note:</strong> The {@code analyzeEvidence()} method is pending implementation in issue
 * #516. Currently, only the availability check via health check is implemented.
 */
@Service
@ConditionalOnProperty(name = "analysis.provider", havingValue = "claude-mcp")
public class ClaudeMcpAnalysisProvider implements AnalysisProvider {

  private static final Logger logger = LoggerFactory.getLogger(ClaudeMcpAnalysisProvider.class);

  private final McpTransportClient transport;
  private final McpConfigProperties config;

  /**
   * Creates a new Claude MCP analysis provider.
   *
   * @param transport the MCP transport client
   * @param config the MCP configuration properties
   */
  @Autowired
  public ClaudeMcpAnalysisProvider(McpTransportClient transport, McpConfigProperties config) {
    this.transport = transport;
    this.config = config;

    logger.info(
        "ClaudeMcpAnalysisProvider initialized - serverUrl: {}, timeout: {}s, maxRetries: {}",
        config.getServerUrl(),
        config.getTimeoutSeconds(),
        config.getMaxRetries());
  }

  @Override
  public AnalysisV1Response analyzeEvidence(AnalysisV1Request request) throws AnalysisException {
    logger.error(
        "[{}] MCP analyzeEvidence() pending implementation (#516). Health check available.",
        getProviderName());
    throw new AnalysisException(
        AnalysisV1ErrorCode.INTERNAL_ERROR,
        "MCP analyzeEvidence() pending implementation (#516). Use health check for availability"
            + " testing.");

    // TODO: Implement MCP protocol integration (#516)
    // 1. Build MCP request from AnalysisV1Request
    // 2. Call MCP server with timeout and retry logic
    // 3. Parse MCP response to AnalysisV1Response
    // 4. Validate response schema
    // 5. Map MCP errors to AnalysisException error codes
  }

  @Override
  public String getProviderName() {
    return "claude-mcp";
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
