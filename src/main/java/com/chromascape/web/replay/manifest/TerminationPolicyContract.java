package com.chromascape.web.replay.manifest;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

/** Data-only declarations. Expectations and later observations are never policy sources. */
public final class TerminationPolicyContract {
  static final String SCHEMA = "xrsps-termination-policy/v1";
  private static final Set<String> CATEGORIES = Set.of("success", "failure", "stop");

  private TerminationPolicyContract() {}

  static ObjectNode defaults(ObjectMapper json) {
    ObjectNode policy = json.createObjectNode().put("schema", SCHEMA);
    for (String category : new String[] {"success", "failure", "stop"}) policy.putArray(category);
    return policy;
  }

  static ObjectNode fromSource(ObjectMapper json, JsonNode source) {
    JsonNode policy = source.path("contentSnapshot").path("termination");
    if (policy.isMissingNode()) return defaults(json);
    validate(policy);
    return ((ObjectNode) policy).deepCopy();
  }

  public static void validateManifest(JsonNode document) {
    JsonNode policy = document.path("termination");
    if (policy.isMissingNode()) {
      if (document
          .path("curation")
          .path("sourceCuration")
          .path("contentSnapshot")
          .has("termination")) invalid();
      return; // Legacy conditionless documents remain readable.
    }
    validate(policy);
    // Rehashing the outer document cannot sever the selected immutable source binding.
    JsonNode expected =
        fromSource(new ObjectMapper(), document.path("curation").path("sourceCuration"));
    if (!policy.equals(expected)) invalid();
    if (document.has("compilationEvidence")
        || "2.4.0".equals(document.path("compiler").path("version").asText())) {
      JsonNode source = document.path("curation").path("sourceCuration");
      boolean supplied = source.path("contentSnapshot").has("termination");
      String reference =
          supplied
              ? "curation:" + source.path("id").asText() + "/content"
              : "compiler:"
                  + document.path("compiler").path("version").asText()
                  + "/termination-policy/v1";
      provenance(
          policy,
          "/termination",
          document.path("compilationEvidence").path("fieldProvenance"),
          supplied,
          reference);
    }
  }

  private static void provenance(
      JsonNode value, String pointer, JsonNode fields, boolean supplied, String reference) {
    JsonNode source = fields.path(pointer);
    if (!pointer.equals(source.path("outputPointer").asText())
        || !(supplied ? "OPERATOR_SUPPLIED" : "DEFAULTED")
            .equals(source.path("provenance").asText())
        || !(supplied ? reference + pointer : reference).equals(source.path("sourceRef").asText()))
      invalid();
    if (value.isObject())
      value
          .fields()
          .forEachRemaining(
              field ->
                  provenance(
                      field.getValue(),
                      pointer + "/" + field.getKey(),
                      fields,
                      supplied,
                      reference));
    else if (value.isArray())
      for (int i = 0; i < value.size(); i++)
        provenance(value.get(i), pointer + "/" + i, fields, supplied, reference);
  }

  static boolean nonempty(JsonNode document) {
    for (String category : CATEGORIES)
      if (!document.path("termination").path(category).isEmpty()) return true;
    return false;
  }

  public static void validate(JsonNode policy) {
    shape(policy, "schema", "success", "failure", "stop");
    if (!SCHEMA.equals(policy.path("schema").asText())) invalid();
    Set<String> ids = new HashSet<>();
    int[] nodes = {0};
    for (String category : new String[] {"failure", "success", "stop"}) {
      JsonNode clauses = policy.path(category);
      if (!clauses.isArray() || clauses.size() > 32) invalid();
      for (JsonNode clause : clauses) {
        shape(clause, "id", "predicate");
        String id = clause.path("id").asText();
        if (!clause.path("id").isTextual()
            || id.length() > 64
            || !id.matches("[A-Za-z][A-Za-z0-9_.:-]*")
            || !ids.add(id)) invalid();
        predicate(clause.path("predicate"), 1, nodes);
      }
    }
  }

  private static void predicate(JsonNode node, int depth, int[] nodes) {
    bound(depth, nodes);
    switch (node.path("op").asText()) {
      case "all", "any" -> {
        shape(node, "op", "predicates");
        JsonNode children = node.path("predicates");
        if (!children.isArray() || children.isEmpty() || children.size() > 16) invalid();
        for (JsonNode child : children) predicate(child, depth + 1, nodes);
      }
      case "not" -> {
        shape(node, "op", "predicate");
        predicate(node.path("predicate"), depth + 1, nodes);
      }
      case "exists" -> {
        shape(node, "op", "pointer");
        pointer(node.path("pointer"));
      }
      case "some", "every" -> {
        shape(node, "op", "pointer", "predicate");
        pointer(node.path("pointer"));
        predicate(node.path("predicate"), depth + 1, nodes);
      }
      case "compare" -> {
        shape(node, "op", "comparison", "left", "right");
        if (!Set.of("eq", "ne", "gt", "gte", "lt", "lte")
            .contains(node.path("comparison").asText())) invalid();
        operand(node.path("left"), depth + 1, nodes);
        operand(node.path("right"), depth + 1, nodes);
      }
      default -> invalid();
    }
  }

  private static void operand(JsonNode node, int depth, int[] nodes) {
    bound(depth, nodes);
    switch (node.path("kind").asText()) {
      case "literal" -> {
        shape(node, "kind", "value");
        JsonNode value = node.path("value");
        if (!(value.isNull()
            || value.isBoolean()
            || value.isTextual() && value.asText().length() <= 256
            || value.isNumber()
                && Double.isFinite(value.asDouble())
                && Math.abs(value.asDouble()) <= 1e12)) invalid();
      }
      case "path" -> {
        shape(node, "kind", "pointer");
        pointer(node.path("pointer"));
      }
      case "count", "sum" -> {
        boolean sum = "sum".equals(node.path("kind").asText());
        Set<String> fields = new HashSet<>(Set.of("kind", "pointer"));
        if (sum) fields.add("valuePointer");
        if (node.has("where")) fields.add("where");
        shape(node, fields.toArray(String[]::new));
        pointer(node.path("pointer"));
        if (sum) pointer(node.path("valuePointer"));
        if (node.has("where")) predicate(node.path("where"), depth + 1, nodes);
      }
      default -> invalid();
    }
  }

  private static void pointer(JsonNode node) {
    if (!node.isTextual()) invalid();
    String value = node.asText();
    if (value.length() > 256 || !value.isEmpty() && !value.startsWith("/")) invalid();
    if (!value.isEmpty())
      for (String token : value.substring(1).split("/", -1)) {
        if (java.util.regex.Pattern.compile("~(?![01])").matcher(token).find()) invalid();
        String decoded = token.replace("~1", "/").replace("~0", "~");
        if (Set.of("__proto__", "prototype", "constructor").contains(decoded)) invalid();
      }
  }

  private static void bound(int depth, int[] nodes) {
    if (depth > 12 || ++nodes[0] > 512) invalid();
  }

  private static void shape(JsonNode node, String... fields) {
    if (!node.isObject() || node.size() != fields.length) invalid();
    for (String field : fields) if (!node.has(field)) invalid();
  }

  private static void invalid() {
    throw ReplayException.invalid(
        ReplayErrorCode.VALIDATION_ERROR,
        "TERMINATION_POLICY_INVALID: policy must be bounded data from the exact selected curation"
            + " or explicit default");
  }
}
