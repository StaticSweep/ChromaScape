package com.chromascape.web.aiplayer.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Cross-repository canonical JSON implementation for identity/content hashes. */
public final class CanonicalJsonHasher {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private CanonicalJsonHasher() {}

  public static JsonNode parse(byte[] utf8Json) {
    try {
      JsonNode node = MAPPER.readTree(utf8Json);
      if (node == null) throw new IllegalArgumentException("JSON document is empty");
      validateNumbers(node);
      return normalize(node);
    } catch (CurrentSliceContractException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid identity JSON", e);
    }
  }

  public static String canonical(JsonNode node) { return canonical(node, null); }

  public static String canonical(JsonNode node, String excludedRootField) {
    validateNumbers(node);
    JsonNode copy = normalize(node);
    if (excludedRootField != null && copy.isObject()) ((ObjectNode) copy).remove(excludedRootField);
    try {
      return MAPPER.writeValueAsString(copy);
    } catch (Exception e) { throw new IllegalArgumentException("Unable to serialize canonical JSON", e); }
  }

  public static String hash(JsonNode node, String excludedRootField) {
    byte[] bytes = canonical(node, excludedRootField).getBytes(StandardCharsets.UTF_8);
    return "sha256:" + HexFormat.of().formatHex(digest(bytes));
  }

  public static String hashUtf8(String json, String excludedRootField) {
    return hash(parse(json.getBytes(StandardCharsets.UTF_8)), excludedRootField);
  }

  public static byte[] digest(byte[] bytes) {
    try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
  }

  public static boolean isSha256(String value) { return value != null && value.matches("sha256:[0-9a-f]{64}"); }

  private static void validateNumbers(JsonNode node) {
    if (node == null) return;
    if (node.isFloatingPointNumber() || (node.isNumber() && !node.canConvertToLong())) {
      throw new IllegalArgumentException("Floating-point or out-of-range identity number is not allowed");
    }
    if (node.isTextual()) validateUnicode(node.textValue());
    if (node.isContainerNode()) {
      node.elements().forEachRemaining(CanonicalJsonHasher::validateNumbers);
      if (node.isObject()) node.fieldNames().forEachRemaining(CanonicalJsonHasher::validateUnicode);
    }
  }

  private static JsonNode normalize(JsonNode node) {
    if (node == null || node.isNull() || node.isBoolean() || node.isNumber()) return node;
    if (node.isTextual()) {
      return JsonNodeFactory.instance.textNode(node.textValue().replace("\r\n", "\n").replace("\r", "\n"));
    }
    if (node.isArray()) {
      ArrayNode array = JsonNodeFactory.instance.arrayNode();
      node.elements().forEachRemaining(child -> array.add(normalize(child)));
      return array;
    }
    ObjectNode object = JsonNodeFactory.instance.objectNode();
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    keys.sort(CanonicalJsonHasher::compareUtf8);
    for (String key : keys) object.set(key, normalize(node.get(key)));
    return object;
  }

  /** UTF-8 lexicographic order is stable across the Java, Go, and Micro6 implementations. */
  private static int compareUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int limit = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < limit; index++) {
      int comparison = Integer.compare(Byte.toUnsignedInt(leftBytes[index]),
          Byte.toUnsignedInt(rightBytes[index]));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
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
