package com.chromascape.web.replay.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Short-lived launch URL for the read-only xRSPS observer. */
public record ViewerSessionResponse(
    String schema, UUID runId, String viewerUrl, Instant expiresAt, boolean controlAllowed) {

  public static final String SCHEMA = "xrsps-replay-viewer-session/v1";

  public ViewerSessionResponse {
    schema = schema == null ? SCHEMA : schema;
  }
}
