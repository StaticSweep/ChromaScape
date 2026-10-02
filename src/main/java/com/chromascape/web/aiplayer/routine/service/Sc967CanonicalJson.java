package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Dedicated SC-967 canonical JSON for the signed certified-candidate domain.
 *
 * <p>This is intentionally separate from the generic {@link
 * com.chromascape.web.aiplayer.contract.CanonicalJsonHasher} used for identity hashing elsewhere.
 * The SC-967 rule is fixed and shared with the Go registry and the Micro6 loader:
 *
 * <ul>
 *   <li>object members recursively sorted by UTF-8 code point;
 *   <li>array order preserved;
 *   <li>UTF-8 serialization with no insignificant whitespace;
 *   <li><strong>no string newline rewriting</strong> — CR/LF bytes inside string values are
 *       preserved and escaped verbatim, so {@code "\r\n"} and {@code "\n"} hash differently;
 *   <li>duplicate object keys rejected;
 *   <li>only integers within signed 64-bit range are allowed in the signed domain.
 * </ul>
 */
public final class Sc967CanonicalJson {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private Sc967CanonicalJson() {}

  /** Parses UTF-8 JSON, rejecting duplicate keys, floats, and out-of-range integers. */
  public static JsonNode parse(byte[] utf8Json) {
    try {
      JsonNode node = MAPPER.readTree(utf8Json);
      if (node == null) {
        throw new IllegalArgumentException("JSON document is empty");
      }
      validateNumbers(node);
      validateUnicode(node);
      return node;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid SC-967 JSON", e);
    }
  }

  public static String canonical(JsonNode node) {
    validateNumbers(node);
    validateUnicode(node);
    JsonNode copy = normalize(node);
    try {
      return MAPPER.writeValueAsString(copy);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to serialize SC-967 canonical JSON", e);
    }
  }

  public static String hash(JsonNode node) {
    byte[] bytes = canonical(node).getBytes(StandardCharsets.UTF_8);
    return "sha256:" + HexFormat.of().formatHex(digest(bytes));
  }

  public static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void validateNumbers(JsonNode node) {
    if (node == null) {
      return;
    }
    if (node.isFloatingPointNumber() || (node.isNumber() && !node.canConvertToLong())) {
      throw new IllegalArgumentException(
          "Floating-point or out-of-range SC-967 number is not allowed");
    }
    if (node.isContainerNode()) {
      node.elements().forEachRemaining(Sc967CanonicalJson::validateNumbers);
    }
  }

  private static JsonNode normalize(JsonNode node) {
    if (node == null || node.isNull() || node.isBoolean() || node.isNumber() || node.isTextual()) {
      // No string newline rewriting: textual values are preserved verbatim.
      return node;
    }
    if (node.isArray()) {
      ArrayNode array = JsonNodeFactory.instance.arrayNode();
      node.elements().forEachRemaining(child -> array.add(normalize(child)));
      return array;
    }
    ObjectNode object = JsonNodeFactory.instance.objectNode();
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    keys.sort(Sc967CanonicalJson::compareUtf8);
    for (String key : keys) {
      object.set(key, normalize(node.get(key)));
    }
    return object;
  }

  static JsonNode readTree(byte[] bytes) throws IOException {
    return MAPPER.readTree(bytes);
  }

  /** UTF-8 lexicographic order is the wire-compatible Unicode scalar order for valid strings. */
  private static int compareUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int limit = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < limit; index++) {
      int comparison =
          Integer.compare(
              Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private static void validateUnicode(JsonNode node) {
    if (node == null) return;
    if (node.isTextual()) validateUnicodeString(node.textValue());
    if (node.isObject()) {
      node.fieldNames().forEachRemaining(Sc967CanonicalJson::validateUnicodeString);
    }
    if (node.isContainerNode())
      node.elements().forEachRemaining(Sc967CanonicalJson::validateUnicode);
  }

  private static void validateUnicodeString(String value) {
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
