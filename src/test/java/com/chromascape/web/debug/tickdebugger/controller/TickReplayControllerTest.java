package com.chromascape.web.debug.tickdebugger.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickVideoSyncService;
import com.chromascape.web.logs.LogRepository;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Tests for {@link TickReplayController}. */
@ExtendWith(MockitoExtension.class)
class TickReplayControllerTest {

  @Mock private DebugSessionRepository sessionRepository;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private TickLogRepository tickLogRepository;
  @Mock private TickOverlayRepository overlayRepository;
  @Mock private TimelineMarkerRepository markerRepository;
  @Mock private TickVideoSyncService tickVideoSyncService;
  @Mock private RecordingSessionRepository recordingSessionRepository;
  @Mock private LogRepository standardLogRepository;
  @Mock private DebugSessionService debugSessionService;

  private TickReplayController controller;

  @BeforeEach
  void setUp() {
    controller =
        new TickReplayController(
            sessionRepository,
            snapshotRepository,
            tickLogRepository,
            overlayRepository,
            markerRepository,
            tickVideoSyncService,
            recordingSessionRepository,
            standardLogRepository,
            debugSessionService,
            new ObjectMapper());
  }

  private DebugSession createSession(String sessionId) {
    return new DebugSession(
        sessionId, "testAccount", "testScript", LocalDateTime.of(2025, 6, 15, 12, 0), 0L);
  }

  @Test
  void recordingHealth_onlyQueriesRecordingLinkedSessions() {
    DebugSession linked = createSession("debug-1");
    linked.setRecordingId("recording-1");
    DebugSession unlinked = createSession("debug-2");

    when(sessionRepository.findByRecordingIdIsNotNullOrderByStartTimeDesc())
        .thenReturn(List.of(linked));
    when(debugSessionService.toSummaries(List.of(linked)))
        .thenReturn(
            List.of(
                new DebugSessionSummary(
                    "debug-1",
                    "testAccount",
                    "testScript",
                    linked.getStartTime(),
                    null,
                    0L,
                    null,
                    "recording-1",
                    true,
                    0L,
                    0L,
                    0L,
                    false,
                    null,
                    null,
                    null)));

    ResponseEntity<List<DebugSessionSummary>> response = controller.getRecordingHealth(null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().size());
    verify(sessionRepository, never()).findAllByOrderByStartTimeDesc();
  }

  @Test
  void getTickCards_includesQuestHelperInSectionData() throws Exception {
    DebugSession session = createSession("debug-3");
    session.setRecordingId("recording-3");

    ObjectMapper mapper = new ObjectMapper();
    JsonNode questHelper = mapper.readTree(
        "{\"schemaVersion\":1,\"collectionMode\":\"questhelper\","
            + "\"activeQuest\":{\"questName\":\"Cook's Assistant\"},"
            + "\"currentStep\":{\"stepId\":\"c\",\"text\":\"Talk to the Cook.\"}}");

    TickSnapshot snapshot = new TickSnapshot(
        "debug-3", 100L, LocalDateTime.of(2025, 6, 15, 12, 0),
        mapper.readTree("{\"x\":3200,\"z\":3200,\"hp\":99,\"prayer\":99}"));
    snapshot.setQuestHelper(questHelper);
    snapshot.setQuest(mapper.readTree("{\"active\":true,\"name\":\"Cook's Assistant\"}"));
    snapshot.setInventory(mapper.readTree("[]"));
    snapshot.setEquipment(mapper.readTree("[]"));
    snapshot.setThreats(mapper.readTree("[]"));
    snapshot.setProjectiles(mapper.readTree("[]"));
    snapshot.setCooldowns(mapper.readTree("{}"));
    snapshot.setNpcs(mapper.readTree("[]"));
    snapshot.setGameObjects(mapper.readTree("[]"));
    snapshot.setGroundObjects(mapper.readTree("[]"));
    snapshot.setGroundItems(mapper.readTree("[]"));
    snapshot.setPlayers(mapper.readTree("[]"));
    snapshot.setWidgets(mapper.readTree("[]"));

    when(sessionRepository.findByRecordingId("recording-3"))
        .thenReturn(java.util.Optional.of(session));
    when(snapshotRepository.findBySessionIdAndTickBetweenOrderByTick("debug-3", 0L, 100L))
        .thenReturn(List.of(snapshot));

    ResponseEntity<?> response = controller.getTickCards("recording-3", 0L, 100L);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertNotNull(body);
    List<?> cards = (List<?>) body.get("cards");
    assertNotNull(cards);
    assertEquals(1, cards.size());

    Map<?, ?> card = (Map<?, ?>) cards.get(0);
    Map<?, ?> sectionData = (Map<?, ?>) card.get("sectionData");
    assertNotNull(sectionData);
    assertNotNull(sectionData.get("questHelper"), "questHelper should be present in sectionData");
  }
}
