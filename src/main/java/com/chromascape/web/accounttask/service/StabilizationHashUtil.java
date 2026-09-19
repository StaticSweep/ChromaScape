package com.chromascape.web.accounttask.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonical JSON hashing that mirrors the Micro6 harness {@code CanonicalJson} contract so the
 * ingestion service can recompute and verify the {@code manifest_hash} and {@code matrix_hash}
 * declared by generated artifacts. Object keys are sorted, strings are NFC-normalized with
 * normalized line endings, numbers are canonicalized, and fields are excluded by key name.
 */
public final class StabilizationHashUtil {

  /** Fields excluded when recomputing a harness run manifest integrity hash. */
  public static final Set<String> MANIFEST_EXCLUSIONS =
      Set.of(
          "generated_at",
          "source_timestamp",
          "ingest_timestamp",
          "manifest_hash",
          "deterministic_content_hash",
          "latency_ms",
          "poll_count");

  /** Fields excluded when recomputing a reliability matrix hash. */
  public static final Set<String> MATRIX_EXCLUSIONS = Set.of("generated_at", "matrix_hash");

  /** Fields excluded when recomputing the deterministic content hash of a manifest. */
  public static final Set<String> DETERMINISTIC_EXCLUSIONS =
      Set.of(
          "generated_at",
          "source_timestamp",
          "ingest_timestamp",
          "manifest_hash",
          "deterministic_content_hash",
          "latency_ms",
          "poll_count",
          "run_id",
          "attempt_id",
          "entry_id",
          "iteration_id",
          "transitions");

  /**
   * Fields excluded when recomputing an {@code attempt_manifest_hash} from a manifest
   * {@code attempts[]} entry. Mirrors the Micro6 harness
   * {@code HarnessRunManifest.AttemptEntry.computeAttemptManifestHash()} contract.
   */
  public static final Set<String> ATTEMPT_MANIFEST_EXCLUSIONS =
      Set.of(
          "attempt_manifest_hash",
          "latency_ms",
          "poll_count",
          "source_timestamp",
          "ingest_timestamp",
          "generated_at");

  /**
   * Fields excluded when recomputing a harness {@code plan_hash} from the plan document. Mirrors
   * the Micro6 harness {@code HarnessPlan.computePlanHash()} contract (canonicalize the plan
   * minus {@code planHash}, excluding volatile identity/timestamp fields).
   */
  public static final Set<String> PLAN_EXCLUSIONS =
      Set.of(
          "planHash",
          "generated_at",
          "source_timestamp",
          "ingest_timestamp",
          "run_id",
          "attempt_id");

  private StabilizationHashUtil() {}

  public static String sha256(JsonNode node, Set<String> exclusions) {
    return sha256(canonicalize(node, exclusions));
  }

  public static String canonicalize(JsonNode node, Set<String> exclusions) {
    return canonical(node, exclusions == null ? Set.of() : exclusions);
  }

  private static String canonical(JsonNode node, Set<String> exclusions) {
    if (node == null || node.isNull()) {
      return "null";
    }
    if (node.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String key = normalize(entry.getKey());
        if (key == null || exclusions.contains(key)) {
          continue;
        }
        sorted.put(key, entry.getValue());
      }
      StringBuilder out = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append(quote(entry.getKey())).append(':').append(canonical(entry.getValue(), exclusions));
      }
      return out.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder out = new StringBuilder("[");
      boolean first = true;
      for (JsonNode element : node) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append(canonical(element, exclusions));
      }
      return out.append(']').toString();
    }
    if (node.isTextual()) {
      return quote(normalize(node.textValue()));
    }
    if (node.isNumber()) {
      BigDecimal decimal = node.decimalValue().stripTrailingZeros();
      return decimal.toPlainString();
    }
    if (node.isBoolean()) {
      return node.asBoolean() ? "true" : "false";
    }
    return node.toString();
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    return Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace("\r\n", "\n")
        .replace('\r', '\n');
  }

  private static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    return out.append('"').toString();
  }

  private static String sha256(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
