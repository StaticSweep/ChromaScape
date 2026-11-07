package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Closed native lifecycle negotiation, independent of fidelity/certification and causal-v2. */
public final class NativeRunProtocol {
  private NativeRunProtocol() {}

  public static boolean negotiated(JsonNode capability) {
    if (capability == null || !capability.isObject())
      throw ReplayException.invalid(
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          "NATIVE_RUN_PROTOCOL_INCOMPATIBLE: capability object required");
    if (!capability.has("nativeRunProtocol")) return false;
    JsonNode protocol = capability.path("nativeRunProtocol");
    Set<String> fields =
        Set.of("version", "stateSchema", "policySchema", "snapshotDescriptorVersion", "evaluation");
    if (!protocol.isObject()
        || protocol.size() != fields.size()
        || fields.stream().anyMatch(field -> !protocol.has(field))
        || !protocol.path("version").isInt()
        || protocol.path("version").asInt() != 1
        || !NativeRunLedger.SCHEMA.equals(protocol.path("stateSchema").asText())
        || !"xrsps-termination-policy/v1".equals(protocol.path("policySchema").asText())
        || !protocol.path("snapshotDescriptorVersion").isInt()
        || protocol.path("snapshotDescriptorVersion").asInt() != 4
        || !"RESET_ACTION_EACH_TICK".equals(protocol.path("evaluation").asText())
        || capability.has("causal")) {
      throw ReplayException.invalid(
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          "NATIVE_RUN_PROTOCOL_INCOMPATIBLE: native v1 requires exact v2 ledger/v1 policy/v4"
              + " descriptor and reset/action/each-tick evaluation; mixed causal-v2 is not"
              + " qualified");
    }
    return true;
  }
}
