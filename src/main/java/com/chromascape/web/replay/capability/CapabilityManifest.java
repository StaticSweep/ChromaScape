package com.chromascape.web.replay.capability;

import java.util.List;

/**
 * Immutable capability manifest describing the current replay subsystem capabilities. Returned by
 * GET /api/replay/capabilities.
 *
 * <p>{@code certifiable} is the xRSPS worker's own attestation that the running build carries a
 * complete, reproducible identity (real git commit, non-empty recursive cache and content
 * inventories). It is REQUIRED: a missing field is treated as false everywhere downstream — it can
 * never default to true.
 *
 * <p>Server-produced manifests are strictly fail-closed: {@code controlAllowed} and {@code
 * openRuneAvailable} are ALWAYS {@code false}; they are never accepted as parameters because a
 * caller could otherwise advertise a forbidden capability.
 */
public record CapabilityManifest(
    int schemaVersion,
    boolean controlAllowed,
    CapabilityModes modes,
    List<Integer> scenarioSchemaVersions,
    List<Integer> semanticActionVersions,
    List<Integer> snapshotVersions,
    List<Integer> projectionVersions,
    List<String> hashVersions,
    XrspsIdentity xrsps,
    boolean openRuneAvailable,
    boolean certifiable,
    Fidelity fidelity,
    List<String> supportedActions) {

  /** Legacy viewer capability retained for replay-v2 clients. */
  public String viewerTraceSchema() {
    return "xrsps-replay-trace/v1";
  }

  /** Closed provenance set for simulation capabilities. */
  public enum Fidelity {
    SYNTHETIC_CONTRACT,
    XRSPS_AUTHORITATIVE
  }

  /** Exact action surface implemented by the pinned v1 replay-kernel worker. */
  public static final List<String> V1_SUPPORTED_ACTIONS =
      List.of("WAIT_UNTIL_TICK", "INTERACT_NPC");

  /** Server-produced manifest: forbidden capabilities are hardcoded off, never parameterized. */
  public static CapabilityManifest current(
      CapabilityModes modes, XrspsIdentity xrsps, boolean certifiable) {
    return new CapabilityManifest(
        /* schemaVersion */ 1,
        /* controlAllowed */ false,
        modes,
        List.of(1),
        List.of(1),
        List.of(1),
        List.of(1),
        List.of("sha256-canonical-json-v1"),
        xrsps,
        /* openRuneAvailable */ false,
        certifiable,
        Fidelity.SYNTHETIC_CONTRACT,
        V1_SUPPORTED_ACTIONS);
  }

  public record CapabilityModes(
      boolean observedPlayback, boolean policyReplay, boolean causalSimulation) {}

  public record XrspsIdentity(
      String adapterVersion, String buildCommit, String cacheRevision, String contentRevision) {}
}
