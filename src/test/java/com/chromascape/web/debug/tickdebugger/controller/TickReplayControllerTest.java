package com.chromascape.web.debug.tickdebugger.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.TickCardData;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickVideoSyncService;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    JsonNode questHelper =
        mapper.readTree(
            "{\"schemaVersion\":1,\"collectionMode\":\"questhelper\","
                + "\"activeQuest\":{\"questName\":\"Cook's Assistant\"},"
                + "\"currentStep\":{\"stepId\":\"c\",\"text\":\"Talk to the Cook.\"}}");

    TickSnapshot snapshot =
        new TickSnapshot(
            "debug-3",
            100L,
            LocalDateTime.of(2025, 6, 15, 12, 0),
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
    assertEquals("true", response.getHeaders().getFirst("Deprecation"));
    assertEquals(
        "</api/replay/playback/v1/recordings/recording-3/frames>; rel=\"successor-version\"",
        response.getHeaders().getFirst("Link"));

    List<TickCardData> cards = (List<TickCardData>) response.getBody();
    assertNotNull(cards);
    assertEquals(1, cards.size());

    assertNotNull(
        cards.get(0).getSectionData().get("questHelper"),
        "questHelper should be present in sectionData");
  }

  @Test
  @DisplayName("Tick card summary displays correct position for flat x/z payload")
  void flatXZPayload_displaysCorrectPositionInSummary() throws Exception {
    DebugSession session = createSession("debug-4");
    session.setRecordingId("recording-4");

    ObjectMapper mapper = new ObjectMapper();

    TickSnapshot snapshot =
        new TickSnapshot(
            "debug-4",
            100L,
            LocalDateTime.of(2025, 6, 15, 12, 0),
            mapper.readTree("{\"x\":3200,\"z\":3200,\"plane\":0}"));
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

    when(sessionRepository.findByRecordingId("recording-4"))
        .thenReturn(java.util.Optional.of(session));
    when(snapshotRepository.findBySessionIdAndTickBetweenOrderByTick("debug-4", 0L, 100L))
        .thenReturn(List.of(snapshot));

    ResponseEntity<?> response = controller.getTickCards("recording-4", 0L, 100L);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    List<TickCardData> cards = (List<TickCardData>) response.getBody();
    assertNotNull(cards);
    assertEquals(1, cards.size());
    assertTrue(cards.get(0).getPlayerSummary().contains("Pos (3200,3200,0)"));
  }

  @Test
  @DisplayName("Tick card summary displays correct position for flat worldX/worldY payload")
  void worldXWorldYPayload_displaysCorrectPositionInSummary() throws Exception {
    DebugSession session = createSession("debug-5");
    session.setRecordingId("recording-5");

    ObjectMapper mapper = new ObjectMapper();

    TickSnapshot snapshot =
        new TickSnapshot(
            "debug-5",
            100L,
            LocalDateTime.of(2025, 6, 15, 12, 0),
            mapper.readTree("{\"worldX\":3200,\"worldY\":3200,\"plane\":0}"));
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

    when(sessionRepository.findByRecordingId("recording-5"))
        .thenReturn(java.util.Optional.of(session));
    when(snapshotRepository.findBySessionIdAndTickBetweenOrderByTick("debug-5", 0L, 100L))
        .thenReturn(List.of(snapshot));

    ResponseEntity<?> response = controller.getTickCards("recording-5", 0L, 100L);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    List<TickCardData> cards = (List<TickCardData>) response.getBody();
    assertNotNull(cards);
    assertEquals(1, cards.size());
    assertTrue(cards.get(0).getPlayerSummary().contains("Pos (3200,3200,0)"));
  }

  @Test
  @DisplayName("Tick card summary does not display fake zero position for non-numeric coordinates")
  void nonNumericCoordinate_doesNotDisplayFakeZeroPosition() throws Exception {
    DebugSession session = createSession("debug-6");
    session.setRecordingId("recording-6");

    ObjectMapper mapper = new ObjectMapper();

    TickSnapshot snapshot =
        new TickSnapshot(
            "debug-6",
            100L,
            LocalDateTime.of(2025, 6, 15, 12, 0),
            mapper.readTree("{\"worldX\":\"bad\",\"worldY\":3200,\"plane\":0}"));
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

    when(sessionRepository.findByRecordingId("recording-6"))
        .thenReturn(java.util.Optional.of(session));
    when(snapshotRepository.findBySessionIdAndTickBetweenOrderByTick("debug-6", 0L, 100L))
        .thenReturn(List.of(snapshot));

    ResponseEntity<?> response = controller.getTickCards("recording-6", 0L, 100L);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    List<TickCardData> cards = (List<TickCardData>) response.getBody();
    assertNotNull(cards);
    assertEquals(1, cards.size());
    assertFalse(cards.get(0).getPlayerSummary().contains("Pos (0,3200,0)"));
    assertFalse(cards.get(0).getPlayerSummary().contains("Pos ("));
  }

  // ==================== Unified replay log endpoint ====================

  /** Standard client log with no tick, matching the recording-75fcaa33-style recording. */
  private LogEntity createClientLog(
      Long id, LocalDateTime timestamp, String source, String message) {
    LogEntity log = new LogEntity(timestamp, "INFO", message, source, "testScript");
    log.setId(id);
    return log;
  }

  private LogEntity createSessionScopedLog(
      Long id, String sessionId, LocalDateTime timestamp, String source, String message) {
    LogEntity log = new LogEntity(timestamp, "INFO", message, source, "testScript");
    log.setId(id);
    log.setDebugSessionId(sessionId);
    return log;
  }

  private LogEntity createRecordingScopedLog(
      Long id, String recordingId, LocalDateTime timestamp, String source, String message) {
    LogEntity log = createClientLog(id, timestamp, source, message);
    log.setRecordingId(recordingId);
    return log;
  }

  private TickLog createTickLog(
      Long id,
      String sessionId,
      Long tick,
      LocalDateTime timestamp,
      String subsystem,
      String message) {
    TickLog log = new TickLog(sessionId, tick, timestamp, subsystem, "INFO", message);
    log.setId(id);
    return log;
  }

  private RecordingSession createRecordingSession(String recordingId, LocalDateTime startTime) {
    return new RecordingSession(
        recordingId,
        "testAccount",
        "testScript",
        startTime,
        RecordingSession.SessionTrigger.MANUAL,
        "video.mp4");
  }

  private LocalDateTime atSecond(int second) {
    return LocalDateTime.of(2026, 8, 5, 2, 11, 18).plusSeconds(second);
  }

  /** ObjectMapper that can serialize LocalDateTime (as used by the Spring MVC layer). */
  private ObjectMapper responseMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  @Test
  @DisplayName("Unified endpoint returns standard logs with origin=external and nullable tick")
  void unified_standardLogsWithoutTickEvents() throws Exception {
    String recordingId = "recording-75fcaa33";
    String sessionId = "debug-75fcaa33";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    LogEntity clientLog =
        createClientLog(28490L, atSecond(0), "external", "ChromaScape session confirmed");
    LogEntity webLog = createClientLog(28491L, atSecond(1), "web", "Client connected");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(clientLog, webLog));
    when(tickVideoSyncService.getTickForVideoTime(sessionId, 0L)).thenReturn(277L);

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(recordingId, response.getBody().get("recordingId"));
    assertEquals(277L, ((Number) response.getBody().get("currentTick")).longValue());
    assertEquals(true, response.getBody().get("hasAnyLogs"));

    List<?> logs = (List<?>) response.getBody().get("logs");
    assertNotNull(logs);
    assertEquals(2, logs.size());
    JsonNode first = responseMapper().valueToTree(logs.get(0));
    assertEquals("external", first.get("origin").asText());
    assertEquals(28490L, first.get("id").asLong());
    assertTrue(first.get("tick").isNull(), "Nullable tick must be preserved as JSON null");
    assertEquals("external", first.get("source").asText());
  }

  @Test
  @DisplayName("Unified endpoint merges standard and tick logs with correct origins and ordering")
  void unified_mergedStandardAndTickLogs() throws Exception {
    String recordingId = "recording-merged";
    String sessionId = "debug-merged";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    TickLog tickLog = createTickLog(500L, sessionId, 100L, atSecond(1), "Combat", "Dealt 5 damage");
    LogEntity clientLog =
        createClientLog(28490L, atSecond(0), "external", "ChromaScape session confirmed");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(tickLog));
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(clientLog));
    when(tickVideoSyncService.getTickForVideoTime(sessionId, 0L)).thenReturn(100L);

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> logs = (List<?>) response.getBody().get("logs");
    assertEquals(2, logs.size());
    ObjectMapper mapper = responseMapper();
    JsonNode client = mapper.valueToTree(logs.get(0));
    JsonNode tick = mapper.valueToTree(logs.get(1));
    assertEquals("external", client.get("origin").asText());
    assertEquals("tick", tick.get("origin").asText());
    assertEquals(100L, tick.get("tick").asLong());
    assertEquals("Combat", tick.get("source").asText());
    assertTrue(
        client.get("timestamp").asText().compareTo(tick.get("timestamp").asText()) <= 0,
        "Logs must be ordered by timestamp");
  }

  @Test
  @DisplayName("Unified endpoint sorts equal timestamps deterministically by ID")
  void unified_equalTimestampsSortBySecondaryId() {
    String recordingId = "recording-equal";
    String sessionId = "debug-equal";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    LocalDateTime sameTime = atSecond(1);
    LogEntity first = createClientLog(10L, sameTime, "external", "first");
    LogEntity second = createClientLog(20L, sameTime, "external", "second");
    LogEntity third = createClientLog(30L, sameTime, "external", "third");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(new ArrayList<>(List.of(second, third, first)));

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> logs = (List<?>) response.getBody().get("logs");
    ObjectMapper mapper = responseMapper();
    List<Long> ids = new ArrayList<>();
    for (Object entry : logs) {
      ids.add(mapper.valueToTree(entry).get("id").asLong());
    }
    assertEquals(List.of(10L, 20L, 30L), ids, "Equal timestamps must order by ID ascending");
  }

  @Test
  @DisplayName("Unified endpoint returns standard logs even without a linked debug session")
  void unified_noLinkedDebugSession() {
    String recordingId = "recording-nosession";
    LocalDateTime start = atSecond(0);
    RecordingSession recording = createRecordingSession(recordingId, start);
    LogEntity clientLog =
        createClientLog(28490L, atSecond(0), "external", "ChromaScape session confirmed");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.empty());
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(clientLog));

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> logs = (List<?>) response.getBody().get("logs");
    assertEquals(1, logs.size());
    JsonNode first = responseMapper().valueToTree(logs.get(0));
    assertEquals("external", first.get("origin").asText());
  }

  @Test
  @DisplayName("Unified endpoint returns an empty logs array when both repositories are empty")
  void unified_emptyWindow() {
    String recordingId = "recording-empty";
    String sessionId = "debug-empty";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody().get("logs"));
    assertTrue(((List<?>) response.getBody().get("logs")).isEmpty());
    assertEquals(0, ((List<?>) response.getBody().get("logs")).size());
  }

  @Test
  @DisplayName("Unified endpoint returns 404 for an unknown recording")
  void unified_unknownRecording() {
    when(recordingSessionRepository.findByRecordingId("recording-missing"))
        .thenReturn(Optional.empty());

    ResponseEntity<Map<String, Object>> response =
        controller.getUnifiedLogs("recording-missing", 0L, 3);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  @DisplayName("Unified endpoint returns 400 for a recording without a start time")
  void unified_recordingWithoutStartTime() {
    String recordingId = "recording-nostart";
    RecordingSession recording = createRecordingSession(recordingId, null);
    recording.setStartTime(null);

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  @DisplayName("Unified endpoint returns the requested recordingId in the response")
  void unified_responseEchoesRecordingId() {
    String recordingId = "recording-echo";
    String sessionId = "debug-echo";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    LogEntity clientLog = createClientLog(1L, atSecond(0), "external", "hello");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(clientLog));

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(recordingId, response.getBody().get("recordingId"));
  }

  @Test
  @DisplayName(
      "Unified endpoint orders equal timestamps by origin then ID, surviving colliding IDs")
  void unified_ordersByOriginThenIdOnCollision() throws Exception {
    String recordingId = "recording-collide";
    String sessionId = "debug-collide";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    // Same timestamp and same ID in both tables: origin tie-break must order deterministically.
    LocalDateTime sameTime = atSecond(1);
    TickLog tickLog = createTickLog(777L, sessionId, 10L, sameTime, "Combat", "tick event");
    LogEntity standardLog = createClientLog(777L, sameTime, "web", "standard log");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(tickLog));
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(standardLog));

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> logs = (List<?>) response.getBody().get("logs");
    assertEquals(2, logs.size());
    ObjectMapper mapper = responseMapper();
    JsonNode first = mapper.valueToTree(logs.get(0));
    JsonNode second = mapper.valueToTree(logs.get(1));
    assertEquals(777L, first.get("id").asLong());
    assertEquals(777L, second.get("id").asLong());
    assertTrue(
        first.get("origin").asText().compareTo(second.get("origin").asText()) <= 0,
        "Equal timestamps and IDs must order by origin");
  }

  @Test
  @DisplayName("Unified endpoint clamps the window to the recording end time")
  void unified_clampsWindowToRecordingEnd() {
    String recordingId = "recording-clamp";
    String sessionId = "debug-clamp";
    LocalDateTime start = atSecond(0);
    LocalDateTime end = atSecond(10);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    recording.setEndTime(end);

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());

    // Request a window far past the recording end.
    ResponseEntity<Map<String, Object>> response =
        controller.getUnifiedLogs(recordingId, 60000L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    // The clamped windowEnd equals the recording end, so a post-recording log at
    // atSecond(20) would fall outside the queried window.
    assertEquals(end, response.getBody().get("windowEnd"));
  }

  @Test
  @DisplayName("Unified endpoint rejects negative videoTimeMs")
  void unified_negativeVideoTimeRejected() {
    ResponseEntity<Map<String, Object>> response =
        controller.getUnifiedLogs("recording-neg", -1L, 3);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  @DisplayName("Unified endpoint rejects invalid windowSeconds")
  void unified_invalidWindowSecondsRejected() {
    ResponseEntity<Map<String, Object>> response =
        controller.getUnifiedLogs("recording-win", 0L, 31);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  @DisplayName(
      "Unified endpoint uses session scope when correlated logs exist and excludes other sessions")
  void unified_sessionScopedExcludesOtherSessions() {
    String recordingId = "recording-session";
    String sessionId = "debug-session";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    LogEntity scopedLog = createSessionScopedLog(1L, sessionId, atSecond(1), "external", "mine");
    LogEntity foreignLog =
        createSessionScopedLog(2L, "debug-other", atSecond(2), "external", "theirs");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(standardLogRepository.existsByDebugSessionId(sessionId)).thenReturn(true);
    when(standardLogRepository.findByDebugSessionIdAndTimestampBetweenOrderByTimestampAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(scopedLog));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("session", response.getBody().get("scopeMode"));
    List<?> logs = (List<?>) response.getBody().get("logs");
    assertEquals(1, logs.size());
    verify(standardLogRepository, never()).findByTimestampBetweenOrderByTimestampAsc(any(), any());
  }

  @Test
  @DisplayName("Unified endpoint falls back to timestamp scope for historical recordings")
  void unified_historicalRecordingUsesTimestampFallback() {
    String recordingId = "recording-historical";
    String sessionId = "debug-historical";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    LogEntity historicalLog = createClientLog(1L, atSecond(1), "external", "old client log");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(standardLogRepository.existsByDebugSessionId(sessionId)).thenReturn(false);
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(historicalLog));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("timestamp_fallback", response.getBody().get("scopeMode"));
    List<?> logs = (List<?>) response.getBody().get("logs");
    assertEquals(1, logs.size());
  }

  @Test
  @DisplayName("Unified endpoint never mixes correlated rows with unscoped fallback rows")
  void unified_neverMixesCorrelatedAndUnscopedRows() {
    String recordingId = "recording-mixed";
    String sessionId = "debug-mixed";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    LogEntity scopedLog = createSessionScopedLog(1L, sessionId, atSecond(1), "external", "scoped");
    LogEntity unscopedLog = createClientLog(2L, atSecond(2), "external", "unscoped");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(standardLogRepository.existsByDebugSessionId(sessionId)).thenReturn(true);
    when(standardLogRepository.findByDebugSessionIdAndTimestampBetweenOrderByTimestampAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(scopedLog));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> logs = (List<?>) response.getBody().get("logs");
    // The unscoped log must not leak into the session-scoped response.
    assertEquals(1, logs.size());
    JsonNode only = responseMapper().valueToTree(logs.get(0));
    assertEquals(1L, only.get("id").asLong());
  }

  @Test
  @DisplayName("Unified endpoint reports the correct scopeMode per mode")
  void unified_scopeModeReflectsQueryMode() {
    String recordingId = "recording-scope";
    String sessionId = "debug-scope";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(standardLogRepository.existsByDebugSessionId(sessionId)).thenReturn(false);
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("timestamp_fallback", response.getBody().get("scopeMode"));
  }

  @Test
  @DisplayName("Unified endpoint uses recording scope when only recording-correlated logs exist")
  void unified_recordingScopeUsesRecordingQuery() {
    String recordingId = "recording-recording-scope";
    String sessionId = "debug-recording-scope";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    LogEntity recordingLog =
        createRecordingScopedLog(17L, recordingId, atSecond(1), "external", "recording log");

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(standardLogRepository.existsByDebugSessionId(sessionId)).thenReturn(false);
    when(standardLogRepository.existsByRecordingId(recordingId)).thenReturn(true);
    when(standardLogRepository.findByRecordingIdAndTimestampBetweenOrderByTimestampAsc(
            eq(recordingId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(recordingLog));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("recording", response.getBody().get("scopeMode"));
    List<?> logs = (List<?>) response.getBody().get("logs");
    assertEquals(1, logs.size());
    JsonNode only = responseMapper().valueToTree(logs.get(0));
    assertEquals(17L, only.get("id").asLong());
    verify(standardLogRepository, never()).findByTimestampBetweenOrderByTimestampAsc(any(), any());
  }

  @Test
  @DisplayName("Unified endpoint preserves null ticks in standard logs")
  void unified_nullTickStaysNull() throws Exception {
    String recordingId = "recording-nulltick";
    String sessionId = "debug-nulltick";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    LogEntity clientLog = createClientLog(1L, atSecond(0), "external", "no tick here");
    clientLog.setTick(null);

    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(clientLog));

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> logs = (List<?>) response.getBody().get("logs");
    JsonNode first = responseMapper().valueToTree(logs.get(0));
    assertTrue(first.get("tick").isNull(), "Null tick must remain JSON null, not currentTick");
  }

  @Test
  @DisplayName("Unified endpoint serializes logs as an empty array")
  void unified_logsAlwaysSerializesAsArray() throws Exception {
    String recordingId = "recording-array";
    String sessionId = "debug-array";
    LocalDateTime start = atSecond(0);
    DebugSession session = createSession(sessionId);
    session.setRecordingId(recordingId);

    RecordingSession recording = createRecordingSession(recordingId, start);
    when(recordingSessionRepository.findByRecordingId(recordingId))
        .thenReturn(Optional.of(recording));
    when(sessionRepository.findByRecordingId(recordingId)).thenReturn(Optional.of(session));
    when(tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
            eq(sessionId), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());
    when(standardLogRepository.findByTimestampBetweenOrderByTimestampAsc(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of());

    ResponseEntity<Map<String, Object>> response = controller.getUnifiedLogs(recordingId, 0L, 3);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    JsonNode tree = responseMapper().valueToTree(response.getBody());
    assertTrue(tree.get("logs").isArray(), "logs must serialize as a JSON array");
    assertEquals(0, tree.get("logs").size());
    assertEquals(false, tree.get("hasAnyLogs").asBoolean());
  }
}
