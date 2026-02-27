package com.chromascape.web.debug.patchloop.service.provider;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;

/**
 * Provider abstraction for analysis services.
 *
 * <p>Implementations can use different backends (Claude API, Claude MCP, mock, etc.) but must
 * return the same AnalysisV1Response contract.
 *
 * <p>This interface decouples the orchestration logic from specific analysis providers, allowing
 * for flexible provider selection via configuration without changing core business logic.
 */
public interface AnalysisProvider {

  /**
   * Analyzes evidence and returns diagnosis.
   *
   * @param request the analysis request with evidence
   * @return analysis response with diagnosis
   * @throws AnalysisException if analysis fails
   */
  AnalysisV1Response analyzeEvidence(AnalysisV1Request request) throws AnalysisException;

  /**
   * Returns the provider name for logging/debugging.
   *
   * @return provider name (e.g., "claude-api", "claude-mcp")
   */
  String getProviderName();

  /**
   * Returns whether this provider is available/configured.
   *
   * @return true if provider can be used
   */
  boolean isAvailable();
}
