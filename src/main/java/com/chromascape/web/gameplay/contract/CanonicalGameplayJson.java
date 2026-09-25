package com.chromascape.web.gameplay.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Stable canonicalization used for envelope, scope, fixture, and evidence digests. */
@Component
public final class CanonicalGameplayJson {
  private final ObjectMapper objectMapper;

  public CanonicalGameplayJson(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String digest(Object value) {
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(canonicalize(objectMapper.valueToTree(value)));
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to create canonical gameplay digest", exception);
    }
  }

  public byte[] canonicalBytes(Object value) {
    try {
      return objectMapper
          .writeValueAsString(canonicalize(objectMapper.valueToTree(value)))
          .getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to canonicalize gameplay value", exception);
    }
  }

  private JsonNode canonicalize(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) {
      return node;
    }
    if (node.isArray()) {
      ArrayNode result = objectMapper.createArrayNode();
      node.forEach(item -> result.add(canonicalize(item)));
      return result;
    }
    ObjectNode result = objectMapper.createObjectNode();
    List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
    node.fields().forEachRemaining(fields::add);
    fields.sort(Comparator.comparing(Map.Entry::getKey));
    fields.forEach(field -> result.set(field.getKey(), canonicalize(field.getValue())));
    return result;
  }
}
