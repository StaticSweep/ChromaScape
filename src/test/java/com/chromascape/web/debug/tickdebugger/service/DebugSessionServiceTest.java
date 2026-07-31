package com.chromascape.web.debug.tickdebugger.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionCountProjection;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
