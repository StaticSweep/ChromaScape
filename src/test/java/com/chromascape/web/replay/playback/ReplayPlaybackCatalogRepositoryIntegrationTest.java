package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chromascape.web.replay.support.HostPortWaitStrategy;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL coverage for the bounded playback catalog projection. */
@SpringBootTest(
    classes = ReplayPlaybackCatalogRepositoryIntegrationTest.PersistenceSlice.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@org.springframework.test.context.ActiveProfiles("replay-playback-catalog")
@Import({ReplayPlaybackCatalogRepository.class, ReplayPlaybackIndexConvergence.class})
@Transactional
class ReplayPlaybackCatalogRepositoryIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("replay_playback_catalog")
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
  @Profile("replay-playback-catalog")
  @EnableAutoConfiguration
  @EntityScan(
      basePackageClasses = {
        DebugSession.class,
        TickSnapshot.class,
        TickLog.class,
        RecordingSession.class
      })
  @EnableJpaRepositories(
      basePackageClasses = {
        ReplayPlaybackCatalogRepository.class,
        TickSnapshotRepository.class,
        TickLogRepository.class
      })
  static class PersistenceSlice {}

  @Autowired private EntityManager entityManager;
  @Autowired private ReplayPlaybackCatalogRepository catalogRepository;
  @Autowired private TickSnapshotRepository tickSnapshotRepository;
  @Autowired private DataSource dataSource;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void cleanDatabase() {
    entityManager.createNativeQuery("TRUNCATE tick_logs, tick_snapshots, debug_sessions, recording_sessions").executeUpdate();
  }

  @Test
  void projectionExcludesOrphansAndAggregatesNestedPositionAndLogs() throws Exception {
    LocalDateTime start = LocalDateTime.of(2026, 8, 28, 3, 0);
    persistRecording("recording-linked", "account-a", start);
    DebugSession linked = persistDebugSession("debug-linked", "recording-linked", start);
    persistSnapshot(
        linked.getSessionId(),
        1L,
        "{\"position\":{\"x\":3200,\"y\":3201}}",
        start);
    persistSnapshot(linked.getSessionId(), 2L, "{\"x\":3202,\"z\":3203}", start.plusSeconds(1));
    persistLog(linked.getSessionId(), 2L, "dialogue", "ChatMessage");
    persistLog(linked.getSessionId(), 2L, "Game", "VarBitChanged");

    persistDebugSession("debug-orphan", "recording-missing", start.minusMinutes(1));
    entityManager.flush();

    List<Object[]> rows = catalogRepository.findCatalogRows(null, null, null, 10, 0);

    assertEquals(1, rows.size());
    assertEquals("recording-linked", rows.get(0)[1]);
    assertEquals(2L, ((Number) rows.get(0)[9]).longValue());
    assertEquals(2L, ((Number) rows.get(0)[10]).longValue());
    assertEquals(2L, ((Number) rows.get(0)[11]).longValue());
  }

  @Test
  void keysetPredicateContinuesAfterTheIssuedTuple() {
    LocalDateTime newest = LocalDateTime.of(2026, 8, 28, 3, 0);
    LocalDateTime older = newest.minusMinutes(1);
    persistRecording("recording-new", "account-a", newest);
    persistDebugSession("debug-new", "recording-new", newest);
    persistRecording("recording-old", "account-a", older);
    persistDebugSession("debug-old", "recording-old", older);
    entityManager.flush();

    List<Object[]> firstPage = catalogRepository.findCatalogRows(null, null, null, 1, 0);
    assertEquals(1, firstPage.size());

    LocalDateTime cursorTime = asLocalDateTime(firstPage.get(0)[5]);
    Long cursorId = ((Number) firstPage.get(0)[0]).longValue();
    List<Object[]> continuation =
        catalogRepository.findCatalogRows(null, cursorTime, cursorId, 10, 0);

    assertEquals(1, continuation.size());
    assertEquals("recording-old", continuation.get(0)[1]);
    assertTrue(((Number) continuation.get(0)[0]).longValue() != cursorId);
  }

  @Test
  void equalStartTimesUseDescendingDatabaseIdOrder() {
    LocalDateTime start = LocalDateTime.of(2026, 8, 28, 3, 0);
    persistRecording("recording-first", "account-a", start);
    DebugSession first = persistDebugSession("debug-first", "recording-first", start);
    persistRecording("recording-second", "account-a", start);
    DebugSession second = persistDebugSession("debug-second", "recording-second", start);
    entityManager.flush();

    List<Object[]> rows = catalogRepository.findCatalogRows(null, null, null, 10, 0);

    assertEquals(2, rows.size());
    assertEquals(second.getId(), ((Number) rows.get(0)[0]).longValue());
    assertEquals(first.getId(), ((Number) rows.get(1)[0]).longValue());
  }

  @Test
  void committedNewerSessionDoesNotShiftCursorContinuation() {
    LocalDateTime firstPageTime = LocalDateTime.of(2026, 8, 28, 3, 0);
    LocalDateTime olderTime = firstPageTime.minusMinutes(1);
    persistRecording("recording-page-one", "account-a", firstPageTime);
    DebugSession pageOne =
        persistDebugSession("debug-page-one", "recording-page-one", firstPageTime);
    persistRecording("recording-older", "account-a", olderTime);
    persistDebugSession("debug-older", "recording-older", olderTime);
    entityManager.flush();

    List<Object[]> firstPage = catalogRepository.findCatalogRows(null, null, null, 1, 0);
    LocalDateTime cursorTime = asLocalDateTime(firstPage.get(0)[5]);
    Long cursorId = ((Number) firstPage.get(0)[0]).longValue();

    persistRecording(
        "recording-inserted-newer", "account-a", firstPageTime.plusMinutes(1));
    persistDebugSession(
        "debug-inserted-newer", "recording-inserted-newer", firstPageTime.plusMinutes(1));
    entityManager.flush();

    List<Object[]> continuation =
        catalogRepository.findCatalogRows(null, cursorTime, cursorId, 10, 0);

    assertEquals(1, continuation.size());
    assertEquals("recording-older", continuation.get(0)[1]);
    assertEquals(pageOne.getId().longValue(), cursorId.longValue());
  }

  @Test
  void accountFilterIsExactAndDoesNotMatchSimilarNames() {
    LocalDateTime start = LocalDateTime.of(2026, 8, 28, 3, 0);
    persistRecording("recording-account", "account-a", start);
    persistDebugSession("debug-account", "recording-account", start, "account-a");
    persistRecording("recording-similar-account", "account-ab", start.minusMinutes(1));
    persistDebugSession(
        "debug-similar-account", "recording-similar-account", start.minusMinutes(1), "account-ab");
    entityManager.flush();

    List<Object[]> rows = catalogRepository.findCatalogRows("account-a", null, null, 10, 0);

    assertEquals(1, rows.size());
    assertEquals("account-a", rows.get(0)[3]);
  }

  @Test
  void freshHibernateBootstrapConvergesToTheExactPlaybackIndexes() {
    assertTrue(tableExists("debug_sessions"));
    assertTrue(tableExists("tick_snapshots"));
    assertTrue(tableExists("tick_logs"));

    assertEquals(
        "CREATE INDEX idx_debug_sessions_playback_keyset ON public.debug_sessions USING btree (start_time DESC, id DESC) WHERE (recording_id IS NOT NULL)",
        indexDefinition("idx_debug_sessions_playback_keyset"));
    assertEquals(
        "CREATE INDEX idx_debug_sessions_playback_account_keyset ON public.debug_sessions USING btree (account_name, start_time DESC, id DESC) WHERE (recording_id IS NOT NULL)",
        indexDefinition("idx_debug_sessions_playback_account_keyset"));
    assertEquals(
        "CREATE INDEX idx_tick_snapshots_playback_session_tick_id ON public.tick_snapshots USING btree (session_id, tick, id)",
        indexDefinition("idx_tick_snapshots_playback_session_tick_id"));
    assertEquals(
        "CREATE INDEX idx_tick_logs_playback_session_tick_id ON public.tick_logs USING btree (session_id, tick, id)",
        indexDefinition("idx_tick_logs_playback_session_tick_id"));
  }

  @Test
  void repeatedStartupConvergenceKeepsOneStableDefinitionPerManagedIndex() {
    // The class-level test transaction owns the setup TRUNCATE lock.  The production runner uses
    // its own DataSource connection, so release that setup transaction before exercising a second
    // startup pass; otherwise PostgreSQL correctly waits forever for the suspended transaction's
    // AccessExclusiveLock.  Start a fresh transaction for the assertions below.
    entityManager.flush();
    TestTransaction.flagForRollback();
    TestTransaction.end();

    ReplayPlaybackIndexConvergence convergence = new ReplayPlaybackIndexConvergence(dataSource);

    convergence.run(null);
    convergence.run(null);

    TestTransaction.start();

    assertEquals(1, indexCount("idx_debug_sessions_playback_keyset"));
    assertEquals(1, indexCount("idx_debug_sessions_playback_account_keyset"));
    assertEquals(1, indexCount("idx_tick_snapshots_playback_session_tick_id"));
    assertEquals(1, indexCount("idx_tick_logs_playback_session_tick_id"));
    assertEquals(
        "CREATE INDEX idx_debug_sessions_playback_keyset ON public.debug_sessions USING btree (start_time DESC, id DESC) WHERE (recording_id IS NOT NULL)",
        indexDefinition("idx_debug_sessions_playback_keyset"));
  }

  @Test
  void databaseFrameCursorPreservesDuplicateTickRowsAcrossPages() throws Exception {
    DebugSession session = persistDebugSession("debug-frames", "recording-frames", LocalDateTime.now());
    persistSnapshot(session.getSessionId(), 4L, "{}", LocalDateTime.now());
    persistSnapshot(session.getSessionId(), 4L, "{}", LocalDateTime.now());
    persistSnapshot(session.getSessionId(), 4L, "{}", LocalDateTime.now());
    persistSnapshot(session.getSessionId(), 5L, "{}", LocalDateTime.now());
    entityManager.flush();

    Page<TickSnapshot> firstPage =
        tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            session.getSessionId(), 0L, PageRequest.of(0, 3));
    Long cursorId = firstPage.getContent().get(1).getId();
    Page<TickSnapshot> secondPage =
        tickSnapshotRepository.findBySessionIdAndAfterCursor(
            session.getSessionId(), 4L, cursorId, PageRequest.of(0, 3));

    assertEquals(3, firstPage.getContent().size());
    assertEquals(List.of(4L, 5L), secondPage.getContent().stream().map(TickSnapshot::getTick).toList());
    assertTrue(secondPage.getContent().get(0).getId() > cursorId);
  }

  private RecordingSession persistRecording(String recordingId, String accountName, LocalDateTime start) {
    RecordingSession recording =
        new RecordingSession(
            recordingId,
            accountName,
            "script",
            start,
            RecordingSession.SessionTrigger.MANUAL,
            null);
    entityManager.persist(recording);
    return recording;
  }

  private DebugSession persistDebugSession(
      String sessionId, String recordingId, LocalDateTime start) {
    return persistDebugSession(sessionId, recordingId, start, "account-a");
  }

  private DebugSession persistDebugSession(
      String sessionId, String recordingId, LocalDateTime start, String accountName) {
    DebugSession session = new DebugSession(sessionId, accountName, "script", start, 0L);
    session.setRecordingId(recordingId);
    entityManager.persist(session);
    return session;
  }

  private void persistSnapshot(
      String sessionId, long tick, String playerState, LocalDateTime timestamp) throws Exception {
    entityManager.persist(
        new TickSnapshot(sessionId, tick, timestamp, objectMapper.readTree(playerState)));
  }

  private void persistLog(String sessionId, long tick, String subsystem, String eventType) {
    TickLog log =
        new TickLog(
            sessionId,
            tick,
            LocalDateTime.of(2026, 8, 28, 3, 0),
            subsystem,
            "INFO",
            "event");
    log.setEventType(eventType);
    entityManager.persist(log);
  }

  private static LocalDateTime asLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    throw new AssertionError("Unexpected native timestamp type: " + value);
  }

  private boolean tableExists(String tableName) {
    Number count =
        (Number)
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = :tableName")
                .setParameter("tableName", tableName)
                .getSingleResult();
    return count.longValue() == 1L;
  }

  private String indexDefinition(String indexName) {
    return (String)
        entityManager
            .createNativeQuery(
                "SELECT indexdef FROM pg_indexes "
                    + "WHERE schemaname = current_schema() AND indexname = :indexName")
            .setParameter("indexName", indexName)
            .getSingleResult();
  }

  private long indexCount(String indexName) {
    Number count =
        (Number)
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = current_schema() AND indexname = :indexName")
                .setParameter("indexName", indexName)
                .getSingleResult();
    return count.longValue();
  }
}
