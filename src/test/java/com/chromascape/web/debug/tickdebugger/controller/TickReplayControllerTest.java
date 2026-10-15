package com.chromascape.web.debug.tickdebugger.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.TickCardData;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.PacketObservation;
import com.chromascape.web.debug.tickdebugger.entity.PacketObservationRepository;
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
  @Test
  void provenanceBindsMediaDigestAndRejectsChangedMediaBytes(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var session = new DebugSession("identity-media", null, "combat", LocalDateTime.now(), 0L);
    session.setRecordingId("recording-identity");
    var video = directory.resolve("capture.mp4");
    java.nio.file.Files.writeString(video, "abc");
    var index =
        new com.chromascape.web.recording.service.MeasuredVideoIndex.Index(
            "measured-video-index/v1",
            "recording-identity",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            true,
            List.of(),
            List.of(
                new com.chromascape.web.recording.service.MeasuredVideoIndex.PhysicalFrame(
                    0, 0, 1000, 1000, 0, 0)),
            50000);
    new ObjectMapper()
        .writeValue(
            com.chromascape.web.recording.service.MeasuredVideoIndex.sidecar(video).toFile(),
            index);
    var recording = new RecordingSession();
    recording.setVideoPath(video.toString());
    when(sessionRepository.findBySessionId("identity-media")).thenReturn(Optional.of(session));
    when(recordingSessionRepository.findByRecordingId("recording-identity"))
        .thenReturn(Optional.of(recording));
    var response = controller.captureProvenance("identity-media").getBody();
    assertEquals(index.mediaSha256(), response.get("mediaSha256"));
    assertEquals("VERIFIED", response.get("mediaStatus"));
    java.nio.file.Files.writeString(video, "changed");
    var corrupt = controller.captureProvenance("identity-media").getBody();
    assertNull(corrupt.get("mediaSha256"));
    assertEquals("UNAVAILABLE", corrupt.get("mediaStatus"));
    assertTrue(((List<?>) corrupt.get("qualityCodes")).contains("VIDEO_INDEX_OR_MEDIA_INVALID"));
  }

  @Test
  void provenanceExposesPersistedIdentityAndExplicitLegacyMissingMedia() {
    var session = new DebugSession("identity", null, "combat", LocalDateTime.now(), 0L);
    when(sessionRepository.findBySessionId("identity")).thenReturn(Optional.of(session));
    var response = controller.captureProvenance("identity");
    assertNull(response.getBody().get("captureIdentity"));
    assertEquals(
        List.of("LEGACY_CAPTURE_IDENTITY_MISSING", "VIDEO_INDEX_MISSING"),
        response.getBody().get("qualityCodes"));
    var identity = new ObjectMapper().createObjectNode().put("schema", "test-persisted");
    session.bindCaptureIdentity(identity);
    assertEquals(
        identity, controller.captureProvenance("identity").getBody().get("captureIdentity"));
    assertEquals(HttpStatus.NOT_FOUND, controller.captureProvenance("missing").getStatusCode());
  }

  @Mock private DebugSessionRepository sessionRepository;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private TickLogRepository tickLogRepository;
  @Mock private TickOverlayRepository overlayRepository;
  @Mock private TimelineMarkerRepository markerRepository;
  @Mock private TickVideoSyncService tickVideoSyncService;
  @Mock private RecordingSessionRepository recordingSessionRepository;
  @Mock private LogRepository standardLogRepository;
  @Mock private DebugSessionService debugSessionService;
  @Mock private PacketObservationRepository packetObservationRepository;

  @Mock
  private com.chromascape.web.debug.tickdebugger.service.ManualRecordingCoordinator
      manualRecordingCoordinator;

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
    org.springframework.test.util.ReflectionTestUtils.setField(
        controller, "manualRecordingCoordinator", manualRecordingCoordinator);
  }

  @Test
  void unavailableRecorderCannotStartAVideoOrSession() {
    when(manualRecordingCoordinator.selectRecorder(null))
        .thenThrow(new IllegalStateException("RECORDER_UNAVAILABLE"));
    var response = controller.startRecording(new TickReplayController.RecordingStartRequest());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    verifyNoInteractions(debugSessionService);
  }

  private DebugSessionService.SessionStartOutcome manualStart(boolean created) {
    var response =
        new com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse(
            "debug_manual", true, "rec_manual", 0L, null);
    var token =
        new DebugSessionService.SessionStartRollbackToken(
            "debug_manual", created, created ? "rec_manual" : null, null, null, null);
    var outcome =
        new DebugSessionService.SessionStartOutcome(response, token, created, !created, created);
    when(manualRecordingCoordinator.selectRecorder(null)).thenReturn("socket");
    when(debugSessionService.startSessionWithOutcome(
            any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(outcome);
    return outcome;
  }

  @Test
  void recordingSuccessWaitsForBindingAndACommittedTick() {
    var outcome = manualStart(true);
    var response = controller.startRecording(new TickReplayController.RecordingStartRequest());
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertSame(outcome.response(), response.getBody());
    var ordered = inOrder(manualRecordingCoordinator, debugSessionService);
    ordered.verify(manualRecordingCoordinator).selectRecorder(null);
    ordered
        .verify(debugSessionService)
        .startSessionWithOutcome(any(), any(), any(), any(), any(), any(), any(), any());
    ordered
        .verify(manualRecordingCoordinator)
        .startAndAwaitTick("socket", "debug_manual", "Manual Recording");
  }

  @Test
  void readinessFailureDrainsAndCompensatesOnlyTheNewSession() {
    var outcome = manualStart(true);
    doThrow(new IllegalStateException("RECORDER_TICK_TIMEOUT"))
        .when(manualRecordingCoordinator)
        .startAndAwaitTick(any(), any(), any());
    doThrow(new IllegalStateException("RECORDER_DISCONNECTED"))
        .when(manualRecordingCoordinator)
        .stopAndAwaitDrain(any());
    var response = controller.startRecording(new TickReplayController.RecordingStartRequest());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    verify(debugSessionService)
        .rollbackSessionStart(outcome.rollbackToken(), "manual capture readiness failed");
  }

  @Test
  void readinessFailureCannotStopAReusedSession() {
    var outcome = manualStart(false);
    doThrow(new IllegalStateException("RECORDER_TICK_TIMEOUT"))
        .when(manualRecordingCoordinator)
        .startAndAwaitTick(any(), any(), any());
    assertEquals(
        HttpStatus.SERVICE_UNAVAILABLE,
        controller
            .startRecording(new TickReplayController.RecordingStartRequest())
            .getStatusCode());
    verify(manualRecordingCoordinator, never()).stopAndAwaitDrain(any());
    verify(debugSessionService)
        .rollbackSessionStart(outcome.rollbackToken(), "manual capture readiness failed");
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

  private PacketObservation packetRow(String sessionId, long tick, long sequence, long id) {
    PacketObservation row = new PacketObservation(sessionId, tick, "proxy-session", sequence);
    row.setDirection("SERVER_TO_CLIENT");
    row.setOpcode(81);
    row.setProtocolName("NPC_INFO_LARGE");
    row.setEncodedSize(137);
    row.setDecodeStatus("DECODED");
    row.setSynthetic(false);
    org.springframework.test.util.ReflectionTestUtils.setField(row, "id", id);
    return row;
  }

  private PacketObservation v2PacketRow(
      String sessionId, long tick, String sourceSessionId, long observationSequence, long id) {
    PacketObservation row =
        new PacketObservation(
            sessionId,
            tick,
            sourceSessionId,
            "rsprox-packet/v2",
            observationSequence,
            observationSequence + 4800L);
    row.setDirection("SERVER_TO_CLIENT");
    row.setOpcode(81);
    row.setProtocolName("NPC_INFO_LARGE");
    row.setEncodedSize(137);
    row.setDecodeStatus("DECODED");
    row.setSynthetic(false);
    org.springframework.test.util.ReflectionTestUtils.setField(row, "id", id);
    return row;
  }

  @Test
  @DisplayName("Packet endpoint pages through the repository with limit+1")
  void packets_pagesThroughRepositoryWithLimitPlusOne() {
    String sessionId = "debug-packets";
    when(sessionRepository.findBySessionId(sessionId))
        .thenReturn(Optional.of(createSession(sessionId)));
    org.springframework.test.util.ReflectionTestUtils.setField(
        controller, "packetObservationRepository", packetObservationRepository);

    PacketObservation first = packetRow(sessionId, 100L, 1L, 11L);
    PacketObservation second = packetRow(sessionId, 100L, 2L, 12L);
    PacketObservation extra = packetRow(sessionId, 100L, 3L, 13L);
    when(packetObservationRepository.searchPacketsPage(
            eq(sessionId),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            any()))
        .thenReturn(List.of(first, second, extra));

    ResponseEntity<Map<String, Object>> response =
        controller.getPackets(
            sessionId, null, null, null, null, null, null, null, null, null, 2, false);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(true, response.getBody().get("hasMore"));
    assertEquals(2, ((List<?>) response.getBody().get("packets")).size());
    assertFalse(((String) response.getBody().get("cursor")).isBlank());
    verify(packetObservationRepository)
        .searchPacketsPage(
            eq(sessionId),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            any());
  }

  @Test
  @DisplayName("Packet cursor follows source/schema/observation identity for interleaved v2 rows")
  void packets_v2CursorUsesCompleteOrderingTuple() {
    String sessionId = "debug-packets-v2-cursor";
    when(sessionRepository.findBySessionId(sessionId))
        .thenReturn(Optional.of(createSession(sessionId)));
    org.springframework.test.util.ReflectionTestUtils.setField(
        controller, "packetObservationRepository", packetObservationRepository);

    PacketObservation sourceA = v2PacketRow(sessionId, 100L, "source-a", 7L, 31L);
    PacketObservation sourceB = v2PacketRow(sessionId, 100L, "source-b", 0L, 32L);
    PacketObservation next = v2PacketRow(sessionId, 100L, "source-b", 1L, 33L);
    when(packetObservationRepository.searchPacketsPageOrdered(
            eq(sessionId),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            any()))
        .thenReturn(List.of(sourceA, sourceB, next));

    ResponseEntity<Map<String, Object>> firstPage =
        controller.getPackets(
            sessionId, null, null, null, null, null, null, null, null, null, 2, false);

    assertEquals(HttpStatus.OK, firstPage.getStatusCode());
    assertNotNull(firstPage.getBody());
    List<?> firstPackets = (List<?>) firstPage.getBody().get("packets");
    assertEquals(2, firstPackets.size());
    assertEquals("source-a", ((Map<?, ?>) firstPackets.get(0)).get("sourceSessionId"));
    assertEquals("source-b", ((Map<?, ?>) firstPackets.get(1)).get("sourceSessionId"));
    String cursor = (String) firstPage.getBody().get("cursor");
    assertFalse(cursor.isBlank());

    when(packetObservationRepository.searchPacketsPageOrdered(
            eq(sessionId),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(100L),
            eq("source-b"),
            eq("rsprox-packet/v2"),
            eq(0L),
            eq(32L),
            any()))
        .thenReturn(List.of(next));

    ResponseEntity<Map<String, Object>> secondPage =
        controller.getPackets(
            sessionId, null, null, null, null, null, null, null, null, cursor, 2, false);
    assertEquals(HttpStatus.OK, secondPage.getStatusCode());
    assertNotNull(secondPage.getBody());
    assertEquals(1, ((List<?>) secondPage.getBody().get("packets")).size());
    verify(packetObservationRepository)
        .searchPacketsPageOrdered(
            eq(sessionId),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(100L),
            eq("source-b"),
            eq("rsprox-packet/v2"),
            eq(0L),
            eq(32L),
            any());
  }

  @Test
  @DisplayName("Packet endpoint clears the cursor on the final page")
  void packets_clearsCursorOnFinalPage() {
    String sessionId = "debug-packets-final";
    when(sessionRepository.findBySessionId(sessionId))
        .thenReturn(Optional.of(createSession(sessionId)));
    org.springframework.test.util.ReflectionTestUtils.setField(
        controller, "packetObservationRepository", packetObservationRepository);

    PacketObservation first = packetRow(sessionId, 100L, 1L, 21L);
    when(packetObservationRepository.searchPacketsPage(
            eq(sessionId),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            any()))
        .thenReturn(List.of(first));

    ResponseEntity<Map<String, Object>> response =
        controller.getPackets(
            sessionId, null, null, null, null, null, null, null, null, null, 2, false);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(false, response.getBody().get("hasMore"));
    assertEquals("", response.getBody().get("cursor"));
    assertEquals(1, ((List<?>) response.getBody().get("packets")).size());
  }

  @Test
  @DisplayName("Packet endpoint pushes the text filter into the repository query")
  void packets_pushesTextFilterIntoRepositoryQuery() {
    String sessionId = "debug-packets-search";
    when(sessionRepository.findBySessionId(sessionId))
        .thenReturn(Optional.of(createSession(sessionId)));
    org.springframework.test.util.ReflectionTestUtils.setField(
        controller, "packetObservationRepository", packetObservationRepository);
    when(packetObservationRepository.searchPacketsPage(
            eq(sessionId),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq("npc_info"),
            isNull(),
            isNull(),
            isNull(),
            any()))
        .thenReturn(List.of());

    ResponseEntity<Map<String, Object>> response =
        controller.getPackets(
            sessionId, null, null, null, null, null, null, null, "NPC_INFO", null, 100, false);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(packetObservationRepository)
        .searchPacketsPage(
            eq(sessionId),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq("npc_info"),
            isNull(),
            isNull(),
            isNull(),
            any());
  }
}
