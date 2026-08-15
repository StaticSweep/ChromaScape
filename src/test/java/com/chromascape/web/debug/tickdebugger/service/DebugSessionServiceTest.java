package com.chromascape.web.debug.tickdebugger.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionCountProjection;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.exception.FrameCaptureException;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link DebugSessionService}. */
@ExtendWith(MockitoExtension.class)
class DebugSessionServiceTest {

  @Mock private DebugSessionRepository sessionRepository;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private TickLogRepository tickLogRepository;
  @Mock private TimelineMarkerRepository timelineMarkerRepository;
  @Mock private RecordingService recordingService;
  @Mock private RecordingSessionRepository recordingSessionRepository;
  @Mock private RecordingConfig recordingConfig;

  private DebugSessionService service;

  @BeforeEach
  void setUp() {
    service =
        new DebugSessionService(
            sessionRepository,
            snapshotRepository,
            tickLogRepository,
            timelineMarkerRepository,
            recordingService,
            recordingSessionRepository,
            recordingConfig,
            new ObjectMapper());
  }

  private DebugSession createSession(String sessionId) {
    return new DebugSession(
        sessionId, "testAccount", "testScript", LocalDateTime.of(2025, 6, 15, 12, 0), 0L);
  }

  private SessionCountProjection projection(String sessionId, long count) {
    return new SessionCountProjection() {
      @Override
      public String getSessionId() {
        return sessionId;
      }

      @Override
      public Long getCount() {
        return count;
      }
    };
  }

  @Test
  void toSummaries_usesGroupedCountQueriesAndMergesCounts() {
    DebugSession s1 = createSession("debug-1");
    DebugSession s2 = createSession("debug-2");
    List<DebugSession> sessions = List.of(s1, s2);

    when(snapshotRepository.countBySessionIdIn(List.of("debug-1", "debug-2")))
        .thenReturn(List.of(projection("debug-1", 10L), projection("debug-2", 5L)));
    when(tickLogRepository.countBySessionIdIn(List.of("debug-1", "debug-2")))
        .thenReturn(List.of(projection("debug-1", 3L)));
    when(timelineMarkerRepository.countFailuresBySessionIdIn(List.of("debug-1", "debug-2")))
        .thenReturn(List.of(projection("debug-2", 2L)));

    List<DebugSessionSummary> summaries = service.toSummaries(sessions);

    assertEquals(2, summaries.size());
    DebugSessionSummary one = summaries.get(0);
    assertEquals("debug-1", one.getSessionId());
    assertEquals(10L, one.getTickCount());
    assertEquals(3L, one.getEventCount());
    assertEquals(0L, one.getFailureMarkerCount());
    assertTrue(one.getHasEvents());

    DebugSessionSummary two = summaries.get(1);
    assertEquals("debug-2", two.getSessionId());
    assertEquals(5L, two.getTickCount());
    assertEquals(0L, two.getEventCount());
    assertEquals(2L, two.getFailureMarkerCount());
    assertFalse(two.getHasEvents());
  }

  @Test
  void toSummaries_emptyList_returnsEmpty() {
    assertTrue(service.toSummaries(List.of()).isEmpty());
  }

  @Test
  void startSession_recordingStartFailure_degradesToTickOnlySession() {
    when(sessionRepository.save(ArgumentMatchers.any(DebugSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(recordingService.startRecording(RecordingTriggerSource.MANUAL))
        .thenThrow(new FrameCaptureException(10, 1000L, 1000L, "no frame captured", false));

    SessionStartResponse response =
        service.startSession("account-1", "script-1", true, RecordingTriggerSource.MANUAL, null);

    // Session must survive as a tick-only session, not FAILED
    ArgumentCaptor<DebugSession> captor = ArgumentCaptor.forClass(DebugSession.class);
    verify(sessionRepository, atLeastOnce()).save(captor.capture());
    DebugSession saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
    assertEquals(DebugSessionLifecycleState.RECORDING, saved.getLifecycleState());
    assertEquals("REC_START_FAILED", saved.getFailureCode());
    assertNotNull(saved.getFailureReason());
    assertNull(saved.getRecordingId());

    // No RecordingSession row created for the failed recording
    verify(recordingSessionRepository, never()).save(any());

    // Response reflects the degraded mode
    assertNotNull(response.getSessionId());
    assertTrue(response.getSessionId().startsWith("debug_"));
    assertFalse(response.getRecordingEnabled());
    assertNull(response.getRecordingId());
    assertNotNull(response.getRecordingFailureReason());
    assertTrue(response.getRecordingFailureReason().contains("failed to start"));
  }

  @Test
  void startSession_unknownPreferredSessionId_createsNewAuthoritativeId() {
    String provisionalId = "a4a7d886-1111-2222-3333-444455556666";
    when(sessionRepository.findBySessionId(provisionalId)).thenReturn(java.util.Optional.empty());
    when(sessionRepository.save(ArgumentMatchers.any(DebugSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(recordingService.startRecording(RecordingTriggerSource.MANUAL))
        .thenReturn("recording_new_1");

    SessionStartResponse response =
        service.startSession(
            "account-1", "script-1", true, RecordingTriggerSource.MANUAL, provisionalId);

    // The provisional client request id must never be persisted as the authoritative session id:
    // an unknown preferred id falls back to a freshly generated debug_* id.
    assertNotNull(response.getSessionId());
    assertTrue(response.getSessionId().startsWith("debug_"));
    assertNotEquals(provisionalId, response.getSessionId());

    // The session is persisted on creation (STARTING) and again when it transitions to RECORDING.
    // The authoritative id and recording binding must be present on the final persisted state.
    ArgumentCaptor<DebugSession> captor = ArgumentCaptor.forClass(DebugSession.class);
    verify(sessionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    java.util.List<DebugSession> saved = captor.getAllValues();
    DebugSession last = saved.get(saved.size() - 1);
    assertEquals(response.getSessionId(), last.getSessionId());
    assertEquals("recording_new_1", response.getRecordingId());
    assertTrue(response.getRecordingEnabled());
    // The provisional client request id is never persisted as the authoritative session id.
    assertTrue(
        saved.stream()
            .noneMatch(session -> provisionalId.equals(session.getSessionId())));
  }
}
