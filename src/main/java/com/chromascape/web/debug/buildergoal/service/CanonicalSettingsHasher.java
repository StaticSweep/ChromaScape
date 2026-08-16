package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Deterministic settings hash shared by ChromaScape's publish metadata and executor receipts. */
public final class CanonicalSettingsHasher {

  private CanonicalSettingsHasher() {}

  public static String sha256(ObjectMapper objectMapper, Object settings) {
    try {
      JsonNode node = objectMapper.valueToTree(settings == null ? Map.of() : settings);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(canonicalJson(node).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
        hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
        hex.append(Character.forDigit(value & 0x0f, 16));
      }
      return "sha256:" + hex;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  public static String canonicalJson(JsonNode node) {
    if (node == null || node.isNull()) {
      return "null";
    }
    if (node.isObject()) {
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      StringBuilder out = new StringBuilder("{");
      for (int i = 0; i < names.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        String name = names.get(i);
        out.append(quote(normalize(name))).append(':').append(canonicalJson(node.get(name)));
      }
      return out.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder out = new StringBuilder("[");
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        out.append(canonicalJson(node.get(i)));
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

  private static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
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
    return out.append('"').toString();
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    return Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace("\r\n", "\n")
        .replace('\r', '\n');
  }
}
