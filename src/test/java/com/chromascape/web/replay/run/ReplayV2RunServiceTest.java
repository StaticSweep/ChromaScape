package com.chromascape.web.replay.run;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.api.dto.ReplayMismatchPageResponse;
import com.chromascape.web.replay.api.dto.ReplayMismatchResponse;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.catalog.ReplayV2PreflightService;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import com.chromascape.web.replay.viewer.ReplayViewerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplayV2RunServiceTest {

  @Mock private ReplayV2PreflightService preflightService;
  @Mock private ScenarioManifestV2Compiler compiler;
  @Mock private ReplayRunService runService;
  @Mock private ScenarioManifestRepository manifestRepository;
  @Mock private ReplayViewerService viewerService;
  @Mock private ArtifactRefRepository artifactRepository;
  @Mock private ReplayArtifactService artifactService;
  @Mock private ReplayReportRepository reportRepository;
  @Mock private ReplayMismatchRepository mismatchRepository;
  @Mock private StateHashSampleRepository hashSampleRepository;

  private final CanonicalProjectionService projectionService = new CanonicalProjectionService();
  private final ObjectMapper mapper = new ObjectMapper();
  private ReplayV2RunService service;
  private com.chromascape.web.replay.config.ReplayProperties properties;

  @BeforeEach
  void setUp() {
    properties = new com.chromascape.web.replay.config.ReplayProperties();
    properties.setEnabled(true);
    service =
        new ReplayV2RunService(
            preflightService,
            compiler,
            runService,
            manifestRepository,
            viewerService,
            artifactRepository,
            artifactService,
            reportRepository,
            mismatchRepository,
            hashSampleRepository,
            projectionService,
            mapper,
            properties);
  }

  @Test
  void creationCompilesExactOwnerSelectionOnceAndDoesNotRecheckADifferentDefaultPreview() {
    UUID selected = UUID.fromString("2458e418-890e-456d-ad3e-6249367c1710");
    var request =
        new com.chromascape.web.replay.run.dto.CreateXrspsRunRequest(
            com.chromascape.web.replay.conversion.ConversionMode.AUTO,
            false,
            com.chromascape.web.replay.api.dto.ReplayStopPolicy.RUN_TO_END,
            "new-key",
            selected,
            "sha256:" + "a".repeat(64));
    var compilation = org.mockito.Mockito.mock(ScenarioManifestV2Compiler.Compilation.class);
    ScenarioManifest manifest = new ScenarioManifest();
    manifest.setId(UUID.randomUUID());
    manifest.setRecordingId("recording");
    manifest.setManifestChecksum(request.expectedManifestChecksum());
    when(compilation.manifest()).thenReturn(manifest);
    when(compiler.compile("recording", request.conversionMode(), selected, "owner"))
        .thenReturn(compilation);
    when(runService.findByIdempotencyKey("new-key", "owner")).thenReturn(Optional.empty());
    ReplayRun created = new ReplayRun();
    created.setId(UUID.randomUUID());
    created.setManifestId(manifest.getId());
    created.setState(ReplayRunState.QUEUED);
    when(runService.createV2(
            org.mockito.ArgumentMatchers.eq(manifest),
            org.mockito.ArgumentMatchers.eq(request),
            org.mockito.ArgumentMatchers.eq("owner"),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(created);
    when(manifestRepository.findById(manifest.getId())).thenReturn(Optional.of(manifest));
    assertEquals(created.getId(), service.create("recording", request, "owner").response().runId());
    org.mockito.Mockito.verify(compiler)
        .compile("recording", request.conversionMode(), selected, "owner");
    org.mockito.Mockito.verify(compilation).requireSimulationAllowed();
    org.mockito.Mockito.verifyNoInteractions(preflightService);
    org.mockito.Mockito.verify(compiler, org.mockito.Mockito.never())
        .preview(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void sourceAuditRefusalNeverCreatesARun() {
    var request =
        new com.chromascape.web.replay.run.dto.CreateXrspsRunRequest(
            com.chromascape.web.replay.conversion.ConversionMode.AUTO,
            false,
            com.chromascape.web.replay.api.dto.ReplayStopPolicy.RUN_TO_END,
            "new-key");
    var compilation = org.mockito.Mockito.mock(ScenarioManifestV2Compiler.Compilation.class);
    when(runService.findByIdempotencyKey("new-key", "owner")).thenReturn(Optional.empty());
    when(compiler.compile("recording", request.conversionMode(), null, "owner"))
        .thenReturn(compilation);
    org.mockito.Mockito.doThrow(
            com.chromascape.web.replay.error.ReplayException.invalid(
                com.chromascape.web.replay.error.ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE,
                "source gaps"))
        .when(compilation)
        .requireSimulationAllowed();
    var error =
        assertThrows(
            com.chromascape.web.replay.error.ReplayException.class,
            () -> service.create("recording", request, "owner"));
    assertEquals(
        com.chromascape.web.replay.error.ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE,
        error.getErrorCode());
    org.mockito.Mockito.verify(runService, org.mockito.Mockito.never())
        .createV2(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verifyNoInteractions(preflightService);
  }

  @Test
  void stalePreviewChecksumRejectsChangedInputsWithoutCreatingARun() {
    var request =
        new com.chromascape.web.replay.run.dto.CreateXrspsRunRequest(
            com.chromascape.web.replay.conversion.ConversionMode.AUTO,
            false,
            com.chromascape.web.replay.api.dto.ReplayStopPolicy.RUN_TO_END,
            "new-key",
            null,
            "sha256:" + "a".repeat(64));
    var compilation = org.mockito.Mockito.mock(ScenarioManifestV2Compiler.Compilation.class);
    ScenarioManifest changed = new ScenarioManifest();
    changed.setManifestChecksum("sha256:" + "b".repeat(64));
    when(compilation.manifest()).thenReturn(changed);
    when(runService.findByIdempotencyKey("new-key", "owner")).thenReturn(Optional.empty());
    when(compiler.compile("recording", request.conversionMode(), null, "owner"))
        .thenReturn(compilation);
    var error =
        assertThrows(
            com.chromascape.web.replay.error.ReplayException.class,
            () -> service.create("recording", request, "owner"));
    assertEquals(
        com.chromascape.web.replay.error.ReplayErrorCode.CHECKSUM_MISMATCH, error.getErrorCode());
    assertEquals(409, error.getHttpStatus().value());
    org.mockito.Mockito.verify(runService, org.mockito.Mockito.never())
        .createV2(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void selectedCurationParticipatesInIdempotencyAndCannotReuseAnotherSelection() {
    UUID first = UUID.fromString("0d51f486-5b6b-4474-990c-66bbfbe672a1");
    UUID second = UUID.fromString("0d51f486-5b6b-4474-990c-66bbfbe672a2");
    var request =
        new com.chromascape.web.replay.run.dto.CreateXrspsRunRequest(
            com.chromascape.web.replay.conversion.ConversionMode.AUTO,
            false,
            com.chromascape.web.replay.api.dto.ReplayStopPolicy.RUN_TO_END,
            "same-key",
            second);
    ObjectNode basis =
        mapper
            .createObjectNode()
            .put("recordingId", "recording")
            .put("conversionMode", "AUTO")
            .put("openViewer", false)
            .put("stopPolicy", "RUN_TO_END")
            .put("curationId", first.toString());
    ReplayRun existing = new ReplayRun();
    existing.setRequestFingerprint(
        projectionService.sha256Hex(projectionService.canonicalJsonBytes(basis)));
    when(runService.findByIdempotencyKey("same-key", "owner")).thenReturn(Optional.of(existing));
    var error =
        assertThrows(
            com.chromascape.web.replay.error.ReplayException.class,
            () -> service.create("recording", request, "owner"));
    assertEquals(
        com.chromascape.web.replay.error.ReplayErrorCode.RUN_IDEMPOTENCY_CONFLICT,
        error.getErrorCode());
    org.mockito.Mockito.verifyNoInteractions(compiler, preflightService);
  }

  @Test
  void reportFallbackVerifiesEmbeddedDocumentChecksumAndReturnsFullArtifactChecksum()
      throws Exception {
    UUID runId = UUID.randomUUID();
    ReplayRun run = terminalRun(runId);
    when(runService.getOwnedEntity(runId, "owner")).thenReturn(run);
    when(artifactRepository.findFirstByRunIdAndKindAndStateOrderByCreatedAtDesc(
            runId,
            com.chromascape.web.replay.api.dto.ArtifactKind.REPORT,
            ArtifactRef.STATE_FINALIZED))
        .thenReturn(Optional.empty());

    ObjectNode reportDocument = mapper.createObjectNode();
    reportDocument.put("reportVersion", 1);
    String reportChecksum =
        projectionService.sha256Hex(projectionService.canonicalJsonBytes(reportDocument));
    reportDocument.put("reportChecksum", reportChecksum);
    byte[] content = projectionService.canonicalJsonBytes(reportDocument);
    ReplayReport report = new ReplayReport();
    report.setReportJson(new String(content, StandardCharsets.UTF_8));
    report.setReportChecksum(reportChecksum);
    when(reportRepository.findByRunId(runId)).thenReturn(Optional.of(report));
    when(artifactService.checksum(content)).thenReturn("sha256:full-report-artifact");

    ReplayV2RunService.ArtifactDownload download = service.artifact(runId, "report", "owner");

    assertEquals("sha256:full-report-artifact", download.checksum());
    assertArrayEquals(content, download.content());
  }

  @Test
  void reportFallbackRejectsTamperedEmbeddedDocument() throws Exception {
    UUID runId = UUID.randomUUID();
    ReplayRun run = terminalRun(runId);
    when(runService.getOwnedEntity(runId, "owner")).thenReturn(run);
    when(artifactRepository.findFirstByRunIdAndKindAndStateOrderByCreatedAtDesc(
            runId,
            com.chromascape.web.replay.api.dto.ArtifactKind.REPORT,
            ArtifactRef.STATE_FINALIZED))
        .thenReturn(Optional.empty());
    ReplayReport report = new ReplayReport();
    report.setReportJson("{\"reportVersion\":1,\"reportChecksum\":\"sha256:wrong\"}");
    report.setReportChecksum("sha256:wrong");
    when(reportRepository.findByRunId(runId)).thenReturn(Optional.of(report));

    com.chromascape.web.replay.error.ReplayException error =
        assertThrows(
            com.chromascape.web.replay.error.ReplayException.class,
            () -> service.artifact(runId, "report", "owner"));

    assertEquals(
        com.chromascape.web.replay.error.ReplayErrorCode.TRACE_CORRUPT, error.getErrorCode());
  }

  @Test
  void mismatchProjectionIncludesRecordedActualAndSourceEvidence() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID manifestId = UUID.randomUUID();
    ReplayRun run = terminalRun(runId);
    run.setManifestId(manifestId);
    when(runService.getOwnedEntity(runId, "owner")).thenReturn(run);

    ScenarioManifest manifest = new ScenarioManifest();
    manifest.setId(manifestId);
    manifest.setManifestJson(
        mapper.readTree(
            """
                {
                  "actions": [
                    {
                      "type": "WALK_TO",
                      "sourceActionTick": 42,
                      "sourceActionSequence": 0,
                      "sourceEvidence": {"logId": 7}
                    }
                  ]
                }
                """));
    when(manifestRepository.findById(manifestId)).thenReturn(Optional.of(manifest));
    ReplayMismatch mismatch =
        new ReplayMismatch.Builder(
                runId, UUID.randomUUID(), MismatchLevel.HARD, "EXPECTATION_MISMATCH")
            .firstTick(42)
            .tickSequence(0)
            .domain("player")
            .jsonPointer("player.position.x")
            .expectedValue("10")
            .actualValue("11")
            .build();
    when(mismatchRepository.findByRunIdOrderByFirstTickAscTickSequenceAsc(runId))
        .thenReturn(List.of(mismatch));

    ReplayMismatchPageResponse page = service.mismatches(runId, "owner", 100);
    ReplayMismatchResponse projected = page.mismatches().get(0);

    assertEquals(10, projected.expected().asInt());
    assertEquals(11, projected.actual().asInt());
    assertEquals(7, projected.sourceEvidence().path("actionSource").path("logId").asInt());
    assertEquals(0, projected.sourceEvidence().path("actionIndex").asInt());
    assertEquals(42, projected.sourceEvidence().path("tick").asInt());
  }

  private static ReplayRun terminalRun(UUID runId) {
    ReplayRun run = new ReplayRun();
    run.setId(runId);
    run.setOwnerId("owner");
    run.setState(ReplayRunState.SUCCEEDED);
    return run;
  }

  @Test
  void outcomeDownloadUsesOwnedTerminalRunAndOnlyFinalizedContentAddress() {
    UUID runId = UUID.randomUUID();
    ReplayRun run = terminalRun(runId);
    when(runService.getOwnedEntity(runId, "owner")).thenReturn(run);
    var kind = com.chromascape.web.replay.api.dto.ArtifactKind.OUTCOME;
    var ref = new ArtifactRef(UUID.randomUUID(), runId, kind, "sha256:" + "d".repeat(64), 2);
    ref.setMediaType("application/json");
    when(artifactRepository.findFirstByRunIdAndKindAndStateOrderByCreatedAtDesc(
            runId, kind, ArtifactRef.STATE_FINALIZED))
        .thenReturn(Optional.of(ref));
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(artifactService.readVerified(ref)).thenReturn(bytes);
    var download = service.artifact(runId, "outcome", "owner");
    assertArrayEquals(bytes, download.content());
    assertEquals(ref.getChecksum(), download.checksum());
    org.mockito.Mockito.verify(runService).getOwnedEntity(runId, "owner");
    org.mockito.Mockito.verifyNoInteractions(reportRepository);
  }

  @Test
  void outcomeDownloadDoesNotExposeNonterminalOrForeignOwnedEvidence() {
    UUID runId = UUID.randomUUID();
    ReplayRun run = terminalRun(runId);
    run.setState(ReplayRunState.RUNNING);
    when(runService.getOwnedEntity(runId, "owner")).thenReturn(run);
    assertThrows(
        com.chromascape.web.replay.error.ReplayException.class,
        () -> service.artifact(runId, "outcome", "owner"));
    when(runService.getOwnedEntity(runId, "foreign"))
        .thenThrow(
            com.chromascape.web.replay.error.ReplayException.notFound(
                com.chromascape.web.replay.error.ReplayErrorCode.RUN_NOT_FOUND, "Run not found"));
    assertThrows(
        com.chromascape.web.replay.error.ReplayException.class,
        () -> service.artifact(runId, "outcome", "foreign"));
    org.mockito.Mockito.verifyNoInteractions(artifactRepository, artifactService, reportRepository);
  }
}
