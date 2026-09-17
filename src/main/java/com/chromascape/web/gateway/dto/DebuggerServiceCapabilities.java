package com.chromascape.web.gateway.dto;

import java.util.List;
import java.util.Map;

/**
 * Sanitized, read-only capabilities published by ChromaScape for a dashboard gateway.
 *
 * <p>The dashboard gateway adds its own identity and health before exposing the public {@code
 * debugger-gateway/v1} manifest. This service response never contains origins, credentials,
 * filesystem paths, or raw exception text.
 */
public record DebuggerServiceCapabilities(
    String schemaVersion,
    String serviceBuildId,
    List<String> restCapabilities,
    List<String> streamKinds,
    Map<String, String> streamSchemaVersions,
    Integer maxRestBodyBytes,
    Integer maxFrameBytes,
    List<String> replayModes,
    Features features,
    boolean controlAllowed,
    List<String> allowedMutations,
    Health health) {

  public record Features(boolean sessions, boolean recordings, boolean patch) {}

  public record Health(String status, List<String> reasons) {}
}
