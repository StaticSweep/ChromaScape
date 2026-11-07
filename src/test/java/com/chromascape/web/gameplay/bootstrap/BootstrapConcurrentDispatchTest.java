package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.RecordingView;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SC-1498 finding 1: two truly concurrent identical dispatches must converge on one durable owner
 * and one active recording, and the request that loses the claim must never stop the winner's
 * recording.
 */
@SpringBootTest(
    classes = BootstrapConcurrentDispatchTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class BootstrapConcurrentDispatchTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);
  private static final String ORCHESTRATION_ID = "orch-race-1";
  private static final String RECORDING_ID = "rec-race-1";

  @Autowired private BootstrapRecordingCoordinator coordinator;
  @Autowired private DebugSessionRepository debugSessions;
  @Autowired private RecordingService recordingService;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void concurrentIdenticalStartsLeaveOneOwnerAndOneRecording() throws Exception {
    debugSessions.saveAndFlush(
        new DebugSession(
            "session-race-1", "race-account", "Fishing", LocalDateTime.now(), 0L));
    AtomicInteger starts = new AtomicInteger();
    // Widen the window a real recording start would occupy so an unserialized second dispatch
    // would definitely reach the recorder too.
    when(recordingService.startRecording(anyString(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              starts.incrementAndGet();
              Thread.sleep(400L);
              return new RecordingStart(
                  invocation.getArgument(0),
                  "target/rec-race-1.mp4",
                  CLOCK.millis(),
                  null,
                  null,
                  null,
                  false);
            });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    try {
      Callable<RecordingView> start =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return coordinator.start(
                ORCHESTRATION_ID,
                RECORDING_ID,
                "session-provider-1",
                "turn-provider-1",
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64),
                "CLAUDE",
                "model-v1",
                "race-account",
                "Fishing");
          };
      Future<RecordingView> first = pool.submit(start);
      Future<RecordingView> second = pool.submit(start);

      assertThat(first.get(60, TimeUnit.SECONDS).manifestState()).isEqualTo("STARTED");
      assertThat(second.get(60, TimeUnit.SECONDS).manifestState()).isEqualTo("STARTED");
    } finally {
      pool.shutdownNow();
    }

    assertThat(starts)
        .as("exactly one dispatch owns the external recording")
        .hasValue(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gameplay_bootstrap_recording WHERE orchestration_id = ?",
                Integer.class,
                ORCHESTRATION_ID))
        .as("one durable projection")
        .isEqualTo(1);
    verify(recordingService, never()).stopRecordingIfCurrent(anyString());
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
  @Import(JsonConfiguration.class)
  static class TestApplication {
    @Bean
    RecordingService recordingService() {
      return mock(RecordingService.class);
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
