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
    List<String> supportedActions,
    String viewerTraceSchema) {

  /** Compatibility constructor for pre-observer capability callers. */
  public CapabilityManifest(
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
    this(
        schemaVersion,
        controlAllowed,
        modes,
        scenarioSchemaVersions,
        semanticActionVersions,
        snapshotVersions,
        projectionVersions,
        hashVersions,
        xrsps,
        openRuneAvailable,
        certifiable,
        fidelity,
        supportedActions,
        "xrsps-replay-trace/v1");
  }

  /** Closed provenance set for simulation capabilities. */
  public enum Fidelity {
    SYNTHETIC_CONTRACT,
    XRSPS_AUTHORITATIVE
  }

  /** Scenario manifests accepted by the pinned authoritative replay-kernel worker. */
  public static final List<Integer> SUPPORTED_SCENARIO_SCHEMA_VERSIONS = List.of(1, 2);

  /** Exact version vector for semantic actions, snapshots, and projections. */
  public static final List<Integer> SUPPORTED_SINGLE_VERSION = List.of(1);

  /**
   * Closed semantic action vocabulary understood by this protocol version.
   *
   * <p>A worker capability is allowed to advertise a duplicate-free subset of this list while
   * individual manifests remain responsible for requiring every action they contain. Keeping the
   * vocabulary separate from a worker's attested subset prevents an incomplete adapter from
   * claiming support merely because an action name is part of the wire contract.
   */
  public static final List<String> V1_SUPPORTED_ACTIONS =
      List.of(
          "WAIT_UNTIL_TICK",
          "WALK_TO",
          "INTERACT_NPC",
          "INTERACT_OBJECT",
          "INTERACT_GROUND_ITEM",
          "INTERACT_INVENTORY_ITEM",
          "INTERACT_WIDGET",
          "SELECT_DIALOGUE_OPTION",
          "CAST_SPELL",
          "EQUIP_ITEM");

  /** Returns whether an action belongs to the closed v1 semantic vocabulary. */
  public static boolean isSupportedAction(String action) {
    return action != null && V1_SUPPORTED_ACTIONS.contains(action);
  }

  /**
   * Returns whether a worker declaration is a non-empty, duplicate-free subset of the vocabulary.
   * Ordering is intentionally not significant at this boundary; manifest action order remains the
   * deterministic execution order.
   */
  public static boolean isValidSupportedActionSubset(List<String> actions) {
    if (actions == null || actions.isEmpty()) {
      return false;
    }
    return actions.stream().allMatch(CapabilityManifest::isSupportedAction)
        && actions.size() == new java.util.HashSet<>(actions).size();
  }

  /** Exact supported profiles; action vocabulary alone never attests an implementation. */
  public static boolean supportedWorkerActions(List<String> actions, Fidelity fidelity) {
    return V1_SUPPORTED_ACTIONS.equals(actions)
        || (fidelity == Fidelity.XRSPS_AUTHORITATIVE && List.of("INTERACT_NPC").equals(actions))
        || (fidelity == Fidelity.SYNTHETIC_CONTRACT
            && List.of("WAIT_UNTIL_TICK", "INTERACT_NPC").equals(actions));
  }

  /** Server-produced manifest: forbidden capabilities are hardcoded off, never parameterized. */
  public static CapabilityManifest current(
      CapabilityModes modes, XrspsIdentity xrsps, boolean certifiable) {
    return new CapabilityManifest(
        /* schemaVersion */ 1,
        /* controlAllowed */ false,
        modes,
        SUPPORTED_SCENARIO_SCHEMA_VERSIONS,
        SUPPORTED_SINGLE_VERSION,
        SUPPORTED_SINGLE_VERSION,
        SUPPORTED_SINGLE_VERSION,
        List.of("sha256-canonical-json-v1"),
        xrsps,
        /* openRuneAvailable */ false,
        certifiable,
        Fidelity.SYNTHETIC_CONTRACT,
        V1_SUPPORTED_ACTIONS,
        "xrsps-replay-trace/v1");
  }

  public record CapabilityModes(
      boolean observedPlayback, boolean policyReplay, boolean causalSimulation) {}

  public record XrspsIdentity(
      String adapterVersion, String buildCommit, String cacheRevision, String contentRevision) {}
}
