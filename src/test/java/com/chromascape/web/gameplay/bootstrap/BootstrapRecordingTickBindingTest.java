package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
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
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingStart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SC-1498 finding 6: a bootstrap recording must be the recording of the debug session that carries
 * the run's ticks.
 *
 * <p>The bootstrap coordinator owns the video. The client's AccountBuilder run emits its tick
 * stream through a debug session it creates first, so the approved recording has to be bound to
 * that session — otherwise the projection exists but no tick snapshot, and no scenario manifest,
 * can ever reference the approved recording.
 */
@SpringBootTest(
    classes = BootstrapRecordingTickBindingTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class BootstrapRecordingTickBindingTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);
  private static final String RECORDING_ID = "rec-bind-1";

  @Autowired private BootstrapRecordingCoordinator coordinator;
  @Autowired private DebugSessionService sessionService;
  @Autowired private DebugSessionRepository sessionRepository;
  @Autowired private TickStreamIngestionService ingestionService;
  @Autowired private TickSnapshotRepository snapshotRepository;
  @Autowired private RecordingSessionRepository recordingSessionRepository;
  @Autowired private RecordingService recordingService;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void approvedRecordingBindsToTheTickBearingSessionAndOnlyOneRecordingExists() {
    var start =
        sessionService.startSessionWithOutcome(
            "bootstrap-account",
            "Fishing",
            false,
            RecordingTriggerSource.TASK_AUTO,
            null,
            null,
            null,
            null,
            null);
    String sessionId = start.response().getSessionId();
    assertThat(sessionRepository.findBySessionId(sessionId).orElseThrow().getRecordingId())
        .as("the client session starts unrecorded")
        .isNull();

    coordinator.start(
        "orch-bind-1",
        RECORDING_ID,
        "session-bind-1",
        "turn-bind-1",
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "CLAUDE",
        "model-v1",
        "bootstrap-account",
        "Fishing");

    DebugSession bound =
        sessionRepository
            .findByRecordingId(RECORDING_ID)
            .orElseThrow(() -> new AssertionError("no debug session is linked to the recording"));
    assertThat(bound.getSessionId())
        .as("the tick-bearing session owns the approved recording")
        .isEqualTo(sessionId);
    verify(recordingService, times(1))
        .startRecording(eq(RECORDING_ID), any(), any(), any());
    assertThat(recordingSessionRepository.count())
        .as("exactly one recording projection exists")
        .isEqualTo(1);

    for (long tick = 100; tick <= 102; tick++) {
      TickBatchMessage batch = strictBatch(sessionId, UUID.randomUUID().toString(), tick);
      applyChecksum(batch);
      ingestionService.processTickBatch(batch, wireDocument(batch));
    }

    List<Long> ticks =
        snapshotRepository.findBySessionIdOrderByTick(sessionId).stream()
            .map(snapshot -> snapshot.getTick())
            .toList();
    assertThat(ticks)
        .as("the approved recording carries contiguous ticks from its session")
        .containsExactly(100L, 101L, 102L);
  }

  private TickBatchMessage strictBatch(String sessionId, String batchId, long tick) {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setType("TICK_BATCH");
    batch.setSchemaVersion(2);
    batch.setBatchId(batchId);
    batch.setSessionId(sessionId);
    batch.setTick(tick);
    batch.setTimestamp("2026-08-30T12:00:00Z");
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
    return batch;
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

  private static void removeNulls(ObjectNode object) {
    List<String> nullFields = new ArrayList<>();
    object
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue() == null || entry.getValue().isNull()) {
                nullFields.add(entry.getKey());
              } else if (entry.getValue().isObject()) {
                removeNulls((ObjectNode) entry.getValue());
              } else if (entry.getValue().isArray()) {
                entry
                    .getValue()
                    .forEach(
                        element -> {
                          if (element.isObject()) {
                            removeNulls((ObjectNode) element);
                          }
                        });
              }
            });
    nullFields.forEach(object::remove);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(
      basePackages = {
        "com.chromascape.web.gameplay.bootstrap",
        "com.chromascape.web.debug.tickdebugger.entity",
        "com.chromascape.web.recording.entity"
      })
  @EnableJpaRepositories(
      basePackages = {
        "com.chromascape.web.gameplay.bootstrap",
        "com.chromascape.web.debug.tickdebugger.entity",
        "com.chromascape.web.recording.entity"
      })
  @Import({JsonConfiguration.class, TickServices.class})
  static class TestApplication {}

  @TestConfiguration
  static class TickServices {
    @Bean
    DebugSessionService debugSessionService(
        DebugSessionRepository sessionRepository,
        TickSnapshotRepository snapshotRepository,
        TickLogRepository tickLogRepository,
        TimelineMarkerRepository timelineMarkerRepository,
        RecordingService recordingService,
        RecordingSessionRepository recordingSessionRepository,
        RecordingConfig recordingConfig,
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
    RecordingService recordingService() {
      RecordingService service = mock(RecordingService.class);
      when(service.startRecording(any(), any(), any(), any()))
          .thenReturn(
              new RecordingStart(
                  RECORDING_ID, "target/rec-bind-1.mp4", CLOCK.millis(), null, null, null, false));
      return service;
    }

    @Bean
    RecordingConfig recordingConfig() {
      return mock(RecordingConfig.class);
    }

    @Bean
    BootstrapFinalizationJournal bootstrapFinalizationJournal(EntityManager entityManager) {
      return new BootstrapFinalizationJournal(entityManager, CLOCK);
    }

    @Bean
    BootstrapRecordingCoordinator bootstrapRecordingCoordinator(
        EntityManager entityManager,
        RecordingService recordingService,
        RecordingSessionRepository recordingSessionRepository,
        DebugSessionRepository debugSessionRepository,
        BootstrapFinalizationJournal finalizationJournal) {
      return new BootstrapRecordingCoordinator(
          entityManager,
          recordingService,
          recordingSessionRepository,
          debugSessionRepository,
          finalizationJournal,
          CLOCK);
    }
  }

  @TestConfiguration
  static class JsonConfiguration {
    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder()
          .findAndAddModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();
    }

    @Bean
    CanonicalGameplayJson canonicalGameplayJson(ObjectMapper objectMapper) {
      return new CanonicalGameplayJson(objectMapper);
    }
  }
}
