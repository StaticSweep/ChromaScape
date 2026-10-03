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

  @BeforeEach
  void setUp() {
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
            new com.chromascape.web.replay.config.ReplayProperties());
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
}
