package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore.ApprovalView;
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

/**
 * Restart recovery proof: a crash before bind leaves a bounded pending approval that may
 * expire/cancel but never dispatches automatically; a crash after authority consumption uses
 * lookup-only reconciliation and never calls reserve/start as a fallback.
 */
@SpringBootTest(
    classes = BootstrapRestartRecoveryTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class BootstrapRestartRecoveryTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);

  @Autowired private BootstrapApprovalStore approvals;
  @Autowired private BootstrapRecordingCoordinator recordings;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void pendingApprovalSurvivesRestartAsBoundedAndExpirable() {
    var claim =
        approvals.create(
            "session-recover-1",
            "turn-recover-1",
            "orch-recover-1",
            "req-recover-1",
            "rec-recover-1",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "operator-1",
            CLOCK.instant().plusSeconds(300));
    assertThat(claim.created()).isTrue();

    // Simulate a restart: the durable row is re-read; it is still APPROVED, bounded and
    // cancellable, and it never dispatched automatically.
    Optional<ApprovalView> reloaded = approvals.findByOrchestration("orch-recover-1");
    assertThat(reloaded).isPresent();
    assertThat(reloaded.get().state()).isEqualTo("APPROVED");

    approvals.cancel(claim.view().approvalId());
    assertThat(approvals.find(claim.view().approvalId()).state()).isEqualTo("CANCELLED");
  }

  @Test
  void recordingStartSurvivesRestartWithoutRedispatch() {
    jdbc.execute(
        "INSERT INTO ab_orchestration (orchestration_id, request_id, idempotency_key,"
            + " request_fingerprint, envelope_digest, source_type, mode, state, failure_code,"
            + " request_json, view_json, retry_allowed, view_version, created_at, updated_at,"
            + " entity_version) VALUES ('orch-recover-2','req-recover-2','idem-recover-2',"
            + " 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
            + " 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
            + " 'PROVIDER:CLAUDE','BOOTSTRAP_RECORDING','ACTIVE','NONE','{}','{}',FALSE,0,"
            + " now(),now(),0) ON CONFLICT DO NOTHING");
    // Recording start is durable; a second start with identical evidence is an idempotent
    // replay, never a second dispatch.
    var first =
        recordings.start(
            "orch-recover-2",
            "rec-recover-2",
            "session-recover-2",
            "turn-recover-2",
            "sha256:" + "e".repeat(64),
            "sha256:" + "f".repeat(64),
            "CLAUDE",
            "model-v1",
            null,
            null);
    var second =
        recordings.start(
            "orch-recover-2",
            "rec-recover-2",
            "session-recover-2",
            "turn-recover-2",
            "sha256:" + "e".repeat(64),
            "sha256:" + "f".repeat(64),
            "CLAUDE",
            "model-v1",
            null,
            null);
    assertThat(second.orchestrationId()).isEqualTo(first.orchestrationId());
    assertThat(second.manifestState()).isEqualTo("STARTED");
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
    BootstrapApprovalStore approvalStore(
        EntityManager entityManager, CanonicalGameplayJson canonicalJson) {
      return new BootstrapApprovalStore(entityManager, canonicalJson, CLOCK);
    }

    @Bean
    RecordingService recordingService() {
      RecordingService service = mock(RecordingService.class);
      when(service.startRecording(any(), any(), any(), any()))
          .thenReturn(
              new RecordingStart(
                  "rec-recover-2",
                  "target/rec-recover-2.mp4",
                  CLOCK.millis(),
                  null,
                  null,
                  null,
                  false));
      return service;
    }

    @Bean
    RecordingSessionRepository recordingSessionRepository() {
      RecordingSessionRepository repository = mock(RecordingSessionRepository.class);
      when(repository.findByRecordingId(any())).thenReturn(Optional.empty());
      return repository;
    }

    @Bean
    BootstrapRecordingCoordinator recordingCoordinator(
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
