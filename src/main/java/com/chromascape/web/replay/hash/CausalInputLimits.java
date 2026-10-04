package com.chromascape.web.replay.hash;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Admission bounds before recursive hashing and checkpoint reconstruction. */
public final class CausalInputLimits {
  private CausalInputLimits() {}

  private record Item(JsonNode value, int depth, boolean exit) {}

  /** At most 100,000 values and depth 64 per bound input document. */
  public static void validate(JsonNode input) {
    if (input == null) {
      throw new Sc275Exception("PROJECTION_VALUE_INVALID", "missing causal input");
    }
    ArrayDeque<Item> stack = new ArrayDeque<>();
    Set<JsonNode> active = Collections.newSetFromMap(new IdentityHashMap<>());
    stack.push(new Item(input, 0, false));
    int nodes = 100000;
    while (!stack.isEmpty()) {
      Item item = stack.pop();
      if (item.exit()) {
        active.remove(item.value());
        continue;
      }
      if (--nodes < 0 || item.depth() > 64) {
        throw new Sc275Exception(
            "PROJECTION_VALUE_INVALID", "causal input exceeds admission limits");
      }
      if (item.value().isContainerNode()) {
        if (!active.add(item.value()) || item.value().size() > nodes) {
          throw new Sc275Exception("PROJECTION_VALUE_INVALID", "invalid causal input structure");
        }
        stack.push(new Item(item.value(), item.depth(), true));
        for (JsonNode child : item.value()) {
          if (stack.size() >= nodes) {
            throw new Sc275Exception(
                "PROJECTION_VALUE_INVALID", "causal input exceeds admission limits");
          }
          stack.push(new Item(child, item.depth() + 1, false));
        }
      }
    }
  }
}
