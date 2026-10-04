package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * SC-275 anchored transition-chain verification, mirroring {@code verifyTransitionChainV2} frame
 * for frame.
 *
 * <p>Every ordinal, version, identity binding, digest recomputation, and tick continuity is
 * checked; the chain must start at the trusted anchor and (when given) end at the trusted final
 * digest. A chain that merely agrees with itself is insufficient.
 */
public final class TransitionChainVerifier {

  private static final Set<String> KINDS = Set.of("RESET", "ACTION", "ADVANCE", "TERMINAL");

  private TransitionChainVerifier() {}

  /** Verification anchors: independently trusted identity and chain endpoints. */
  public record Anchors(
      String manifestDigest,
      String runtimeDigest,
      String expectedGenesis,
      String expectedFinalChainHash) {
    /** Anchors without a final-chain expectation: internal consistency only. */
    public Anchors(String manifestDigest, String runtimeDigest, String expectedGenesis) {
      this(manifestDigest, runtimeDigest, expectedGenesis, null);
    }
  }

  /**
   * Explicit suffix anchors for verifying a chain continuation without its prefix. The first suffix
   * frame must continue exactly from (tick, nextSequence) after the predecessor hash; RESET is
   * never valid mid-chain.
   */
  public record SuffixStart(
      long ordinal, long tick, long nextSequence, String predecessorChainHash) {}

  /** Verification outcome: the first failure reason and its frame ordinal, if frame-local. */
  public record VerifyResult(boolean valid, String reason, Integer ordinal) {
    /** Successful verification. */
    public static VerifyResult ok() {
      return new VerifyResult(true, null, null);
    }
  }

  /** Verifies an externally supplied transition chain against its anchors. */
  public static VerifyResult verify(List<JsonNode> frames, Anchors anchors) {
    return verify(frames, anchors, null);
  }

  /**
   * Verifies an externally supplied transition chain with the same lifecycle rules the producer
   * enforces: RESET-first full runs, exact ordinal increments, per-tick sequences starting at zero
   * and incrementing exactly, non-negative non-regressing ticks, at most one TERMINAL which must
   * close the run, and full digest recomputation. The chain must start at the trusted anchor
   * (genesis for a full run, the explicit suffix start otherwise) and, when given, end at the
   * trusted final digest.
   */
  public static VerifyResult verify(List<JsonNode> frames, Anchors anchors, SuffixStart start) {
    if (frames == null) {
      return new VerifyResult(false, "HASH_CHAIN_INVALID: frames must be an array", null);
    }
    try {
      SemanticHashV2.assertDigest(anchors.manifestDigest(), "manifestDigest");
      SemanticHashV2.assertDigest(anchors.runtimeDigest(), "runtimeDigest");
      SemanticHashV2.assertDigest(anchors.expectedGenesis(), "expectedGenesis");
      if (anchors.expectedFinalChainHash() != null) {
        SemanticHashV2.assertDigest(anchors.expectedFinalChainHash(), "expectedFinalChainHash");
      }
      if (start != null) {
        assertSafeOrdinal(start.ordinal(), "start.ordinal");
        assertSafeOrdinal(start.tick(), "start.tick");
        assertSafeOrdinal(start.nextSequence(), "start.nextSequence");
        SemanticHashV2.assertDigest(start.predecessorChainHash(), "start.predecessorChainHash");
        if (start.ordinal() < 0 || start.tick() < 0 || start.nextSequence() < 0) {
          return new VerifyResult(
              false, "HASH_CHAIN_INVALID: suffix start must be non-negative", null);
        }
      }
    } catch (Sc275Exception invalid) {
      return new VerifyResult(false, invalid.getCode() + ": " + invalid.getMessage(), null);
    }
    long baseOrdinal = start == null ? 0 : start.ordinal();
    String previous = start == null ? anchors.expectedGenesis() : start.predecessorChainHash();
    long previousTick = start == null ? -1 : start.tick();
    long previousSequence = start == null ? -1 : start.nextSequence() - 1;
    boolean terminated = false;
    for (int index = 0; index < frames.size(); index++) {
      JsonNode frame = frames.get(index);
      if (frame == null || !frame.isObject()) {
        return fail(index, "HASH_CHAIN_INVALID: frame must be an object");
      }
      if (terminated) {
        return fail(index, "HASH_CHAIN_INVALID: no transitions are allowed after TERMINAL");
      }
      if (!frame.path("traceVersion").isIntegralNumber()
          || !frame.path("traceVersion").canConvertToInt()
          || frame.path("traceVersion").intValue() != SemanticHashV2.TRACE_VERSION) {
        return fail(
            index,
            "PROJECTION_VERSION_UNSUPPORTED: frame traceVersion must be "
                + SemanticHashV2.TRACE_VERSION);
      }
      if (!isSafeInteger(frame.path("ordinal"))
          || frame.path("ordinal").asLong(-1) != baseOrdinal + index) {
        return fail(
            index,
            "HASH_CHAIN_INVALID: frame ordinal "
                + frame.path("ordinal").asText()
                + " breaks continuity at index "
                + index);
      }
      JsonNode tickNode = frame.path("tick");
      JsonNode sequenceNode = frame.path("sequence");
      if (!isSafeInteger(tickNode) || !isSafeInteger(sequenceNode)) {
        return fail(index, "HASH_CHAIN_INVALID: frame tick/sequence must be safe integers");
      }
      long tick = tickNode.longValue();
      long sequence = sequenceNode.longValue();
      if (tick < 0 || sequence < 0) {
        return fail(index, "HASH_CHAIN_INVALID: frame tick/sequence must be non-negative");
      }
      if (tick < previousTick) {
        return fail(index, "HASH_CHAIN_INVALID: frame tick regresses");
      }
      if (tick == previousTick) {
        if (sequence != previousSequence + 1) {
          return fail(
              index,
              "HASH_CHAIN_INVALID: frame sequence "
                  + sequence
                  + " must increment exactly after "
                  + previousSequence
                  + " at tick "
                  + tick);
        }
      } else if (sequence != 0) {
        return fail(
            index, "HASH_CHAIN_INVALID: frame sequence must restart at zero for a new tick");
      }
      previousTick = tick;
      previousSequence = sequence;
      if (!KINDS.contains(frame.path("kind").asText(null))) {
        return fail(
            index, "HASH_CHAIN_INVALID: unknown transition kind " + frame.path("kind").asText());
      }
      if (frame.path("ordinal").asLong(-1) == 0 && !"RESET".equals(frame.path("kind").asText())) {
        return fail(index, "HASH_CHAIN_INVALID: a full run must start with one RESET");
      }
      if (frame.path("ordinal").asLong(-1) > 0 && "RESET".equals(frame.path("kind").asText())) {
        return fail(
            index, "HASH_CHAIN_INVALID: RESET starts a new run chain, not a frame in an old chain");
      }
      if (!frame.path("manifestDigest").asText("").equals(anchors.manifestDigest())) {
        return fail(
            index, "CHECKPOINT_IDENTITY_MISMATCH: frame manifestDigest does not match the anchor");
      }
      if (!frame.path("runtimeDigest").asText("").equals(anchors.runtimeDigest())) {
        return fail(
            index, "CHECKPOINT_IDENTITY_MISMATCH: frame runtimeDigest does not match the anchor");
      }
      if (!frame.path("previousChainHash").asText("").equals(previous)) {
        return fail(
            index, "HASH_CHAIN_INVALID: frame previousChainHash does not continue the chain");
      }
      String recomputed;
      try {
        recomputed =
            SemanticHashV2.chainHash(
                frame.path("manifestDigest").asText(),
                frame.path("runtimeDigest").asText(),
                frame.path("previousChainHash").asText(),
                tick,
                sequenceNode.longValue(),
                baseOrdinal + index,
                frame.path("kind").asText(),
                frame.path("actionDigest").asText(),
                frame.path("resultDigest").asText(),
                frame.path("stateHash").asText());
      } catch (Sc275Exception invalid) {
        return fail(index, "HASH_CHAIN_INVALID: " + invalid.getMessage());
      }
      if (!recomputed.equals(frame.path("chainHash").asText())) {
        return fail(index, "HASH_CHAIN_INVALID: frame chainHash does not match its bound inputs");
      }
      previous = frame.path("chainHash").asText();
      if ("TERMINAL".equals(frame.path("kind").asText())) {
        terminated = true;
      }
    }
    if (anchors.expectedFinalChainHash() != null
        && !previous.equals(anchors.expectedFinalChainHash())) {
      return new VerifyResult(
          false, "HASH_CHAIN_INVALID: final chain hash does not match the anchor", null);
    }
    return VerifyResult.ok();
  }

  private static VerifyResult fail(int ordinal, String reason) {
    return new VerifyResult(false, reason, ordinal);
  }

  private static void assertSafeOrdinal(long value, String name) {
    if (value < -CausalCanonicalJson.SAFE_INTEGER_LIMIT
        || value > CausalCanonicalJson.SAFE_INTEGER_LIMIT) {
      throw new Sc275Exception("HASH_CHAIN_INVALID", name + " must be a safe integer");
    }
  }

  private static boolean isSafeInteger(JsonNode node) {
    if (!node.isIntegralNumber()) {
      return false;
    }
    BigInteger limit = BigInteger.valueOf(CausalCanonicalJson.SAFE_INTEGER_LIMIT);
    if (node.isBigInteger()) {
      BigInteger exact = node.bigIntegerValue();
      return exact.compareTo(limit.negate()) >= 0 && exact.compareTo(limit) <= 0;
    }
    long value = node.longValue();
    return value >= -CausalCanonicalJson.SAFE_INTEGER_LIMIT
        && value <= CausalCanonicalJson.SAFE_INTEGER_LIMIT;
  }
}
