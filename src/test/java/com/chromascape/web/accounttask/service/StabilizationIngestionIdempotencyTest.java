package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.entity.ScriptStabilizationSnapshot;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.chromascape.web.accounttask.service.StabilizationArtifactStore;
import com.chromascape.web.accounttask.repository.StabilizationArtifactRepository;
import com.chromascape.web.accounttask.repository.StabilizationRunAggregateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Idempotency contract (SC-572): duplicate manifests and matrices are never stored twice. */
@ExtendWith(MockitoExtension.class)
class StabilizationIngestionIdempotencyTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private ScriptStabilizationRunRepository runRepository;
  @Mock private ScriptFailureTriageRepository triageRepository;
  @Mock private ScriptStabilizationSnapshotRepository snapshotRepository;
  @Mock private StabilizationArtifactStore artifactStore;
  @Mock private StabilizationArtifactRepository artifactRepository;
  @Mock private StabilizationRunAggregateRepository aggregateRepository;
  @Mock private jakarta.persistence.EntityManager entityManager;

  private StabilizationIngestionService service;

  @BeforeEach
  void setUp() {
    service = new StabilizationIngestionService(runRepository, triageRepository, snapshotRepository,
            artifactStore, artifactRepository, aggregateRepository, entityManager);
  }

  private JsonNode manifest() {
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
    manifest.putArray("evidence").addObject()
        .put("type", "LOG")
        .put("reference", "logs/task.log");
    manifest.put("manifest_hash", "sha256:" + StabilizationHashUtil.sha256(
        manifest, StabilizationHashUtil.MANIFEST_EXCLUSIONS));
    return manifest;
  }

  @Test
  @DisplayName("a duplicate manifest upload is detected and not stored")
  void duplicateManifestIsSkipped() {
    JsonNode manifest = manifest();
    when(runRepository.existsBySchemaVersionAndRunIdAndAttemptIdAndManifestHash(
        "harness-run-manifest/v1", "run-1", "attempt-1", manifest.path("manifest_hash").asText()))
        .thenReturn(true);

    Map<String, Object> result = service.ingestHarnessManifest(manifest);

    assertThat(result).containsEntry("duplicate", true).containsEntry("saved", false);
    verify(runRepository, never()).save(any(ScriptStabilizationRun.class));
  }

  @Test
  @DisplayName("a duplicate matrix group upload is skipped")
  void duplicateMatrixIsSkipped() throws Exception {
    ObjectNode matrix = objectMapper.createObjectNode();
    matrix.put("schema_version", "reliability-matrix/v1");
    ObjectNode group = matrix.putArray("groups").addObject();
    group.put("group_key", "group-a");
    group.put("task_key", "FISH_TRAINING");
    group.put("total_runs", 1);
    group.put("passes", 1);
    group.put("failures", 0);
    group.put("timeouts", 0);
    group.put("cancelled", 0);
    group.put("invalid", 0);
    group.putObject("failure_signatures");
    group.put("comparison_status", "BASELINE");
    matrix.putArray("incomparable");
    matrix.put("matrix_hash", "sha256:" + StabilizationHashUtil.sha256(
        matrix, StabilizationHashUtil.MATRIX_EXCLUSIONS));

    when(snapshotRepository.existsByMatrixHashAndGroupKey(anyString(), anyString()))
        .thenReturn(true);

    Map<String, Object> result = service.ingestReliabilityMatrix(matrix);

    assertThat(result).containsEntry("skipped", 1).containsEntry("snapshots", 0);
    verify(snapshotRepository, never()).save(any(ScriptStabilizationSnapshot.class));
  }
}
