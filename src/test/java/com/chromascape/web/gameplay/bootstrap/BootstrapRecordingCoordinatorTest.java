package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.BootstrapRecordingException;
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
import java.time.ZoneOffset;
import java.util.Optional;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Durable recording start/finalize lifecycle for SC-1498. */
@SpringBootTest(
    classes = BootstrapRecordingCoordinatorTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class BootstrapRecordingCoordinatorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);

  @Autowired private BootstrapRecordingCoordinator coordinator;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void startIsIdempotentAndFinalizeIsWriteOnce() {
    // The recording references an SC-1431 orchestration with no foreign key; create the minimal
    // parent row so the V89 terminal-evidence gate can be satisfied. Only the identity columns
    // matter for this projection test.
    jdbc.execute(
        "INSERT INTO ab_orchestration (orchestration_id, request_id, idempotency_key,"
            + " request_fingerprint, envelope_digest, source_type, mode, state, failure_code,"
            + " request_json, view_json, retry_allowed, view_version, created_at, updated_at,"
            + " entity_version) VALUES ('orch-rec-1','req-rec-1','idem-rec-1',"
            + " 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
            + " 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
            + " 'PROVIDER:CLAUDE','BOOTSTRAP_RECORDING','ACTIVE','NONE','{}','{}',FALSE,0,"
            + " now(),now(),0) ON CONFLICT DO NOTHING");
    // V89 requires durable terminal SC-1431 evidence before the STARTED projection may finalize.
    jdbc.execute(
        "INSERT INTO ab_orchestration_terminal_result (orchestration_id, outcome, stable_code,"
            + " retry_allowed, reconciled, evidence_digest, terminal_at) VALUES ('orch-rec-1',"
            + " 'CONFIRMED','NONE',FALSE,TRUE,"
            + " 'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',now())"
            + " ON CONFLICT DO NOTHING");

    var first =
        coordinator.start(
            "orch-rec-1",
            "rec-1",
            "session-1",
            "turn-1",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "CLAUDE",
            "model-v1",
            null,
            null);
    var second =
        coordinator.start(
            "orch-rec-1",
            "rec-1",
            "session-1",
            "turn-1",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "CLAUDE",
            "model-v1",
            null,
            null);
    assertThat(second.orchestrationId()).isEqualTo(first.orchestrationId());
    assertThat(second.manifestState()).isEqualTo("STARTED");

    assertThatThrownBy(
            () ->
                coordinator.start(
                    "orch-rec-1",
                    "rec-1",
                    "session-1",
                    "turn-1",
                    "sha256:" + "c".repeat(64),
                    "sha256:" + "b".repeat(64),
                    "CLAUDE",
                    "model-v1",
                    null,
                    null))
        .isInstanceOf(BootstrapRecordingException.class);

    String manifest = "sha256:" + "d".repeat(64);
    var finalized =
        coordinator.finalizeRecording(
            "orch-rec-1", "CONFIRMED", "NONE", manifest, "file.json", manifest);
    assertThat(finalized.manifestState()).isEqualTo("FINALIZED");

    var replay =
        coordinator.finalizeRecording(
            "orch-rec-1", "CONFIRMED", "NONE", manifest, "file.json", manifest);
    assertThat(replay.manifestDigest()).isEqualTo(manifest);

    assertThatThrownBy(
            () ->
                coordinator.finalizeRecording(
                    "orch-rec-1", "REFUTED", "NONE", "sha256:" + "e".repeat(64), null, null))
        .isInstanceOf(BootstrapRecordingException.class);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(
      basePackages = {
        "com.chromascape.web.gameplay.bootstrap",
        "com.chromascape.web.gameplay.orchestration.accountbuilder"
      })
  @Import(JsonConfiguration.class)
  static class TestApplication {
    @Bean
    RecordingService recordingService() {
      RecordingService service = mock(RecordingService.class);
      when(service.startRecording(any(), any(), any(), any()))
          .thenReturn(
              new RecordingStart(
                  "rec-1", "target/rec-1.mp4", CLOCK.millis(), null, null, null, false));
      return service;
    }

    @Bean
    RecordingSessionRepository recordingSessionRepository() {
      RecordingSessionRepository repository = mock(RecordingSessionRepository.class);
      when(repository.findByRecordingId(any())).thenReturn(Optional.empty());
      return repository;
    }

    @Bean
    BootstrapRecordingCoordinator coordinator(
        EntityManager entityManager,
        RecordingService recordingService,
        RecordingSessionRepository recordingSessionRepository) {
      return new BootstrapRecordingCoordinator(
          entityManager, recordingService, recordingSessionRepository, CLOCK);
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
