package com.chromascape.web.debug.patchloop.service.provider;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;

/**
 * Provider abstraction for analysis services.
 *
 * <p>Implementations can use different backends (Claude API, Claude MCP, mock, etc.) but must
 * return the same AnalysisV2Response contract.
 *
 * <p>This interface decouples the orchestration logic from specific analysis providers, allowing
 * for flexible provider selection via configuration without changing core business logic.
 *
 * <p><b>Breaking Change (V2):</b> Updated from V1 to V2 contract types. All implementations must
 * use V2 request/response schemas.
 *
 * @since 2.0.0
 */
public interface AnalysisProvider {

  /**
   * Analyzes evidence and returns diagnosis with patch draft and retest plan.
   *
   * @param request the V2 analysis request with evidence selectors
   * @return V2 analysis response with diagnosis, patchDraft, and retestPlan
   * @throws AnalysisException if analysis fails
   */
  AnalysisV2Response analyzeEvidence(AnalysisV2Request request) throws AnalysisException;

  /**
   * Returns the provider name for logging/debugging.
   *
   * @return provider name (e.g., "claude-api", "codex-mcp", "claude-mcp", "openai-codex")
   */
  String getProviderName();

  /**
   * Returns whether this provider is available/configured.
   *
   * @return true if provider can be used
   */
  boolean isAvailable();
}
