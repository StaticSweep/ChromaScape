package com.chromascape.web.aiplayer.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Validates the actual Micro6 fields/depends_on/options_by_dependency contract, fail closed. */
public final class TaskSettingsValidator {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> TYPES =
      Set.of("integer", "number", "boolean", "string", "select", "multi_select");

  private TaskSettingsValidator() {}

  /** Rejects unsupported field schemas, ranges and dependency cycles. */
  public static void schema(JsonNode schema) {
    if (schema == null
        || !schema.isObject()
        || !schema.path("fields").isArray()
        || schema.path("fields").isEmpty()
        || schema.path("fields").size() > 200) {
      invalid("Missing or unsupported settings fields.");
    }
    Set<String> keys = new HashSet<>();
    for (JsonNode f : schema.path("fields")) {
      String key = f.path("key").asText();
      if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,79}")
          || !keys.add(key)
          || !TYPES.contains(f.path("type").asText())) {
        invalid("Invalid, duplicate or unsupported field.");
      }
      if (f.has("min") && !f.path("min").isNumber() || f.has("max") && !f.path("max").isNumber()) {
        invalid("Invalid range.");
      }
      if (f.has("min")
          && f.has("max")
          && f.path("min").decimalValue().compareTo(f.path("max").decimalValue()) > 0) {
        invalid("Inverted range.");
      }
      if (f.has("options") && (!f.path("options").isArray() || f.path("options").size() > 1000)) {
        invalid("Invalid options.");
      }
      if (Set.of("select", "multi_select").contains(f.path("type").asText())
          && !f.path("options").isArray()
          && !f.path("options_by_dependency").isObject()) {
        invalid("Select options are unavailable.");
      }
      if (f.has("options_by_dependency")
          && (!f.path("options_by_dependency").isObject() || !f.hasNonNull("depends_on"))) {
        invalid("Invalid dependent options.");
      }
    }
    for (JsonNode f : schema.path("fields")) {
      if (f.has("depends_on")
          && (!keys.contains(f.path("depends_on").asText())
              || f.path("key").asText().equals(f.path("depends_on").asText()))) {
        invalid("Missing or circular dependency.");
      }
      Set<String> seen = new HashSet<>();
      JsonNode current = f;
      while (current.has("depends_on")) {
        if (!seen.add(current.path("key").asText())) {
          invalid("Circular dependency.");
        }
        String parent = current.path("depends_on").asText();
        current = null;
        for (JsonNode candidate : schema.path("fields")) {
          if (candidate.path("key").asText().equals(parent)) {
            current = candidate;
          }
        }
        if (current == null) {
          invalid("Missing dependency.");
        }
      }
      if (f.has("options_by_dependency")) {
        for (JsonNode branch : f.path("options_by_dependency")) {
          if (!branch.isArray() || branch.size() > 1000) {
            invalid("Invalid dependent option branch.");
          }
        }
      }
    }
    try {
      validateValues(schema, JSON.createObjectNode());
    } catch (CatalogProblem failure) {
      for (JsonNode field : schema.path("fields")) {
        if (field.has("default") && failure.errors().containsKey(field.path("key").asText())) {
          invalid("Invalid default for " + field.path("key").asText() + ".");
        }
      }
    }
  }

  /** Resolves defaults and validates all active settings against their schema. */
  public static ObjectNode validate(JsonNode schema, JsonNode provided) {
    schema(schema);
    return validateValues(schema, provided);
  }

  private static ObjectNode validateValues(JsonNode schema, JsonNode provided) {
    if (provided == null || !provided.isObject() || provided.toString().length() > 65536) {
      throw new CatalogProblem(422, "INVALID_SETTINGS", "Settings must be a bounded JSON object.");
    }
    Map<String, String> errors = new LinkedHashMap<>();
    Set<String> keys = new HashSet<>();
    ObjectNode values = ((ObjectNode) provided).deepCopy();
    for (JsonNode f : schema.path("fields")) {
      String key = f.path("key").asText();
      keys.add(key);
      if (!values.has(key) && f.has("default")) {
        values.set(key, f.get("default").deepCopy());
      }
    }
    provided
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!keys.contains(key)) {
                errors.put(key, "Unknown setting.");
              }
            });
    for (JsonNode f : schema.path("fields")) {
      String key = f.path("key").asText();
      JsonNode value = values.get(key);
      if (f.has("options_by_dependency") && !options(f, values).isArray() && value != null) {
        errors.put(key, "Choose a valid dependency before this setting.");
        continue;
      }
      if (!visible(f, values)) {
        if (provided.has(key) && !provided.get(key).equals(f.get("default"))) {
          errors.put(key, "Setting is inactive for this dependency.");
        }
        values.remove(key);
        continue;
      }
      if (value == null || value.isNull()) {
        if (value != null && value.isNull()) {
          errors.put(key, "Null is not a valid setting.");
        } else if (f.path("required").asBoolean()) {
          errors.put(key, "Required setting.");
        }
        continue;
      }
      String type = f.path("type").asText();
      boolean good =
          switch (type) {
            case "integer" -> value.isIntegralNumber()
                && value.canConvertToLong()
                && Math.abs(value.doubleValue()) <= 9007199254740991d;
            case "number" -> value.isNumber()
                && Double.isFinite(value.doubleValue())
                && Math.abs(value.doubleValue()) <= 9007199254740991d;
            case "boolean" -> value.isBoolean();
            case "string", "select" -> value.isTextual() && value.asText().length() <= 4096;
            case "multi_select" -> value.isArray() && value.size() <= 100;
            default -> false;
          };
      if (!good) {
        errors.put(key, "Expected " + type + ".");
        continue;
      }
      if (value.isNumber()
          && ((f.has("min") && value.decimalValue().compareTo(f.path("min").decimalValue()) < 0)
              || (f.has("max")
                  && value.decimalValue().compareTo(f.path("max").decimalValue()) > 0))) {
        errors.put(key, "Value is outside the permitted range.");
      }
      if ((type.equals("select")
              || type.equals("string") && (f.has("options") || f.has("options_by_dependency")))
          && !contains(options(f, values), value)) {
        errors.put(key, "Choose a permitted option for the dependency.");
      }
      if (type.equals("multi_select")) {
        Set<String> unique = new HashSet<>();
        for (JsonNode v : value) {
          if (!contains(options(f, values), v) || !unique.add(v.toString())) {
            errors.put(key, "Choose unique permitted options.");
          }
        }
      }
    }
    if (!errors.isEmpty()) {
      throw new CatalogProblem(
          422, "INVALID_SETTINGS", "Correct the highlighted settings.", errors);
    }
    return values;
  }

  /** Checks whether the selected dependency branch enables this field. */
  public static boolean visible(JsonNode field, JsonNode values) {
    if (!field.has("options_by_dependency")) {
      return true;
    }
    JsonNode options = options(field, values);
    return options.isArray() && !options.isEmpty();
  }

  private static JsonNode options(JsonNode field, JsonNode values) {
    if (field.has("options_by_dependency")) {
      return field
          .path("options_by_dependency")
          .path(values.path(field.path("depends_on").asText()).asText());
    }
    return field.path("options");
  }

  private static boolean contains(JsonNode list, JsonNode value) {
    for (JsonNode option : list) {
      if (option.equals(value) || option.isObject() && option.path("value").equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static void invalid(String message) {
    throw new CatalogProblem(422, "INVALID_SCHEMA", message);
  }
}
