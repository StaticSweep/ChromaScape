package com.chromascape.web.debug.patchloop.service.provider.mcp;

import java.time.Instant;

/**
 * Cached result of an end-to-end Codex MCP verification.
 *
 * <p>Represents the outcome of running a real minimal MCP analyze call through the production
 * transport path to confirm Codex is actually the active runtime provider.
 */
public class McpVerificationResult {

  private final boolean success;
  private final String outcome;
  private final String error;
  private final String runtimeProvider;
  private final String runtimeModel;
  private final Instant verifiedAt;

  public McpVerificationResult(
      boolean success,
      String outcome,
      String error,
      String runtimeProvider,
      String runtimeModel,
      Instant verifiedAt) {
    this.success = success;
    this.outcome = outcome;
    this.error = error;
    this.runtimeProvider = runtimeProvider;
    this.runtimeModel = runtimeModel;
    this.verifiedAt = verifiedAt;
  }

  /** Creates a successful verification result. */
  public static McpVerificationResult success(String runtimeProvider, String runtimeModel) {
    return new McpVerificationResult(
        true, "verified", null, runtimeProvider, runtimeModel, Instant.now());
  }

  /** Creates a failed verification result. */
  public static McpVerificationResult failure(String error, String runtimeProvider) {
    return new McpVerificationResult(false, "failed", error, runtimeProvider, null, Instant.now());
  }

  public boolean isSuccess() {
    return success;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getError() {
    return error;
  }

  public String getRuntimeProvider() {
    return runtimeProvider;
  }

  public String getRuntimeModel() {
    return runtimeModel;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }
}
