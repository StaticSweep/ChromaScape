package com.chromascape.web.replay.capability;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded, inert worker-declared method requirements. These are handshake evidence, not a native
 * world resolver, capture-completeness proof, or authorization to clear a compiler gap. In
 * particular, a profile name alone never proves container state, charges, targets or collision.
 */
@com.fasterxml.jackson.annotation.JsonInclude(
    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
public record ReplayMethodCapability(
    String method,
    String requiredProfile,
    List<String> requiredInitialStateFields,
    List<Integer> requiredNpcTypeIds,
    String option,
    List<String> actions) {

  private static final int MAX_ENTRIES = 64;
  private static final Set<String> FIELDS =
      Set.of(
          "method",
          "requiredProfile",
          "requiredInitialStateFields",
          "requiredNpcTypeIds",
          "option",
          "actions");

  public ReplayMethodCapability {
    requireText(method, "method");
    if (requiredProfile != null) requireText(requiredProfile, "requiredProfile");
    if (option != null) requireText(option, "option");
    requiredInitialStateFields =
        requiredInitialStateFields == null ? List.of() : List.copyOf(requiredInitialStateFields);
    requireUniqueBounded(requiredInitialStateFields, "requiredInitialStateFields");
    for (String field : requiredInitialStateFields) {
      requireText(field, "requiredInitialStateFields");
      if (!field.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")) {
        throw invalid("requiredInitialStateFields must contain inert dotted field names");
      }
    }
    requiredNpcTypeIds = requiredNpcTypeIds == null ? List.of() : List.copyOf(requiredNpcTypeIds);
    requireUniqueBounded(requiredNpcTypeIds, "requiredNpcTypeIds");
    for (Integer type : requiredNpcTypeIds) {
      if (type < 1 || type > 65534) throw invalid("requiredNpcTypeIds must be within 1..65534");
    }
    if (!CapabilityManifest.isValidSupportedActionSubset(actions)) {
      throw invalid("actions must be a non-empty duplicate-free worker action subset");
    }
    actions = List.copyOf(actions);
  }

  /** Missing is backward-compatible empty coverage. Explicit null or malformed is not missing. */
  public static List<ReplayMethodCapability> parse(JsonNode document) {
    JsonNode declarations = document.get("supportedMethods");
    if (declarations == null) return List.of();
    if (!declarations.isArray() || declarations.size() > MAX_ENTRIES) {
      throw invalid("must be an array of at most 64 methods");
    }
    List<ReplayMethodCapability> result = new ArrayList<>();
    for (int index = 0; index < declarations.size(); index++) {
      JsonNode declaration = declarations.get(index);
      try {
        if (!declaration.isObject()) throw invalid("entry must be an object");
        var names = declaration.fieldNames();
        while (names.hasNext()) {
          if (!FIELDS.contains(names.next())) throw invalid("entry contains an unsupported field");
        }
        result.add(
            new ReplayMethodCapability(
                text(declaration, "method", true),
                text(declaration, "requiredProfile", false),
                strings(declaration, "requiredInitialStateFields", false),
                npcTypes(declaration),
                text(declaration, "option", false),
                strings(declaration, "actions", true)));
      } catch (IllegalArgumentException invalid) {
        throw invalid("entry " + index + ": " + invalid.getMessage());
      }
    }
    JsonNode declaredActions = document.path("supportedActions");
    List<String> actions = new ArrayList<>();
    if (declaredActions.isArray()) {
      for (JsonNode action : declaredActions) {
        if (action.isTextual()) actions.add(action.textValue());
      }
    }
    validateDeclarations(result, actions);
    return List.copyOf(result);
  }

  /** Enforced for both raw handshakes and in-process capability construction. */
  public static void validateDeclarations(
      List<ReplayMethodCapability> methods, List<String> supportedActions) {
    if (methods.size() > MAX_ENTRIES) throw invalid("must contain at most 64 methods");
    Set<String> names = new HashSet<>();
    for (ReplayMethodCapability entry : methods) {
      if (!names.add(entry.method())) throw invalid("duplicate method " + entry.method());
      if (supportedActions == null || !supportedActions.containsAll(entry.actions())) {
        throw invalid("method " + entry.method() + " requires an action not advertised by worker");
      }
    }
  }

  private static String text(JsonNode declaration, String key, boolean required) {
    JsonNode value = declaration.get(key);
    if (value == null && !required) return null;
    if (value == null || !value.isTextual()) throw invalid(key + " must be a string");
    requireText(value.textValue(), key);
    return value.textValue();
  }

  private static List<String> strings(JsonNode declaration, String key, boolean required) {
    JsonNode value = declaration.get(key);
    if (value == null && !required) return List.of();
    if (value == null || !value.isArray() || value.size() > MAX_ENTRIES) {
      throw invalid(key + " must be an array of at most 64 strings");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode entry : value) {
      if (!entry.isTextual()) throw invalid(key + " entries must be strings");
      requireText(entry.textValue(), key);
      result.add(entry.textValue());
    }
    requireUniqueBounded(result, key);
    return result;
  }

  private static List<Integer> npcTypes(JsonNode declaration) {
    JsonNode value = declaration.get("requiredNpcTypeIds");
    if (value == null) return List.of();
    if (!value.isArray() || value.size() > MAX_ENTRIES) {
      throw invalid("requiredNpcTypeIds must be an array of at most 64 integers");
    }
    List<Integer> result = new ArrayList<>();
    for (JsonNode entry : value) {
      if (!entry.isIntegralNumber() || !entry.canConvertToInt()) {
        throw invalid("requiredNpcTypeIds entries must be bounded integers");
      }
      result.add(entry.intValue());
    }
    return result;
  }

  private static void requireText(String value, String key) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !value.equals(value.strip())
        || value.chars().anyMatch(Character::isISOControl)) {
      throw invalid(key + " must be non-blank unpadded text of at most 128 characters");
    }
  }

  private static void requireUniqueBounded(List<?> values, String key) {
    if (values.size() > MAX_ENTRIES || new HashSet<>(values).size() != values.size()) {
      throw invalid(key + " must contain at most 64 unique entries");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("supportedMethods " + message);
  }
}
