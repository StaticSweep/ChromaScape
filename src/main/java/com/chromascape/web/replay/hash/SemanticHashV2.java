package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SC-275 semantic hash envelopes (hashVersion sha256-semantic-state-v2), mirroring {@code
 * semantic-hash-v2.ts} digest for digest.
 *
 * <p>Every digest is domain-separated by an explicit tag object so a hash can never be transplanted
 * across domains, versions, or roles. Digests are lowercase {@code sha256:} plus exactly 64 hex
 * characters; validation rejects anything else before any state is read.
 */
public final class SemanticHashV2 {

  public static final int PROJECTION_VERSION = 2;
  public static final String HASH_VERSION = "sha256-semantic-state-v2";
  public static final int TRACE_VERSION = 2;

  /** Domains every complete causal projection must carry, in declared order. */
  public static final List<String> REQUIRED_DOMAINS =
      List.of(
          "playerMotion",
          "playerStats",
          "possessions",
          "world",
          "execution",
          "scenario",
          "restoreContext");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SemanticHashV2() {}

  /** Validates a strict v2 digest and returns its hex body. */
  public static String assertDigest(String hash, String name) {
    if (hash == null || !hash.matches("sha256:[0-9a-f]{64}")) {
      throw new Sc275Exception(
          "HASH_DIGEST_INVALID",
          name + " must be sha256: plus exactly 64 lowercase hex characters");
    }
    return hash.substring("sha256:".length());
  }

  /** Per-domain hash: SHA-256 over the domain-separated domain envelope. */
  public static String domainHash(String domain, JsonNode value) {
    if (domain == null || domain.isEmpty()) {
      throw new Sc275Exception("HASH_DIGEST_INVALID", "domain must be a non-empty string");
    }
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("tag", "sc275-domain-v2");
    envelope.put("projectionVersion", PROJECTION_VERSION);
    envelope.put("domain", domain);
    envelope.set("value", value);
    return envelope(envelope);
  }

  /** Composite state hash over the tick and every domain digest. */
  public static String stateHash(long tick, Map<String, String> domainHashes) {
    assertTick(tick, "tick");
    TreeMap<String, String> sorted = new TreeMap<>(domainHashes);
    ObjectNode hashes = MAPPER.createObjectNode();
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      assertDigest(entry.getValue(), "domainHashes[" + entry.getKey() + "]");
      hashes.put(entry.getKey(), entry.getValue());
    }
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("tag", "sc275-state-v2");
    envelope.put("projectionVersion", PROJECTION_VERSION);
    envelope.put("hashVersion", HASH_VERSION);
    envelope.put("tick", assertTick(tick, "tick"));
    envelope.set("domainHashes", hashes);
    return envelope(envelope);
  }

  /** Chain genesis: binds the immutable manifest and runtime identity. */
  public static String genesisHash(String manifestDigest, String runtimeDigest) {
    assertDigest(manifestDigest, "manifestDigest");
    assertDigest(runtimeDigest, "runtimeDigest");
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("tag", "sc275-genesis-v2");
    envelope.put("manifestDigest", manifestDigest);
    envelope.put("runtimeDigest", runtimeDigest);
    envelope.put("projectionVersion", PROJECTION_VERSION);
    envelope.put("hashVersion", HASH_VERSION);
    return envelope(envelope);
  }

  /** Transition chain hash over the fully bound transition envelope. */
  public static String chainHash(
      String manifestDigest,
      String runtimeDigest,
      String previousChainHash,
      long tick,
      long sequence,
      long ordinal,
      String kind,
      String actionDigest,
      String resultDigest,
      String stateHash) {
    assertDigest(manifestDigest, "manifestDigest");
    assertDigest(runtimeDigest, "runtimeDigest");
    assertDigest(previousChainHash, "previousChainHash");
    assertDigest(actionDigest, "actionDigest");
    assertDigest(resultDigest, "resultDigest");
    assertDigest(stateHash, "stateHash");
    assertTick(tick, "tick");
    assertTick(sequence, "sequence");
    assertTick(ordinal, "ordinal");
    if (kind == null || kind.isEmpty()) {
      throw new Sc275Exception("HASH_DIGEST_INVALID", "kind must be a non-empty string");
    }
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("tag", "sc275-transition-v2");
    envelope.put("manifestDigest", manifestDigest);
    envelope.put("runtimeDigest", runtimeDigest);
    envelope.put("projectionVersion", PROJECTION_VERSION);
    envelope.put("hashVersion", HASH_VERSION);
    envelope.put("previousChainHash", previousChainHash);
    envelope.put("tick", tick);
    envelope.put("sequence", sequence);
    envelope.put("ordinal", ordinal);
    envelope.put("kind", kind);
    envelope.put("actionDigest", actionDigest);
    envelope.put("resultDigest", resultDigest);
    envelope.put("stateHash", stateHash);
    return envelope(envelope);
  }

  /** Canonical null-action digest for no-action (RESET/ADVANCE) transitions. */
  public static String nullActionDigest() {
    return envelope(MAPPER.nullNode());
  }

  /** Result digest binding the outcome status and optional message. */
  public static String resultDigest(String status, String message) {
    if (status == null
        || (!status.equals("ACCEPTED")
            && !status.equals("REJECTED")
            && !status.equals("ABSTAINED")
            && !status.equals("TERMINAL"))) {
      throw new Sc275Exception("HASH_CHAIN_INVALID", "unknown transition result " + status);
    }
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("status", status);
    if (message == null) {
      envelope.putNull("message");
    } else {
      envelope.put("message", message);
    }
    return envelope(envelope);
  }

  /** Runtime identity digest over the four exact worker pins. */
  public static String runtimeDigest(
      String adapterVersion, String buildCommit, String cacheRevision, String contentRevision) {
    requirePin("adapterVersion", adapterVersion);
    requirePin("buildCommit", buildCommit);
    requirePin("cacheRevision", cacheRevision);
    requirePin("contentRevision", contentRevision);
    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("tag", "sc275-runtime-v2");
    envelope.put("adapterVersion", adapterVersion);
    envelope.put("buildCommit", buildCommit);
    envelope.put("cacheRevision", cacheRevision);
    envelope.put("contentRevision", contentRevision);
    return envelope(envelope);
  }

  /**
   * Hashes a causal projection: per-domain hashes plus the composite state hash. Strict: unknown
   * projection versions, missing required domains, and unknown extra domains reject with stable
   * codes instead of hashing a partial or off-schema projection.
   */
  public static Hashes hashProjection(
      int projectionVersion, long tick, Map<String, JsonNode> domains) {
    if (projectionVersion != PROJECTION_VERSION) {
      throw new Sc275Exception(
          "PROJECTION_VERSION_UNSUPPORTED",
          "projectionVersion " + projectionVersion + " is not supported");
    }
    if (tick < 0 || tick > CausalCanonicalJson.SAFE_INTEGER_LIMIT) {
      throw new Sc275Exception(
          "PROJECTION_VALUE_INVALID", "projection tick must be a nonnegative safe integer");
    }
    if (domains == null) {
      throw new Sc275Exception("PROJECTION_STATE_INCOMPLETE", "projection domains must be present");
    }
    for (String domain : REQUIRED_DOMAINS) {
      if (!domains.containsKey(domain)) {
        throw new Sc275Exception(
            "PROJECTION_STATE_INCOMPLETE", "required causal domain missing: " + domain);
      }
    }
    for (String domain : domains.keySet()) {
      if (!REQUIRED_DOMAINS.contains(domain)) {
        throw new Sc275Exception("PROJECTION_VALUE_INVALID", "unknown causal domain: " + domain);
      }
    }
    ObjectNode complete = MAPPER.createObjectNode();
    domains.forEach(complete::set);
    CausalDomainValidator.validate(complete);
    TreeMap<String, String> domainHashes = new TreeMap<>();
    for (String domain : REQUIRED_DOMAINS) {
      domainHashes.put(domain, domainHash(domain, domains.get(domain)));
    }
    return new Hashes(domainHashes, stateHash(tick, domainHashes));
  }

  /** Per-domain digests plus the composite state digest. */
  public record Hashes(Map<String, String> domainHashes, String stateHash) {}

  private static long assertTick(long value, String name) {
    if (value < -CausalCanonicalJson.SAFE_INTEGER_LIMIT
        || value > CausalCanonicalJson.SAFE_INTEGER_LIMIT) {
      throw new Sc275Exception("HASH_DIGEST_INVALID", name + " must be a safe integer");
    }
    return value;
  }

  private static void requirePin(String field, String value) {
    if (value == null || value.isEmpty()) {
      throw new Sc275Exception(
          "HASH_DIGEST_INVALID", "runtime identity field " + field + " must be a non-blank string");
    }
  }

  private static String envelope(JsonNode envelope) {
    return "sha256:" + sha256Hex(CausalCanonicalJson.canonicalize(envelope));
  }

  static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte unit : bytes) {
        hex.append(String.format("%02x", unit));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
