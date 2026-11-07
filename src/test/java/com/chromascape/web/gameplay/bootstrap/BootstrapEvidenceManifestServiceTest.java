package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore.ApprovalView;
import com.chromascape.web.gameplay.bootstrap.BootstrapEvidenceManifestService.ManifestPublication;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.RecordingArtifact;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.RecordingView;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the SC-1498 evidence manifest is a pure function of durable inputs: the same approval,
 * recording, artifact, decision, envelope, bindings, observation, authority, terminal orchestration
 * and durable finalization timestamp always produce byte-identical bytes and digest, so crash
 * recovery between publication and database finalization replays the identical file.
 */
class BootstrapEvidenceManifestServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:01:00Z");

  @TempDir private Path evidenceRoot;

  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private JsonNode fixtures;
  private BootstrapEvidenceManifestService service;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    fixtures =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    service = new BootstrapEvidenceManifestService(objectMapper, canonicalJson, evidenceRoot);
  }

  @Test
  void identicalDurableInputsProduceIdenticalManifestAndDigest() {
    Inputs inputs = inputs();
    Instant finalizedAt = NOW.plus(1, ChronoUnit.MINUTES);

    ManifestPublication first =
        service.buildManifest(
            inputs.approval(),
            inputs.recording(),
            inputs.artifact(),
            inputs.decision(),
            inputs.envelope(),
            inputs.bindings(),
            inputs.observation(),
            inputs.authority(),
            inputs.view(),
            finalizedAt);
    ManifestPublication second =
        service.buildManifest(
            inputs.approval(),
            inputs.recording(),
            inputs.artifact(),
            inputs.decision(),
            inputs.envelope(),
            inputs.bindings(),
            inputs.observation(),
            inputs.authority(),
            inputs.view(),
            finalizedAt);

    assertThat(second.manifestDigest()).isEqualTo(first.manifestDigest());
    assertThat(canonicalJson.digest(second.document()))
        .isEqualTo(canonicalJson.digest(first.document()));
    assertThat(first.document().get("finalizedAt")).isEqualTo(finalizedAt.toString());
  }

  @Test
  void finalizedAtIsTheOnlyTimeInput() {
    Inputs inputs = inputs();

    ManifestPublication first =
        service.buildManifest(
            inputs.approval(),
            inputs.recording(),
            inputs.artifact(),
            inputs.decision(),
            inputs.envelope(),
            inputs.bindings(),
            inputs.observation(),
            inputs.authority(),
            inputs.view(),
            NOW.plus(1, ChronoUnit.MINUTES));
    ManifestPublication second =
        service.buildManifest(
            inputs.approval(),
            inputs.recording(),
            inputs.artifact(),
            inputs.decision(),
            inputs.envelope(),
            inputs.bindings(),
            inputs.observation(),
            inputs.authority(),
            inputs.view(),
            NOW.plus(2, ChronoUnit.MINUTES));

    assertThat(second.manifestDigest()).isNotEqualTo(first.manifestDigest());
  }

  @Test
  void missingFinalizedAtFailsClosed() {
    Inputs inputs = inputs();
    assertThatThrownBy(
            () ->
                service.buildManifest(
                    inputs.approval(),
                    inputs.recording(),
                    inputs.artifact(),
                    inputs.decision(),
                    inputs.envelope(),
                    inputs.bindings(),
                    inputs.observation(),
                    inputs.authority(),
                    inputs.view(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("durable finalization timestamp");
  }

  @Test
  void identicalRepublicationIsIdempotentAndConflictingRepublicationFailsClosed() {
    Inputs inputs = inputs();
    Instant finalizedAt = NOW.plus(1, ChronoUnit.MINUTES);
    ManifestPublication publication =
        service.buildManifest(
            inputs.approval(),
            inputs.recording(),
            inputs.artifact(),
            inputs.decision(),
            inputs.envelope(),
            inputs.bindings(),
            inputs.observation(),
            inputs.authority(),
            inputs.view(),
            finalizedAt);

    Path first =
        service.publish("rec-manifest-1", publication.document(), publication.manifestDigest());
    Path second =
        service.publish("rec-manifest-1", publication.document(), publication.manifestDigest());
    assertThat(second).isEqualTo(first);

    assertThatThrownBy(
            () ->
                service.publish(
                    "rec-manifest-1", publication.document(), "sha256:" + "0".repeat(64)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Immutable bootstrap evidence already exists");
  }

  /**
   * Recovery integration proof (acceptance criterion 22): a manifest was atomically published and
   * the database finalization then rolled back. Because the recording was already stopped before
   * the crash, recovery rebuilds the artifact from the durable recording session and rebuilds the
   * manifest from the same durable inputs and the same durable finalization instant. The result is
   * byte-identical, re-publishes to the same immutable path, and never starts a second recording.
   */
  @Test
  void recoveryRebuildsIdenticalManifestAfterFinalizationRollbackWithoutRedispatch()
      throws Exception {
    String recordingId = "recording-sc1498-001";
    Inputs inputs = inputs();
    Instant finalizedAt = NOW.plus(1, ChronoUnit.MINUTES);

    // The live recording was stopped and finalized to disk before the database rollback.
    Path video = evidenceRoot.resolve(recordingId + ".mp4");
    byte[] videoBytes = "sc1498-durable-recording-bytes".getBytes(StandardCharsets.UTF_8);
    Files.write(video, videoBytes);
    String expectedArtifactDigest =
        "sha256:"
            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(videoBytes));

    RecordingService recordingService = mock(RecordingService.class);
    RecordingSessionRepository sessions = mock(RecordingSessionRepository.class);
    RecordingSession durable =
        new RecordingSession(
            recordingId,
            "account-fixture",
            "task-fixture",
            NOW.atZone(ZoneOffset.UTC).toLocalDateTime(),
            RecordingSession.SessionTrigger.BOOTSTRAP_RECORDING,
            video.toString());
    durable.setEndTime(finalizedAt.atZone(ZoneOffset.UTC).toLocalDateTime());
    when(sessions.findByRecordingId(recordingId)).thenReturn(Optional.of(durable));
    // The crashed run had already committed its finalization intent, which is what makes the
    // existing video recoverable instead of a mid-recording crash.
    BootstrapFinalizationJournal journal = mock(BootstrapFinalizationJournal.class);
    when(journal.finalizationRequested(recordingId)).thenReturn(true);
    BootstrapRecordingCoordinator coordinator =
        new BootstrapRecordingCoordinator(
            mock(EntityManager.class),
            recordingService,
            sessions,
            mock(com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository.class),
            journal,
            Clock.fixed(NOW, ZoneOffset.UTC));

    // Publication succeeded before the database finalization rolled back.
    RecordingArtifact artifact = coordinator.rebuildArtifactForRecovery(recordingId).orElseThrow();
    assertThat(artifact.artifactDigest()).isEqualTo(expectedArtifactDigest);
    ManifestPublication first =
        service.buildManifest(
            inputs.approval(),
            inputs.recording(),
            artifact,
            inputs.decision(),
            inputs.envelope(),
            inputs.bindings(),
            inputs.observation(),
            inputs.authority(),
            inputs.view(),
            finalizedAt);
    Path firstPath = service.publish(recordingId, first.document(), first.manifestDigest());

    // Recovery runs from durable evidence only: the artifact is rebuilt, never re-recorded.
    RecordingArtifact recovered = coordinator.rebuildArtifactForRecovery(recordingId).orElseThrow();
    ManifestPublication recoveredManifest =
        service.buildManifest(
            inputs.approval(),
            inputs.recording(),
            recovered,
            inputs.decision(),
            inputs.envelope(),
            inputs.bindings(),
            inputs.observation(),
            inputs.authority(),
            inputs.view(),
            finalizedAt);
    Path recoveredPath =
        service.publish(
            recordingId, recoveredManifest.document(), recoveredManifest.manifestDigest());

    assertThat(recoveredManifest.manifestDigest()).isEqualTo(first.manifestDigest());
    assertThat(canonicalJson.digest(recoveredManifest.document()))
        .isEqualTo(canonicalJson.digest(first.document()));
    assertThat(Files.readAllBytes(recoveredPath)).isEqualTo(Files.readAllBytes(firstPath));
    assertThat(recoveredPath).isEqualTo(firstPath);
    verify(recordingService, never()).startRecording(any(), any(), any(), any());
  }

  private Inputs inputs() {
    JsonNode observation = fixtures.path("observation").deepCopy();
    ExecutionEnvelope envelope = envelope();
    JsonNode action = fixtures.path("semanticAction").deepCopy();
    ProviderDecision decision =
        new ProviderDecision(
            "sc-1429/v1",
            Provider.CLAUDE,
            envelope.modelVersion(),
            envelope.decisionId(),
            0.9,
            action,
            List.of("observation:obs-sc1429-001"));
    SnapshotBindings bindings =
        new SnapshotBindings(
            canonicalJson.digest(decision),
            canonicalJson.digest(action),
            envelope.catalogDigest(),
            "sha256:" + "c".repeat(64),
            envelope.goalPlanDigest(),
            envelope.taskSkillDigests(),
            envelope.schemaDigest(),
            envelope.knowledgeDigest(),
            envelope.policyDigest(),
            envelope.verifierId(),
            envelope.buildId(),
            envelope.expectedObservationRevision(),
            envelope.expectedTaskRevision(),
            canonicalJson.digest(observation),
            "sha256:" + "p".repeat(64),
            "session-fixture",
            "turn-fixture");
    HumanAuthority authority =
        new HumanAuthority(
            "sc-1429/v1",
            "auth-sc1429-001",
            "HUMAN",
            "fixture-operator",
            NOW,
            NOW.plus(10, ChronoUnit.MINUTES),
            canonicalJson.digest(envelope),
            "sha256:" + "3".repeat(64),
            true,
            "hmac-sha256:" + "f".repeat(64));
    ApprovalView approval =
        new ApprovalView(
            "approval-manifest-1",
            authority.scopeDigest(),
            "session-sc1429",
            "turn-sc1429",
            "orch-manifest-1",
            envelope.requestId(),
            "recording-sc1498-001",
            canonicalJson.digest(decision),
            "fixture-operator",
            NOW,
            NOW.plus(10, ChronoUnit.MINUTES),
            "BOUND",
            authority.authorityId(),
            "sha256:" + "f".repeat(64),
            NOW,
            NOW);
    RecordingView recording =
        new RecordingView(
            "orch-manifest-1",
            "recording-sc1498-001",
            "session-sc1429",
            "turn-sc1429",
            canonicalJson.digest(decision),
            canonicalJson.digest(envelope),
            "CLAUDE",
            envelope.modelVersion(),
            "STARTED",
            null,
            null,
            null,
            null,
            null,
            NOW,
            null);
    RecordingArtifact artifact =
        new RecordingArtifact(
            "recording-sc1498-001",
            "target/rec-manifest-1.mp4",
            "sha256:" + "9".repeat(64),
            1000L,
            30L,
            30,
            "libx264",
            1L,
            NOW.atZone(java.time.ZoneOffset.UTC).toLocalDateTime(),
            NOW.plus(1, ChronoUnit.MINUTES).atZone(java.time.ZoneOffset.UTC).toLocalDateTime());
    OrchestrationView view =
        new OrchestrationView(
            "sc-1431/v1",
            "orch-manifest-1",
            envelope.requestId(),
            envelope.idempotencyKey(),
            "sha256:" + "f".repeat(64),
            canonicalJson.digest(envelope),
            AccountBuilderOrchestrationModels.Mode.BOOTSTRAP_RECORDING,
            "PROVIDER:CLAUDE",
            AccountBuilderOrchestrationModels.State.CONFIRMED,
            AccountBuilderOrchestrationModels.FailureCode.NONE,
            null,
            List.of(),
            true,
            false,
            5,
            NOW,
            NOW.plus(1, ChronoUnit.MINUTES));
    return new Inputs(
        approval, recording, artifact, decision, envelope, bindings, observation, authority, view);
  }

  private ExecutionEnvelope envelope() {
    ObjectNode node = fixtures.path("executionEnvelope").deepCopy();
    node.put("deadline", NOW.plus(10, ChronoUnit.MINUTES).toString());
    node.put("authorityId", "auth-sc1429-001");
    return objectMapper.convertValue(node, ExecutionEnvelope.class);
  }

  private record Inputs(
      ApprovalView approval,
      RecordingView recording,
      RecordingArtifact artifact,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      SnapshotBindings bindings,
      JsonNode observation,
      HumanAuthority authority,
      OrchestrationView view) {}
}
