package com.chromascape.web.aiplayer.routine.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The canonical JSON the SC-2106 request and result digests are computed over. It mirrors Micro6's
 * {@code FishingCanonicalJson} exactly: object keys sorted by UTF-16 code unit, no whitespace,
 * integers as plain digits, decimals with trailing zeros stripped, and control characters escaped
 * as {@code \\uXXXX} apart from {@code \\n}, {@code \\r} and {@code \\t}. The shared {@code
 * candidate-execution-v2-vectors.json} pins the two implementations together.
 */
public final class Sc2106CanonicalJson {
  private Sc2106CanonicalJson() {}

  /** Canonical text of a value built from maps, lists, strings, numbers, booleans and null. */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  /** {@code sha256:<hex>} of the canonical UTF-8 text. */
  public static String sha256(Object value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(write(value).getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void append(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Number number) {
      appendNumber(out, number);
    } else if (value instanceof CharSequence || value instanceof Character) {
      appendString(out, value.toString());
    } else if (value instanceof Enum<?> constant) {
      appendString(out, constant.name());
    } else if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        sorted.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      out.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> entry : sorted.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        appendString(out, entry.getKey());
        out.append(':');
        append(out, entry.getValue());
      }
      out.append('}');
    } else if (value instanceof Collection<?> elements) {
      out.append('[');
      boolean first = true;
      for (Object element : elements) {
        if (!first) {
          out.append(',');
        }
        first = false;
        append(out, element);
      }
      out.append(']');
    } else {
      throw new IllegalArgumentException(
          "unsupported canonical JSON value: " + value.getClass().getName());
    }
  }

  private static void appendNumber(StringBuilder out, Number number) {
    if (number instanceof Double || number instanceof Float) {
      double value = number.doubleValue();
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        throw new IllegalArgumentException("non-finite number in canonical JSON");
      }
      appendDecimal(out, new BigDecimal(Double.toString(value)));
    } else if (number instanceof BigDecimal decimal) {
      appendDecimal(out, decimal);
    } else if (number instanceof BigInteger integer) {
      if (integer.bitLength() > 63) {
        throw new IllegalArgumentException("integer outside the signed 64-bit range");
      }
      out.append(integer.longValue());
    } else {
      out.append(number.longValue());
    }
  }

  private static void appendDecimal(StringBuilder out, BigDecimal value) {
    BigDecimal decimal = value.stripTrailingZeros();
    out.append(decimal.scale() <= 0 ? decimal.toBigInteger().toString() : decimal.toPlainString());
  }

  private static void appendString(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
