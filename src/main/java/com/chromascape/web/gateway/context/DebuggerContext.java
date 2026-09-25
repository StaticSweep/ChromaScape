package com.chromascape.web.gateway.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authorized, versioned correlation identity shared by every debugger transport and view.
 *
 * <p>The values in this record are correlation metadata. They never grant access; callers must be
 * authenticated and authorized before a context is resolved.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DebuggerContext(
    @JsonProperty("schemaVersion") String schemaVersion,
    @JsonProperty("accountId") String accountId,
    @JsonProperty("clientId") String clientId,
    @JsonProperty("agentId") String agentId,
    @JsonProperty("runId") String runId,
    @JsonProperty("debugSessionId") String debugSessionId,
    @JsonProperty("recordingId") String recordingId,
    @JsonProperty("patchIterationId") String patchIterationId,
    @JsonProperty("buildId") String buildId,
    @JsonProperty("mode") Mode mode,
    @JsonProperty("selectedTick") Long selectedTick,
    @JsonProperty("contextRevision") long contextRevision) {

  public static final String SCHEMA_VERSION = "debugger-context/v1";

  public enum Mode {
    live,
    recording,
    replay
  }
}
