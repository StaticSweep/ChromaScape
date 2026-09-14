package com.chromascape.web.replay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplayReportServiceTest {

  @Mock private ReplayReportRepository reportRepository;

  @Mock private com.chromascape.web.replay.mismatch.MismatchEngine mismatchEngine;

  private final CanonicalProjectionService projectionService = new CanonicalProjectionService();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void reportChecksumCoversCanonicalDocumentWithoutSelfChecksumField() throws Exception {
    when(mismatchEngine.countByLevel(any(UUID.class), any(MismatchLevel.class))).thenReturn(0L);
    when(mismatchEngine.getMismatches(any(UUID.class))).thenReturn(List.of());
    when(reportRepository.save(any(ReplayReport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    UUID runId = UUID.randomUUID();
    ReplayRun run = new ReplayRun();
    run.setId(runId);
    run.setMode(ReplayMode.CAUSAL_SIMULATION);
    run.setManifestChecksum("sha256:" + "1".repeat(64));
    run.setManifestVersion(2);
    run.setFidelity("SYNTHETIC_CONTRACT");
    run.setConversionQuality("BEST_EFFORT");

    ReplayReport report =
        new ReplayReportService(reportRepository, mismatchEngine, projectionService, mapper)
            .finalizeReport(
                run,
                null,
                ReplayOutcome.PASSED,
                null,
                2,
                1,
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                List.of("sha256:" + "4".repeat(64)),
                null);

    ObjectNode document = (ObjectNode) mapper.readTree(report.getReportJson());
    String embeddedChecksum = document.remove("reportChecksum").asText();
    String recomputed = projectionService.sha256Hex(projectionService.canonicalJsonBytes(document));

    assertEquals(report.getReportChecksum(), embeddedChecksum);
    assertEquals(report.getReportChecksum(), recomputed);
    assertNotEquals(
        report.getReportChecksum(),
        projectionService.sha256Hex(report.getReportJson().getBytes(StandardCharsets.UTF_8)));
  }
}
