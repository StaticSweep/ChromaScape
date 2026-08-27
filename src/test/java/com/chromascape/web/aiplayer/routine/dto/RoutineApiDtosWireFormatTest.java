package com.chromascape.web.aiplayer.routine.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Pins the capture wire format against the payloads {@code CaptureSessionClient} actually sends.
 *
 * <p>These records are deserialize-only, and the client emits snake_case field names plus a {@code
 * schema} discriminator. A component that is neither named nor annotated to match is not merely
 * ignored: unknown properties are fatal, so the whole body fails to parse and the plugin sees
 * RUN_CREATION_REJECTED / CAPTURE_START_REJECTED with no indication of which field was at fault.
 * Every field below was verified against a live SC-968 run.
 */
class RoutineApiDtosWireFormatTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void runCreationBodyBinds() throws Exception {
    String body =
        "{\"schema\":\"current-slice-run-request/v1\",\"identity\":{"
            + "\"schema\":\"current-slice-identity/v1\","
            + "\"client\":{\"client_kind\":\"RUNELITE_CAPTURE\"}}}";
    RoutineApiDtos.StartRunRequest request =
        mapper.readValue(body, RoutineApiDtos.StartRunRequest.class);
    assertEquals("current-slice-run-request/v1", request.schema());
    assertNotNull(request.identity());
    assertEquals("RUNELITE_CAPTURE", request.identity().client().clientKind());
  }

  @Test
  void captureSessionBodyBinds() throws Exception {
    String body =
        "{\"schema\":\"routine-capture-session/v1\",\"identity\":null,"
            + "\"started_at\":\"2026-08-27T19:32:27Z\",\"start_tick\":42,"
            + "\"video_capture\":\"NONE\"}";
    RoutineApiDtos.CaptureSessionRequest request =
        mapper.readValue(body, RoutineApiDtos.CaptureSessionRequest.class);
    assertEquals("routine-capture-session/v1", request.schema());
    assertEquals("2026-08-27T19:32:27Z", request.startedAt());
    assertEquals(42L, request.startTick());
    assertEquals("NONE", request.videoCapture());
  }

  @Test
  void tickAndActionBatchBodiesBind() throws Exception {
    String ticks =
        "{\"schema\":\"routine-tick-batch/v1\",\"session_id\":\"sess_1\","
            + "\"sequence\":7,\"ticks\":[]}";
    RoutineApiDtos.BatchRequest tickBatch =
        mapper.readValue(ticks, RoutineApiDtos.BatchRequest.class);
    assertEquals("sess_1", tickBatch.sessionId());
    assertEquals(7, tickBatch.sequence());

    String actions =
        "{\"schema\":\"routine-action-batch/v1\",\"session_id\":\"sess_1\","
            + "\"sequence\":8,\"actions\":[]}";
    RoutineApiDtos.BatchRequest actionBatch =
        mapper.readValue(actions, RoutineApiDtos.BatchRequest.class);
    assertEquals("sess_1", actionBatch.sessionId());
    assertEquals(8, actionBatch.sequence());
  }

  @Test
  void captureCompleteBodyBinds() throws Exception {
    String body =
        "{\"schema\":\"routine-capture-complete/v1\",\"final_tick\":120,"
            + "\"tick_sequence\":9,\"action_sequence\":3,\"video_sha256\":null}";
    RoutineApiDtos.CompleteRequest request =
        mapper.readValue(body, RoutineApiDtos.CompleteRequest.class);
    assertEquals(120, request.finalTick());
    assertEquals(9, request.tickSequence());
    assertEquals(3, request.actionSequence());
  }

  @Test
  void shadowRunBodyBinds() throws Exception {
    String body = "{\"schema\":\"routine-shadow-run-request/v1\",\"session_id\":\"sess_1\"}";
    RoutineApiDtos.ShadowRunRequest request =
        mapper.readValue(body, RoutineApiDtos.ShadowRunRequest.class);
    assertEquals("routine-shadow-run-request/v1", request.schema());
    assertEquals("sess_1", request.sessionId());
  }
}
