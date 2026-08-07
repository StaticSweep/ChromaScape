package com.chromascape.web.integration.micro6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.integration.adapter.RuntimeAdapterRegistry;
import com.chromascape.web.integration.micro6.Micro6IntegrationController.Micro6LogBatch;
import com.chromascape.web.integration.micro6.Micro6IntegrationController.Micro6LogEntry;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import com.chromascape.web.logs.LogWebSocketHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

/** Tests for {@link Micro6IntegrationController} log ingestion correlation. */
class Micro6IntegrationControllerTest {

  private LogRepository logRepository;
  private LogWebSocketHandler webSocketHandler;
  private RuntimeAdapterRegistry adapterRegistry;
  private Micro6IntegrationController controller;

  @BeforeEach
  void setUp() {
    logRepository = mock(LogRepository.class);
    webSocketHandler = mock(LogWebSocketHandler.class);
    adapterRegistry = mock(RuntimeAdapterRegistry.class);
    controller = new Micro6IntegrationController(logRepository, webSocketHandler, adapterRegistry);
  }

  private Micro6LogEntry entry(String message) {
    Micro6LogEntry entry = new Micro6LogEntry();
    entry.setLevel("INFO");
    entry.setMessage(message);
    return entry;
  }

  private ResponseEntity<Map<String, Object>> ingest(Micro6LogBatch batch) {
    return controller.receiveLogs(batch);
  }

  @Test
  void batchLevelCorrelationIsPersistedOnEveryEntry() {
    when(logRepository.save(any(LogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Micro6LogBatch batch = new Micro6LogBatch();
    batch.setDebugSessionId("debug-1");
    batch.setRecordingId("recording-1");
    batch.setLogs(List.of(entry("first"), entry("second")));

    ResponseEntity<Map<String, Object>> response = ingest(batch);

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<LogEntity> captor = ArgumentCaptor.forClass(LogEntity.class);
    verify(logRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    for (LogEntity saved : captor.getAllValues()) {
      assertEquals("debug-1", saved.getDebugSessionId());
      assertEquals("recording-1", saved.getRecordingId());
    }
  }

  @Test
  void entryLevelCorrelationOverridesBatchCorrelation() {
    when(logRepository.save(any(LogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Micro6LogEntry overridden = entry("overridden");
    overridden.setDebugSessionId("debug-entry");
    overridden.setRecordingId("recording-entry");

    Micro6LogBatch batch = new Micro6LogBatch();
    batch.setDebugSessionId("debug-batch");
    batch.setRecordingId("recording-batch");
    batch.setLogs(List.of(overridden, entry("plain")));

    ResponseEntity<Map<String, Object>> response = ingest(batch);

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<LogEntity> captor = ArgumentCaptor.forClass(LogEntity.class);
    verify(logRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertEquals("debug-entry", captor.getAllValues().get(0).getDebugSessionId());
    assertEquals("recording-entry", captor.getAllValues().get(0).getRecordingId());
    assertEquals("debug-batch", captor.getAllValues().get(1).getDebugSessionId());
    assertEquals("recording-batch", captor.getAllValues().get(1).getRecordingId());
  }

  @Test
  void missingCorrelationRemainsBackwardCompatible() {
    when(logRepository.save(any(LogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Micro6LogBatch batch = new Micro6LogBatch();
    batch.setLogs(List.of(entry("plain")));

    ResponseEntity<Map<String, Object>> response = ingest(batch);

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<LogEntity> captor = ArgumentCaptor.forClass(LogEntity.class);
    verify(logRepository).save(captor.capture());
    assertNull(captor.getValue().getDebugSessionId());
    assertNull(captor.getValue().getRecordingId());
  }

  @Test
  void blankCorrelationValuesAreTreatedAsNull() {
    when(logRepository.save(any(LogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Micro6LogBatch batch = new Micro6LogBatch();
    batch.setDebugSessionId("   ");
    batch.setRecordingId("");
    batch.setLogs(List.of(entry("plain")));

    ResponseEntity<Map<String, Object>> response = ingest(batch);

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<LogEntity> captor = ArgumentCaptor.forClass(LogEntity.class);
    verify(logRepository).save(captor.capture());
    assertNull(captor.getValue().getDebugSessionId());
    assertNull(captor.getValue().getRecordingId());
  }

  @Test
  void webSocketBroadcastIncludesCorrelationFields() {
    when(logRepository.save(any(LogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Micro6LogBatch batch = new Micro6LogBatch();
    batch.setDebugSessionId("debug-bc");
    batch.setRecordingId("recording-bc");
    batch.setLogs(List.of(entry("broadcast me")));

    ingest(batch);

    ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
    verify(webSocketHandler).broadcast(jsonCaptor.capture());
    String json = jsonCaptor.getValue();
    assertTrue(json.contains("\"debugSessionId\": \"debug-bc\""), json);
    assertTrue(json.contains("\"recordingId\": \"recording-bc\""), json);
  }

  @Test
  void singleLogEndpointPassesCorrelationThrough() {
    when(logRepository.save(any(LogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Micro6LogEntry entry = entry("single");
    entry.setDebugSessionId("debug-single");
    entry.setRecordingId("recording-single");

    ResponseEntity<Map<String, Object>> response = controller.receiveLog(entry);

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<LogEntity> captor = ArgumentCaptor.forClass(LogEntity.class);
    verify(logRepository).save(captor.capture());
    assertEquals("debug-single", captor.getValue().getDebugSessionId());
    assertEquals("recording-single", captor.getValue().getRecordingId());
  }

  @Test
  void ingestionStillClassifiesAndPersistsPlainLogs() {
    when(logRepository.save(any(LogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Micro6LogEntry entry = entry("Menu clicked: Walk here");
    Micro6LogBatch batch = new Micro6LogBatch();
    batch.setLogs(List.of(entry));

    ResponseEntity<Map<String, Object>> response = ingest(batch);

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<LogEntity> captor = ArgumentCaptor.forClass(LogEntity.class);
    verify(logRepository).save(captor.capture());
    assertEquals("WALKHERE", captor.getValue().getLogType());
    assertTrue(captor.getValue().getMessage().contains("Menu clicked"));
    assertTrue(response.getBody().get("success").equals(Boolean.TRUE));
  }

  @Test
  void emptyBatchReturnsBadRequest() {
    Micro6LogBatch batch = new Micro6LogBatch();
    batch.setLogs(List.of());

    ResponseEntity<Map<String, Object>> response = ingest(batch);

    assertEquals(400, response.getStatusCode().value());
    org.mockito.Mockito.verify(logRepository, org.mockito.Mockito.never()).save(any());
  }
}
