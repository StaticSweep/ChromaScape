package com.chromascape.web.aiplayer.routine.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Published only after a capture batch transaction commits. */
public record RoutineCaptureBatchAccepted(
    String sessionId, String kind, int sequence, List<JsonNode> values) {
  public RoutineCaptureBatchAccepted {
    values = values == null ? List.of() : List.copyOf(values);
  }
}
