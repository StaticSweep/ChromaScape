package com.chromascape.web.replay.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Opaque-cursor page over an immutable, content-verified xRSPS trace. */
public record XrspsTraceFramePageResponse(
    String schema,
    UUID runId,
    /** Canonical SHA-256 over {@code {schema:"xrsps-replay-trace/v1",frames:[...]}}. */
    String traceChecksum,
    List<JsonNode> frames,
    String nextCursor,
    boolean hasMore) {

  public static final String SCHEMA = "xrsps-replay-trace-page/v1";

  public XrspsTraceFramePageResponse {
    schema = schema == null ? SCHEMA : schema;
    frames = List.copyOf(frames == null ? List.of() : frames);
  }
}
