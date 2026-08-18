package com.chromascape.web.aiplayer.routine.dto;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public final class RoutineApiDtos {
  private RoutineApiDtos() {}
  public record StartRunRequest(String attemptId, CurrentSliceIdentity identity) {}
  public record CaptureSessionRequest(CurrentSliceIdentity identity, String startedAt, long startTick, String videoCapture) {}
  public record BatchRequest(String sessionId, int sequence, List<JsonNode> ticks, List<JsonNode> actions) {}
  public record CompleteRequest(int finalTick, int tickSequence, int actionSequence, String videoSha256) {}
  public record CurationRequest(List<Map<String, Object>> includeRanges, List<Map<String, Object>> excludeRanges) {}
  public record ReplayRequest(String recordingId, String graphId) {}
  public record ShadowRunRequest(String schema, String sessionId) {}
  public record ShadowCompleteRequest(String schema) {}
  public record AssistantMessageRequest(String message) {}
}
