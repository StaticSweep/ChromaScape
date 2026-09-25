package com.chromascape.web.debug.tickdebugger.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** Canonical checksum contract for the tick-batch/v2 wire document. */
@Service
public class TickBatchCanonicalChecksumService {
  private final ObjectMapper mapper;

  public TickBatchCanonicalChecksumService(ObjectMapper mapper) {
    this.mapper =
        mapper
            .copy()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
  }

  /** Returns the canonical JSON document, removing only the root payloadChecksum property. */
  public String canonicalJson(JsonNode wireDocument) {
    StringBuilder output = new StringBuilder();
    writeNode(output, wireDocument, true);
    return output.toString();
  }

  /** Returns sha256:<64 lowercase hex> for a parsed wire document. */
  public String checksum(JsonNode wireDocument) {
    return "sha256:" + sha256(canonicalJson(wireDocument));
  }

  /** Parses JSON without converting numeric values through double. */
  public JsonNode parseWireJson(String wireJson) {
    if (wireJson == null) {
      throw new IllegalArgumentException("wire JSON must not be null");
    }
    try (JsonParser parser = mapper.getFactory().createParser(wireJson)) {
      return parser.readValueAsTree();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Invalid tick-batch JSON", exception);
    }
  }

  public String checksumForWireJson(String wireJson) {
    return checksum(parseWireJson(wireJson));
  }

  private void writeNode(StringBuilder output, JsonNode value, boolean root) {
    if (value == null || value.isNull()) {
      output.append("null");
      return;
    }
    if (value.isObject()) {
      output.append('{');
      boolean first = true;
      Map<String, JsonNode> sorted = new TreeMap<>();
      value
          .fields()
          .forEachRemaining(
              entry -> {
                if (!(root && "payloadChecksum".equals(entry.getKey()))) {
                  sorted.put(entry.getKey(), entry.getValue());
                }
              });
      for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
        if (!first) {
          output.append(',');
        }
        first = false;
        writeString(output, entry.getKey());
        output.append(':');
        writeNode(output, entry.getValue(), false);
      }
      output.append('}');
      return;
    }
    if (value.isArray()) {
      output.append('[');
      boolean first = true;
      for (JsonNode child : value) {
        if (!first) {
          output.append(',');
        }
        first = false;
        writeNode(output, child, false);
      }
      output.append(']');
      return;
    }
    if (value.isTextual()) {
      writeString(output, value.textValue());
    } else if (value.isBoolean()) {
      output.append(value.booleanValue() ? "true" : "false");
    } else if (value.isNumber()) {
      output.append(canonicalNumber(value));
    } else {
      writeString(output, value.asText());
    }
  }

  private String canonicalNumber(JsonNode value) {
    final BigDecimal decimal;
    try {
      decimal = value.decimalValue();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Invalid JSON number", exception);
    }
    if (decimal.signum() == 0) {
      return "0";
    }
    return decimal.stripTrailingZeros().toPlainString();
  }

  /** Writes the protocol's string form without delegating escaping to Jackson. */
  private static void writeString(StringBuilder output, String value) {
    if (value == null) {
      output.append("null");
      return;
    }
    output.append('"');
    for (int index = 0; index < value.length(); ) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException("Malformed surrogate in JSON string");
        }
        output.appendCodePoint(Character.toCodePoint(character, value.charAt(index + 1)));
        index += 2;
        continue;
      }
      if (Character.isLowSurrogate(character)) {
        throw new IllegalArgumentException("Malformed surrogate in JSON string");
      }
      switch (character) {
        case '"' -> output.append("\\\"");
        case '\\' -> output.append("\\\\");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        case '\n' -> output.append("\\n");
        case '\r' -> output.append("\\r");
        case '\t' -> output.append("\\t");
        case '\u2028' -> output.append("\\u2028");
        case '\u2029' -> output.append("\\u2029");
        default -> {
          if (character < 0x20) {
            output.append("\\u00");
            output.append(Character.forDigit((character >>> 4) & 0xf, 16));
            output.append(Character.forDigit(character & 0xf, 16));
          } else {
            output.append(character);
          }
        }
      }
      index++;
    }
    output.append('"');
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        result.append(String.format("%02x", item & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for tick-batch/v2", exception);
    }
  }
}
