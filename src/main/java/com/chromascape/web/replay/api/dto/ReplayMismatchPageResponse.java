package com.chromascape.web.replay.api.dto;

import java.util.List;
import java.util.UUID;

/** Bounded mismatch projection for the replay debugger. */
public record ReplayMismatchPageResponse(
    String schema, UUID runId, List<ReplayMismatchResponse> mismatches, boolean hasMore) {

  public static final String SCHEMA = "xrsps-replay-mismatch-page/v1";

  public ReplayMismatchPageResponse {
    schema = schema == null ? SCHEMA : schema;
    mismatches = List.copyOf(mismatches == null ? List.of() : mismatches);
  }
}
