package com.chromascape.web.aiplayer.routine.source;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Canonical JSON for the identity-bearing SC-810 source contract.
 *
 * <p>This is intentionally separate from the application's historical generic JSON hasher. Source
 * identity preserves string contents byte-for-byte, including line endings, while still rejecting
 * malformed UTF-8, duplicate keys, trailing values, floating-point numbers, and invalid Unicode.
 * Object keys are ordered by their UTF-8 bytes and array order is preserved.
 */
public final class SourceManifestCanonicalJson {
  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final Comparator<String> UTF8_ORDER = SourceManifestCanonicalJson::compareUtf8;

  private SourceManifestCanonicalJson() {}

  public static JsonNode parse(byte[] utf8Json) {
    if (utf8Json == null) throw new IllegalArgumentException("JSON document is null");
    try {
      String json = strictUtf8(utf8Json);
      JsonParser parser = MAPPER.getFactory().createParser(json);
      try {
        JsonNode node = MAPPER.readTree(parser);
        if (parser.nextToken() != null) {
          throw new IllegalArgumentException("JSON document contains trailing values");
        }
        if (node == null) throw new IllegalArgumentException("JSON document is empty");
        validateNumbers(node);
        return normalize(node);
      } finally {
        parser.close();
      }
    } catch (Exception exception) {
      if (exception instanceof IllegalArgumentException illegal) throw illegal;
      throw new IllegalArgumentException("Invalid source manifest JSON", exception);
    }
  }

  public static String canonical(JsonNode node) {
    return canonical(node, Set.of());
  }

  public static String canonical(JsonNode node, String excludedRootField) {
    return canonical(node, excludedRootField == null ? Set.of() : Set.of(excludedRootField));
  }

  public static String canonical(JsonNode node, Set<String> excludedRootFields) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("canonical source JSON root must be an object");
    }
    validateNumbers(node);
    JsonNode normalized = normalize(node);
    ObjectNode object = (ObjectNode) normalized;
    if (excludedRootFields != null) {
      excludedRootFields.forEach(object::remove);
    }
    try {
      return MAPPER.writeValueAsString(object);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Unable to serialize canonical source JSON", exception);
    }
  }

  public static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static JsonNode normalize(JsonNode node) {
    if (node == null || node.isNull() || node.isBoolean() || node.isNumber()) return node;
    if (node.isTextual()) {
      validateUnicode(node.textValue());
      return JsonNodeFactory.instance.textNode(node.textValue());
    }
    if (node.isArray()) {
      ArrayNode array = JsonNodeFactory.instance.arrayNode();
      node.elements().forEachRemaining(child -> array.add(normalize(child)));
      return array;
    }
    ObjectNode object = JsonNodeFactory.instance.objectNode();
    List<String> keys = new ArrayList<>();
    node.fieldNames()
        .forEachRemaining(
            key -> {
              validateUnicode(key);
              keys.add(key);
            });
    keys.sort(UTF8_ORDER);
    for (String key : keys) object.set(key, normalize(node.get(key)));
    return object;
  }

  private static void validateNumbers(JsonNode node) {
    if (node == null) return;
    if (node.isFloatingPointNumber() || (node.isNumber() && !node.canConvertToLong())) {
      throw new IllegalArgumentException(
          "Floating-point or out-of-range source number is not allowed");
    }
    if (node.isTextual()) validateUnicode(node.textValue());
    if (node.isContainerNode()) {
      node.elements().forEachRemaining(SourceManifestCanonicalJson::validateNumbers);
    }
  }

  private static void validateUnicode(String value) {
    if (value == null) throw new IllegalArgumentException("source JSON string is null");
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
          throw new IllegalArgumentException("Invalid Unicode surrogate sequence");
        }
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException("Invalid Unicode surrogate sequence");
      }
    }
  }

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

  private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }
}
