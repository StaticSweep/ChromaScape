package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper gateway for analysis providers.
 *
 * <p><b>V2 Update:</b> This class is now a simple wrapper that delegates to the configured {@link
 * AnalysisProvider} implementation. All business logic has been moved to the provider
 * implementations (e.g., {@code ClaudeApiAnalysisProvider}).
 *
 * <p>This design allows the provider to be selected via configuration ({@code
 * analysis.provider=claude-api|codex-mcp|claude-mcp|openai-codex}) without changing core
 * orchestration logic.
 *
 * @since 2.0.0
 */
@Service
public class PatchLoopAnalysisClient {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopAnalysisClient.class);

  private final AnalysisProvider analysisProvider;

  public PatchLoopAnalysisClient(AnalysisProvider analysisProvider) {
    this.analysisProvider = analysisProvider;
    logger.info(
        "PatchLoopAnalysisClient initialized with provider: {}",
        analysisProvider.getProviderName());
  }

  /**
   * Analyzes evidence and returns structured diagnosis with patch draft.
   *
   * <p>Delegates to the configured {@link AnalysisProvider} implementation.
   *
   * @param request the V2 analysis request
   * @return V2 analysis response with diagnosis, patchDraft, and retestPlan
   * @throws AnalysisException if analysis fails
   */
  public AnalysisV2Response analyze(AnalysisV2Request request) throws AnalysisException {
    logger.debug("Delegating analysis request to provider: {}", analysisProvider.getProviderName());
    return analysisProvider.analyzeEvidence(request);
  }

  /**
   * Returns the configured provider name.
   *
   * @return provider name (e.g., "claude-api", "codex-mcp", "claude-mcp", "openai-codex")
   */
  public String getProviderName() {
    return analysisProvider.getProviderName();
  }

  /**
   * Returns whether the configured provider is available.
   *
   * @return true if provider can be used
   */
  public boolean isProviderAvailable() {
    return analysisProvider.isAvailable();
  }
}
