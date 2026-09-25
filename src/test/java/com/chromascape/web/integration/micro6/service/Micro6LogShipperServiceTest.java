package com.chromascape.web.integration.micro6.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.integration.micro6.Micro6IntegrationController.Micro6LogBatch;
import com.chromascape.web.integration.micro6.Micro6IntegrationController.Micro6LogEntry;
import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests correlation safety for the Micro6 log-file shipper. */
class Micro6LogShipperServiceTest {

  private final Micro6Config config = new Micro6Config();
  private final DebugSessionService debugSessionService =
      org.mockito.Mockito.mock(DebugSessionService.class);

  private Micro6LogShipperService service() {
    return new Micro6LogShipperService(config, new ObjectMapper(), debugSessionService);
  }

  private DebugSession session(String sessionId, String recordingId) {
    DebugSession session =
        new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L);
    session.setRecordingId(recordingId);
    return session;
  }

  @Test
  void leavesLogsUncorrelatedWhenNoSessionIsActive() {
    when(debugSessionService.getActiveSessions()).thenReturn(List.of());

    assertNull(service().resolveUnambiguousActiveSession());
  }

  @Test
  void resolvesOnlyTheSingleActiveSession() {
    DebugSession active = session("debug-one", "recording-one");
    when(debugSessionService.getActiveSessions()).thenReturn(List.of(active));

    DebugSession resolved = service().resolveUnambiguousActiveSession();

    assertEquals("debug-one", resolved.getSessionId());
    assertEquals("recording-one", resolved.getRecordingId());
  }

  @Test
  void leavesLogsUncorrelatedWhenMultipleSessionsAreActive() {
    when(debugSessionService.getActiveSessions())
        .thenReturn(
            List.of(session("debug-one", "recording-one"), session("debug-two", "recording-two")));

    assertNull(service().resolveUnambiguousActiveSession());
  }

  @Test
  void capturesCorrelationOnEachQueuedEntry() {
    Micro6LogShipperService service = service();
    LogLineParser.ParsedLogLine parsed =
        new LogLineParser.ParsedLogLine(
            LocalDateTime.of(2026, 8, 7, 12, 0), "INFO", "client", "menu clicked");

    Micro6LogEntry entry = service.createLogEntry(parsed, "debug-one", "recording-one");

    assertEquals("debug-one", entry.getDebugSessionId());
    assertEquals("recording-one", entry.getRecordingId());
  }

  @Test
  void batchKeepsEntryCorrelation() {
    Micro6LogShipperService service = service();
    Micro6LogEntry entry = new Micro6LogEntry();
    entry.setDebugSessionId("debug-before");
    entry.setRecordingId("recording-before");

    Micro6LogBatch batch = service.buildBatchRequest(List.of(entry));

    assertNull(batch.getDebugSessionId());
    assertNull(batch.getRecordingId());
    assertEquals("debug-before", batch.getLogs().get(0).getDebugSessionId());
    assertEquals("recording-before", batch.getLogs().get(0).getRecordingId());
  }
}
