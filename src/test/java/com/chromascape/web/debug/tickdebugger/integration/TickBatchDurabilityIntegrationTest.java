package com.chromascape.web.debug.tickdebugger.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingActionRepository;
import com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceiptRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.ReplayRecordingActionIngestionService;
import com.chromascape.web.debug.tickdebugger.service.TickBatchCanonicalChecksumService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * REAL PostgreSQL behavioural coverage for the durable tick-batch receipt boundary through the
 * proxied application service.
 *
 * <p>Unlike the JDBC-only {@link ReplayTickBatchReceiptIntegrationTest}, every test here calls the
 * Spring-proxied {@link TickStreamIngestionService#processTickBatch} so the receipt lookup,
 * checksum validation, action ingestion, snapshot/log/overlay persistence, session tick bounds,
 * and post-commit publication form one transactional boundary. A late persistence failure must
 * leave every table unchanged and emit no acknowledgment; concurrent exact duplicates must
 * converge on one evidence set through the service's own conflict resolution.
 */
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.main.lazy-initialization=true",
      "spring.datasource.hikari.maximum-pool-size=16",
      "spring.datasource.hikari.connection-timeout=120000",
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@org.springframework.test.context.ContextConfiguration(
    classes = TickBatchDurabilityIntegrationTest.DurabilitySliceApplication.class)
@Import(TickBatchDurabilityIntegrationTest.DurabilityServices.class)
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class TickBatchDurabilityIntegrationTest {

  @org.springframework.boot.autoconfigure.domain.EntityScan(
      basePackages = "com.chromascape.web.debug.tickdebugger.entity")
  @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
      basePackages = "com.chromascape.web.debug.tickdebugger.entity")
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  static class DurabilitySliceApplication {}

  @TestConfiguration(proxyBeanMethods = false)
  static class DurabilityServices {
    @Bean
    TickBatchCanonicalChecksumService tickBatchCanonicalChecksumService(ObjectMapper objectMapper) {
      return new TickBatchCanonicalChecksumService(objectMapper);
    }

    @Bean
    ReplayRecordingActionIngestionService replayRecordingActionIngestionService(
        ReplayRecordingActionRepository repository,
        ObjectMapper objectMapper,
        TickBatchCanonicalChecksumService checksumService) {
      return new ReplayRecordingActionIngestionService(repository, objectMapper, checksumService);
    }

    @Bean
    DebugSessionService debugSessionService(
        DebugSessionRepository sessionRepository,
        TickSnapshotRepository snapshotRepository,
        TickLogRepository tickLogRepository,
        TimelineMarkerRepository timelineMarkerRepository,
        com.chromascape.web.recording.service.RecordingService recordingService,
        com.chromascape.web.recording.entity.RecordingSessionRepository recordingSessionRepository,
        com.chromascape.web.recording.config.RecordingConfig recordingConfig,
        ObjectMapper objectMapper) {
      return new DebugSessionService(
          sessionRepository,
          snapshotRepository,
          tickLogRepository,
          timelineMarkerRepository,
          recordingService,
          recordingSessionRepository,
          recordingConfig,
          objectMapper);
    }

    @Bean
    TickStreamIngestionService tickStreamIngestionService(
        DebugSessionService sessionService,
        TickSnapshotRepository snapshotRepository,
        TickLogRepository logRepository,
        TickOverlayRepository overlayRepository,
        TimelineMarkerRepository markerRepository,
        ObjectMapper objectMapper,
        ReplayTickBatchReceiptRepository batchReceiptRepository,
        ReplayRecordingActionIngestionService actionIngestionService,
        TickBatchCanonicalChecksumService checksumService) {
      return new TickStreamIngestionService(
          sessionService,
          snapshotRepository,
          logRepository,
          overlayRepository,
          markerRepository,
          objectMapper,
          null,
          null,
          actionIngestionService,
          batchReceiptRepository,
          checksumService);
    }

    @Bean
    com.chromascape.web.recording.service.RecordingService recordingService() {
      return org.mockito.Mockito.mock(
          com.chromascape.web.recording.service.RecordingService.class);
    }

    @Bean
    com.chromascape.web.recording.entity.RecordingSessionRepository recordingSessionRepository() {
      return org.mockito.Mockito.mock(
          com.chromascape.web.recording.entity.RecordingSessionRepository.class);
    }

    @Bean
    com.chromascape.web.recording.config.RecordingConfig recordingConfig() {
      return org.mockito.Mockito.mock(
          com.chromascape.web.recording.config.RecordingConfig.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }
  }

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("tick_batch_durability")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.locations", () -> "classpath:db/migration");
  }

  @Autowired TickStreamIngestionService ingestionService;
  @Autowired DebugSessionRepository sessionRepository;
  @Autowired TickSnapshotRepository snapshotRepository;
  @Autowired TickLogRepository logRepository;
  @Autowired TickOverlayRepository overlayRepository;
  @Autowired TimelineMarkerRepository markerRepository;
  @Autowired ReplayTickBatchReceiptRepository receiptRepository;
  @Autowired ReplayRecordingActionRepository actionRepository;
  @Autowired PlatformTransactionManager txManager;
  @Autowired ObjectMapper objectMapper;

  private TransactionTemplate tx() {
    return new TransactionTemplate(txManager);
  }

  @Test
  void successfulBatchCreatesOneReceiptSnapshotLogsOverlaysActionsAndBounds() {
    String sessionId = "durable-" + UUID.randomUUID();
    String batchId = UUID.randomUUID().toString();
    seedSession(sessionId, 100L);
    TickBatchMessage batch = strictBatch(sessionId, batchId, 100L);
    batch.setLogs(
        List.of(
            new TickBatchMessage.TickLogEntry("System", "Info", null, "hello", null, null)));
    batch.setOverlays(
        List.of(new TickBatchMessage.TickOverlayEntry("note", Map.of("k", "v"))));
    applyChecksum(batch);

    TickStreamIngestionService.TickBatchAcknowledgment acknowledgment =
        ingestionService.processTickBatch(batch, wireDocument(batch));

    assertEquals("PERSISTED", acknowledgment.status());
    assertEquals(batchId, acknowledgment.batchId());
    assertEquals(batch.getPayloadChecksum(), acknowledgment.payloadChecksum());
    assertTrue(
        receiptRepository.findBySessionIdAndBatchId(sessionId, UUID.fromString(batchId)).isPresent(),
        "one receipt must exist");
    assertEquals(1, snapshotRepository.countBySessionId(sessionId));
    assertEquals(1, logRepository.countBySessionId(sessionId));
    assertEquals(1, overlayRepository.countBySessionId(sessionId));
    assertEquals(
        100L,
        sessionRepository.findBySessionId(sessionId).orElseThrow().getEndTick(),
        "session bounds must advance");
  }

  @Test
  void overlayFailureRollsBackReceiptSnapshotLogsActionsAndBounds() {
    String sessionId = "durable-" + UUID.randomUUID();
    seedSession(sessionId, 200L);
    // An overlay with a null type violates the NOT NULL overlay_type column after the receipt,
    // snapshot, logs, and actions have been written: everything must roll back together.
    TickBatchMessage batch = strictBatch(sessionId, UUID.randomUUID().toString(), 200L);
    batch.setOverlays(
        List.of(new TickBatchMessage.TickOverlayEntry(null, Map.of("k", "v"))));
    applyChecksum(batch);

    assertThrows(Exception.class, () -> ingestionService.processTickBatch(batch, wireDocument(batch)));

    assertEquals(0, receiptRepository.findAll().stream()
        .filter(receipt -> sessionId.equals(receipt.getSessionId())).count());
    assertEquals(0, snapshotRepository.countBySessionId(sessionId));
    assertEquals(0, logRepository.countBySessionId(sessionId));
    assertEquals(0, overlayRepository.countBySessionId(sessionId));
    assertEquals(
        200L,
        sessionRepository.findBySessionId(sessionId).orElseThrow().getEndTick(),
        "failed ingestion must not move session bounds");
  }

  @Test
  void actionFailureRollsBackTheReceipt() {
    String sessionId = "durable-" + UUID.randomUUID();
    seedSession(sessionId, 300L);
    TickBatchMessage batch = strictBatch(sessionId, UUID.randomUUID().toString(), 300L);
    RecordingAction tampered = action(sessionId, 300L, 0);
    tampered.getArguments().put("option", "talk-to");
    batch.setActions(List.of(tampered));
    applyChecksum(batch);

    assertThrows(
        IllegalArgumentException.class,
        () -> ingestionService.processTickBatch(batch, wireDocument(batch)));

    assertEquals(0, receiptRepository.findAll().stream()
        .filter(receipt -> sessionId.equals(receipt.getSessionId())).count());
    assertEquals(0, snapshotRepository.countBySessionId(sessionId));
  }

  @Test
  void commitTimeConstraintFailureSendsNoAcknowledgment() {
    String sessionId = "durable-" + UUID.randomUUID();
    seedSession(sessionId, 400L);
    TickBatchMessage batch = strictBatch(sessionId, UUID.randomUUID().toString(), 400L);
    // Null message violates the NOT NULL tick_logs.message column at flush time.
    batch.setLogs(
        List.of(new TickBatchMessage.TickLogEntry("System", "Info", null, null, null, null)));
    applyChecksum(batch);

    Exception failure =
        assertThrows(
            Exception.class, () -> ingestionService.processTickBatch(batch, wireDocument(batch)));
    assertTrue(
        failure instanceof IllegalArgumentException
            || failure instanceof org.springframework.dao.DataIntegrityViolationException
            || failure instanceof jakarta.persistence.PersistenceException,
        "constraint failure must surface, got " + failure.getClass());

    assertEquals(0, receiptRepository.findAll().stream()
        .filter(receipt -> sessionId.equals(receipt.getSessionId())).count());
    assertEquals(0, logRepository.countBySessionId(sessionId));
  }

  @Test
  void concurrentExactDuplicatesYieldOneEvidenceSet() throws Exception {
    String sessionId = "durable-" + UUID.randomUUID();
    seedSession(sessionId, 500L);
    String batchId = UUID.randomUUID().toString();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<TickStreamIngestionService.TickBatchAcknowledgment>> futures =
          List.of(
              pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                TickBatchMessage batch = strictBatch(sessionId, batchId, 500L);
                applyChecksum(batch);
                return ingestionService.processTickBatch(batch, wireDocument(batch));
              }),
              pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                TickBatchMessage batch = strictBatch(sessionId, batchId, 500L);
                applyChecksum(batch);
                return ingestionService.processTickBatch(batch, wireDocument(batch));
              }));
      TickStreamIngestionService.TickBatchAcknowledgment first = futures.get(0).get(60, TimeUnit.SECONDS);
      TickStreamIngestionService.TickBatchAcknowledgment second = futures.get(1).get(60, TimeUnit.SECONDS);
      assertEquals(first.batchId(), second.batchId());
      assertEquals(first.payloadChecksum(), second.payloadChecksum());
      assertEquals("PERSISTED", first.status());
      assertEquals("PERSISTED", second.status());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, receiptRepository.findAll().stream()
        .filter(receipt -> sessionId.equals(receipt.getSessionId())).count());
    assertEquals(1, snapshotRepository.countBySessionId(sessionId));
  }

  @Test
  void concurrentConflictingBatchesYieldExactlyOneWinner() throws Exception {
    String sessionId = "durable-" + UUID.randomUUID();
    seedSession(sessionId, 600L);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    String batchA = UUID.randomUUID().toString();
    String batchB = UUID.randomUUID().toString();
    try {
      List<Future<String>> futures =
          List.of(
              pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                TickBatchMessage batch = strictBatch(sessionId, batchA, 600L);
                batch.getPlayerState().put("markerA", "a");
                applyChecksum(batch);
                try {
                  ingestionService.processTickBatch(batch, wireDocument(batch));
                  return batchA;
                } catch (IllegalArgumentException conflict) {
                  return "conflict:" + batchA;
                }
              }),
              pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                TickBatchMessage batch = strictBatch(sessionId, batchB, 600L);
                batch.getPlayerState().put("markerB", "b");
                applyChecksum(batch);
                try {
                  ingestionService.processTickBatch(batch, wireDocument(batch));
                  return batchB;
                } catch (IllegalArgumentException conflict) {
                  return "conflict:" + batchB;
                }
              }));
      String resultA = futures.get(0).get(60, TimeUnit.SECONDS);
      String resultB = futures.get(1).get(60, TimeUnit.SECONDS);
      boolean exactlyOneWinner =
          (resultA.equals(batchA) && resultB.startsWith("conflict:"))
              || (resultB.equals(batchB) && resultA.startsWith("conflict:"));
      assertTrue(exactlyOneWinner, "exactly one conflicting batch must win, got " + resultA + " / " + resultB);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, receiptRepository.findAll().stream()
        .filter(receipt -> sessionId.equals(receipt.getSessionId())).count());
  }

  @Test
  void deletingAnEligibleSessionRemovesItsReceipts() {
    String sessionId = "durable-" + UUID.randomUUID();
    seedSession(sessionId, 700L);
    TickBatchMessage batch = strictBatch(sessionId, UUID.randomUUID().toString(), 700L);
    applyChecksum(batch);
    ingestionService.processTickBatch(batch, wireDocument(batch));
    assertEquals(1, receiptRepository.findAll().stream()
        .filter(receipt -> sessionId.equals(receipt.getSessionId())).count());

    tx().executeWithoutResult(status -> {
      DebugSession session = sessionRepository.findBySessionId(sessionId).orElseThrow();
      sessionRepository.delete(session);
    });

    assertEquals(0, receiptRepository.findAll().stream()
        .filter(receipt -> sessionId.equals(receipt.getSessionId())).count(),
        "receipt rows must follow the documented cascade policy");
  }

  private void seedSession(String sessionId, long startTick) {
    tx().executeWithoutResult(status -> {
      DebugSession session =
          new DebugSession(sessionId, "account", "script", LocalDateTime.now(), startTick);
      session.setEndTick(startTick);
      sessionRepository.saveAndFlush(session);
    });
  }

  private TickBatchMessage strictBatch(String sessionId, String batchId, long tick) {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setType("TICK_BATCH");
    batch.setSchemaVersion(2);
    batch.setBatchId(batchId);
    batch.setSessionId(sessionId);
    batch.setTick(tick);
    batch.setTimestamp("2026-09-04T00:00:00Z");
    Map<String, Object> playerState = new HashMap<>();
    playerState.put("x", 3200);
    playerState.put("z", 3200);
    batch.setPlayerState(playerState);
    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());
    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    quest.put("changed", false);
    batch.setQuest(quest);
    batch.setActions(List.of(action(sessionId, tick, 0)));
    return batch;
  }

  private RecordingAction action(String sessionId, long tick, int sequence) {
    RecordingAction action = new RecordingAction();
    action.setRecordingId(null);
    action.setSessionId(sessionId);
    action.setTick(tick);
    action.setSequence(sequence);
    action.setActionType("WAIT_UNTIL_TICK");
    action.setTarget(new HashMap<>());
    action.setArguments(new HashMap<>());
    action.setSource(RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH);
    action.setProvenance(RecordingAction.PROVENANCE_CAPTURED);
    action.setConfidence(BigDecimal.ONE);
    action.setCapturedAt("2026-09-04T00:00:00Z");
    // Package-private producer checksum: build the identical content document locally through the
    // shared canonical checksum service instead of calling into the ingestion package.
    com.fasterxml.jackson.databind.node.ObjectNode content = objectMapper.createObjectNode();
    content.put("schema", action.getSchema());
    content.putNull("recordingId");
    content.put("sessionId", action.getSessionId());
    content.put("tick", action.getTick());
    content.put("sequence", action.getSequence());
    content.put("actionType", action.getActionType());
    content.set("target", objectMapper.valueToTree(Map.of()));
    content.set("arguments", objectMapper.valueToTree(Map.of()));
    content.put("source", action.getSource());
    content.put("provenance", action.getProvenance());
    content.put("confidence", action.getConfidence());
    content.put("capturedAt", action.getCapturedAt());
    action.setContentChecksum(
        new TickBatchCanonicalChecksumService(objectMapper).checksum(content));
    return action;
  }

  private void applyChecksum(TickBatchMessage batch) {
    TickBatchCanonicalChecksumService checksums =
        new TickBatchCanonicalChecksumService(objectMapper);
    JsonNode document = objectMapper.valueToTree(batch);
    removeNulls((ObjectNode) document);
    ((ObjectNode) document).remove("payloadChecksum");
    batch.setPayloadChecksum(checksums.checksum(document));
  }

  private JsonNode wireDocument(TickBatchMessage batch) {
    JsonNode document = objectMapper.valueToTree(batch);
    removeNulls((ObjectNode) document);
    return document;
  }

  private void removeNulls(ObjectNode object) {
    List<String> nullFields = new ArrayList<>();
    object.fields().forEachRemaining(entry -> {
      if (entry.getValue() == null || entry.getValue().isNull()) {
        nullFields.add(entry.getKey());
      } else if (entry.getValue().isObject()) {
        removeNulls((ObjectNode) entry.getValue());
      } else if (entry.getValue().isArray()) {
        entry.getValue().forEach(child -> {
          if (child.isObject()) {
            removeNulls((ObjectNode) child);
          }
        });
      }
    });
    nullFields.forEach(object::remove);
  }
}
