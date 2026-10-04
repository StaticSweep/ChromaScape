package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * SC-275 causal canonical JSON (projectionVersion 2, hashVersion sha256-semantic-state-v2),
 * mirroring {@code causal-scalar.ts} byte for byte.
 *
 * <p>Stricter than the v1 canonicalizer: no implicit float rounding, no bigint coercion, no missing
 * nodes. Only safe integers serialize (largest exactly representable integer in both languages is
 * 2^53 - 1); integral binary floating values within range normalize to their integer form,
 * everything else floating rejects. Non-integral, non-finite, and unsafe numbers, text with lone
 * surrogates, missing nodes, and POJOs throw {@code PROJECTION_VALUE_INVALID} instead of diverging.
 *
 * <p>Object keys sort in UTF-16 code-unit order (Java {@link String} natural ordering), arrays keep
 * their order, and output carries no whitespace.
 */
public final class CausalCanonicalJson {

  /** Largest integer exactly representable in both Java and TypeScript numbers. */
  public static final long SAFE_INTEGER_LIMIT = 9007199254740991L;

  private CausalCanonicalJson() {}

  /** Serializes a JSON tree to causal canonical bytes. */
  public static String canonicalize(JsonNode value) {
    StringBuilder out = new StringBuilder();
    writeValue(value, out);
    return out.toString();
  }

  private static void writeValue(JsonNode value, StringBuilder out) {
    if (value == null || value.isMissingNode()) {
      throw new Sc275Exception(
          "PROJECTION_VALUE_INVALID", "missing values require an explicit null");
    }
    if (value.isNull()) {
      out.append("null");
      return;
    }
    if (value.isBoolean()) {
      out.append(value.booleanValue() ? "true" : "false");
      return;
    }
    if (value.isTextual()) {
      writeString(value.textValue(), out);
      return;
    }
    if (value.isIntegralNumber()) {
      out.append(writeInteger(coerceIntegral(value)));
      return;
    }
    if (value.isFloatingPointNumber()) {
      out.append(writeInteger(coerceFloating(value)));
      return;
    }
    if (value.isArray()) {
      out.append('[');
      boolean first = true;
      for (JsonNode entry : value) {
        if (!first) {
          out.append(',');
        }
        first = false;
        writeValue(entry, out);
      }
      out.append(']');
      return;
    }
    if (value.isObject()) {
      out.append('{');
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        sorted.put(field.getKey(), field.getValue());
      }
      boolean first = true;
      for (Map.Entry<String, JsonNode> field : sorted.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        writeString(field.getKey(), out);
        out.append(':');
        writeValue(field.getValue(), out);
      }
      out.append('}');
      return;
    }
    throw new Sc275Exception(
        "PROJECTION_VALUE_INVALID",
        "unsupported value type "
            + value.getNodeType()
            + "; use null, boolean, string,"
            + " safe integer, array, or object");
  }

  private static long coerceIntegral(JsonNode value) {
    BigInteger exact;
    if (value.isBigInteger()) {
      exact = value.bigIntegerValue();
    } else {
      exact = BigInteger.valueOf(value.longValue());
    }
    if (exact.compareTo(BigInteger.valueOf(-SAFE_INTEGER_LIMIT)) < 0
        || exact.compareTo(BigInteger.valueOf(SAFE_INTEGER_LIMIT)) > 0) {
      throw new Sc275Exception(
          "PROJECTION_VALUE_INVALID", "integer outside the safe range: " + exact);
    }
    return exact.longValueExact();
  }

  private static long coerceFloating(JsonNode value) {
    BigDecimal decimal;
    if (value.isBigDecimal()) {
      decimal = value.decimalValue();
    } else {
      double raw = value.doubleValue();
      if (!Double.isFinite(raw)) {
        throw new Sc275Exception(
            "PROJECTION_VALUE_INVALID", "non-finite numbers cannot be canonicalized");
      }
      decimal = BigDecimal.valueOf(raw);
    }
    BigDecimal stripped = decimal.stripTrailingZeros();
    if (stripped.scale() > 0) {
      throw new Sc275Exception(
          "PROJECTION_VALUE_INVALID",
          "non-integral numbers cannot be canonicalized: " + decimal.toPlainString());
    }
    try {
      BigInteger exact = stripped.toBigIntegerExact();
      if (exact.compareTo(BigInteger.valueOf(-SAFE_INTEGER_LIMIT)) < 0
          || exact.compareTo(BigInteger.valueOf(SAFE_INTEGER_LIMIT)) > 0) {
        throw new Sc275Exception(
            "PROJECTION_VALUE_INVALID", "integer outside the safe range: " + exact);
      }
      return exact.longValueExact();
    } catch (ArithmeticException overflow) {
      throw new Sc275Exception(
          "PROJECTION_VALUE_INVALID", "integer outside the safe range: " + decimal.toPlainString());
    }
  }

  private static String writeInteger(long value) {
    return Long.toString(value);
  }

  private static void writeString(String text, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char unit = text.charAt(i);
      if (unit >= 0xD800 && unit <= 0xDBFF) {
        if (i + 1 >= text.length()) {
          throw new Sc275Exception(
              "PROJECTION_VALUE_INVALID", "lone high surrogate in string input");
        }
        char low = text.charAt(i + 1);
        if (low < 0xDC00 || low > 0xDFFF) {
          throw new Sc275Exception(
              "PROJECTION_VALUE_INVALID", "lone high surrogate in string input");
        }
        out.append(unit).append(low);
        i++;
        continue;
      }
      if (unit >= 0xDC00 && unit <= 0xDFFF) {
        throw new Sc275Exception("PROJECTION_VALUE_INVALID", "lone low surrogate in string input");
      }
      switch (unit) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
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
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        default:
          if (unit < 0x20) {
            out.append(String.format("\\u%04x", (int) unit));
          } else {
            out.append(unit);
          }
      }
    }
    out.append('"');
  }
}
