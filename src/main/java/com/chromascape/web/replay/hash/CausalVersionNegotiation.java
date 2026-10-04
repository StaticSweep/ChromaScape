package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SC-275 explicit version negotiation for the causal v2 contract.
 *
 * <p>Reads a worker capability document and requires projection version 2 with hash version {@code
 * sha256-semantic-state-v2}. Anything else — unknown versions, missing arrays, malformed entries —
 * rejects with {@code PROJECTION_VERSION_UNSUPPORTED} before any reset or mutation. This is
 * additive: the v1 handshake predicate is untouched.
 */
public final class CausalVersionNegotiation {

  /** Causal projection version this verifier implements. */
  public static final int PROJECTION_VERSION = 2;

  /** Causal hash version this verifier implements. */
  public static final String HASH_VERSION = "sha256-semantic-state-v2";

  private CausalVersionNegotiation() {}

  /** True when the capability document offers the v2 causal contract. */
  public static boolean supportsCausalV2(JsonNode capabilities) {
    return versions(capabilities, "projectionVersions")
            .equals(java.util.Set.of(String.valueOf(PROJECTION_VERSION)))
        && versions(capabilities, "hashVersions").equals(java.util.Set.of(HASH_VERSION))
        && versions(capabilities, "traceVersions").equals(java.util.Set.of("2"))
        && versions(capabilities, "snapshotVersions").equals(java.util.Set.of("1"))
        && versions(capabilities, "contextVersions").equals(java.util.Set.of("2"));
  }

  /** Requires the v2 causal contract, failing closed otherwise. */
  public static void requireCausalV2(JsonNode capabilities) {
    if (!supportsCausalV2(capabilities)) {
      throw new Sc275Exception(
          "PROJECTION_VERSION_UNSUPPORTED", "worker causal version combination is unsupported");
    }
  }

  private static java.util.Set<String> versions(JsonNode capabilities, String field) {
    java.util.Set<String> out = new java.util.HashSet<>();
    if (capabilities == null || !capabilities.isObject()) {
      return out;
    }
    JsonNode array = capabilities.get(field);
    if (array == null || !array.isArray()) {
      return out;
    }
    for (JsonNode entry : array) {
      String value;
      if (field.equals("hashVersions")) {
        if (!entry.isTextual()) {
          return java.util.Set.of();
        }
        value = entry.textValue();
      } else {
        if (!entry.isIntegralNumber() || !entry.canConvertToInt()) {
          return java.util.Set.of();
        }
        value = String.valueOf(entry.intValue());
      }
      if (!out.add(value)) {
        return java.util.Set.of();
      }
    }
    return out;
  }
}
