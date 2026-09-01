package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.chromascape.web.debug.tickdebugger.controller.TickReplayController;
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
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse;
import com.chromascape.web.replay.support.HostPortWaitStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL parity proof between the retained tick-card route and playback-v1 frames. */
@SpringBootTest(
    classes = ReplayPlaybackParityIntegrationTest.PersistenceSlice.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@org.springframework.test.context.ActiveProfiles("replay-playback-parity")
@Transactional
class ReplayPlaybackParityIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("replay_playback_parity")
          .withUsername("test")
          .withPassword("test")
          .waitingFor(HostPortWaitStrategy.postgres());

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("replay-playback-parity")
  @EnableAutoConfiguration
  @EntityScan(
      basePackageClasses = {
        DebugSession.class, TickSnapshot.class, TickLog.class, RecordingSession.class
      })
  @EnableJpaRepositories(
      basePackageClasses = {
        DebugSessionRepository.class,
        TickSnapshotRepository.class,
        TickLogRepository.class,
        RecordingSessionRepository.class
      })
  @Import(ReplayPlaybackCatalogRepository.class)
  static class PersistenceSlice {}

  @org.springframework.beans.factory.annotation.Autowired private EntityManager entityManager;
  @org.springframework.beans.factory.annotation.Autowired private DebugSessionRepository sessions;
  @org.springframework.beans.factory.annotation.Autowired private TickSnapshotRepository snapshots;
  @org.springframework.beans.factory.annotation.Autowired private TickLogRepository logs;
  @org.springframework.beans.factory.annotation.Autowired private RecordingSessionRepository recordings;

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @BeforeEach
  void cleanDatabase() {
    entityManager
        .createNativeQuery("TRUNCATE tick_logs, tick_snapshots, debug_sessions, recording_sessions")
        .executeUpdate();
  }

  @Test
  void legacyTickCardsAndV1FramesPreserveTheSameEvidence() throws Exception {
    LocalDateTime start = LocalDateTime.of(2026, 8, 28, 4, 0);
    RecordingSession recording =
        new RecordingSession(
            "recording-parity",
            "parity-account",
            "parity-script",
            start,
            RecordingSession.SessionTrigger.MANUAL,
            null);
    recording.setEndTime(start.plusSeconds(5));
    entityManager.persist(recording);

    DebugSession session = new DebugSession("debug-parity", "parity-account", "parity-script", start, 100L);
    session.setRecordingId(recording.getRecordingId());
    session.setEndTime(start.plusSeconds(5));
    session.setEndTick(103L);
    entityManager.persist(session);

    persistSnapshot(session.getSessionId(), 100L, start, "{\"x\":3200,\"z\":3201}");
    persistSnapshot(session.getSessionId(), 101L, start.plusSeconds(1), "{\"x\":3201,\"z\":3201}");
    // A snapshot with no logs proves empty frame log collections serialize as arrays.
    persistSnapshot(session.getSessionId(), 103L, start.plusSeconds(3), "{\"x\":3203,\"z\":3201}");

    TickLog action = persistLog(session.getSessionId(), 100L, start, "PLAYER_ACTION", "MENU_OPTION_CLICKED", "Net");
    TickLog dialogue = persistLog(session.getSessionId(), 100L, start.plusNanos(1), "dIaLoGuE", "ChatMessage", "Talk to Cook");
    TickLog varbit = persistLog(session.getSessionId(), 101L, start.plusSeconds(1), "Game", "vArBiT_CHANGED", "Varbit 42 = 1");
    // This log must never create a synthetic legacy card or a v1 frame.
    persistLog(session.getSessionId(), 102L, start.plusSeconds(2), "Game", "ACTION_ONLY", "no snapshot");
    entityManager.flush();
    entityManager.clear();

    TickReplayController legacy =
        new TickReplayController(
            sessions,
            snapshots,
            logs,
            mock(TickOverlayRepository.class),
            mock(TimelineMarkerRepository.class),
            mock(TickVideoSyncService.class),
            recordings,
            mock(com.chromascape.web.logs.LogRepository.class),
            mock(DebugSessionService.class),
            mapper);
    var legacyResponse = legacy.getTickCards("recording-parity", 100L, 103L);
    assertEquals(200, legacyResponse.getStatusCode().value());
    assertEquals("true", legacyResponse.getHeaders().getFirst("Deprecation"));
    assertEquals(
        "</api/replay/playback/v1/recordings/recording-parity/frames>; rel=\"successor-version\"",
        legacyResponse.getHeaders().getFirst("Link"));

    @SuppressWarnings("unchecked")
    List<TickCardData> cards = (List<TickCardData>) legacyResponse.getBody();
    assertNotNull(cards);
    assertEquals(List.of(100L, 101L, 103L), cards.stream().map(TickCardData::getTick).toList());
    assertFalse(cards.stream().anyMatch(card -> card.getTick() == 102L));

    ReplayPlaybackService playback =
        new ReplayPlaybackService(
            mock(ReplayPlaybackCatalogRepository.class),
            snapshots,
            logs,
            new ReplayRecordingResolver(recordings, sessions),
            new ReplayPlaybackCursorCodec(mapper),
            new ReplayPlaybackFrameCursorCodec(mapper));
    ReplayPlaybackFramePageResponse v1 = playback.frames("recording-parity", 100L, 100);
    assertEquals(List.of(100L, 101L, 103L), v1.getFrames().stream().map(ReplayPlaybackFramePageResponse.FrameData::getTick).toList());

    Map<Long, TickCardData> legacyByTick =
        cards.stream().collect(Collectors.toMap(TickCardData::getTick, Function.identity()));
    for (ReplayPlaybackFramePageResponse.FrameData frame : v1.getFrames()) {
      List<TickLog> legacyLogs =
          castTickLogs(
              legacyByTick.get(frame.getTick()).getSectionData().getOrDefault("actions", List.of()));
      List<Long> legacyIds = legacyLogs.stream().map(TickLog::getId).toList();
      List<Long> v1Ids = frame.getLogs().stream().map(log -> log.getId()).toList();
      assertEquals(legacyIds, v1Ids, "legacy and v1 must expose identical underlying log rows");
      assertTrue(
          frame.getLogs().stream()
              .allMatch(log -> log.getCategories() != null && log.getCategories().contains("ACTION")));
    }

    assertEquals(
        List.of("ACTION", "DIALOGUE"),
        v1.getFrames().get(0).getLogs().get(1).getCategories(),
        "dialogue subsystem matching is case-insensitive");
    assertEquals(
        List.of("ACTION", "VARBIT"),
        v1.getFrames().get(1).getLogs().get(0).getCategories(),
        "varbit event matching is case-insensitive");
    assertEquals(List.of(action.getId(), dialogue.getId()), v1.getFrames().get(0).getLogs().stream().map(l -> l.getId()).toList());
    assertEquals(varbit.getId(), v1.getFrames().get(1).getLogs().get(0).getId());

    JsonNode frameJson = mapper.valueToTree(v1);
    assertTrue(frameJson.path("frames").get(2).path("logs").isArray());
    assertEquals(0, frameJson.path("frames").get(2).path("logs").size());
    assertFalse(v1.getFrames().stream().anyMatch(frame -> frame.getTick() == 102L));
  }

  private void persistSnapshot(String sessionId, long tick, LocalDateTime timestamp, String state)
      throws Exception {
    TickSnapshot snapshot = new TickSnapshot(sessionId, tick, timestamp, mapper.readTree(state));
    snapshot.setInventory(mapper.readTree("[]"));
    snapshot.setEquipment(mapper.readTree("[]"));
    snapshot.setThreats(mapper.readTree("[]"));
    snapshot.setProjectiles(mapper.readTree("[]"));
    snapshot.setCooldowns(mapper.readTree("{}"));
    snapshot.setQuest(mapper.readTree("{}"));
    snapshot.setQuestHelper(mapper.readTree("{}"));
    snapshot.setNpcs(mapper.readTree("[]"));
    snapshot.setGameObjects(mapper.readTree("[]"));
    snapshot.setGroundObjects(mapper.readTree("[]"));
    snapshot.setGroundItems(mapper.readTree("[]"));
    snapshot.setPlayers(mapper.readTree("[]"));
    snapshot.setWidgets(mapper.readTree("[]"));
    entityManager.persist(snapshot);
  }

  private TickLog persistLog(
      String sessionId, long tick, LocalDateTime timestamp, String subsystem, String eventType, String message) {
    TickLog log = new TickLog(sessionId, tick, timestamp, subsystem, "INFO", message);
    log.setEventType(eventType);
    entityManager.persist(log);
    return log;
  }

  @SuppressWarnings("unchecked")
  private static List<TickLog> castTickLogs(Object value) {
    assertNotNull(value);
    return (List<TickLog>) value;
  }
}
