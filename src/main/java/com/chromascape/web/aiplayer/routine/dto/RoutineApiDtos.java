package com.chromascape.web.aiplayer.routine.dto;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public final class RoutineApiDtos {
  private RoutineApiDtos() {}

  public record StartRunRequest(String schema, String attemptId, CurrentSliceIdentity identity) {}

  public record CaptureSessionRequest(
      String schema,
      CurrentSliceIdentity identity,
      @JsonProperty("started_at") String startedAt,
      @JsonProperty("start_tick") long startTick,
      @JsonProperty("video_capture") String videoCapture) {}

  public record BatchRequest(
      String schema,
      @JsonProperty("session_id") String sessionId,
      int sequence,
      List<JsonNode> ticks,
      List<JsonNode> actions) {}

  public record CompleteRequest(
      String schema,
      @JsonProperty("final_tick") int finalTick,
      @JsonProperty("tick_sequence") int tickSequence,
      @JsonProperty("action_sequence") int actionSequence,
      @JsonProperty("video_sha256") String videoSha256) {}

  public record CurationRequest(
      List<Map<String, Object>> includeRanges, List<Map<String, Object>> excludeRanges) {}

  public record ReplayRequest(String recordingId, String graphId) {}

  public record ShadowRunRequest(String schema, @JsonProperty("session_id") String sessionId) {}

  public record ShadowCompleteRequest(String schema) {}

  public record AssistantMessageRequest(String message) {}
}
