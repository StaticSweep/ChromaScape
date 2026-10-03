package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * SC-728 canonical layout preimage. The root {@code layout_hash} is excluded and only the root
 * {@code nodes} and {@code groups} arrays are ordered.
 *
 * <p>A missing {@code groups} array is materialized as an empty array before hashing, so a layout
 * that omits the optional field and one that declares {@code "groups": []} share a single hash. The
 * editor's canonicalizer always emits {@code groups}, and a freshly created server draft now writes
 * {@code groups} explicitly, so all three agree.
 */
@Component
public class BehaviorGraphLayoutCanonicalizer {
  public String canonicalLayout(JsonNode layout) {
    return BehaviorGraphCanonicalJson.canonical(preimage(layout));
  }

  public String layoutHash(JsonNode layout) {
    return BehaviorGraphCanonicalJson.hash(preimage(layout));
  }

  private JsonNode preimage(JsonNode layout) {
    if (layout == null || !layout.isObject()) return layout;
    ObjectNode root = ((ObjectNode) layout).deepCopy();
    root.remove("layout_hash");
    JsonNode nodes = root.get("nodes");
    if (nodes != null && nodes.isArray()) {
      root.set("nodes", sort(nodes, Comparator.comparing(node -> node.path("node_id").asText())));
    }
    JsonNode groups = root.get("groups");
    root.set(
        "groups",
        groups != null && groups.isArray()
            ? sort(groups, Comparator.comparing(group -> group.path("id").asText()))
            : JsonNodeFactory.instance.arrayNode());
    return root;
  }

  private static ArrayNode sort(JsonNode input, Comparator<JsonNode> comparator) {
    List<JsonNode> items = new ArrayList<>();
    input.forEach(items::add);
    items.sort(comparator);
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    items.forEach(out::add);
    return out;
  }
}
