package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpHealthResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportException;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpVerificationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages end-to-end Codex MCP verification with server-side caching.
 *
 * <p>Runs a real minimal MCP analyze call through the production transport path to confirm that
 * Codex is the active runtime provider. Results are cached with a configurable TTL to avoid running
 * live verification on every UI poll.
 */
@Service
public class McpVerificationService {

  private static final Logger logger = LoggerFactory.getLogger(McpVerificationService.class);

  private final McpTransportClient mcpTransportClient;
  private final AnalysisProvider analysisProvider;
  private final long verificationTtlSeconds;
  private final boolean enableManualVerification;

  private volatile McpVerificationResult cachedResult;
  private volatile Instant lastVerificationAttempt;

  public McpVerificationService(
      McpTransportClient mcpTransportClient,
      AnalysisProvider analysisProvider,
      @Value("${analysis.mcp.verification-ttl-seconds:300}") long verificationTtlSeconds,
      @Value("${analysis.mcp.enable-manual-verification:true}") boolean enableManualVerification) {
    this.mcpTransportClient = mcpTransportClient;
    this.analysisProvider = analysisProvider;
    this.verificationTtlSeconds = verificationTtlSeconds;
    this.enableManualVerification = enableManualVerification;
    logger.info(
        "McpVerificationService initialized - ttl: {}s, manual-verification: {}",
        verificationTtlSeconds,
        enableManualVerification);
  }

  /**
   * Returns the cached verification result if still fresh, null otherwise.
   *
   * @return cached result or null
   */
  public McpVerificationResult getCachedResult() {
    McpVerificationResult result = cachedResult;
    if (result == null) {
      return null;
    }
    Instant verifiedAt = result.getVerifiedAt();
    if (verifiedAt == null) {
      return null;
    }
    long ageSeconds = Duration.between(verifiedAt, Instant.now()).getSeconds();
    if (ageSeconds > verificationTtlSeconds) {
      logger.debug(
          "Cached verification result expired (age: {}s, ttl: {}s)",
          ageSeconds,
          verificationTtlSeconds);
      return null;
    }
    return result;
  }

  /** Returns the last verification attempt timestamp. */
  public Instant getLastVerificationAttempt() {
    return lastVerificationAttempt;
  }

  /** Returns whether manual verification is enabled via configuration. */
  public boolean isManualVerificationEnabled() {
    return enableManualVerification;
  }

  /**
   * Runs a live end-to-end verification through the MCP analyze path.
   *
   * <p>This performs a real minimal MCP analyze call (not just a health check) to confirm that the
   * runtime wrapper is actually using Codex. Only runs the analyze call when the configured
   * provider is MCP-based.
   *
   * @return verification result
   */
  public McpVerificationResult verify() {
    lastVerificationAttempt = Instant.now();

    // Guard: only meaningful for MCP-based providers
    String frameworkProvider = analysisProvider.getProviderName();
    if (frameworkProvider == null || !frameworkProvider.endsWith("-mcp")) {
      String reason =
          "Verification only supported for MCP providers, current: " + frameworkProvider;
      logger.warn(reason);
      McpVerificationResult failure = McpVerificationResult.failure(reason, frameworkProvider);
      cachedResult = failure;
      return failure;
    }

    // Step 1: Check bridge health first
    McpHealthResponse health;
    try {
      health = mcpTransportClient.healthCheck();
    } catch (McpTransportException e) {
      logger.warn("MCP bridge health check failed during verification: {}", e.getMessage());
      McpVerificationResult failure =
          McpVerificationResult.failure("MCP bridge unreachable: " + e.getMessage(), null);
      cachedResult = failure;
      return failure;
    }

    if (health == null || !health.isHealthy()) {
      String reason =
          health == null
              ? "MCP bridge returned null health response"
              : "MCP bridge reported unhealthy";
      logger.warn("MCP bridge unhealthy during verification: {}", reason);
      McpVerificationResult failure = McpVerificationResult.failure(reason, null);
      cachedResult = failure;
      return failure;
    }

    // Step 2: Extract runtime provider from health metadata
    Map<String, Object> metadata = health.getMetadata();
    String runtimeProvider = readStringMetadata(metadata, "provider", null);
    String runtimeModel = readStringMetadata(metadata, "model", null);

    // Step 3: Check if runtime provider is codex
    if (runtimeProvider == null) {
      logger.warn("MCP health metadata missing provider field");
      McpVerificationResult failure =
          McpVerificationResult.failure("MCP bridge health metadata missing provider field", null);
      cachedResult = failure;
      return failure;
    }

    String normalizedProvider = normalizeRuntimeProvider(runtimeProvider);
    if (!normalizedProvider.equals("codex-cli")) {
      logger.warn(
          "MCP runtime provider is not Codex: {} (normalized: {})",
          runtimeProvider,
          normalizedProvider);
      McpVerificationResult failure =
          McpVerificationResult.failure(
              "Runtime wrapper is using " + normalizedProvider + ", not Codex", normalizedProvider);
      cachedResult = failure;
      return failure;
    }

    // Step 4: Run a real minimal MCP analyze call through the production path
    try {
      AnalysisV2Request minimalRequest = AnalysisV2Request.createMinimalVerificationRequest();
      analysisProvider.analyzeEvidence(minimalRequest);
      logger.info(
          "End-to-end Codex MCP verification succeeded - provider: codex, model: {}", runtimeModel);
      McpVerificationResult success = McpVerificationResult.success(runtimeProvider, runtimeModel);
      cachedResult = success;
      return success;
    } catch (Exception e) {
      logger.warn("End-to-end MCP analyze verification failed: {}", e.getMessage());
      McpVerificationResult failure =
          McpVerificationResult.failure(
              "Verification analyze call failed: " + e.getMessage(), normalizedProvider);
      cachedResult = failure;
      return failure;
    }
  }

  private String readStringMetadata(Map<String, Object> metadata, String key, String fallback) {
    if (metadata == null) {
      return fallback;
    }
    Object value = metadata.get(key);
    if (value == null) {
      return fallback;
    }
    String stringValue = value.toString().trim();
    return stringValue.isEmpty() ? fallback : stringValue;
  }

  private String normalizeRuntimeProvider(String runtimeProvider) {
    if (runtimeProvider == null || runtimeProvider.isBlank()) {
      return runtimeProvider;
    }
    return switch (runtimeProvider) {
      case "codex" -> "codex-cli";
      case "claude" -> "claude-cli";
      case "opencode" -> "opencode-cli";
      default -> runtimeProvider;
    };
  }
}
