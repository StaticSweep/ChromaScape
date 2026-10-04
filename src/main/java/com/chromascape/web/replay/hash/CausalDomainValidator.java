package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Validates the shared schema before causal domain hashing; no absent provider defaults. */
public final class CausalDomainValidator {
  private static final JsonNode SCHEMA = load();

  private CausalDomainValidator() {}

  private static JsonNode load() {
    try (var in =
        CausalDomainValidator.class.getResourceAsStream(
            "/replay/contracts/causal-domain-schema-v2.json")) {
      if (in == null) {
        throw new IllegalStateException("causal schema missing");
      }
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Applies required nested fields, ranges, absence and identity uniqueness rules. */
  public static void validate(JsonNode domains) {
    check(domains, SCHEMA, "domains", 0, new int[] {100000});
  }

  private static void invalid(String path) {
    throw new Sc275Exception("PROJECTION_VALUE_INVALID", "invalid causal value at " + path);
  }

  private static void check(JsonNode value, JsonNode spec, String path, int depth, int[] budget) {
    if (--budget[0] < 0 || depth > 64 || value == null) {
      invalid(path);
    }
    if (value.isNull() && spec.path("nullable").asBoolean()) {
      return;
    }
    if (spec.has("enum")) {
      boolean match = false;
      for (JsonNode allowed : spec.get("enum")) {
        if (allowed.equals(value)) {
          match = true;
        }
      }
      if (!match) {
        invalid(path);
      }
    }
    switch (spec.path("type").asText()) {
      case "integer" -> {
        if (!value.isIntegralNumber()
            || !value.canConvertToLong()
            || value.longValue() < -CausalCanonicalJson.SAFE_INTEGER_LIMIT
            || value.longValue() > CausalCanonicalJson.SAFE_INTEGER_LIMIT
            || (spec.has("min") && value.longValue() < spec.get("min").longValue())) {
          invalid(path);
        }
      }
      case "string" -> {
        if (!value.isTextual()
            || (spec.has("pattern") && !value.textValue().matches(spec.get("pattern").asText()))) {
          invalid(path);
        }
      }
      case "boolean" -> {
        if (!value.isBoolean()) {
          invalid(path);
        }
      }
      case "scalar" -> {
        if (!(value.isTextual() || value.isNumber() || value.isBoolean())) {
          invalid(path);
        }
        CausalCanonicalJson.canonicalize(value);
      }
      case "json" -> {
        if (value.isContainerNode()) {
          if (value.size() > budget[0]) {
            invalid(path);
          }
          for (JsonNode child : value) {
            check(child, spec, path + "/json", depth + 1, budget);
          }
        } else {
          CausalCanonicalJson.canonicalize(value);
        }
      }
      case "array" -> {
        if (!value.isArray()
            || value.size() > budget[0]
            || (spec.has("length") && value.size() != spec.get("length").asInt())
            || (spec.has("maxItems") && value.size() > spec.get("maxItems").asInt())) {
          invalid(path);
        }
        Set<JsonNode> ids = new HashSet<>();
        int i = 0;
        for (JsonNode entry : value) {
          check(entry, spec.get("items"), path + "/" + i++, depth + 1, budget);
          if (spec.has("unique") && !ids.add(entry.get(spec.get("unique").asText()))) {
            invalid(path);
          }
        }
      }
      default -> {
        if (!value.isObject()) {
          invalid(path);
        }
        if (spec.path("type").asText().equals("object")) {
          Iterator<String> fields = spec.get("fields").fieldNames();
          while (fields.hasNext()) {
            String field = fields.next();
            if (!value.has(field)) {
              boolean optional = false;
              for (JsonNode entry : spec.path("optional")) {
                if (entry.asText().equals(field)) {
                  optional = true;
                }
              }
              if (optional) {
                continue;
              }
              throw new Sc275Exception(
                  "PROJECTION_STATE_INCOMPLETE",
                  "required causal state missing at " + path + "/" + field);
            }
            check(
                value.get(field),
                spec.get("fields").get(field),
                path + "/" + field,
                depth + 1,
                budget);
          }
          Iterator<String> names = value.fieldNames();
          while (names.hasNext()) {
            if (!spec.get("fields").has(names.next())) {
              invalid(path);
            }
          }
        } else {
          Iterator<String> names = value.fieldNames();
          while (names.hasNext()) {
            String field = names.next();
            check(value.get(field), spec.get("values"), path + "/" + field, depth + 1, budget);
          }
        }
      }
    }
  }
}
