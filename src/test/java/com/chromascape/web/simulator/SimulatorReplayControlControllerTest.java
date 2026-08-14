package com.chromascape.web.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.chromascape.web.simulator.dto.ReplayFramePageResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Tests for {@link SimulatorReplayControlController}.
 *
 * <p>Verifies status code mapping for disabled mode, validation errors, upstream domain failures,
 * upstream transport failures, and success paths.
 */
@ExtendWith(MockitoExtension.class)
class SimulatorReplayControlControllerTest {

  @Mock private SimulatorReplayControlService service;
  @Mock private SimulatorReplaySourceService sourceService;
  @Mock private SimulatorReplayControlProperties properties;

  private SimulatorReplayControlController controller;

  @BeforeEach
  void setUp() {
    controller = new SimulatorReplayControlController(service, sourceService, properties);
  }

  @Nested
  @DisplayName("Disabled mode")
  class DisabledMode {

    @BeforeEach
    void setUp() {
      when(properties.isEnabled()).thenReturn(false);
    }

    @Test
    @DisplayName("getStatus returns 503 when disabled")
    void getStatusReturns503() {
      ResponseEntity<Map<String, Object>> response = controller.getStatus("player1");
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
      assertDisabledBody(response.getBody());
    }

    @Test
    @DisplayName("start returns 503 when disabled")
    void startReturns503() {
      ResponseEntity<Map<String, Object>> response = controller.start(Map.of("player", "player1"));
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
      assertDisabledBody(response.getBody());
    }

    @Test
    @DisplayName("pause returns 503 when disabled")
    void pauseReturns503() {
      ResponseEntity<Map<String, Object>> response = controller.pause(Map.of("player", "player1"));
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
      assertDisabledBody(response.getBody());
    }

    @Test
    @DisplayName("stop returns 503 when disabled")
    void stopReturns503() {
      ResponseEntity<Map<String, Object>> response = controller.stop(Map.of("player", "player1"));
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
      assertDisabledBody(response.getBody());
    }
  }

  @Nested
  @DisplayName("Validation errors")
  class ValidationErrors {

    @BeforeEach
    void setUp() {
      when(properties.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("start allows missing player and forwards auto-target request")
    void startMissingPlayer() {
      ReplayFramePageResponse.FrameData frame = new ReplayFramePageResponse.FrameData();
      frame.setTick(1L);
      when(sourceService.frames("rec-1", 0, 1))
          .thenReturn(
              new ReplayFramePageResponse(
                  "rec-1", "sess-1", java.util.List.of(frame), null, false));
      when(service.start(null, "latest", null, null, "rec-1", "tick"))
          .thenReturn(Map.of("success", true, "message", "Started"));
      ResponseEntity<Map<String, Object>> response =
          controller.start(Map.of("recordingId", "rec-1", "sourceMode", "tick"));
      assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("start returns 409 when recording has no tick frames")
    void startRecordingNotReplayableNoFrames() {
      when(sourceService.frames("rec-empty", 0, 1))
          .thenReturn(
              new ReplayFramePageResponse("rec-empty", "sess-1", java.util.List.of(), null, false));
      ResponseEntity<Map<String, Object>> response =
          controller.start(Map.of("recordingId", "rec-empty", "sourceMode", "tick"));
      assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
      assertTrue(response.getBody().get("error").toString().contains("not replayable"));
    }

    @Test
    @DisplayName("start returns 409 when no debug session exists for recording")
    void startRecordingNotReplayableNoDebugSession() {
      when(sourceService.frames("rec-missing", 0, 1))
          .thenThrow(
              new SimulatorReplayControlException(
                  "No debug session found for recordingId: rec-missing"));
      ResponseEntity<Map<String, Object>> response =
          controller.start(Map.of("recordingId", "rec-missing", "sourceMode", "tick"));
      assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
      assertTrue(response.getBody().get("error").toString().contains("no tick snapshot session"));
    }

    @Test
    @DisplayName("start allows missing player and forwards auto-target request")
    void startMissingPlayerActionMode() {
      when(service.start(null, "latest", null, null, null, null))
          .thenReturn(Map.of("success", true, "message", "Started"));
      ResponseEntity<Map<String, Object>> response =
          controller.start(Map.of("sessionId", "latest"));
      assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("seek returns 400 when seconds is missing")
    void seekMissingSeconds() {
      ResponseEntity<Map<String, Object>> response = controller.seek(Map.of("player", "player1"));
      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
      assertErrorBody(response.getBody(), "Missing required field: seconds");
    }

    @Test
    @DisplayName("speed returns 400 when speed is missing")
    void speedMissingSpeed() {
      ResponseEntity<Map<String, Object>> response = controller.speed(Map.of("player", "player1"));
      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
      assertErrorBody(response.getBody(), "Missing required field: speed");
    }

    @Test
    @DisplayName("pause allows missing player and forwards auto-target request")
    void pauseMissingPlayer() {
      when(service.pause(null)).thenReturn(Map.of("success", true, "message", "Paused"));
      ResponseEntity<Map<String, Object>> response = controller.pause(Map.of());
      assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("speed returns 400 for invalid number")
    void speedInvalidNumber() {
      ResponseEntity<Map<String, Object>> response =
          controller.speed(Map.of("player", "player1", "speed", "not-a-number"));
      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Upstream domain failure (success=false)")
  class UpstreamDomainFailure {

    @BeforeEach
    void setUp() {
      when(properties.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("start returns 409 when upstream returns success=false")
    void startDomainFailure() {
      Map<String, Object> upstreamResult =
          Map.of("success", false, "message", "Player not found: unknown");
      when(service.start("player1", "latest", null, null, null, null)).thenReturn(upstreamResult);

      ResponseEntity<Map<String, Object>> response = controller.start(Map.of("player", "player1"));

      assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
      assertFalse((Boolean) response.getBody().get("success"));
      assertTrue(response.getBody().containsKey("message"));
    }

    @Test
    @DisplayName("pause returns 409 when upstream returns success=false")
    void pauseDomainFailure() {
      Map<String, Object> upstreamResult =
          Map.of("success", false, "message", "No active playback");
      when(service.pause("player1")).thenReturn(upstreamResult);

      ResponseEntity<Map<String, Object>> response = controller.pause(Map.of("player", "player1"));

      assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Upstream transport failure")
  class UpstreamTransportFailure {

    @BeforeEach
    void setUp() {
      when(properties.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("getStatus returns 502 on upstream connection failure")
    void getStatusTransportFailure() {
      when(service.getStatus("player1"))
          .thenThrow(new SimulatorReplayControlException("Connection refused"));

      ResponseEntity<Map<String, Object>> response = controller.getStatus("player1");
      assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
      assertErrorBody(response.getBody(), "Connection refused");
    }

    @Test
    @DisplayName("start returns 502 on upstream non-2xx without parsed status")
    void startUpstreamError() {
      when(service.start("player1", "latest", null, null, null, null))
          .thenThrow(
              new SimulatorReplayControlException("OpenRune API returned 500: Internal error"));

      ResponseEntity<Map<String, Object>> response = controller.start(Map.of("player", "player1"));

      assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
      assertTrue(response.getBody().get("error").toString().contains("500"));
    }
  }

  @Nested
  @DisplayName("Upstream status propagation")
  class UpstreamStatusPropagation {

    @BeforeEach
    void setUp() {
      when(properties.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("propagates upstream 401 status")
    void propagatesUpstream401() {
      when(service.getStatus("player1"))
          .thenThrow(
              new SimulatorReplayControlException(
                  "OpenRune API returned 401: Invalid API key", 401));

      ResponseEntity<Map<String, Object>> response = controller.getStatus("player1");
      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
      assertErrorBody(response.getBody(), "401");
    }

    @Test
    @DisplayName("propagates upstream 403 status")
    void propagatesUpstream403() {
      when(service.pause("player1"))
          .thenThrow(
              new SimulatorReplayControlException("OpenRune API returned 403: Forbidden", 403));

      ResponseEntity<Map<String, Object>> response = controller.pause(Map.of("player", "player1"));
      assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    @DisplayName("propagates upstream 404 status")
    void propagatesUpstream404() {
      when(service.start("player1", "latest", null, null, null, null))
          .thenThrow(
              new SimulatorReplayControlException(
                  "OpenRune API returned 404: Player not found", 404));

      ResponseEntity<Map<String, Object>> response = controller.start(Map.of("player", "player1"));
      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("propagates upstream 500 status")
    void propagatesUpstream500() {
      when(service.stop("player1"))
          .thenThrow(
              new SimulatorReplayControlException(
                  "OpenRune API returned 500: Internal error", 500));

      ResponseEntity<Map<String, Object>> response = controller.stop(Map.of("player", "player1"));
      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Success path")
  class SuccessPath {

    @BeforeEach
    void setUp() {
      when(properties.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("players returns 200 with selected and player list")
    void playersSuccess() {
      Map<String, Object> result =
          Map.of("success", true, "players", java.util.List.of("dev-one"), "selected", "dev-one");
      when(service.getPlayers()).thenReturn(result);

      ResponseEntity<Map<String, Object>> response = controller.getPlayers();
      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertEquals(true, response.getBody().get("success"));
      assertEquals("dev-one", response.getBody().get("selected"));
    }

    @Test
    @DisplayName("getStatus returns 200 with status data")
    void getStatusSuccess() {
      Map<String, Object> status =
          Map.of(
              "state", "RUNNING",
              "sessionId", "sess-1",
              "speed", 2.0);
      when(service.getStatus("player1")).thenReturn(status);

      ResponseEntity<Map<String, Object>> response = controller.getStatus("player1");
      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertEquals("RUNNING", response.getBody().get("state"));
    }

    @Test
    @DisplayName("start returns 200 with success result")
    void startSuccess() {
      Map<String, Object> result = Map.of("success", true, "message", "Playback started");
      when(service.start("player1", "latest", 2.0, null, null, null)).thenReturn(result);

      ResponseEntity<Map<String, Object>> response =
          controller.start(Map.of("player", "player1", "speed", 2.0));

      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertEquals(true, response.getBody().get("success"));
    }

    @Test
    @DisplayName("start passes recordingId and sourceMode to service")
    void startPassesRecordingIdAndSourceMode() {
      ReplayFramePageResponse.FrameData frame = new ReplayFramePageResponse.FrameData();
      frame.setTick(1L);
      when(sourceService.frames("rec-42", 0, 1))
          .thenReturn(
              new ReplayFramePageResponse(
                  "rec-42", "sess-42", java.util.List.of(frame), null, false));
      Map<String, Object> result = Map.of("success", true, "message", "Started tick replay");
      when(service.start("player1", "latest", null, null, "rec-42", "tick")).thenReturn(result);

      ResponseEntity<Map<String, Object>> response =
          controller.start(
              Map.of(
                  "player", "player1",
                  "recordingId", "rec-42",
                  "sourceMode", "tick"));

      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertEquals(true, response.getBody().get("success"));
    }

    @Test
    @DisplayName("pause returns 200 on success")
    void pauseSuccess() {
      Map<String, Object> result = Map.of("success", true, "message", "Paused");
      when(service.pause("player1")).thenReturn(result);

      ResponseEntity<Map<String, Object>> response = controller.pause(Map.of("player", "player1"));

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("stop returns 200 on success")
    void stopSuccess() {
      Map<String, Object> result = Map.of("success", true, "message", "Stopped");
      when(service.stop("player1")).thenReturn(result);

      ResponseEntity<Map<String, Object>> response = controller.stop(Map.of("player", "player1"));

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("seek returns 200 on success")
    void seekSuccess() {
      Map<String, Object> result = Map.of("success", true, "message", "Seeked");
      when(service.seek("player1", 60.0)).thenReturn(result);

      ResponseEntity<Map<String, Object>> response =
          controller.seek(Map.of("player", "player1", "seconds", 60));

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("speed returns 200 on success")
    void speedSuccess() {
      Map<String, Object> result = Map.of("success", true, "message", "Speed set");
      when(service.setSpeed("player1", 4.0)).thenReturn(result);

      ResponseEntity<Map<String, Object>> response =
          controller.speed(Map.of("player", "player1", "speed", 4));

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }
  }

  private static void assertDisabledBody(Map<String, Object> body) {
    assertNotNull(body);
    assertEquals(false, body.get("success"));
    assertEquals("Replay control is disabled", body.get("error"));
  }

  private static void assertErrorBody(Map<String, Object> body, String expectedFragment) {
    assertNotNull(body);
    assertEquals(false, body.get("success"));
    assertTrue(
        body.get("error").toString().contains(expectedFragment),
        "Expected error to contain '" + expectedFragment + "' but was: " + body.get("error"));
  }
}
