package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
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

/** Manifest integrity contract (SC-572): hashes are recomputed and verified before storage. */
@ExtendWith(MockitoExtension.class)
class StabilizationManifestIntegrityTest {

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

  private JsonNode baseManifest() {
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
    manifest.put("outcome", "PASS");
    manifest.put("terminal_state", "PASSED");
    manifest.put("primary_failure_code", "none");
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
  @DisplayName("an invalid manifest hash is rejected")
  void invalidManifestHashIsRejected() {
    JsonNode manifest = baseManifest();
    ((ObjectNode) manifest).put("manifest_hash", "sha256:0000000000000000000000000000000000000000000000000000000000000000");

    assertThatThrownBy(() -> service.ingestHarnessManifest(manifest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hash mismatch");
    verify(runRepository, never()).save(any(ScriptStabilizationRun.class));
  }

  @Test
  @DisplayName("an unknown schema version is rejected")
  void unknownSchemaRejected() throws Exception {
    JsonNode manifest = objectMapper.readTree("{\"schema_version\":\"harness-run-manifest/v999\"}");

    assertThatThrownBy(() -> service.ingestHarnessManifest(manifest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported");
  }

  @Test
  @DisplayName("an invalid outcome/terminal combination is rejected")
  void invalidOutcomeTerminalRejected() {
    JsonNode manifest = baseManifest();
    ((ObjectNode) manifest).put("outcome", "PASS").put("terminal_state", "FAILED");

    assertThatThrownBy(() -> service.ingestHarnessManifest(manifest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inconsistent");
  }

  @Test
  @DisplayName("a PASS manifest that lacks complete evidence is rejected")
  void passWithoutCompleteEvidenceRejected() {
    ObjectNode manifest = (ObjectNode) baseManifest();
    manifest.put("evidence_complete", false);
    manifest.put("manifest_hash", "sha256:" + StabilizationHashUtil.sha256(
        manifest, StabilizationHashUtil.MANIFEST_EXCLUSIONS));

    assertThatThrownBy(() -> service.ingestHarnessManifest(manifest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("evidence_complete");
  }

  @Test
  @DisplayName("missing required identity is rejected")
  void missingRequiredIdentityRejected() {
    JsonNode manifest = baseManifest();
    ObjectNode node = (ObjectNode) manifest;
    node.remove("run_id");

    assertThatThrownBy(() -> service.ingestHarnessManifest(manifest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("run_id");
  }

  @Test
  @DisplayName("direct manifest ingestion can never create a verified certification record")
  void directManifestIngestionIsNeverVerified() {
    when(runRepository.existsBySchemaVersionAndRunIdAndAttemptIdAndManifestHash(
        any(), any(), any(), any())).thenReturn(false);

    Map<String, Object> result = service.ingestHarnessManifest(baseManifest());
    assertThat(result.get("saved")).isEqualTo(true);

    org.mockito.ArgumentCaptor<ScriptStabilizationRun> captor =
        org.mockito.ArgumentCaptor.forClass(ScriptStabilizationRun.class);
    verify(runRepository).save(captor.capture());
    ScriptStabilizationRun saved = captor.getValue();
    assertThat(saved.getVerified()).as("direct /harness-manifest ingestion must never be verified")
        .isFalse();
  }
}
