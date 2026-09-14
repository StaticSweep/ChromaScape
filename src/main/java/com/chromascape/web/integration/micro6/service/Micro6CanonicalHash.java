package com.chromascape.web.integration.micro6.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonical JSON hashing shared between ChromaScape and the Micro6 executor.
 *
 * <p>This is a faithful port of the Micro6 {@code CanonicalJson} algorithm so ChromaScape can
 * recompute {@code catalog_hash}, per-task {@code settings_schema_hash}, and {@code
 * schema_bundle_hash} and compare them against values attested by the executor. A single byte of
 * divergence is a permanent contract error and the payload must never enter the cache.
 */
final class Micro6CanonicalHash {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SHA_256 = "SHA-256";
  private static final String DEFAULT_CAPABILITY_VERSION = "micro6-executor/v1";
  private static final Set<String> DEFAULT_EXCLUSIONS =
      Set.of("generated_at", "source_timestamp", "ingest_timestamp", "run_id", "attempt_id");
  private static final String SCHEMA_STATE_AVAILABLE = "AVAILABLE";
  private static final String SCHEMA_STATE_NOT_APPLICABLE = "NOT_APPLICABLE";
  private static final String SCHEMA_STATE_ERROR = "ERROR";

  private Micro6CanonicalHash() {}

  static String sha256(JsonNode node) {
    return sha256(canonicalize(node));
  }

  static String sha256WithPrefix(JsonNode node) {
    return "sha256:" + sha256(node);
  }

  static String sha256WithPrefix(Object value) {
    return "sha256:" + sha256(toNode(value));
  }

  static String sha256(String canonical) {
    return hex(digest(canonical.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Canonical task summary fields used by {@link #catalogHash}. These match Micro6's stable
   * catalog identity projection: schema-bearing fields are excluded so lightweight, full, and
   * health attestations retain one catalog hash while schema changes move the bundle hash.
   */
  static final Set<String> CATALOG_HASH_TASK_FIELDS =
      Set.of(
          "key",
          "task_key",
          "name",
          "display_name",
          "panel_display_name",
          "category",
          "category_family",
          "enabled",
          "runnable",
          "disabled_reason",
          "task_type",
          "type",
          "search_aliases",
          "stability_tier",
          "tags",
          "membership",
          "primary_skill",
          "description");

  /**
   * Recomputes the canonical catalog hash over a parsed catalog payload. The stable map includes
   * only {@code catalog_version}, {@code capability_version}, sorted canonical task summaries, and
   * sorted normalized aliases — mirroring Micro6 exactly.
   */
  static String catalogHash(JsonNode payload, String defaultCapabilityVersion) {
    String catalogVersion =
        firstNonBlank(text(payload.get("catalog_version")), text(payload.get("version")), "2");
    String capabilityVersion =
        firstNonBlank(
            text(payload.get("capability_version")),
            defaultCapabilityVersion,
            DEFAULT_CAPABILITY_VERSION);

    List<JsonNode> tasks = new ArrayList<>();
    JsonNode tasksNode = payload.get("tasks");
    if (tasksNode != null && tasksNode.isArray()) {
      tasksNode.forEach(node -> tasks.add(node));
    }
    List<Map<String, Object>> summaryTasks = new ArrayList<>();
    for (JsonNode task : tasks) {
      Map<String, Object> summary = new LinkedHashMap<>();
      for (String key : CATALOG_HASH_TASK_FIELDS) {
        if (task != null && task.has(key)) {
          summary.put(key, nodeValue(task.get(key)));
        }
      }
      summaryTasks.add(summary);
    }
    summaryTasks.sort(Comparator.comparing(row -> String.valueOf(row.get("task_key"))));

    List<JsonNode> aliases = new ArrayList<>();
    JsonNode aliasesNode = payload.get("aliases");
    if (aliasesNode != null && aliasesNode.isArray()) {
      aliasesNode.forEach(node -> aliases.add(node));
    }
    List<Map<String, Object>> summaryAliases = new ArrayList<>();
    for (JsonNode alias : aliases) {
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("alias", nodeValue(alias.get("alias")));
      summary.put("normalized_alias", nodeValue(alias.get("normalized_alias")));
      summary.put("task_key", nodeValue(alias.get("task_key")));
      summaryAliases.add(summary);
    }
    summaryAliases.sort(Comparator.comparing(row -> String.valueOf(row.get("normalized_alias"))));

    Map<String, Object> stable = new LinkedHashMap<>();
    stable.put("catalog_version", catalogVersion);
    stable.put("capability_version", capabilityVersion);
    stable.put("tasks", summaryTasks);
    stable.put("aliases", summaryAliases);
    return sha256WithPrefix(stable);
  }

  /**
   * Recomputes the schema bundle exactly as {@code CatalogV2Publication.schemaBundleHash}: only
   * tasks with an object {@code args_schema} participate, and their published schema fields are
   * hashed without adding lifecycle state or diagnostic fields.
   */
  static String schemaBundleHash(JsonNode payload, String catalogHash) {
    if (catalogHash == null) {
      return null;
    }
    JsonNode tasksNode = payload.get("tasks");
    if (tasksNode == null || !tasksNode.isArray() || tasksNode.isEmpty()) {
      return null;
    }
    List<Map<String, Object>> schemaTasks = new ArrayList<>();
    for (JsonNode task : tasksNode) {
      if (task == null || task.isNull()) {
        continue;
      }
      JsonNode argsSchema = task.get("args_schema");
      if (argsSchema == null || !argsSchema.isObject()) {
        continue;
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("task_key", nodeValue(task.get("task_key")));
      entry.put("settings_schema_version", nodeValue(task.get("settings_schema_version")));
      entry.put("settings_schema_hash", nodeValue(task.get("settings_schema_hash")));
      // Micro6 publishes a canonical schema already; applying the same canonicalization again is
      // idempotent and also keeps verification deterministic for equivalent JSON field ordering.
      entry.put("args_schema", nodeValue(canonicalizeSchema(argsSchema)));
      schemaTasks.add(entry);
    }
    if (schemaTasks.isEmpty()) {
      return null;
    }
    schemaTasks.sort(Comparator.comparing(row -> String.valueOf(row.get("task_key"))));

    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("catalog_hash", catalogHash);
    bundle.put("tasks", schemaTasks);
    return sha256WithPrefix(bundle);
  }

  /**
   * Recomputes a per-task settings schema hash over the canonicalized args schema. The published
   * schema is already canonical; canonicalizing again is idempotent, so this must match the
   * executor's {@code settings_schema_hash}.
   */
  static String settingsSchemaHash(JsonNode argsSchema) {
    if (argsSchema == null || argsSchema.isNull()) {
      return null;
    }
    return sha256WithPrefix(canonicalizeSchema(argsSchema));
  }

  /** Canonical JSON serialization with sorted object keys and Micro6-compatible escaping. */
  private static String canonicalize(JsonNode node) {
    if (node == null || node.isNull()) {
      return "null";
    }
    if (node.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String key = normalize(entry.getKey());
        if (key == null || DEFAULT_EXCLUSIONS.contains(key)) {
          continue;
        }
        sorted.put(key, entry.getValue());
      }
      StringBuilder out = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append(quote(entry.getKey())).append(':').append(canonicalize(entry.getValue()));
      }
      return out.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder out = new StringBuilder("[");
      boolean first = true;
      for (JsonNode element : node) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append(canonicalize(element));
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

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    return Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace("\r\n", "\n")
        .replace('\r', '\n');
  }

  private static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    return out.append('"').toString();
  }

  /** Canonicalized schema structure for hashing, mirroring Micro6. */
  private static JsonNode canonicalizeSchema(JsonNode node) {
    if (node == null || !node.isObject()) {
      return node;
    }
    ObjectNode out = MAPPER.createObjectNode();
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    keys.sort(Comparator.naturalOrder());
    for (String key : keys) {
      JsonNode value = node.get(key);
      if (("fields".equals(key) || "properties".equals(key)) && value != null && value.isArray()) {
        List<JsonNode> fields = new ArrayList<>();
        for (JsonNode rawField : value) {
          fields.add(canonicalizeField(rawField));
        }
        fields.sort(Comparator.comparing(f -> f.has("key") ? f.get("key").asText("") : ""));
        ArrayNode fieldsArray = MAPPER.createArrayNode();
        fields.forEach(fieldsArray::add);
        out.set(key, fieldsArray);
      } else {
        out.set(key, canonicalizeValue(value));
      }
    }
    return out;
  }

  private static JsonNode canonicalizeField(JsonNode field) {
    if (field == null || !field.isObject()) {
      return field;
    }
    ObjectNode out = MAPPER.createObjectNode();
    List<String> keys = new ArrayList<>();
    field.fieldNames().forEachRemaining(keys::add);
    keys.sort(Comparator.naturalOrder());
    for (String key : keys) {
      JsonNode value = field.get(key);
      if (("options".equals(key) || "required".equals(key)) && value != null && value.isArray()) {
        List<JsonNode> sorted = new ArrayList<>();
        value.forEach(item -> sorted.add(canonicalizeValue(item)));
        sorted.sort(Comparator.comparing(Micro6CanonicalHash::sortableValue));
        ArrayNode array = MAPPER.createArrayNode();
        sorted.forEach(array::add);
        out.set(key, array);
      } else if ("options_by_dependency".equals(key) && value != null && value.isObject()) {
        ObjectNode branches = MAPPER.createObjectNode();
        List<String> branchKeys = new ArrayList<>();
        value.fieldNames().forEachRemaining(branchKeys::add);
        branchKeys.sort(Comparator.naturalOrder());
        for (String branchKey : branchKeys) {
          List<JsonNode> sortedOptions = new ArrayList<>();
          JsonNode branchValue = value.get(branchKey);
          if (branchValue != null && branchValue.isArray()) {
            branchValue.forEach(option -> sortedOptions.add(canonicalizeValue(option)));
          }
          sortedOptions.sort(Comparator.comparing(Micro6CanonicalHash::sortableValue));
          ArrayNode array = MAPPER.createArrayNode();
          sortedOptions.forEach(array::add);
          branches.set(branchKey, array);
        }
        out.set(key, branches);
      } else {
        out.set(key, canonicalizeValue(value));
      }
    }
    return out;
  }

  private static JsonNode canonicalizeValue(JsonNode value) {
    if (value == null) {
      return null;
    }
    if (value.isObject()) {
      return canonicalizeSchema(value);
    }
    if (value.isArray()) {
      ArrayNode out = MAPPER.createArrayNode();
      value.forEach(item -> out.add(canonicalizeValue(item)));
      return out;
    }
    return value;
  }

  private static String sortableValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    return node.asText("");
  }

  private static Object nodeValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isObject()) {
      Map<String, Object> map = new LinkedHashMap<>();
      node.fields().forEachRemaining(entry -> map.put(entry.getKey(), nodeValue(entry.getValue())));
      return map;
    }
    if (node.isArray()) {
      List<Object> list = new ArrayList<>();
      node.forEach(item -> list.add(nodeValue(item)));
      return list;
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    return node.asText();
  }

  private static JsonNode toNode(Object value) {
    if (value == null) {
      return NullNode.getInstance();
    }
    if (value instanceof JsonNode) {
      return (JsonNode) value;
    }
    if (value instanceof Boolean) {
      return BooleanNode.valueOf((Boolean) value);
    }
    if (value instanceof String) {
      return TextNode.valueOf((String) value);
    }
    return MAPPER.convertValue(value, JsonNode.class);
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    String value = node.isTextual() ? node.textValue() : node.asText();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance(SHA_256).digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16));
      hex.append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }
}
