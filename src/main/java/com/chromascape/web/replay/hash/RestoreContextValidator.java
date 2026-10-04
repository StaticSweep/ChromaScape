package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SC-275 typed causal restore-context validation, mirroring {@code validateRestoreContextV2}.
 *
 * <p>A version-2 checkpoint binds the immutable manifest digest, the exact runtime identity digest,
 * the snapshot schema version, the snapshot content digest, the causal state digest at the
 * checkpoint, the snapshot tick, the next transition ordinal, and the predecessor chain hash.
 * Version 1 is the original minimal shape (identity, versions, ordinals, predecessor) kept for
 * historical checkpoints. Validation is anchored against independently trusted identity before any
 * state is rebuilt; a checkpoint that merely agrees with itself is insufficient.
 */
public final class RestoreContextValidator {

  /** Current restore-context schema version; version 1 remains accepted for history. */
  public static final int CONTEXT_VERSION = 2;

  /** Snapshot schema versions this verifier restores. */
  public static final int SUPPORTED_SNAPSHOT_VERSION = 1;

  private RestoreContextValidator() {}

  /** Validation outcome with the stable failure reason. */
  public record Validation(boolean valid, String reason) {
    /** Successful validation. */
    public static Validation ok() {
      return new Validation(true, null);
    }
  }

  /** Validates a restore context against independently trusted identity. */
  public static Validation validate(JsonNode context, String manifestDigest, String runtimeDigest) {
    if (context == null || !context.isObject()) {
      return new Validation(
          false, "CHECKPOINT_IDENTITY_MISMATCH: restore context must be an object");
    }
    JsonNode version = context.path("contextVersion");
    int contextVersion =
        version.isIntegralNumber() && version.canConvertToInt() ? version.intValue() : -1;
    if (contextVersion != 1 && contextVersion != 2) {
      return new Validation(
          false, "PROJECTION_VERSION_UNSUPPORTED: restore contextVersion must be 1 or 2");
    }
    if (!context.path("manifestDigest").asText("").equals(manifestDigest)) {
      return new Validation(false, "CHECKPOINT_IDENTITY_MISMATCH: manifest digest substitution");
    }
    if (!context.path("runtimeDigest").asText("").equals(runtimeDigest)) {
      return new Validation(false, "CHECKPOINT_IDENTITY_MISMATCH: runtime identity substitution");
    }
    if (!context.path("snapshotVersion").isIntegralNumber()
        || !context.path("snapshotVersion").canConvertToInt()
        || context.path("snapshotVersion").intValue() != SUPPORTED_SNAPSHOT_VERSION) {
      return new Validation(
          false,
          "PROJECTION_VERSION_UNSUPPORTED: unsupported snapshotVersion "
              + context.path("snapshotVersion").asText());
    }
    JsonNode ordinal = context.path("nextTransitionOrdinal");
    if (!isSafeNonNegative(ordinal)) {
      return new Validation(false, "CHECKPOINT_IDENTITY_MISMATCH: invalid next transition ordinal");
    }
    JsonNode predecessor = context.path("predecessorChainHash");
    if ((ordinal.longValue() == 0) != predecessor.isNull()) {
      return new Validation(
          false, "CHECKPOINT_IDENTITY_MISMATCH: predecessor must anchor nonempty history");
    }
    if (!predecessor.isNull()) {
      try {
        SemanticHashV2.assertDigest(predecessor.asText(null), "predecessorChainHash");
      } catch (Sc275Exception invalid) {
        return new Validation(false, "HASH_DIGEST_INVALID: " + invalid.getMessage());
      }
    }
    if (contextVersion == 2) {
      try {
        SemanticHashV2.assertDigest(context.path("snapshotDigest").asText(null), "snapshotDigest");
        SemanticHashV2.assertDigest(
            context.path("checkpointStateDigest").asText(null), "checkpointStateDigest");
      } catch (Sc275Exception invalid) {
        return new Validation(false, "HASH_DIGEST_INVALID: " + invalid.getMessage());
      }
      if (!isSafeNonNegative(context.path("tick"))) {
        return new Validation(false, "CHECKPOINT_IDENTITY_MISMATCH: invalid checkpoint tick");
      }
    }
    return Validation.ok();
  }

  private static boolean isSafeNonNegative(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      return false;
    }
    long value = node.longValue();
    return value >= 0 && value <= CausalCanonicalJson.SAFE_INTEGER_LIMIT;
  }
}
