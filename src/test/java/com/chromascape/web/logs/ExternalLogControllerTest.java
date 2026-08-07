package com.chromascape.web.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.chromascape.web.logs.ExternalLogController.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

/** Tests for {@link ExternalLogController} optional correlation persistence. */
class ExternalLogControllerTest {

  private LogWebSocketHandler webSocketHandler;
  private LogRecordingService logRecordingService;
  private ExternalLogController controller;

  @BeforeEach
  void setUp() {
    webSocketHandler = mock(LogWebSocketHandler.class);
    logRecordingService = mock(LogRecordingService.class);
    controller = new ExternalLogController(webSocketHandler, logRecordingService);
  }

  @Test
  void legacyLevelMessagePayloadStillSucceeds() {
    LogEntry entry = new LogEntry();
    entry.setLevel("ERROR");
    entry.setMessage("legacy failure");

    ResponseEntity<Void> response = controller.receiveExternalLog(entry);

    assertEquals(200, response.getStatusCode().value());
    verify(logRecordingService)
        .recordLog(
            eq("ERROR"),
            eq("legacy failure"),
            eq("external"),
            any(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull());
    ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
    verify(webSocketHandler).broadcast(jsonCaptor.capture());
    assertTrue(jsonCaptor.getValue().contains("\"level\": \"ERROR\""));
    assertTrue(jsonCaptor.getValue().contains("\"message\": \"legacy failure\""));
    assertTrue(jsonCaptor.getValue().contains("\"source\": \"external\""));
  }

  @Test
  void optionalCorrelationFieldsArePassedToRecordingService() {
    LogEntry entry = new LogEntry();
    entry.setLevel("INFO");
    entry.setMessage("correlated event");
    entry.setDebugSessionId("debug-ext");
    entry.setRecordingId("recording-ext");
    entry.setSource("micro6");
    entry.setScriptName("Fishing");
    entry.setLogType("DIALOGUE");
    entry.setTick(42);
    entry.setTimestamp("2026-08-05T02:12:38.643");

    ResponseEntity<Void> response = controller.receiveExternalLog(entry);

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(logRecordingService)
        .recordLog(
            eq("INFO"),
            eq("correlated event"),
            eq("micro6"),
            any(),
            eq("DIALOGUE"),
            eq(42),
            isNull(),
            eq("Fishing"),
            eq("debug-ext"),
            eq("recording-ext"));
    ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
    verify(webSocketHandler).broadcast(jsonCaptor.capture());
    String json = jsonCaptor.getValue();
    assertTrue(json.contains("\"debugSessionId\": \"debug-ext\""), json);
    assertTrue(json.contains("\"recordingId\": \"recording-ext\""), json);
    assertTrue(json.contains("\"source\": \"micro6\""), json);
    assertTrue(json.contains("\"tick\": 42"), json);
  }

  @Test
  void nullFieldsFallBackToDefaults() {
    LogEntry entry = new LogEntry();
    entry.setLevel(null);
    entry.setMessage(null);

    ResponseEntity<Void> response = controller.receiveExternalLog(entry);

    assertEquals(200, response.getStatusCode().value());
    verify(logRecordingService)
        .recordLog(
            eq("INFO"),
            eq(""),
            eq("external"),
            any(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull());
  }
}
