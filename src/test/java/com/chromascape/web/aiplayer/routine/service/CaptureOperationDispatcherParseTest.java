package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Pins the operation parsers against the payload actually persisted in {@code
 * routine_capture_operations.request_json}.
 *
 * <p>That column stores the whole operation payload, while these records read only the few fields
 * the recorder needs. Parsing it with a default mapper makes every other property an error, so the
 * START operation failed with CAPTURE_START_REQUEST_INVALID, retried to UNCERTAIN, and the capture
 * sat in STARTING until the harness timed out. The JSON below is copied from a live SC-968 run.
 */
class CaptureOperationDispatcherParseTest {

  private final CaptureOperationDispatcher dispatcher =
      new CaptureOperationDispatcher(null, null, null, mock(PlatformTransactionManager.class));

  @Test
  void startRequestParsesTheStoredSupersetPayload() {
    String stored =
        "{\"endpoint\":\"/capture/sessions\","
            + "\"idempotency_key\":\"5422f2bf-c2cb-49e4-846a-95e72ab296ea\","
            + "\"identity\":{\"schema\":\"current-slice-identity/v1\"},"
            + "\"session_id\":\"capture_6f1d4db5a739412ea274927035b7c5c6\","
            + "\"start_tick\":0,\"started_at\":\"2026-08-27T20:06:07.939820200Z\","
            + "\"task_key\":\"FISHING_TRAINING\",\"video_capture\":\"CHROMASCAPE_WINDOW\"}";
    assertEquals("FISHING_TRAINING", dispatcher.parseStart(stored).taskKey());
  }

  @Test
  void completeRequestParsesTheStoredSupersetPayload() {
    String stored =
        "{\"endpoint\":\"/capture/sessions/x/complete\",\"idempotency_key\":\"k\","
            + "\"recording_id\":\"rec_1\",\"debug_session_id\":\"dbg_1\",\"final_tick\":120,"
            + "\"tick_sequence\":9,\"action_sequence\":3}";
    CaptureOperationDispatcher.CompleteRequest parsed = dispatcher.parseComplete(stored);
    assertEquals("rec_1", parsed.recordingId());
    assertEquals("dbg_1", parsed.debugSessionId());
    assertEquals(120, parsed.finalTick());
  }
}
