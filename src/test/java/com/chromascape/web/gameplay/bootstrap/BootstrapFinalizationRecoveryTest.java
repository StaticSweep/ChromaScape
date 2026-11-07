package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.BootstrapRecordingException;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * SC-1498 finding 5: stopping the external recording is irreversible, so recovery must never see an
 * empty artifact for a stop that already happened.
 *
 * <p>The intent to finalize is journalled and committed before the recorder is stopped. When no
 * live recording remains, the artifact is rebuilt from the durable session row and its video file
 * instead of being reported as absent; when even that evidence is unavailable the call fails closed
 * with a stable code rather than silently leaving a STARTED projection.
 */
@SpringBootTest(
    classes = BootstrapFinalizationRecoveryTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class BootstrapFinalizationRecoveryTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);

  @Autowired private BootstrapRecordingCoordinator coordinator;
  @Autowired private RecordingSessionRepository recordingSessions;
  @Autowired private RecordingService recordingService;
  @Autowired private JdbcTemplate jdbc;
  @TempDir private Path videoDirectory;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @BeforeEach
  void resetRecorder() {
    // The recorder is a context-wide mock; each test owns its own stop behaviour.
    reset(recordingService);
  }

  @Test
  void stopIntentIsDurableBeforeTheIrreversibleRecorderStop() {
    seedProjection("orch-stop-1", "rec-stop-1");
    when(recordingService.stopRecordingIfCurrentWithMetadata(anyString()))
        .thenThrow(new IllegalStateException("recorder crashed mid-stop"));

    // The recorder failure surfaces through Spring's JPA exception translation; the cause is the
    // recorder's own error and is what the caller must see.
    assertThatThrownBy(() -> coordinator.stopForFinalization("rec-stop-1"))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("recorder crashed mid-stop");

    assertThat(finalizationRequestedAt("orch-stop-1"))
        .as("the intent is committed before the stop can be lost")
        .isNotNull();
  }

  @Test
  void anAlreadyStoppedRecordingIsRebuiltFromItsDurableVideo() throws Exception {
    seedProjection("orch-stop-2", "rec-stop-2");
    Path video = videoDirectory.resolve("rec-stop-2.mp4");
    Files.write(video, "bootstrap-frames".getBytes(StandardCharsets.UTF_8));
    RecordingSession session =
        new RecordingSession(
            "rec-stop-2",
            "bootstrap-account",
            "Fishing",
            LocalDateTime.of(2026, 8, 30, 11, 59, 30),
            RecordingSession.SessionTrigger.BOOTSTRAP_RECORDING,
            video.toString());
    recordingSessions.saveAndFlush(session);
    // The live recording is gone: the stop reports no metadata, which previously became an empty
    // artifact and stranded the STARTED projection forever.
    when(recordingService.stopRecordingIfCurrentWithMetadata("rec-stop-2")).thenReturn(null);

    var artifact = coordinator.stopForFinalization("rec-stop-2").orElseThrow();

    assertThat(artifact.videoPath()).isEqualTo(video.toString());
    assertThat(artifact.artifactDigest()).isEqualTo(sha256(video));
    assertThat(artifact.finalizedAt()).isNotNull();
    assertThat(recordingSessions.findByRecordingId("rec-stop-2").orElseThrow().getEndTime())
        .as("the reconstructed end time is durable")
        .isNotNull();
    assertThat(coordinator.stopForFinalization("rec-stop-2").orElseThrow().artifactDigest())
        .as("a second recovery attempt returns the same bytes")
        .isEqualTo(artifact.artifactDigest());
  }

  @Test
  void aStopWithoutDurableEvidenceFailsClosed() {
    seedProjection("orch-stop-3", "rec-stop-3");
    when(recordingService.stopRecordingIfCurrentWithMetadata("rec-stop-3")).thenReturn(null);

    assertThatThrownBy(() -> coordinator.stopForFinalization("rec-stop-3"))
        .isInstanceOfSatisfying(
            BootstrapRecordingException.class,
            exception -> assertThat(exception.failureCode()).isEqualTo("RECORDING_STOP_UNRECONCILED"));
  }

  private void seedProjection(String orchestrationId, String recordingId) {
    jdbc.execute(
        "INSERT INTO gameplay_bootstrap_recording (orchestration_id, recording_id,"
            + " provider_session_id, provider_turn_id, decision_digest, envelope_digest, provider,"
            + " model_version, manifest_state, created_at, entity_version) VALUES ('"
            + orchestrationId
            + "','"
            + recordingId
            + "','session-x','turn-x','sha256:"
            + "a".repeat(64)
            + "','sha256:"
            + "b".repeat(64)
            + "','CLAUDE','model-v1','STARTED', now(), 0) ON CONFLICT DO NOTHING");
  }

  private LocalDateTime finalizationRequestedAt(String orchestrationId) {
    return jdbc.queryForObject(
        "SELECT finalization_requested_at FROM gameplay_bootstrap_recording WHERE"
            + " orchestration_id = ?",
        LocalDateTime.class,
        orchestrationId);
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return "sha256:" + HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
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
