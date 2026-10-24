package com.chromascape.web.replay.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Produces canonical observations for state comparison and state-hash computation.
 *
 * <p>Hash version: sha256-canonical-json-v1 - Object keys sorted recursively by UTF-16 code unit
 * order (parity with the TypeScript canonicalize() in
 * replay-kernel/src/observation/canonical-json.ts). - Arrays preserve order; producers sort
 * inventory by slot and npcs by stableId. - Non-integer doubles are rounded to 9 decimal places; -0
 * normalizes to 0.
 *
 * <p>Chain hash: SHA-256(previousChainHash || actionDigest || stateHash) over raw bytes of the hex
 * digests — NOT a JSON object wrapper.
 */
@Service
public class CanonicalProjectionService {

  public static final String HASH_PREFIX = "sha256:";

  /** Version of the canonical-JSON projection algorithm. */
  public static final String PROJECTION_VERSION = "canonical-json-v1";

  /** Version of the state/chain hashing scheme. */
  public static final String HASH_VERSION = "sha256-canonical-json-v1";

  private static final int HEX_LEN = 64;

  /** Deterministic SHA-256 state hash from a canonical observation. Returns "sha256:<hex>". */
  public String stateHash(JsonNode canonicalObservation) {
    return sha256Hex(canonicalJsonBytes(canonicalObservation));
  }

  /**
   * Canonical JSON encoding: recursively key-sorted, normalized numbers, no whitespace.
   * Byte-compatible with the TypeScript implementation.
   */
  public String canonicalJson(JsonNode node) {
    return writeNode(node);
  }

  /** Serialized canonical form as UTF-8 bytes. */
  public byte[] canonicalJsonBytes(JsonNode node) {
    return canonicalJson(node).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Chain hash per spec: SHA-256 over the concatenated raw digest bytes of (previousChainHash,
   * actionDigest, stateHash). All inputs must be prefixed "sha256:" hex digests. Fails closed on
   * malformed input.
   */
  public String chainHash(String previousChainHash, String actionDigest, String stateHash) {
    byte[] prev = decodeDigest(previousChainHash, "previousChainHash");
    byte[] act = decodeDigest(actionDigest, "actionDigest");
    byte[] state = decodeDigest(stateHash, "stateHash");
    MessageDigest md = newSha256();
    md.update(prev);
    md.update(act);
    md.update(state);
    return HASH_PREFIX + hex(md.digest());
  }

  /** Action digest: SHA-256 of the canonical action document ("sha256:<hex>"). */
  public String actionDigest(JsonNode action) {
    return sha256Hex(canonicalJsonBytes(action));
  }

  // ── canonical serialization ──

  private String writeNode(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return "null";
    }
    if (node.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> e = fields.next();
        sorted.put(e.getKey(), e.getValue());
      }
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        sb.append(quote(e.getKey())).append(':').append(writeNode(e.getValue()));
      }
      return sb.append('}').toString();
    }
    if (node.isArray()) {
      ArrayNode arr = (ArrayNode) node;
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < arr.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(writeNode(arr.get(i)));
      }
      return sb.append(']').toString();
    }
    if (node.isNumber()) {
      double d = node.asDouble();
      if (node.canConvertToLong() && d == Math.rint(d)) {
        long l = node.asLong();
        return Long.toString(l);
      }
      double rounded = Math.round(d * 1e9) / 1e9;
      if (rounded == 0.0) rounded = 0.0; // normalize -0
      if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15) {
        return Long.toString((long) rounded);
      }
      // ECMAScript prints ordinary decimal notation through this range (for example native
      // magic XP 13034448.5). Double.toString instead switches to 1.30344485E7 at 1e7, breaking
      // cross-language snapshot hashes despite identical state. Rounded v1 fractions are at
      // least 1e-9; only values below 1e-6 use the small scientific notation on the TS side.
      BigDecimal decimal = BigDecimal.valueOf(rounded).stripTrailingZeros();
      return Math.abs(rounded) < 1e-6
          ? decimal.toString().replace("E-", "e-")
          : decimal.toPlainString();
    }
    if (node.isBoolean()) {
      return node.asBoolean() ? "true" : "false";
    }
    if (node.isTextual()) {
      return quote(node.asText());
    }
    // Fallback: textual representation quoted.
    return quote(node.toString());
  }

  private String quote(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  // ── hashing helpers ──

  private byte[] decodeDigest(String hash, String name) {
    if (hash == null
        || !hash.startsWith(HASH_PREFIX)
        || hash.length() != HASH_PREFIX.length() + HEX_LEN) {
      throw new IllegalArgumentException("Malformed " + name + ": expected sha256:<64 hex chars>");
    }
    String hex = hash.substring(HASH_PREFIX.length());
    byte[] out = new byte[HEX_LEN / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = Character.digit(hex.charAt(2 * i), 16);
      int lo = Character.digit(hex.charAt(2 * i + 1), 16);
      if (hi < 0 || lo < 0) {
        throw new IllegalArgumentException("Malformed " + name + ": invalid hex");
      }
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  public String sha256Hex(byte[] input) {
    MessageDigest md = newSha256();
    return HASH_PREFIX + hex(md.digest(input));
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(
          "SHA-256 algorithm unavailable; refusing to compute insecure fallback", e);
    }
  }

  private static String hex(byte[] digest) {
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  /** Sorts an array of object nodes by a string field (for npcs by stableId etc.). */
  public static void sortByStringField(ArrayNode array, String fieldName) {
    List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
    for (JsonNode n : array) {
      entries.add(Map.entry(n.path(fieldName).asText(""), n));
    }
    entries.sort(Comparator.comparing(Map.Entry::getKey));
    array.removeAll();
    for (var e : entries) {
      array.add(e.getValue());
    }
  }
}
