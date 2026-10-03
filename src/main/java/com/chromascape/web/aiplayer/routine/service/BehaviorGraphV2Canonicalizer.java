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
 * SC-728 canonical content preimage. The root {@code graph_hash} is excluded and only the root
 * {@code nodes} and {@code edges} arrays are ordered.
 *
 * <p>Ordering is a property of the contract's root arrays, not of a field name. An array named
 * {@code nodes} nested inside a node's {@code configuration} is behavior-significant data and keeps
 * whatever order the author declared, which is what the editor's canonicalizer does.
 *
 * <p>Node ids, edge ids and object keys compare by UTF-16 code unit so that this preimage is
 * byte-identical to {@code JSON.stringify} after the editor sorts the same arrays.
 */
@Component
public class BehaviorGraphV2Canonicalizer {
  public String canonicalDefinition(JsonNode definition) {
    return BehaviorGraphCanonicalJson.canonical(preimage(definition));
  }

  public String graphHash(JsonNode definition) {
    return BehaviorGraphCanonicalJson.hash(preimage(definition));
  }

  private JsonNode preimage(JsonNode definition) {
    if (definition == null || !definition.isObject()) return definition;
    ObjectNode root = ((ObjectNode) definition).deepCopy();
    root.remove("graph_hash");
    JsonNode nodes = root.get("nodes");
    if (nodes != null && nodes.isArray()) {
      root.set("nodes", sort(nodes, Comparator.comparing(node -> node.path("id").asText())));
    }
    JsonNode edges = root.get("edges");
    if (edges != null && edges.isArray()) {
      root.set(
          "edges",
          sort(
              edges,
              Comparator.comparingInt((JsonNode edge) -> edge.path("priority").asInt())
                  .reversed()
                  .thenComparingInt(edge -> edge.path("source_order").asInt())
                  .thenComparing(edge -> edge.path("id").asText())));
    }
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
