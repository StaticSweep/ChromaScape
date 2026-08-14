package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.entity.ScriptStabilizationSnapshot;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StabilizationIngestionServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private ScriptStabilizationRunRepository runRepository;
  @Mock private ScriptFailureTriageRepository triageRepository;
  @Mock private ScriptStabilizationSnapshotRepository snapshotRepository;

  private StabilizationIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new StabilizationIngestionService(runRepository, triageRepository, snapshotRepository);
  }

  @Test
  @DisplayName("ingestReport stores runs and creates triage rows for failures")
  void ingestReport_storesRunsAndCreatesTriageRows() throws Exception {
    JsonNode report =
        objectMapper.readTree(
            """
            {
              "report_id": "report_123",
              "rows": [
                {
                  "task_key": "COOKS_ASSISTANT",
                  "outcome": "PASS",
                  "status": "COMPLETED",
                  "duration_ms": 1234
                },
                {
                  "task_key": "BARROWS1",
                  "outcome": "FAIL",
                  "status": "FAILED",
                  "duration_ms": 2345,
                  "failure_signature": "barrows1:missing_prayer",
                  "error_message": "missing prayer potions"
                }
              ]
            }
            """);

    when(runRepository.save(any(ScriptStabilizationRun.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(triageRepository.findByTriageKey("BARROWS1::barrows1:missing_prayer"))
        .thenReturn(Optional.empty());
    when(triageRepository.save(any(ScriptFailureTriage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Map<String, Object> result = service.ingestReport(report);

    assertThat(result).containsEntry("reportId", "report_123");
    assertThat(result).containsEntry("ingestedRows", 2);
    assertThat(result).containsEntry("triageUpdates", 1);

    ArgumentCaptor<ScriptStabilizationRun> runCaptor =
        ArgumentCaptor.forClass(ScriptStabilizationRun.class);
    verify(runRepository, org.mockito.Mockito.times(2)).save(runCaptor.capture());
    assertThat(runCaptor.getAllValues())
        .extracting(ScriptStabilizationRun::getScriptKey)
        .containsExactly("COOKS_ASSISTANT", "BARROWS1");

    ArgumentCaptor<ScriptFailureTriage> triageCaptor =
        ArgumentCaptor.forClass(ScriptFailureTriage.class);
    verify(triageRepository).save(triageCaptor.capture());
    ScriptFailureTriage saved = triageCaptor.getValue();
    assertThat(saved.getTriageKey()).isEqualTo("BARROWS1::barrows1:missing_prayer");
    assertThat(saved.getScriptKey()).isEqualTo("BARROWS1");
    assertThat(saved.getLastReportId()).isEqualTo("report_123");
    assertThat(saved.getLastErrorMessage()).isEqualTo("missing prayer potions");
  }

  @Test
  @DisplayName("computeKpis reports pass rate and open triage count")
  void computeKpis_reportsPassRateAndOpenTriageCount() {
    LocalDateTime now = LocalDateTime.now();
    ScriptStabilizationRun recentPass = run("PROGRESSIVE_WOODCUTTING", "PASS", now.minusHours(1));
    ScriptStabilizationRun recentTimeout =
        run("PROGRESSIVE_WOODCUTTING", "TIMEOUT", now.minusHours(2));
    ScriptStabilizationRun oldPass = run("PROGRESSIVE_WOODCUTTING", "PASS", now.minusDays(2));

    when(runRepository.findByExecutedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(recentPass, recentTimeout))
        .thenReturn(List.of(recentPass, recentTimeout, oldPass));
    when(triageRepository.findByStatusOrderByLastSeenAtDesc("OPEN"))
        .thenReturn(List.of(new ScriptFailureTriage()));

    Map<String, Object> result = service.computeKpis();

    assertThat(result).containsEntry("window_24h_count", 2);
    assertThat(result).containsEntry("window_7d_count", 3);
    assertThat(result).containsEntry("open_triage_count", 1);

    @SuppressWarnings("unchecked")
    Map<String, Object> scripts = (Map<String, Object>) result.get("scripts");
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics = (Map<String, Object>) scripts.get("PROGRESSIVE_WOODCUTTING");
    assertThat(metrics).containsEntry("pass_rate_24h", 0.5d);
    assertThat(metrics).containsEntry("pass_rate_7d", 2d / 3d);
    assertThat(metrics).containsEntry("timeout_rate_24h", 0.5d);
  }

  @Test
  @DisplayName("listOpenTriage returns unresolved rows from the repository")
  void listOpenTriage_returnsRepositoryRows() {
    ScriptFailureTriage open = new ScriptFailureTriage();
    open.setTriageKey("BARROWS1::failure");
    open.setScriptKey("BARROWS1");
    open.setFailureSignature("failure");
    when(triageRepository.findByStatusOrderByLastSeenAtDesc("OPEN")).thenReturn(List.of(open));

    List<ScriptFailureTriage> result = service.listOpenTriage();

    assertThat(result).containsExactly(open);
  }

  @Test
  @DisplayName("resolveTriage marks the row as resolved")
  void resolveTriage_marksRowResolved() {
    ScriptFailureTriage triage = new ScriptFailureTriage();
    triage.setTriageKey("BARROWS1::failure");
    triage.setScriptKey("BARROWS1");
    triage.setFailureSignature("failure");
    triage.setStatus("OPEN");
    when(triageRepository.findById(42L)).thenReturn(Optional.of(triage));
    when(triageRepository.save(any(ScriptFailureTriage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptFailureTriage result = service.resolveTriage(42L);

    assertThat(result.getStatus()).isEqualTo("RESOLVED");
  }

  @Test
  @DisplayName("ingestHarnessManifest stores a versioned run with all identities")
  void ingestHarnessManifest_storesVersionedRun() throws Exception {
    ObjectNode manifest = objectMapper.createObjectNode();
    manifest.put("schema_version", "harness-run-manifest/v1");
    manifest.put("plan_id", "fishing-foundation");
    manifest.put("plan_hash", "sha256:plan");
    manifest.put("run_id", "run-1");
    manifest.put("attempt_id", "attempt-1");
    manifest.put("entry_id", "entry-1");
    manifest.put("task_key", "FISH_TRAINING");
    manifest.put("settings_hash", "sha256:settings");
    manifest.put("build_id", "build-1");
    manifest.put("catalog_hash", "sha256:catalog");
    manifest.put("source_snapshot_id", "UNKNOWN");
    manifest.put("tier", "CONTRACT");
    manifest.put("environment", "test");
    manifest.put("outcome", "FAIL");
    manifest.put("terminal_state", "FAILED");
    manifest.put("primary_failure_code", "task_failed");
    manifest.put("failure_signature", "sig:abc");
    manifest.put("evidence_complete", true);
    manifest.putArray("evidence").addObject().put("type", "LOG").put("reference", "logs/task.log");
    manifest.put("latency_ms", 1234);
    manifest.put(
        "manifest_hash",
        "sha256:"
            + StabilizationHashUtil.sha256(manifest, StabilizationHashUtil.MANIFEST_EXCLUSIONS));
    manifest.put(
        "deterministic_content_hash",
        "sha256:"
            + StabilizationHashUtil.sha256(
                manifest, StabilizationHashUtil.DETERMINISTIC_EXCLUSIONS));

    when(runRepository.existsBySchemaVersionAndRunIdAndAttemptIdAndManifestHash(
            "harness-run-manifest/v1",
            "run-1",
            "attempt-1",
            manifest.path("manifest_hash").asText()))
        .thenReturn(false);
    when(runRepository.save(any(ScriptStabilizationRun.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(triageRepository.findByTriageKey("FISH_TRAINING::sig:abc"))
        .thenReturn(Optional.empty());
    when(triageRepository.save(any(ScriptFailureTriage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Map<String, Object> result = service.ingestHarnessManifest(manifest);

    assertThat(result).containsEntry("runId", "run-1");
    assertThat(result).containsEntry("saved", true);
    assertThat(result).containsEntry("schemaVersion", "harness-run-manifest/v1");

    ArgumentCaptor<ScriptStabilizationRun> captor =
        ArgumentCaptor.forClass(ScriptStabilizationRun.class);
    verify(runRepository).save(captor.capture());
    ScriptStabilizationRun saved = captor.getValue();
    assertThat(saved.getScriptKey()).isEqualTo("FISH_TRAINING");
    assertThat(saved.getRunId()).isEqualTo("run-1");
    assertThat(saved.getAttemptId()).isEqualTo("attempt-1");
    assertThat(saved.getPlanHash()).isEqualTo("sha256:plan");
    assertThat(saved.getBuildId()).isEqualTo("build-1");
    assertThat(saved.getPrimaryFailureCode()).isEqualTo("task_failed");
    assertThat(saved.getEvidenceComplete()).isTrue();
    assertThat(saved.getManifestReference()).endsWith("run-manifest.json");
    assertThat(saved.getTransitionsReference()).endsWith("transitions.json");
  }

  @Test
  @DisplayName("ingestHarnessManifest rejects unknown schema versions")
  void ingestHarnessManifest_rejectsUnknownSchema() throws Exception {
    JsonNode manifest =
        objectMapper.readTree(
            """
            {"schema_version": "harness-run-manifest/v999", "run_id": "run-1"}
            """);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> service.ingestHarnessManifest(manifest));
  }

  @Test
  @DisplayName("ingestReliabilityMatrix stores snapshots for each group")
  void ingestReliabilityMatrix_storesSnapshots() throws Exception {
    ObjectNode matrix = objectMapper.createObjectNode();
    matrix.put("schema_version", "reliability-matrix/v1");
    com.fasterxml.jackson.databind.node.ArrayNode groups = matrix.putArray("groups");
    ObjectNode group = groups.addObject();
    group.put("group_key", "sha256:plan|FISH_TRAINING|sha256:settings|build-1|CONTRACT|test");
    group.put("task_key", "FISH_TRAINING");
    group.put("plan_hash", "sha256:plan");
    group.put("build_id", "build-1");
    group.put("catalog_hash", "sha256:catalog");
    group.put("artifact_digest", "sha256:artifact");
    group.put("executor_instance_id", "instance-1");
    group.put("source_snapshot_id", "UNKNOWN");
    group.put("total_runs", 2);
    group.put("passes", 1);
    group.put("failures", 1);
    group.put("timeouts", 0);
    group.put("cancelled", 0);
    group.put("invalid", 0);
    group.put("pass_rate", 0.5);
    group.put("first_attempt_pass_rate", 0.5);
    group.put("eventual_pass_rate", 0.5);
    group.put("failure_rate", 0.5);
    group.put("timeout_rate", 0.0);
    group.put("p50_latency_ms", 100);
    group.put("p95_latency_ms", 200);
    group.put("p99_latency_ms", 300);
    group.putObject("failure_signatures").put("task_failed", 1);
    group.put("evidence_complete", 1);
    group.put("evidence_complete_rate", 1.0);
    group.put("cleanup_failures", 0);
    group.put("cleanup_success_rate", 1.0);
    group.put("retry_count", 1);
    group.put("comparison_status", "BASELINE");
    matrix.putArray("incomparable");
    matrix.put(
        "matrix_hash",
        "sha256:" + StabilizationHashUtil.sha256(matrix, StabilizationHashUtil.MATRIX_EXCLUSIONS));

    when(snapshotRepository.existsByMatrixHashAndGroupKey(anyString(), anyString()))
        .thenReturn(false);
    when(snapshotRepository.save(any(ScriptStabilizationSnapshot.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Map<String, Object> result = service.ingestReliabilityMatrix(matrix);

    assertThat(result).containsEntry("matrixHash", matrix.path("matrix_hash").asText());
    assertThat(result).containsEntry("snapshots", 1);

    ArgumentCaptor<ScriptStabilizationSnapshot> captor =
        ArgumentCaptor.forClass(ScriptStabilizationSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    ScriptStabilizationSnapshot saved = captor.getValue();
    assertThat(saved.getMatrixHash()).isEqualTo(matrix.path("matrix_hash").asText());
    assertThat(saved.getScriptKey()).isEqualTo("FISH_TRAINING");
    assertThat(saved.getFirstAttemptPassRate()).isEqualTo(0.5);
    assertThat(saved.getEventualPassRate()).isEqualTo(0.5);
    assertThat(saved.getEvidenceCompleteRate()).isEqualTo(1.0);
    assertThat(saved.getCleanupSuccessRate()).isEqualTo(1.0);
    assertThat(saved.getTopFailureSignature()).isEqualTo("task_failed");
    assertThat(saved.getRetryCount()).isEqualTo(1);
    assertThat(saved.getComparisonStatus()).isEqualTo("BASELINE");
  }

  @Test
  @DisplayName("ingestReliabilityMatrix rejects unknown schema versions")
  void ingestReliabilityMatrix_rejectsUnknownSchema() throws Exception {
    JsonNode matrix =
        objectMapper.readTree(
            """
            {"schema_version": "reliability-matrix/v999", "groups": []}
            """);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> service.ingestReliabilityMatrix(matrix));
  }

  @Test
  @DisplayName("getRunByRunId returns the stored run")
  void getRunByRunId_returnsRun() {
    ScriptStabilizationRun run = new ScriptStabilizationRun();
    run.setRunId("run-1");
    when(runRepository.findFirstByRunIdOrderByExecutedAtDesc("run-1")).thenReturn(Optional.of(run));

    assertThat(service.getRunByRunId("run-1")).contains(run);
  }

  private ScriptStabilizationRun run(String scriptKey, String outcome, LocalDateTime executedAt) {
    ScriptStabilizationRun run = new ScriptStabilizationRun();
    run.setReportId("report");
    run.setScriptKey(scriptKey);
    run.setOutcome(outcome);
    run.setStatus(outcome);
    run.setExecutedAt(executedAt);
    return run;
  }
}
