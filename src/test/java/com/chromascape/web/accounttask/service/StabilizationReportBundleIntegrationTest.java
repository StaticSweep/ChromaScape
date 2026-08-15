package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.chromascape.web.accounttask.repository.StabilizationArtifactRepository;
import com.chromascape.web.accounttask.repository.StabilizationRunAggregateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SC-572 report-bundle ingestion contract through the {@link StabilizationBundleStreamReader} and
 * the ingestion service. Verifies the exact multipart shape the Micro6 {@code ReportBundleClient}
 * uploads (including checksum-addressed artifact filenames), filename preservation, idempotent
 * duplicate handling, same-run conflicts, tamper rejection, PASS-without-evidence rejection and
 * foreign-evidence rejection.
 */
@ExtendWith(MockitoExtension.class)
class StabilizationReportBundleIntegrationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Mock private ScriptStabilizationRunRepository runRepository;
  @Mock private ScriptFailureTriageRepository triageRepository;
  @Mock private ScriptStabilizationSnapshotRepository snapshotRepository;
  @Mock private StabilizationArtifactStore artifactStore;
  @Mock private StabilizationArtifactRepository artifactRepository;
  @Mock private StabilizationRunAggregateRepository aggregateRepository;
  @Mock private jakarta.persistence.EntityManager entityManager;

  @TempDir java.nio.file.Path tempDir;

  private StabilizationIngestionService service;

  @BeforeEach
  void setUp() {
    service = new StabilizationIngestionService(runRepository, triageRepository, snapshotRepository,
        artifactStore, artifactRepository, aggregateRepository, entityManager);
    lenient().when(artifactStore.stage(any(java.nio.file.Path.class), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          // The staged artifact's digest, size, evidence type, media type and identities must be
          // derived from the ACTUAL uploaded bytes and the real stage arguments so the evidence
          // size/digest/type verification and the returned artifact mappings are exercised against
          // real staged metadata.
          java.nio.file.Path source = invocation.getArgument(0);
          String mediaType = invocation.getArgument(1);
          String evidenceType = invocation.getArgument(2);
          String runId = invocation.getArgument(3);
          String attemptId = invocation.getArgument(4);
          byte[] data = java.nio.file.Files.readAllBytes(source);
          return new StabilizationArtifactStore.StoredArtifact(
              UUID.randomUUID(), rawDigest(data), data.length, mediaType, evidenceType,
              runId, attemptId, "now");
        });
    lenient().when(artifactStore.promote(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("a ReportBundleClient-shaped multipart bundle streams in with preserved artifact filenames")
  void streamingReaderPreservesArtifactFilenames() throws Exception {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    String boundary = "micro6-bundle-" + UUID.randomUUID();
    byte[] body = buildMultipart(boundary, docs);
    java.nio.file.Path staging = tempDir.resolve("stream");
    java.nio.file.Files.createDirectories(staging);

    StabilizationBundleStreamReader.StagedBundle bundle =
        StabilizationBundleStreamReader.read(
            new java.io.ByteArrayInputStream(body),
            "multipart/form-data; boundary=" + boundary,
            staging);

    assertThat(bundle.names()).containsAll(
        Set.of("plan", "manifest", "matrix", "transitions", "evidence", "checksums", "completeMarker"));
    // Artifact filenames must be preserved exactly as uploaded (sha256_<64>.<ext>).
    assertThat(bundle.artifacts()).extracting(StabilizationBundleStreamReader.StagedArtifact::filename)
        .hasSize(3)
        .allMatch(name -> name.matches("sha256_[0-9a-f]{64}\\.ndjson"));
    assertThat(java.nio.file.Files.isRegularFile(bundle.artifacts().get(0).path())).isTrue();
    assertThat(bundle.artifacts().get(0).path().getParent()).isEqualTo(staging);
    assertThat(bundle.artifacts().get(0).filename()).matches("sha256_[0-9a-f]{64}\\.ndjson");
  }

  @Test
  @DisplayName("a valid bundle is ingested with a positive save result")
  void validBundleIsIngested() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    when(runRepository.existsByRunIdAndManifestHash("run-1", manifest.path("manifest_hash").asText()))
        .thenReturn(false);
    when(runRepository.existsByRunId("run-1")).thenReturn(false);
    when(snapshotRepository.existsByMatrixHashAndGroupKey(any(), any())).thenReturn(true);

    Map<String, Object> result = service.ingestReportBundle(docs.source());

    assertThat(result.get("saved")).isEqualTo(true);
    assertThat(result.get("duplicate")).isEqualTo(false);
    assertThat(result.get("artifacts_verified")).isEqualTo(true);
    assertThat(result.get("attempt_ids")).isEqualTo(List.of("attempt-0"));
  }

  @Test
  @DisplayName("the durable external evidence reference is bound to the stored artifact UUID")
  void externalReferenceBoundToStoredArtifact() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    when(runRepository.existsByRunIdAndManifestHash("run-1", manifest.path("manifest_hash").asText()))
        .thenReturn(false);
    when(runRepository.existsByRunId("run-1")).thenReturn(false);
    when(snapshotRepository.existsByMatrixHashAndGroupKey(any(), any())).thenReturn(true);

    service.ingestReportBundle(docs.source());

    org.mockito.ArgumentCaptor<List<com.chromascape.web.accounttask.entity.StabilizationArtifact>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(artifactRepository).saveAll(captor.capture());
    List<com.chromascape.web.accounttask.entity.StabilizationArtifact> saved = captor.getValue();
    assertThat(saved).hasSize(3);
    // The evidence references (artifact://debug-recorder/...) must be durably bound to the stored
    // artifact UUIDs so the mapping never needs a reconstructed checksum lookup (SC-572).
    assertThat(saved)
        .extracting(com.chromascape.web.accounttask.entity.StabilizationArtifact::getExternalReference)
        .containsExactlyInAnyOrder(
            "artifact://debug-recorder/log-1",
            "artifact://debug-recorder/tick-1",
            "artifact://debug-recorder/rec-1");
    assertThat(saved)
        .extracting(com.chromascape.web.accounttask.entity.StabilizationArtifact::getArtifactId)
        .allMatch(id -> id != null && !id.isBlank());
  }

  @Test
  @DisplayName("an identical re-upload returns duplicate=true")
  void identicalReUploadIsDuplicate() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    when(runRepository.existsByRunIdAndManifestHash("run-1", manifest.path("manifest_hash").asText()))
        .thenReturn(true);

    Map<String, Object> result = service.ingestReportBundle(docs.source());

    assertThat(result.get("duplicate")).isEqualTo(true);
    assertThat(result.get("saved")).isEqualTo(false);
  }

  @Test
  @DisplayName("the same run with different content is a 409 conflict")
  void sameRunDifferentContentConflicts() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    when(runRepository.existsByRunIdAndManifestHash("run-1", manifest.path("manifest_hash").asText()))
        .thenReturn(false);
    when(runRepository.existsByRunId("run-1")).thenReturn(true);

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(StabilizationConflictException.class);
  }

  @Test
  @DisplayName("tampered evidence checksums are rejected")
  void tamperedEvidenceRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    // Tamper the evidence checksum after computing the bundle checksums.
    ((ObjectNode) docs.evidence().get(0)).put("checksum", "sha256:" + "b".repeat(64));

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  @DisplayName("a missing marker is rejected")
  void missingMarkerRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments original = documents(manifest);
    final BundleDocuments docs = new BundleDocuments(original.nodes(), original.artifacts(),
        "NOT_COMPLETE", original.tempDir());

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete.marker");
  }

  @Test
  @DisplayName("a PASS with incomplete evidence is rejected even with valid hashes")
  void passWithoutEvidenceRejected() {
    ObjectNode manifest = manifest();
    manifest.put("evidence_complete", false);
    BundleDocuments docs = documents(manifest);
    try {
    } catch (Exception ignored) {
    }

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("evidence");
  }

  @Test
  @DisplayName("evidence owned by a foreign attempt is rejected")
  void foreignAttemptEvidenceRejected() {
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.get("evidence").get(0)).put("attempt_id", "attempt-foreign");
    BundleDocuments docs = documents(manifest);

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("evidence");
  }

  @Test
  @DisplayName("an invalid source snapshot is rejected")
  void nonUnknownSourceSnapshotRejected() {
    ObjectNode manifest = manifest();
    manifest.put("source_snapshot_id", "SNAPSHOT-1");
    BundleDocuments docs = documents(manifest);
    try {
    } catch (Exception ignored) {
    }

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source_snapshot_id");
  }

  @Test
  @DisplayName("a stale attempt_manifest_hash is rejected even when the aggregate hash recomputes")
  void staleAttemptHashRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    // Tamper only the attempt hash; the aggregate manifest hash is recomputed over the modified
    // attempt content so the aggregate hash alone cannot hide the tampered attempt.
    ObjectNode manifestNode = (ObjectNode) docs.nodes().get("manifest");
    ((ObjectNode) manifestNode.get("attempts").get(0)).put("attempt_manifest_hash",
        "sha256:" + "c".repeat(64));
    rehash(manifestNode);
    docs.rechecksum();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("attempt_manifest_hash mismatch");
  }

  @Test
  @DisplayName("blank recorder session/recording identity is rejected, not accepted by equality")
  void blankSessionRecordingRejected() {
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.get("attempts").get(0)).put("debug_session_id", "");
    ((ObjectNode) manifest.get("attempts").get(0)).put("recording_id", "");
    BundleDocuments docs = documents(manifest);

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonblank");
  }

  @Test
  @DisplayName("a top-level evidence record absent from its owner attempt fails the union check")
  void evidenceUnionDivergenceRejected() throws Exception {
    ObjectNode manifest = manifest();
    JsonNode attemptEvidence = manifest.get("attempts").get(0).get("evidence");
    // Divert the owning attempt's first evidence record to a reference absent from the aggregate
    // evidence (cardinality stays one-of-each, so only the ordered union can catch the divergence).
    ((ObjectNode) attemptEvidence.get(0)).put("reference", "artifact://debug-recorder/diverged-log");
    BundleDocuments docs = documents(manifest);
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("union");
  }

  @Test
  @DisplayName("a second record for the same required evidence type in one attempt is rejected")
  void duplicateRequiredEvidenceTypeRejected() throws Exception {
    ObjectNode manifest = manifest();
    // Add a second distinct LOG record (different reference and checksum) to the attempt evidence
    // AND the top-level evidence so every earlier check passes and only the exact cardinality rule
    // can reject it.
    ObjectNode extraLog = (ObjectNode) mapper.readTree(
        mapper.writeValueAsString(manifest.get("evidence").get(0)));
    extraLog.put("reference", "artifact://debug-recorder/log-extra");
    extraLog.put("checksum", rawDigest("extra-log-bytes".getBytes(StandardCharsets.UTF_8)));
    ((ArrayNode) manifest.get("evidence")).add(extraLog);
    ((ArrayNode) manifest.get("attempts").get(0).get("evidence")).add(extraLog);
    BundleDocuments docs = documents(manifest);

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one is permitted");
  }

  @Test
  @DisplayName("the same external reference under two evidence types is rejected as ambiguous")
  void sameReferenceUnderTwoTypesRejected() throws Exception {
    ObjectNode manifest = manifest();
    // Reuse the LOG reference for the TICK_LOG record: the (run_id, external_reference) mapping
    // must be unambiguous, so the reference alone (never type|reference) is the uniqueness key.
    ((ObjectNode) manifest.get("evidence").get(1)).put("reference",
        "artifact://debug-recorder/log-1");
    ((ObjectNode) manifest.get("attempts").get(0).get("evidence").get(1)).put("reference",
        "artifact://debug-recorder/log-1");
    BundleDocuments docs = documents(manifest);
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate evidence reference");
  }

  @Test
  @DisplayName("the success response returns every persisted artifact mapping in evidence order")
  void successResponseReturnsArtifactMappings() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    when(runRepository.existsByRunIdAndManifestHash("run-1", manifest.path("manifest_hash").asText()))
        .thenReturn(false);
    when(runRepository.existsByRunId("run-1")).thenReturn(false);
    when(snapshotRepository.existsByMatrixHashAndGroupKey(any(), any())).thenReturn(true);

    Map<String, Object> result = service.ingestReportBundle(docs.source());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> artifacts = (List<Map<String, Object>>) result.get("artifacts");
    assertThat(artifacts).hasSize(3);
    assertThat(artifacts)
        .extracting(m -> m.get("external_reference"))
        .containsExactly(
            "artifact://debug-recorder/log-1",
            "artifact://debug-recorder/tick-1",
            "artifact://debug-recorder/rec-1");
    assertThat(artifacts)
        .extracting(m -> m.get("type"))
        .containsExactly("LOG", "TICK_LOG", "RECORDING_SESSION");
    assertThat(artifacts)
        .extracting(m -> m.get("checksum"))
        .allMatch(checksum -> ((String) checksum).matches("^sha256:[0-9a-f]{64}$"));
    assertThat(artifacts)
        .extracting(m -> m.get("artifact_id"))
        .allMatch(id -> id != null && !((String) id).isBlank());
    assertThat(artifacts).allMatch(m -> m.get("size_bytes").equals(16L));
  }

  @Test
  @DisplayName("external-reference lookup always requires the run id (no global fallback)")
  void externalReferenceLookupRequiresRunId() {
    com.chromascape.web.accounttask.entity.StabilizationArtifact artifact =
        new com.chromascape.web.accounttask.entity.StabilizationArtifact();
    artifact.setArtifactId("uuid-1");
    artifact.setRunId("run-1");
    artifact.setExternalReference("artifact://debug-recorder/log-1");
    artifact.setEvidenceType("LOG");
    when(artifactRepository.findByRunIdAndExternalReference("run-1",
        "artifact://debug-recorder/log-1"))
        .thenReturn(java.util.Optional.of(artifact));

    assertThat(service.getArtifactByExternalReference("run-1", "artifact://debug-recorder/log-1"))
        .isPresent();
    // The same reference can legitimately repeat across runs, so a blank run id must never resolve
    // globally (SC-572): the fallback lookup is forbidden.
    assertThat(service.getArtifactByExternalReference("", "artifact://debug-recorder/log-1"))
        .isEmpty();
    assertThat(service.getArtifactByExternalReference(null, "artifact://debug-recorder/log-1"))
        .isEmpty();
    assertThat(service.getArtifactByExternalReference("run-1", ""))
        .isEmpty();
  }

  @Test
  @DisplayName("an evidence media_type that differs from the uploaded artifact part is rejected")
  void mediaTypeMismatchRejected() {
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.get("evidence").get(0)).put("media_type", "application/json");
    ((ObjectNode) manifest.get("attempts").get(0).get("evidence").get(0))
        .put("media_type", "application/json");
    BundleDocuments docs = documents(manifest);
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("media_type");
  }

  @Test
  @DisplayName("an uploaded artifact part that is not referenced by evidence is rejected")
  void unusedArtifactRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    List<StabilizationIngestionService.ArtifactPart> parts =
        new ArrayList<>(docs.artifacts());
    byte[] extra = new byte[8];
    String extraHex = rawDigest(extra).substring("sha256:".length());
    java.nio.file.Path extraPath = tempDir.resolve("sha256_" + extraHex + ".ndjson");
    try {
      java.nio.file.Files.write(extraPath, extra);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
    parts.add(new StabilizationIngestionService.ArtifactPart(
        extraPath.getFileName().toString(), "application/x-ndjson", extraPath, extra.length));
    final BundleDocuments withExtra = new BundleDocuments(
        docs.nodes(), parts, "COMPLETE", tempDir);

    assertThatThrownBy(() -> service.ingestReportBundle(withExtra.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unused attached evidence artifact");
  }

  @Test
  @DisplayName("a second-artifact staging failure cleans the first staged artifact exactly")
  void secondArtifactStagingFailureCleansFirst() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    when(runRepository.existsByRunIdAndManifestHash("run-1", manifest.path("manifest_hash").asText()))
        .thenReturn(false);
    when(runRepository.existsByRunId("run-1")).thenReturn(false);
    when(snapshotRepository.existsByMatrixHashAndGroupKey(any(), any())).thenReturn(true);
    when(artifactStore.stage(any(java.nio.file.Path.class), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          // The first artifact must stage with metadata derived from the ACTUAL uploaded bytes so the
          // evidence digest/size validation passes and the second staging call reaches the failure.
          java.nio.file.Path source = invocation.getArgument(0);
          byte[] data = java.nio.file.Files.readAllBytes(source);
          return new StabilizationArtifactStore.StoredArtifact(
              UUID.randomUUID(), rawDigest(data), data.length,
              invocation.getArgument(1), invocation.getArgument(2),
              invocation.getArgument(3), invocation.getArgument(4), "now");
        })
        .thenThrow(new IllegalStateException("simulated staging failure"));

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("staging failed");

    // The first staged artifact id must have been tracked and cleaned immediately even though the
    // stageEvidenceArtifacts() call never returned successfully.
    verify(artifactStore, atLeastOnce())
        .deleteStaged(any(UUID.class));
  }

  @Test
  @DisplayName("a self-hashed plan whose settingsHash does not match the manifest is rejected")
  void planSettingsHashMismatchRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments base = documents(manifest);
    // Change only the plan's settingsHash while keeping the plan hash chain self-consistent
    // (plan.planHash == recomputed == manifest.plan_hash); the manifest settings_hash stays
    // unchanged, so the plan/manifest parity must reject the bundle.
    ObjectNode plan = (ObjectNode) base.nodes().get("plan");
    ObjectNode manifestNode = (ObjectNode) base.nodes().get("manifest");
    ((ObjectNode) plan.get("settings")).put("settingsHash", "sha256:" + "f".repeat(64));
    rehashPlan(plan, manifestNode);
    BundleDocuments docs = new BundleDocuments(base.nodes(), base.artifacts(), "COMPLETE", tempDir);
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match the plan");
  }

  @Test
  @DisplayName("a self-hashed plan whose task catalogHash does not match the manifest is rejected")
  void planCatalogHashMismatchRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments base = documents(manifest);
    ObjectNode plan = (ObjectNode) base.nodes().get("plan");
    ObjectNode manifestNode = (ObjectNode) base.nodes().get("manifest");
    ((ObjectNode) plan.get("task")).put("catalogHash", "sha256:" + "b".repeat(64));
    rehashPlan(plan, manifestNode);
    BundleDocuments docs = new BundleDocuments(base.nodes(), base.artifacts(), "COMPLETE", tempDir);
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match the plan");
  }

  @Test
  @DisplayName("a plan whose planHash field disagrees with the recomputed hash is rejected")
  void planInternalHashTamperRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments base = documents(manifest);
    ObjectNode plan = (ObjectNode) base.nodes().get("plan");
    // Keep planHash self-hashed but different from the manifest plan_hash: a self-hashed but
    // mismatched plan must be rejected.
    plan.put("planHash", "sha256:" + "a".repeat(64));
    BundleDocuments docs = new BundleDocuments(base.nodes(), base.artifacts(), "COMPLETE", tempDir);
    docs.rechecksum();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("planHash");
  }

  @Test
  @DisplayName("a manifest with an UNVERIFIED artifact digest is rejected")
  void unverifiedArtifactDigestRejected() {
    ObjectNode manifest = manifest();
    manifest.put("artifact_digest", "UNVERIFIED");
    BundleDocuments docs = documents(manifest);

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifact_digest");
  }

  @Test
  @DisplayName("a manifest with a malformed artifact digest is rejected")
  void malformedArtifactDigestRejected() {
    ObjectNode manifest = manifest();
    manifest.put("artifact_digest", "sha256:nothex");
    BundleDocuments docs = documents(manifest);

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifact_digest");
  }

  @Test
  @DisplayName("NOT_APPLICABLE plans reject supplied evidence records")
  void notApplicableRejectsSuppliedRecords() {
    // Build a self-consistent NOT_APPLICABLE bundle that still supplies evidence records.
    ObjectNode manifest = notApplicableManifest();
    BundleDocuments base = documents(manifest);
    ObjectNode plan = mapper.createObjectNode();
    plan.put("schemaVersion", "harness-plan/v1");
    plan.put("planId", "fishing-foundation");
    plan.put("tier", "PRODUCTION");
    plan.put("environment", "test");
    ObjectNode task = plan.putObject("task");
    task.put("taskKey", "HARNESS_NO_GAME_SMOKE");
    task.put("catalogHash", "sha256:" + "d".repeat(64));
    ObjectNode settings = plan.putObject("settings");
    settings.put("settingsHash", "sha256:" + "e".repeat(64));
    plan.put("buildId", "build-1");
    plan.put("micro6InstanceId", "instance-1");
    plan.putObject("evidence").put("mode", "NOT_APPLICABLE").putArray("required");
    String notApplicablePlanHash =
        "sha256:" + StabilizationHashUtil.sha256(plan, StabilizationHashUtil.PLAN_EXCLUSIONS);
    plan.put("planHash", notApplicablePlanHash);
    manifest.put("plan_hash", notApplicablePlanHash);
    rehashAll(manifest);
    Map<String, JsonNode> nodes = new LinkedHashMap<>(base.nodes());
    nodes.put("plan", plan);
    ObjectNode checksums = (ObjectNode) nodes.get("checksums");
    checksums.put("plan.json", rawDigest(bytes(plan)));
    checksums.put("run-manifest.json", rawDigest(bytes(manifest)));
    // The manifest still carries the evidence records from the EXTERNAL build, so the union and
    // evidence validation must reject it for the NOT_APPLICABLE mode.
    final BundleDocuments docs = new BundleDocuments(nodes, List.of(), "COMPLETE", tempDir);

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NOT_APPLICABLE");
  }

  /** A NOT_APPLICABLE plan whose manifest retains evidence records from the EXTERNAL fixture. */
  private ObjectNode notApplicableManifest() {
    ObjectNode manifest = manifest();
    manifest.put("evidence_complete", false);
    manifest.put("outcome", "FAIL");
    manifest.put("terminal_state", "FAILED");
    manifest.put("primary_failure_code", "evidence_correlation_mismatch");
    ((ObjectNode) manifest.get("attempts").get(0)).put("evidence_complete", false);
    ((ObjectNode) manifest.get("attempts").get(0)).put("outcome", "FAIL");
    ((ObjectNode) manifest.get("attempts").get(0)).put("terminal_state", "FAILED");
    rehashAll(manifest);
    return manifest;
  }

  private static void rehash(ObjectNode manifest) {
    manifest.put("manifest_hash", "sha256:" + StabilizationHashUtil.sha256(
        manifest, StabilizationHashUtil.MANIFEST_EXCLUSIONS));
  }

  /** Recomputed attempt, deterministic and aggregate manifest hashes after a fixture mutation. */
  private static void rehashAll(ObjectNode manifest) {
    JsonNode attempts = manifest.get("attempts");
    if (attempts.isArray()) {
      for (JsonNode attemptNode : attempts) {
        if (attemptNode.isObject()) {
          ((ObjectNode) attemptNode).put("attempt_manifest_hash",
              "sha256:" + StabilizationHashUtil.sha256(
                  attemptNode, StabilizationHashUtil.ATTEMPT_MANIFEST_EXCLUSIONS));
        }
      }
    }
    manifest.put("deterministic_content_hash", "sha256:" + StabilizationHashUtil.sha256(
        manifest, StabilizationHashUtil.DETERMINISTIC_EXCLUSIONS));
    rehash(manifest);
  }

  /** Re-hashes a mutated plan document and syncs the manifest plan_hash so the plan hash chain
   *  (plan.planHash == recomputed == manifest.plan_hash) stays self-consistent and only the
   *  plan-side field change is the tamper under test. */
  private void rehashPlan(ObjectNode plan, ObjectNode manifest) {
    String planHash = "sha256:" + StabilizationHashUtil.sha256(plan, StabilizationHashUtil.PLAN_EXCLUSIONS);
    plan.put("planHash", planHash);
    if (manifest != null) {
      manifest.put("plan_hash", planHash);
    }
  }

  // ------------------------------------------------------------------
  // bundle construction helpers (mirror the ReportBundleClient wire shape)
  // ------------------------------------------------------------------

  private ObjectNode manifest() {
    ObjectNode manifest = mapper.createObjectNode();
    manifest.put("schema_version", "harness-run-manifest/v1");
    manifest.put("plan_id", "fishing-foundation");
    manifest.put("plan_hash", planHash());
    manifest.put("run_id", "run-1");
    manifest.put("attempt_id", "attempt-0");
    manifest.put("entry_id", "123456789");
    manifest.put("iteration_id", "iter-1");
    manifest.put("task_key", "HARNESS_NO_GAME_SMOKE");
    manifest.put("settings_hash", "sha256:" + "e".repeat(64));
    manifest.put("micro6_instance_id", "instance-1");
    manifest.put("build_id", "build-1");
    manifest.put("artifact_digest", "sha256:" + "c".repeat(64));
    manifest.put("catalog_hash", "sha256:" + "d".repeat(64));
    manifest.put("source_snapshot_id", "UNKNOWN");
    manifest.put("client_id", "client-1");
    manifest.put("debug_session_id", "session-1");
    manifest.put("recording_id", "recording-1");
    manifest.put("tier", "PRODUCTION");
    manifest.put("environment", "test");
    manifest.put("outcome", "PASS");
    manifest.put("terminal_state", "PASSED");
    manifest.put("primary_failure_code", "none");
    manifest.put("failure_codes", mapper.createArrayNode());
    manifest.put("failure_signature", "sig:pass");
    manifest.put("evidence_complete", true);
    manifest.put("cleanup_status", "not_required");
    manifest.put("stop_issued", false);
    manifest.put("stop_confirmed", false);
    manifest.put("cleanup_failed", false);
    manifest.put("latency_ms", 1000);
    manifest.put("poll_count", 3);
    manifest.put("attempt_count", 1);
    manifest.put("first_attempt_pass", true);
    ArrayNode attempts = manifest.putArray("attempts");
    ObjectNode attempt = attempts.addObject();
    attempt.put("attempt_id", "attempt-0");
    attempt.put("entry_id", "123456789");
    attempt.put("iteration_id", "iter-1");
    attempt.put("debug_session_id", "session-1");
    attempt.put("recording_id", "recording-1");
    attempt.put("outcome", "PASS");
    attempt.put("terminal_state", "PASSED");
    attempt.put("primary_failure_code", "none");
    attempt.put("failure_codes", mapper.createArrayNode());
    attempt.put("failure_signature", "sig:pass");
    attempt.put("evidence_complete", true);
    attempt.put("cleanup_status", "not_required");
    attempt.put("stop_issued", false);
    attempt.put("stop_confirmed", false);
    attempt.put("cleanup_failed", false);
    attempt.put("latency_ms", 1000);
    attempt.put("poll_count", 3);
    attempt.putArray("evidence").addAll(evidenceRecords());
    attempt.put("attempt_manifest_hash",
        "sha256:" + StabilizationHashUtil.sha256(attempt, StabilizationHashUtil.ATTEMPT_MANIFEST_EXCLUSIONS));
    ArrayNode evidence = manifest.putArray("evidence");
    evidence.addAll(evidenceRecords());
    manifest.put("deterministic_content_hash",
        "sha256:" + StabilizationHashUtil.sha256(manifest, StabilizationHashUtil.DETERMINISTIC_EXCLUSIONS));
    manifest.put("manifest_hash",
        "sha256:" + StabilizationHashUtil.sha256(manifest, StabilizationHashUtil.MANIFEST_EXCLUSIONS));
    return manifest;
  }

  private String planHash() {
    ObjectNode plan = mapper.createObjectNode();
    plan.put("schemaVersion", "harness-plan/v1");
    plan.put("planId", "fishing-foundation");
    plan.put("tier", "PRODUCTION");
    plan.put("environment", "test");
    ObjectNode task = plan.putObject("task");
    task.put("taskKey", "HARNESS_NO_GAME_SMOKE");
    task.put("catalogHash", "sha256:" + "d".repeat(64));
    ObjectNode settings = plan.putObject("settings");
    settings.put("settingsHash", "sha256:" + "e".repeat(64));
    plan.put("buildId", "build-1");
    plan.put("micro6InstanceId", "instance-1");
    ArrayNode required = plan.putObject("evidence").putArray("required");
    required.add("LOG").add("TICK_LOG").add("RECORDING_SESSION");
    return "sha256:" + StabilizationHashUtil.sha256(plan, StabilizationHashUtil.PLAN_EXCLUSIONS);
  }

  private ArrayNode evidenceRecords() {
    ArrayNode records = mapper.createArrayNode();
    records.add(record("LOG", "artifact://debug-recorder/log-1", rawDigest(artifactBytes(0))));
    records.add(record("TICK_LOG", "artifact://debug-recorder/tick-1", rawDigest(artifactBytes(1))));
    records.add(record("RECORDING_SESSION", "artifact://debug-recorder/rec-1", rawDigest(artifactBytes(2))));
    return records;
  }

  private static byte[] artifactBytes(int seed) {
    byte[] bytes = new byte[16];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i + seed);
    }
    return bytes;
  }

  private ObjectNode record(String type, String reference, String checksum) {
    ObjectNode record = mapper.createObjectNode();
    record.put("type", type);
    record.put("reference", reference);
    record.put("provenance", "DEBUG_RECORDER");
    record.put("checksum", checksum);
    record.put("size_bytes", 16);
    record.put("media_type", "application/x-ndjson");
    record.put("task_key", "HARNESS_NO_GAME_SMOKE");
    record.put("settings_hash", "sha256:" + "e".repeat(64));
    record.put("build_id", "build-1");
    record.put("run_id", "run-1");
    record.put("attempt_id", "attempt-0");
    record.put("entry_id", "123456789");
    record.put("iteration_id", "iter-1");
    record.put("client_id", "client-1");
    record.put("debug_session_id", "session-1");
    record.put("recording_id", "recording-1");
    return record;
  }

  private BundleDocuments documents(ObjectNode manifest) {
    ObjectNode plan = mapper.createObjectNode();
    plan.put("schemaVersion", "harness-plan/v1");
    plan.put("planId", "fishing-foundation");
    plan.put("tier", "PRODUCTION");
    plan.put("environment", "test");
    ObjectNode task = plan.putObject("task");
    task.put("taskKey", "HARNESS_NO_GAME_SMOKE");
    task.put("catalogHash", "sha256:" + "d".repeat(64));
    ObjectNode settings = plan.putObject("settings");
    settings.put("settingsHash", "sha256:" + "e".repeat(64));
    plan.put("buildId", "build-1");
    plan.put("micro6InstanceId", "instance-1");
    plan.put("planHash", planHash());
    ArrayNode required = plan.putObject("evidence").putArray("required");
    required.add("LOG").add("TICK_LOG").add("RECORDING_SESSION");

    ObjectNode matrix = mapper.createObjectNode();
    matrix.put("schema_version", "reliability-matrix/v1");
    ObjectNode group = matrix.putArray("groups").addObject();
    group.put("group_key", "g1");
    group.put("plan_hash", planHash());
    group.put("task_key", "HARNESS_NO_GAME_SMOKE");
    group.put("settings_hash", "sha256:" + "e".repeat(64));
    group.put("build_id", "build-1");
    group.put("artifact_digest", "sha256:" + "c".repeat(64));
    group.put("catalog_hash", "sha256:" + "d".repeat(64));
    group.put("source_snapshot_id", "UNKNOWN");
    group.put("tier", "PRODUCTION");
    group.put("environment", "test");
    group.put("run_count", 1);
    group.put("total_runs", 1);
    group.put("attempt_count", 1);
    group.put("passes", 1);
    group.put("failures", 0);
    group.put("timeouts", 0);
    group.put("cancelled", 0);
    group.put("invalid", 0);
    matrix.putArray("incomparable");
    matrix.put("matrix_hash",
        "sha256:" + StabilizationHashUtil.sha256(matrix, StabilizationHashUtil.MATRIX_EXCLUSIONS));

    ArrayNode transitions = mapper.createArrayNode();

    JsonNode evidence = manifest.get("evidence");

    ObjectNode checksums = mapper.createObjectNode();
    checksums.put("plan.json", rawDigest(bytes(plan)));
    checksums.put("run-manifest.json", rawDigest(bytes(manifest)));
    checksums.put("reliability-matrix.json", rawDigest(bytes(matrix)));
    checksums.put("transitions.json", rawDigest(bytes(transitions)));
    checksums.put("evidence.json", rawDigest(bytes(evidence)));

    Map<String, JsonNode> nodes = new LinkedHashMap<>();
    nodes.put("plan", plan);
    nodes.put("manifest", manifest);
    nodes.put("matrix", matrix);
    nodes.put("transitions", transitions);
    nodes.put("evidence", evidence);
    nodes.put("checksums", checksums);

    List<StabilizationIngestionService.ArtifactPart> artifacts = new ArrayList<>();
    try {
      java.nio.file.Path artifactDir = tempDir.resolve("docs-artifacts");
      java.nio.file.Files.createDirectories(artifactDir);
      for (int i = 0; i < 3; i++) {
        byte[] content = artifactBytes(i);
        String hex = rawDigest(content).substring("sha256:".length());
        java.nio.file.Path path = artifactDir.resolve("sha256_" + hex + ".ndjson");
        java.nio.file.Files.write(path, content);
        artifacts.add(new StabilizationIngestionService.ArtifactPart(
            path.getFileName().toString(), "application/x-ndjson", path, content.length));
      }
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }

    rehashAll(manifest);
    ObjectNode checksumsNode = (ObjectNode) nodes.get("checksums");
    checksumsNode.put("plan.json", rawDigest(bytes(nodes.get("plan"))));
    checksumsNode.put("run-manifest.json", rawDigest(bytes(manifest)));
    checksumsNode.put("reliability-matrix.json", rawDigest(bytes(nodes.get("matrix"))));
    checksumsNode.put("transitions.json", rawDigest(bytes(nodes.get("transitions"))));
    checksumsNode.put("evidence.json", rawDigest(bytes(nodes.get("evidence"))));
    return new BundleDocuments(nodes, artifacts, "COMPLETE", tempDir);
  }

  private static byte[] bytes(JsonNode node) {
    try {
      return new ObjectMapper().writeValueAsBytes(node);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String rawDigest(byte[] data) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
      StringBuilder sb = new StringBuilder("sha256:");
      for (byte b : d) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private byte[] buildMultipart(String boundary, BundleDocuments docs) throws Exception {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    for (Map.Entry<String, JsonNode> entry : docs.nodes().entrySet()) {
      byte[] content = bytes(entry.getValue());
      writePart(out, boundary, entry.getKey(), entry.getKey() + ".json", content);
    }
    writePart(out, boundary, "completeMarker", "complete.marker",
        docs.marker().getBytes(StandardCharsets.UTF_8));
    for (StabilizationIngestionService.ArtifactPart artifact : docs.artifacts()) {
      writePart(out, boundary, "artifacts", artifact.filename(),
          java.nio.file.Files.readAllBytes(artifact.path()));
    }
    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private static void writePart(java.io.ByteArrayOutputStream out, String boundary, String name,
      String filename, byte[] content) throws Exception {
    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
        + "\"\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(("Content-Type: " + (name.equals("completeMarker") ? "text/plain" : "application/json")
        + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(content);
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  /** The seven report documents as live JSON nodes plus artifacts, plus an overridable marker. */
  private record BundleDocuments(Map<String, JsonNode> nodes,
      List<StabilizationIngestionService.ArtifactPart> artifacts, String marker,
      java.nio.file.Path tempDir) {

    JsonNode evidence() {
      return nodes.get("evidence");
    }

    /** Recomputed the raw-byte checksums so deeper evidence validation is reachable. */
    void rechecksum() {
      ObjectNode checksums = (ObjectNode) nodes.get("checksums");
      checksums.put("plan.json", rawDigest(bytes(nodes.get("plan"))));
      checksums.put("run-manifest.json", rawDigest(bytes(nodes.get("manifest"))));
      checksums.put("reliability-matrix.json", rawDigest(bytes(nodes.get("matrix"))));
      checksums.put("transitions.json", rawDigest(bytes(nodes.get("transitions"))));
      checksums.put("evidence.json", rawDigest(bytes(nodes.get("evidence"))));
    }

    /** Rehash the manifest (attempt/deterministic/aggregate) and raw checksums after a mutation. */
    void rehashManifest() {
      rehashAll((ObjectNode) nodes.get("manifest"));
      rechecksum();
    }

    StabilizationIngestionService.BundleSource source() {
      return new StabilizationIngestionService.BundleSource() {
        @Override
        public Set<String> names() {
          Set<String> names = new java.util.LinkedHashSet<>(nodes.keySet());
          names.add("completeMarker");
          return names;
        }

        @Override
        public java.nio.file.Path read(String name) {
          try {
            java.nio.file.Path dir = tempDir.resolve("docs-parts");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path path = dir.resolve(safeName(name));
            if ("completeMarker".equals(name)) {
              java.nio.file.Files.write(path, marker.getBytes(StandardCharsets.UTF_8));
            } else {
              JsonNode node = nodes.get(name);
              if (node == null) {
                return null;
              }
              java.nio.file.Files.write(path, bytes(node));
            }
            return path;
          } catch (java.io.IOException e) {
            throw new RuntimeException(e);
          }
        }

        private static String safeName(String name) {
          return name.replaceAll("[^A-Za-z0-9._-]", "_");
        }
    @Override
        public List<StabilizationIngestionService.ArtifactPart> artifacts() {
          return artifacts;
        }
      };
    }
  }

  @Test
  @DisplayName("a tampered plan hash is rejected (declared vs recomputed vs manifest)")
  void tamperedPlanHashRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    ((ObjectNode) docs.nodes().get("plan")).put("planHash", "sha256:" + "0".repeat(64));
    docs.rechecksum();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plan_hash");
  }

  @Test
  @DisplayName("manifest settings that disagree with the plan are rejected")
  void settingsParityRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    ((ObjectNode) docs.nodes().get("manifest")).put("settings_hash", "sha256:" + "f".repeat(64));
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("settings_hash");
  }

  @Test
  @DisplayName("manifest build identity that disagrees with the plan is rejected")
  void buildIdentityParityRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    ((ObjectNode) docs.nodes().get("manifest")).put("build_id", "tampered-build");
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("build_id");
  }

  @Test
  @DisplayName("manifest catalog identity that disagrees with the plan is rejected")
  void catalogIdentityParityRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    ((ObjectNode) docs.nodes().get("manifest")).put("catalog_hash", "sha256:" + "9".repeat(64));
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("catalog_hash");
  }

  @Test
  @DisplayName("manifest instance identity that disagrees with the plan is rejected")
  void instanceIdentityParityRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    ((ObjectNode) docs.nodes().get("manifest")).put("micro6_instance_id", "tampered-instance");
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("micro6_instance_id");
  }

  @Test
  @DisplayName("an evidence record byte count that differs from the uploaded artifact is rejected")
  void evidenceByteCountMismatchRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    ((ObjectNode) docs.evidence().get(0)).put("size_bytes", 999);
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size_bytes");
  }

  @Test
  @DisplayName("an evidence record digest that differs from the uploaded artifact is rejected")
  void evidenceDigestMismatchRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    ((ObjectNode) docs.evidence().get(0)).put("checksum", "sha256:" + "a".repeat(64));
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  @DisplayName("a reordered evidence reference union is rejected")
  void evidenceReferenceReorderRejected() {
    ObjectNode manifest = manifest();
    BundleDocuments docs = documents(manifest);
    // Swap the first two evidence records so the ordered union no longer matches.
    ArrayNode evidence = (ArrayNode) docs.evidence();
    JsonNode first = evidence.get(0).deepCopy();
    evidence.set(0, evidence.get(1).deepCopy());
    evidence.set(1, first);
    docs.rehashManifest();

    assertThatThrownBy(() -> service.ingestReportBundle(docs.source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reference");
  }
}
