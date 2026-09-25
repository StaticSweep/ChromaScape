package com.chromascape.web.aiplayer.routine.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable, transport-safe validation result. Codes intentionally match the editor validator.
 *
 * <p>{@code nodeId}/{@code edgeId} let the editor attach an issue to the node or edge it belongs
 * to; {@code path} is the JSON pointer the wire contract requires.
 */
public record BehaviorGraphValidationIssue(
    String code, String severity, String path, String message, String nodeId, String edgeId) {

  /** Wire form of the draft response contract, which names node and edge identity in snake_case. */
  public Map<String, Object> toWire() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", code);
    out.put("severity", severity);
    out.put("path", path);
    out.put("message", message);
    if (nodeId != null) out.put("node_id", nodeId);
    if (edgeId != null) out.put("edge_id", edgeId);
    return out;
  }
}
