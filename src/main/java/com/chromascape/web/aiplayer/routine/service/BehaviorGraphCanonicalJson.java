package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Canonical JSON for the SC-728 behavior graph, byte-compatible with JavaScript's {@code
 * JSON.stringify} after recursive key sorting.
 *
 * <p>This is deliberately separate from {@link com.chromascape.web.aiplayer.contract
 * .CanonicalJsonHasher}. The generic contract hashes identity documents for SC-810, SC-967 and the
 * patch pipelines, where every number must be integral and object keys sort by UTF-8 byte order.
 * The editor contract has different requirements, and changing the shared implementation would
 * silently invalidate every hash those other contracts have already published:
 *
 * <ul>
 *   <li>Layout coordinates and zoom are fractional, so numbers are rendered the way ECMAScript
 *       {@code Number::toString} renders them: the shortest decimal that round-trips, no trailing
 *       {@code .0} for integral values, and exponent notation only outside {@code 1e-6}..{@code
 *       1e21}. Inside the schema domain (x/y within +/-1000000, zoom within 0.1..4) this is plain
 *       decimal notation.
 *   <li>Object keys sort by UTF-16 code unit, which is what {@code Array.prototype.sort()} uses by
 *       default. UTF-8 byte order and UTF-16 code unit order disagree for keys containing
 *       supplementary characters above U+FFFF.
 *   <li>Arrays keep their declared order. Ordering of the root {@code nodes}/{@code edges}/{@code
 *       groups} arrays is a domain decision made by the canonicalizers, never a property of a field
 *       name. A nested array that happens to be called {@code nodes} must survive untouched.
 *   <li>Line endings inside string values are normalized to {@code \n}. Object keys are not.
 * </ul>
 *
 * <p>The digest is SHA-256 over the UTF-8 bytes of the canonical string, prefixed with {@code
 * sha256:}. Golden vectors shared with the TypeScript implementation live in {@code
 * contracts/behavior-graph-hash-vectors-v1.json}.
 */
final class BehaviorGraphCanonicalJson {
  private BehaviorGraphCanonicalJson() {}

  static String canonical(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out);
    return out.toString();
  }

  static String hash(JsonNode node) {
    byte[] bytes = canonical(node).getBytes(StandardCharsets.UTF_8);
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void write(JsonNode node, StringBuilder out) {
    if (node == null || node.isNull()) {
      out.append("null");
      return;
    }
    if (node.isBoolean()) {
      out.append(node.booleanValue() ? "true" : "false");
      return;
    }
    if (node.isNumber()) {
      out.append(number(node));
      return;
    }
    if (node.isTextual()) {
      writeString(node.textValue(), true, out);
      return;
    }
    if (node.isArray()) {
      out.append('[');
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) out.append(',');
        write(node.get(index), out);
      }
      out.append(']');
      return;
    }
    if (node.isObject()) {
      List<String> keys = new ArrayList<>();
      node.fieldNames().forEachRemaining(keys::add);
      keys.sort(String::compareTo);
      out.append('{');
      for (int index = 0; index < keys.size(); index++) {
        if (index > 0) out.append(',');
        String key = keys.get(index);
        writeString(key, false, out);
        out.append(':');
        write(node.get(key), out);
      }
      out.append('}');
      return;
    }
    throw new IllegalArgumentException("Unsupported JSON node " + node.getNodeType());
  }

  private static String number(JsonNode node) {
    if (node.isIntegralNumber() && node.canConvertToLong()) return Long.toString(node.longValue());
    double value = node.doubleValue();
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("Non-finite number is not allowed");
    }
    return ecmaNumber(value);
  }

  /**
   * ECMAScript {@code Number::toString} for a finite double. {@link BigDecimal#valueOf} supplies
   * the shortest decimal that round-trips, which is the same digit sequence the JavaScript engine
   * chooses, and the layout below follows the specification's choice of plain or exponent form.
   */
  private static String ecmaNumber(double value) {
    if (value == 0.0d) return "0";
    BigDecimal decimal = BigDecimal.valueOf(Math.abs(value)).stripTrailingZeros();
    String digits = decimal.unscaledValue().toString();
    int length = digits.length();
    int point = length - decimal.scale();
    StringBuilder out = new StringBuilder(value < 0 ? "-" : "");
    if (length <= point && point <= 21) {
      out.append(digits);
      for (int index = 0; index < point - length; index++) out.append('0');
    } else if (point > 0 && point <= 21) {
      out.append(digits, 0, point).append('.').append(digits, point, length);
    } else if (point > -6 && point <= 0) {
      out.append("0.");
      for (int index = 0; index < -point; index++) out.append('0');
      out.append(digits);
    } else {
      out.append(digits.charAt(0));
      if (length > 1) out.append('.').append(digits, 1, length);
      int exponent = point - 1;
      out.append('e').append(exponent >= 0 ? '+' : '-').append(Math.abs(exponent));
    }
    return out.toString();
  }

  private static void writeString(String value, boolean normalizeLineEndings, StringBuilder out) {
    validateUnicode(value);
    out.append('"');
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\t' -> out.append("\\t");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> {
          if (normalizeLineEndings) {
            // CRLF collapses to a single LF; the paired LF is consumed here.
            out.append("\\n");
            if (index + 1 < value.length() && value.charAt(index + 1) == '\n') index++;
          } else {
            out.append("\\r");
          }
        }
        default -> {
          if (current < 0x20) appendUnicodeEscape(current, out);
          else out.append(current);
        }
      }
    }
    out.append('"');
  }

  private static void appendUnicodeEscape(char value, StringBuilder out) {
    String hex = "0123456789abcdef";
    out.append("\\u");
    out.append(hex.charAt((value >> 12) & 0xf));
    out.append(hex.charAt((value >> 8) & 0xf));
    out.append(hex.charAt((value >> 4) & 0xf));
    out.append(hex.charAt(value & 0xf));
  }

  private static void validateUnicode(String value) {
    for (int offset = 0; offset < value.length(); offset++) {
      char current = value.charAt(offset);
      if (Character.isHighSurrogate(current)) {
        if (offset + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(offset + 1))) {
          throw new IllegalArgumentException("Invalid Unicode surrogate sequence");
        }
        offset++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException("Invalid Unicode surrogate sequence");
      }
    }
  }
}
