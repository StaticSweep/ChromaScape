package com.chromascape.web.replay.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Report-v1 contract enforcement: strict schema conformance, immutable prepared reports, defensive
 * byte copies, and logical-vs-artifact checksum parity.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplayReportContractTest {

  @Mock ReplayReportRepository reportRepository;
  @Mock MismatchEngine mismatchEngine;

  private final CanonicalProjectionService projection = new CanonicalProjectionService();
  private final ObjectMapper mapper = new ObjectMapper();

  private ReplayReportService service;

  private final UUID runId = UUID.randomUUID();
  private final LocalDateTime dbTerminal = LocalDateTime.of(2026, 8, 25, 12, 0);

  @BeforeEach
  void setUp() {
    service = new ReplayReportService(reportRepository, mismatchEngine, projection, mapper);
    lenient().when(mismatchEngine.countByLevel(runId, MismatchLevel.HARD)).thenReturn(0L);
    lenient().when(mismatchEngine.countByLevel(runId, MismatchLevel.SOFT)).thenReturn(0L);
    lenient().when(mismatchEngine.countByLevel(runId, MismatchLevel.INFO)).thenReturn(0L);
  }

  private ReplayRun run(ReplayMode mode) {
    ReplayRun run = new ReplayRun();
    run.setId(runId);
    run.setMode(mode);
    run.setManifestChecksum("sha256:" + "a".repeat(64));
    run.setXrspsAdapterVersion("adapter-1");
    run.setXrspsBuildCommit("b".repeat(40));
    run.setCacheRevision("sha256:" + "c".repeat(64));
    run.setContentRevision("sha256:" + "d".repeat(64));
    run.setCreatedAt(dbTerminal.minusMinutes(10));
    run.setStartedAt(dbTerminal.minusMinutes(5));
    return run;
  }

  private ReplayAttempt attempt() {
    ReplayAttempt attempt = new ReplayAttempt();
    attempt.setId(UUID.randomUUID());
    return attempt;
  }

  private ReplayReportService.PreparedReplayReport preparedSuccessful() {
    return service.prepareReport(
        run(ReplayMode.CAUSAL_SIMULATION),
        attempt(),
        ReplayOutcome.PASSED,
        null,
        7,
        1,
        "sha256:" + "e".repeat(64),
        "sha256:" + "f".repeat(64),
        List.of("sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64)),
        dbTerminal,
        null);
  }

  @Test
  void successfulExecutionReportSatisfiesTheStrictContract() throws Exception {
    var prepared = preparedSuccessful();
    JsonNode doc = mapper.readTree(prepared.canonicalBytes());

    assertEquals(1, doc.path("reportVersion").asInt());
    assertEquals(runId.toString(), doc.path("runId").asText());
    assertEquals("CAUSAL_SIMULATION", doc.path("mode").asText());
    assertFalse(doc.path("runtimeIdentity").isNull());
    assertTrue(doc.path("attemptId").isTextual());
    assertTrue(doc.path("startedAt").isTextual());
    assertTrue(doc.hasNonNull("terminalAt"));
    assertEquals(false, doc.path("controlAllowed").asBoolean());
    assertTrue(doc.path("reportChecksum").isTextual());
    assertTrue(doc.path("artifactRefs").isArray());
  }

  @Test
  void preExecutionCancellationReportCarriesExactlyTheNullShape() throws Exception {
    var prepared =
        service.preparePreExecutionCancellationReport(
            run(ReplayMode.CAUSAL_SIMULATION), dbTerminal);
    JsonNode doc = mapper.readTree(prepared.canonicalBytes());

    assertTrue(doc.path("attemptId").isNull(), "attempt id must be JSON null");
    assertTrue(doc.path("runtimeIdentity").isNull());
    assertTrue(doc.path("startedAt").isNull());
    assertEquals("CANCELLED", doc.path("outcome").asText());
    assertEquals("CANCEL_REQUESTED", doc.path("terminalReason").asText());
    assertEquals(0, doc.path("executedActionCount").asInt());
    assertEquals(0, doc.path("checkpointCount").asInt());
    assertEquals(0, doc.path("mismatchTotals").path("hard").asInt());
    assertEquals(0, doc.path("mismatchTotals").path("soft").asInt());
    assertEquals(0, doc.path("mismatchTotals").path("info").asInt());
    assertEquals(0, doc.path("artifactRefs").size());
    assertFalse(doc.has("finalStateHash"));
    assertFalse(doc.has("finalChainHash"));
    assertFalse(doc.path("controlAllowed").asBoolean());

    ReplayReport row = service.materialize(prepared, run(ReplayMode.CAUSAL_SIMULATION), null);
    assertNull(row.getAttemptId());
    assertNull(row.getStartedAt());
    assertEquals(ReplayOutcome.CANCELLED, row.getOutcome());
  }

  @Test
  void preCapabilityInfrastructureFailureCarriesARealAttemptAndNoInventedIdentity()
      throws Exception {
    ReplayRun run = run(ReplayMode.CAUSAL_SIMULATION);
    run.setXrspsAdapterVersion(null);
    run.setXrspsBuildCommit(null);
    run.setCacheRevision(null);
    run.setContentRevision(null);

    var prepared =
        service.prepareReport(
            run,
            attempt(),
            ReplayOutcome.INFRASTRUCTURE_FAILURE,
            "INFRASTRUCTURE_FAILURE",
            0,
            0,
            null,
            null,
            List.of(),
            dbTerminal,
            null);
    JsonNode doc = mapper.readTree(prepared.canonicalBytes());

    assertTrue(doc.path("attemptId").isTextual());
    assertTrue(doc.path("runtimeIdentity").isNull());
    assertTrue(doc.path("startedAt").isTextual());
    assertEquals("INFRASTRUCTURE_FAILURE", doc.path("outcome").asText());
  }

  @Test
  void partialRuntimeIdentityFailsClosedForInfrastructureFailure() {
    ReplayRun run = run(ReplayMode.CAUSAL_SIMULATION);
    run.setXrspsAdapterVersion(null);
    run.setXrspsBuildCommit("b".repeat(40));
    run.setCacheRevision(null);
    run.setContentRevision(null);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                service.prepareReport(
                    run,
                    attempt(),
                    ReplayOutcome.INFRASTRUCTURE_FAILURE,
                    "INFRASTRUCTURE_FAILURE",
                    0,
                    0,
                    null,
                    null,
                    List.of(),
                    dbTerminal,
                    null));
    assertEquals(ReplayErrorCode.REPORT_PREPARATION_FAILED, e.getErrorCode());
  }

  @Test
  void executedReportsWithoutAnAttemptFailClosed() {
    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                service.prepareReport(
                    run(ReplayMode.CAUSAL_SIMULATION),
                    null,
                    ReplayOutcome.PASSED,
                    null,
                    0,
                    0,
                    null,
                    null,
                    List.of(),
                    dbTerminal,
                    null));
    assertEquals(ReplayErrorCode.REPORT_PREPARATION_FAILED, e.getErrorCode());
  }

  @Test
  void blankRuntimeIdentityFailsInsteadOfProducingInvalidBytes() {
    ReplayRun blanked = run(ReplayMode.CAUSAL_SIMULATION);
    blanked.setCacheRevision("");
    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                service.prepareReport(
                    blanked,
                    attempt(),
                    ReplayOutcome.PASSED,
                    null,
                    0,
                    0,
                    null,
                    null,
                    List.of(),
                    dbTerminal,
                    null));
    assertEquals(ReplayErrorCode.REPORT_PREPARATION_FAILED, e.getErrorCode());
  }

  @Test
  void nonCausalModesAreRejectedByTheCheckedInContract() {
    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                service.preparePreExecutionCancellationReport(
                    run(ReplayMode.POLICY_REPLAY), dbTerminal));
    assertEquals(ReplayErrorCode.REPORT_PREPARATION_FAILED, e.getErrorCode());
  }

  @Test
  void jvmClockTimestampsNeverEnterReports() {
    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                service.preparePreExecutionCancellationReport(
                    run(ReplayMode.CAUSAL_SIMULATION), null));
    assertEquals(ReplayErrorCode.REPORT_PREPARATION_FAILED, e.getErrorCode());
  }

  @Test
  void mutatingCallerOwnedListsAfterPreparationCannotChangeReportBytes() {
    List<String> refs = new ArrayList<>();
    refs.add("sha256:" + "9".repeat(64));
    var prepared =
        service.prepareReport(
            run(ReplayMode.CAUSAL_SIMULATION),
            attempt(),
            ReplayOutcome.PASSED,
            null,
            3,
            0,
            null,
            null,
            refs,
            dbTerminal,
            null);
    byte[] before = prepared.canonicalBytes();

    // Caller mutates the list AND the returned array afterwards.
    refs.add("sha256:" + "8".repeat(64));
    byte[] handedOut = prepared.canonicalBytes();
    java.util.Arrays.fill(handedOut, (byte) 'x');

    assertArrayEquals(
        before,
        prepared.canonicalBytes(),
        "prepared bytes are frozen against caller-side mutation");
  }

  @Test
  void logicalAndArtifactChecksumsStayConsistent() throws Exception {
    var prepared = preparedSuccessful();
    byte[] finalBytes = prepared.canonicalBytes();

    // Artifact checksum covers the FINAL canonical bytes.
    String expectedArtifact = projection.sha256Hex(finalBytes);
    assertEquals(expectedArtifact, prepared.artifactChecksum());

    // Logical checksum covers the document WITHOUT the checksum field.
    JsonNode doc = mapper.readTree(finalBytes);
    ((com.fasterxml.jackson.databind.node.ObjectNode) doc).remove("reportChecksum");
    String recomputedLogical = projection.sha256Hex(projection.canonicalJsonBytes(doc));
    assertEquals(recomputedLogical, prepared.logicalChecksum());

    // The stored row carries the LOGICAL checksum in the report_checksum column.
    ReplayReport row = service.materialize(prepared, run(ReplayMode.CAUSAL_SIMULATION), attempt());
    assertEquals(prepared.logicalChecksum(), row.getReportChecksum());
    assertNotEquals(prepared.artifactChecksum(), row.getReportChecksum());
  }

  @Test
  void hardMismatchesAreEmbeddedOnlyWhenPresent() throws Exception {
    lenient().when(mismatchEngine.countByLevel(runId, MismatchLevel.HARD)).thenReturn(2L);
    var withoutRow =
        service.prepareReport(
            run(ReplayMode.CAUSAL_SIMULATION),
            attempt(),
            ReplayOutcome.FAILED,
            "MISMATCH_HARD_STOP",
            5,
            0,
            null,
            null,
            List.of(),
            dbTerminal,
            null);
    JsonNode doc = mapper.readTree(withoutRow.canonicalBytes());
    assertEquals(2, doc.path("mismatchTotals").path("hard").asInt());
    assertFalse(
        doc.has("firstHardMismatch"), "no embedded evidence may appear without its committed row");

    var mismatch =
        new com.chromascape.web.replay.run.entity.ReplayMismatch.Builder(
                runId, UUID.randomUUID(), MismatchLevel.HARD, "position.hard.drift")
            .firstTick(3)
            .tickSequence(1)
            .domain("player.position")
            .build();
    var withRow =
        service.prepareReport(
            run(ReplayMode.CAUSAL_SIMULATION),
            attempt(),
            ReplayOutcome.FAILED,
            "MISMATCH_HARD_STOP",
            5,
            0,
            null,
            null,
            List.of(),
            dbTerminal,
            mismatch);
    JsonNode doc2 = mapper.readTree(withRow.canonicalBytes());
    assertEquals("position.hard.drift", doc2.path("firstHardMismatch").path("ruleId").asText());
  }
}
