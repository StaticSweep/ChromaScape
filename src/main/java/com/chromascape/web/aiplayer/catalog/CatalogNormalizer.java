package com.chromascape.web.aiplayer.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Projects Micro6 metadata without defining tasks, selecting clients or inferring live eligibility.
 */
public final class CatalogNormalizer {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SUMMARY =
      Set.of(
          "task_key",
          "display_name",
          "description",
          "category",
          "category_family",
          "task_type",
          "primary_skill",
          "membership",
          "stability_tier",
          "tags",
          "runnable",
          "disabled_reason",
          "target_enum",
          "requirements",
          "source_summary",
          "method_facts",
          "entry_class",
          "module",
          "commit_id",
          "build_id",
          "source_snapshot_id",
          "source_manifest_hash",
          "tree_id",
          "artifact_digest",
          "components",
          "settings_read_sites");

  private CatalogNormalizer() {}

  /** Projects canonical tasks and rejects ambiguous aliases. */
  public static ObjectNode normalize(JsonNode raw, boolean full) {
    if (!raw.isObject()
        || raw.toString().length() > 8388608
        || !raw.path("tasks").isArray()
        || raw.path("tasks").size() > 5000) {
      throw invalid("Catalog tasks are missing or exceed the supported bound.");
    }
    Map<String, ObjectNode> tasks = new LinkedHashMap<>();
    Map<String, String> aliases = new LinkedHashMap<>();
    for (JsonNode task : raw.path("tasks")) {
      String key = text(task, "task_key", text(task, "key", ""));
      String target = text(task, "target_enum", text(task, "canonical_task_key", key));
      if (("DISPLAY".equalsIgnoreCase(task.path("type").asText())
              || "DISPLAY".equalsIgnoreCase(task.path("task_type").asText()))
          || !target.equals(key)) {
        continue;
      }
      canonical(key);
      if (tasks.containsKey(key)) {
        throw invalid("Duplicate canonical task: " + key);
      }
      ObjectNode summary = JSON.createObjectNode();
      for (String field : SUMMARY) {
        if (task.has(field)) {
          summary.set(field, task.get(field).deepCopy());
        }
      }
      summary.put("task_key", key);
      summary.put("target_enum", target);
      summary.put("display_name", text(task, "display_name", text(task, "name", key)));
      summary.put("category", text(task, "category", "unknown"));
      summary.put(
          "category_family", text(task, "category_family", summary.path("category").asText()));
      summary.put("task_type", text(task, "task_type", text(task, "type", "UNKNOWN")));
      summary.put("live_eligibility", "UNKNOWN");
      summary.put(
          "settings_schema_state",
          full ? text(task, "settings_schema_state", "UNAVAILABLE") : "NOT_REQUESTED");
      if (full) {
        for (String field :
            Set.of("args_schema", "settings_schema_hash", "settings_schema_version")) {
          if (task.has(field)) {
            summary.set(field, task.get(field).deepCopy());
          }
        }
      }
      tasks.put(key, summary);
      alias(aliases, key, key);
      alias(aliases, summary.path("display_name").asText(), key);
      for (JsonNode value : task.path("search_aliases")) {
        alias(aliases, value.asText(), key);
      }
    }
    for (JsonNode task : raw.path("tasks")) {
      String key = text(task, "task_key", text(task, "key", ""));
      String target = text(task, "target_enum", text(task, "canonical_task_key", key));
      if (("DISPLAY".equalsIgnoreCase(task.path("type").asText())
              || "DISPLAY".equalsIgnoreCase(task.path("task_type").asText()))
          || !target.equals(key)) {
        if (!tasks.containsKey(target)) {
          throw invalid("Alias target is absent: " + target);
        }
        alias(aliases, key, target);
        alias(aliases, text(task, "display_name", text(task, "name", key)), target);
      }
    }
    for (JsonNode value : raw.path("aliases")) {
      String target = value.path("task_key").asText();
      if (!tasks.containsKey(target)) {
        throw invalid("Alias target is absent: " + target);
      }
      alias(aliases, value.path("alias").asText(), target);
      alias(aliases, value.path("normalized_alias").asText(), target);
    }
    for (Map.Entry<String, ObjectNode> task : tasks.entrySet()) {
      var names = task.getValue().putArray("search_aliases");
      aliases.forEach(
          (name, target) -> {
            if (target.equals(task.getKey())) {
              names.add(name);
            }
          });
    }
    ObjectNode out = JSON.createObjectNode();
    for (String field :
        Set.of(
            "catalog_version",
            "catalog_hash",
            "schema_bundle_hash",
            "build_id",
            "artifact_digest",
            "source_snapshot_id",
            "executor_instance_id",
            "generated_at",
            "source_manifest_hash",
            "commit_id",
            "tree_id")) {
      if (raw.has(field)) {
        out.set(field, raw.get(field).deepCopy());
      }
    }
    out.put("catalog_version", text(raw, "catalog_version", text(raw, "version", "unknown")));
    var list = out.putArray("tasks");
    tasks.values().forEach(list::add);
    return out;
  }

  /** Rejects keys outside the canonical Micro6 task identity format. */
  public static String canonical(String key) {
    if (key == null || !key.matches("[A-Z][A-Z0-9_]{0,159}")) {
      throw invalid("Invalid canonical task key.");
    }
    return key;
  }

  private static String text(JsonNode node, String key, String fallback) {
    return node.path(key).isTextual() && !node.path(key).asText().isBlank()
        ? node.path(key).asText()
        : fallback;
  }

  private static void alias(Map<String, String> aliases, String name, String target) {
    if (name == null || name.isBlank()) {
      return;
    }
    String normalized = name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    String previous = aliases.putIfAbsent(normalized, target);
    if (previous != null && !previous.equals(target)) {
      throw invalid("Alias collision: " + normalized);
    }
  }

  private static CatalogProblem invalid(String message) {
    return new CatalogProblem(422, "INVALID_CATALOG", message);
  }
}
