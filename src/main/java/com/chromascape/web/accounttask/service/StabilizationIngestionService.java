package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.entity.ScriptStabilizationSnapshot;
import com.chromascape.web.accounttask.entity.StabilizationArtifact;
import com.chromascape.web.accounttask.entity.StabilizationRunAggregate;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.chromascape.web.accounttask.repository.StabilizationArtifactRepository;
import com.chromascape.web.accounttask.repository.StabilizationRunAggregateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Ingests stabilization reports, creates run rows, and maintains deduped triage records. */
@Service
public class StabilizationIngestionService {
  public static final String HARNESS_MANIFEST_SCHEMA = "harness-run-manifest/v1";
  public static final String RELIABILITY_MATRIX_SCHEMA = "reliability-matrix/v1";

  private static final Set<String> MANIFEST_REQUIRED =
      Set.of(
          "schema_version",
          "run_id",
          "attempt_id",
          "task_key",
          "outcome",
          "terminal_state",
          "manifest_hash",
          "evidence",
          "evidence_complete");

  private static final Set<String> AGGREGATE_MANIFEST_REQUIRED =
      Set.of(
          "schema_version",
          "plan_id",
          "plan_hash",
          "run_id",
          "attempt_id",
          "entry_id",
          "iteration_id",
          "task_key",
          "settings_hash",
          "micro6_instance_id",
          "build_id",
          "artifact_digest",
          "catalog_hash",
          "source_snapshot_id",
          "client_id",
          "tier",
          "environment",
          "outcome",
          "terminal_state",
          "primary_failure_code",
          "failure_codes",
          "failure_signature",
          "evidence_complete",
          "cleanup_status",
          "stop_issued",
          "stop_confirmed",
          "cleanup_failed",
          "latency_ms",
          "poll_count",
          "attempt_count",
          "first_attempt_pass",
          "attempts",
          "manifest_hash",
          "deterministic_content_hash");

  private static final Set<String> BUNDLE_REQUIRED =
      Set.of("plan", "manifest", "matrix", "transitions", "evidence", "checksums", "completeMarker");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ScriptStabilizationRunRepository runRepository;
  private final ScriptFailureTriageRepository triageRepository;
  private final ScriptStabilizationSnapshotRepository snapshotRepository;
  private final StabilizationArtifactStore artifactStore;
  private final StabilizationArtifactRepository artifactRepository;
  private final StabilizationRunAggregateRepository aggregateRepository;
  private final jakarta.persistence.EntityManager entityManager;

  public StabilizationIngestionService(
      ScriptStabilizationRunRepository runRepository,
      ScriptFailureTriageRepository triageRepository,
      ScriptStabilizationSnapshotRepository snapshotRepository,
      StabilizationArtifactStore artifactStore,
      StabilizationArtifactRepository artifactRepository,
      StabilizationRunAggregateRepository aggregateRepository,
      jakarta.persistence.EntityManager entityManager) {
    this.runRepository = runRepository;
    this.triageRepository = triageRepository;
    this.snapshotRepository = snapshotRepository;
    this.artifactStore = artifactStore;
    this.artifactRepository = artifactRepository;
    this.aggregateRepository = aggregateRepository;
    this.entityManager = entityManager;
  }

  /**
   * Bounded source of a verified report bundle. The seven required report documents are read by
   * name; optional recorder artifacts are checksum-addressed ({@code sha256_<64hex>.<ext>}).
   *
   * <p>Transport is staged {@link Path}s / bounded streams, never {@code byte[]}: JSON parts are
   * read under the 5 MiB bound and optional artifacts are hashed and copied stream-to-stream so a
   * complete artifact is never loaded into heap memory.
   */
  public interface BundleSource {
    Set<String> names();

    /** Staged file for a part name; {@code null} when absent. The caller reads it under a bound. */
    Path read(String name);

    List<ArtifactPart> artifacts();
  }

  /** A staged optional artifact: preserved original filename, inferred media type and staged path. */
  public record ArtifactPart(String filename, String mediaType, Path path, long sizeBytes) {}

  @Transactional
  public Map<String, Object> ingestReport(JsonNode report) {
    String reportId =
        report
            .path("report_id")
            .asText(report.path("generated_at").asText("report_" + System.currentTimeMillis()));
    JsonNode rows = report.path("rows");
    int ingested = 0;
    int triaged = 0;

    if (rows.isArray()) {
      for (JsonNode row : rows) {
        ScriptStabilizationRun run = new ScriptStabilizationRun();
        run.setVerified(false); // legacy_unverified: excluded from certified matrices/KPIs
        run.setReportId(reportId);
        run.setScriptKey(row.path("task_key").asText("unknown"));
        run.setOutcome(row.path("outcome").asText("UNKNOWN"));
        run.setStatus(row.path("status").asText("UNKNOWN"));
        run.setDurationMs(
            row.path("duration_ms").isNumber() ? row.path("duration_ms").asLong() : null);
        run.setFailureSignature(nullIfBlank(row.path("failure_signature").asText("")));
        run.setErrorMessage(nullIfBlank(row.path("error_message").asText("")));
        runRepository.save(run);
        ingested++;

        if (!"PASS".equalsIgnoreCase(run.getOutcome())
            && run.getFailureSignature() != null
            && !run.getFailureSignature().isBlank()) {
          upsertTriage(run, reportId);
          triaged++;
        }
      }
    }

    return Map.of("reportId", reportId, "ingestedRows", ingested, "triageUpdates", triaged);
  }

  /**
   * Ingests a versioned {@code harness-run-manifest/v1} document idempotently. Unknown schema
   * versions fail clearly; required fields, outcome/terminal consistency, the recomputed content
   * hash and evidence completeness are validated before storage; duplicates are detected by
   * {@code schema_version + run_id + attempt_id + manifest_hash}.
   */
  @Transactional
  public Map<String, Object> ingestHarnessManifest(JsonNode manifest) {
    String schemaVersion = manifest.path("schema_version").asText("");
    if (!HARNESS_MANIFEST_SCHEMA.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "unsupported harness manifest schema_version '" + schemaVersion + "'; expected "
              + HARNESS_MANIFEST_SCHEMA);
    }

    for (String field : MANIFEST_REQUIRED) {
      if (!manifest.hasNonNull(field)) {
        throw new IllegalArgumentException("harness manifest missing required field '" + field + "'");
      }
    }

    String outcome = manifest.path("outcome").asText("");
    String terminalState = manifest.path("terminal_state").asText("");
    if (!outcomeConsistent(outcome, terminalState)) {
      throw new IllegalArgumentException(
          "harness manifest outcome '" + outcome + "' is inconsistent with terminal_state '"
              + terminalState + "'");
    }

    String declaredHash = manifest.path("manifest_hash").asText("");
    String computedHash =
        "sha256:" + StabilizationHashUtil.sha256(manifest, StabilizationHashUtil.MANIFEST_EXCLUSIONS);
    if (!declaredHash.equals(computedHash)) {
      throw new IllegalArgumentException(
          "harness manifest hash mismatch: declared " + declaredHash + " but recomputed " + computedHash);
    }

    if ("PASS".equalsIgnoreCase(outcome)
        && !manifest.path("evidence_complete").asBoolean(false)) {
      throw new IllegalArgumentException(
          "harness manifest outcome PASS requires evidence_complete=true");
    }

    String runId = manifest.path("run_id").asText("");
    String attemptId = manifest.path("attempt_id").asText("");
    if (runRepository.existsBySchemaVersionAndRunIdAndAttemptIdAndManifestHash(
        schemaVersion, runId, attemptId, declaredHash)) {
      return Map.of("runId", runId, "attemptId", attemptId, "saved", false, "duplicate", true,
          "schemaVersion", schemaVersion);
    }

    ScriptStabilizationRun run = new ScriptStabilizationRun();
    // The direct harness-manifest route is LEGACY ingestion only: it can never create a verified
    // certification record. Only the validated /report-bundles path may persist verified=true, so
    // this route's rows are always excluded from certified matrices and KPIs.
    run.setVerified(false);
    run.setReportId("harness-" + runId + "-" + attemptId);
    run.setSchemaVersion(schemaVersion);
    run.setScriptKey(manifest.path("task_key").asText("unknown"));
    run.setOutcome(outcome);
    run.setStatus(terminalState);
    run.setDurationMs(
        manifest.path("latency_ms").isNumber() ? manifest.path("latency_ms").asLong() : null);
    run.setFailureSignature(nullIfBlank(manifest.path("failure_signature").asText("")));
    run.setErrorMessage(nullIfBlank(manifest.path("primary_failure_code").asText("")));
    run.setPlanHash(nullIfBlank(manifest.path("plan_hash").asText("")));
    run.setRunId(runId);
    run.setAttemptId(attemptId);
    run.setEntryId(nullIfBlank(manifest.path("entry_id").asText("")));
    run.setIterationId(nullIfBlank(manifest.path("iteration_id").asText("")));
    run.setDebugSessionId(nullIfBlank(manifest.path("debug_session_id").asText("")));
    run.setRecordingId(nullIfBlank(manifest.path("recording_id").asText("")));
    run.setSettingsHash(nullIfBlank(manifest.path("settings_hash").asText("")));
    run.setBuildId(nullIfBlank(manifest.path("build_id").asText("")));
    run.setCatalogHash(nullIfBlank(manifest.path("catalog_hash").asText("")));
    run.setArtifactDigest(nullIfBlank(manifest.path("artifact_digest").asText("")));
    run.setExecutorInstanceId(nullIfBlank(manifest.path("micro6_instance_id").asText("")));
    run.setSourceSnapshotId(nullIfBlank(manifest.path("source_snapshot_id").asText("")));
    run.setPrimaryFailureCode(nullIfBlank(manifest.path("primary_failure_code").asText("")));
    run.setEvidenceComplete(manifest.path("evidence_complete").asBoolean(false));
    run.setManifestHash(declaredHash);
    run.setDeterministicContentHash(nullIfBlank(manifest.path("deterministic_content_hash").asText("")));
    run.setEnvironment(nullIfBlank(manifest.path("environment").asText("")));
    run.setTier(nullIfBlank(manifest.path("tier").asText("")));
    String baseRef = "micro6-stabilization/" + runId + "/";
    run.setManifestReference(baseRef + "run-manifest.json");
    run.setTransitionsReference(baseRef + "transitions.json");
    run.setEvidenceReference(baseRef + "evidence.json");
    run.setChecksumsReference(baseRef + "checksums.json");
    run.setMatrixReference(baseRef + "reliability-matrix.json");
    runRepository.save(run);

    if (!"PASS".equalsIgnoreCase(run.getOutcome())
        && run.getPrimaryFailureCode() != null
        && !run.getPrimaryFailureCode().isBlank()
        && !"none".equalsIgnoreCase(run.getPrimaryFailureCode())) {
      upsertTriage(run, run.getReportId());
    }

    return Map.of("runId", runId, "attemptId", attemptId, "saved", true, "duplicate", false,
        "schemaVersion", schemaVersion);
  }

  /**
   * Ingests a complete verified SC-572 report bundle. The marker and every raw-byte checksum are
   * verified before any JSON is parsed; schemas, manifest/matrix hashes, identities, terminal
   * consistency and PASS evidence completeness are validated before persistence; recorder
   * artifacts are staged into the filesystem artifact store with opaque UUID ids and metadata is
   * persisted in one transaction. Any failure rolls back the transaction and removes staged
   * files. An exact re-upload returns {@code duplicate=true}, changed content for the same
   * run/attempt returns HTTP 409, and tampering/missing evidence returns HTTP 400.
   */
  @Transactional
  public Map<String, Object> ingestReportBundle(BundleSource source) {
    if (source == null || source.names() == null || !source.names().containsAll(BUNDLE_REQUIRED)) {
      throw new IllegalArgumentException("report bundle is missing one or more required artifacts");
    }
    Path markerPath = source.read("completeMarker");
    if (markerPath == null) {
      throw new IllegalArgumentException("report bundle complete.marker is invalid");
    }
    String marker = new String(readPartBytes("completeMarker", markerPath), StandardCharsets.UTF_8);
    if (!"COMPLETE".equals(marker.trim())) {
      throw new IllegalArgumentException("report bundle complete.marker is invalid");
    }
    JsonNode checksums = parseBundleJson("checksums", source.read("checksums"));
    if (!checksums.isObject()) {
      throw new IllegalArgumentException("report bundle checksums must be a JSON object");
    }
    Map<String, String> partNames = Map.of(
        "plan.json", "plan", "run-manifest.json", "manifest", "reliability-matrix.json", "matrix",
        "transitions.json", "transitions", "evidence.json", "evidence");
    for (Map.Entry<String, String> entry : partNames.entrySet()) {
      String declared = checksums.path(entry.getKey()).asText("");
      String actual = rawSha256(source.read(entry.getValue()));
      if (!declared.matches("sha256:[0-9a-f]{64}") || !declared.equals(actual)) {
        throw new IllegalArgumentException("report bundle checksum mismatch for " + entry.getKey());
      }
    }

    JsonNode plan = parseBundleJson("plan", source.read("plan"));
    JsonNode manifest = parseBundleJson("manifest", source.read("manifest"));
    JsonNode matrix = parseBundleJson("matrix", source.read("matrix"));
    JsonNode transitions = parseBundleJson("transitions", source.read("transitions"));
    JsonNode evidence = parseBundleJson("evidence", source.read("evidence"));
    if (!plan.isObject() || !transitions.isArray() || !evidence.isArray()) {
      throw new IllegalArgumentException("report bundle contains invalid plan, transitions, or evidence JSON");
    }
    // Evidence is duplicated in the manifest intentionally; accepting inconsistent copies would
    // make debugger retrieval ambiguous.
    if (manifest.has("evidence") && !manifest.path("evidence").equals(evidence)) {
      throw new IllegalArgumentException("report bundle evidence.json does not match manifest evidence");
    }

    // Plan hash parity with the Micro6 canonicalization contract: the bundle plan document must
    // hash to the manifest's declared plan_hash, and the plan's OWN declared planHash field must
    // equal it too (a self-hashed but mismatched plan is rejected). The plan schema is validated
    // so a malformed plan cannot be ingested.
    String declaredPlanHash = manifest.path("plan_hash").asText("");
    String recomputedPlanHash =
        "sha256:" + StabilizationHashUtil.sha256(plan, StabilizationHashUtil.PLAN_EXCLUSIONS);
    if (!declaredPlanHash.equals(recomputedPlanHash)) {
      throw new IllegalArgumentException(
          "report bundle plan_hash mismatch: manifest declares " + declaredPlanHash
              + " but the plan document recomputes to " + recomputedPlanHash);
    }
    if (!declaredPlanHash.equals(plan.path("planHash").asText(""))) {
      throw new IllegalArgumentException(
          "report bundle plan.planHash does not equal the recomputed plan hash");
    }
    validatePlanSchema(plan);
    validateManifestArtifactDigest(manifest);

    validateAggregateManifest(manifest, plan);
    validateEvidenceAgainstManifest(manifest, evidence, plan);
    // NOT_APPLICABLE plans admit zero evidence artifacts; any attached artifact is rejected even
    // though the (empty) evidence list would otherwise stage none.
    if ("NOT_APPLICABLE".equalsIgnoreCase(plan.path("evidence").path("mode").asText(""))
        && source.artifacts() != null && !source.artifacts().isEmpty()) {
      throw new IllegalArgumentException(
          "plan evidence mode NOT_APPLICABLE rejects supplied artifacts");
    }

    JsonNode matrixVerified = validateReliabilityMatrix(matrix, manifest);

    String runId = manifest.path("run_id").asText();
    String aggregateManifestHash = manifest.path("manifest_hash").asText();

    // Idempotency: identical content (same run + aggregate manifest hash) returns duplicate; same
    // run with changed content returns conflict.
    if (runRepository.existsByRunIdAndManifestHash(runId, aggregateManifestHash)) {
      return Map.of(
          "run_id", runId,
          "attempt_ids", attemptIds(manifest),
          "manifest_hash", aggregateManifestHash,
          "matrix_hash", matrix.path("matrix_hash").asText(),
          "saved", false,
          "duplicate", true,
          "artifacts_verified", true);
    }
    if (runRepository.existsByRunId(runId)) {
      throw new StabilizationConflictException(
          "stabilization run " + runId + " already exists with different content");
    }

    // Stage artifacts before persistence; every evidence record must be backed by an attached
    // artifact whose raw bytes hash to the record checksum. Staging is validated/checksummed
    // before any database work and is not yet visible under a final opaque id. Every staged and
    // promoted id is tracked from the moment it is allocated so cleanup is exact even when a
    // later artifact fails to stage.
    StabilizationIngestionResourceTracker resourceTracker = new StabilizationIngestionResourceTracker();
    List<StagedEvidence> staged = new ArrayList<>();
    List<StabilizationArtifact> storedArtifacts = new ArrayList<>();
    try {
      staged = stageEvidenceArtifacts(source.artifacts(), manifest, evidence, resourceTracker);
    } catch (IllegalArgumentException e) {
      resourceTracker.rollback(artifactStore);
      throw e;
    } catch (Exception e) {
      resourceTracker.rollback(artifactStore);
      throw new IllegalArgumentException("stabilization artifact staging failed: " + e.getMessage());
    }

    // Register the transaction synchronization BEFORE any promotion so a rollback (including a
    // commit-time failure after this method body returns) deletes every promoted and staged
    // artifact, while a commit removes only the staging/journal files.
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
            resourceTracker.rollback(artifactStore);
          } else {
            resourceTracker.commit(artifactStore);
          }
        }
      });
    }

    try {
      // Promote validated staged artifacts to opaque final ids, then persist the aggregate,
      // attempts, artifacts, matrix snapshots and triage in one transaction. Each promoted id is
      // tracked immediately after its successful promote, before the next artifact is promoted.
      List<StabilizationArtifactStore.StoredArtifact> promoted = new ArrayList<>();
      for (StagedEvidence stagedEvidence : staged) {
        StabilizationArtifactStore.StoredArtifact stagedArtifact = stagedEvidence.stored();
        StabilizationArtifactStore.StoredArtifact promotedArtifact = artifactStore.promote(stagedArtifact);
        resourceTracker.trackPromoted(promotedArtifact.id());
        promoted.add(promotedArtifact);
      }
      for (StabilizationArtifactStore.StoredArtifact storedArtifact : promoted) {
        StabilizationArtifact entity = new StabilizationArtifact();
        entity.setArtifactId(storedArtifact.id().toString());
        entity.setRunId(storedArtifact.runId());
        entity.setAttemptId(storedArtifact.attemptId());
        entity.setExternalReference(findExternalReference(staged, storedArtifact));
        entity.setEvidenceType(storedArtifact.evidenceType());
        entity.setSha256(storedArtifact.sha256());
        entity.setSizeBytes(storedArtifact.sizeBytes());
        entity.setMediaType(storedArtifact.mediaType());
        storedArtifacts.add(entity);
      }
      persistAggregateManifest(manifest, transitions, evidence, matrixVerified, storedArtifacts);
      persistRunAggregate(manifest);
      Map<String, Object> matrixResult = persistMatrixSnapshots(matrix);
      // Flush inside the controlled transaction to surface ordinary database failures (constraint
      // violations, flush errors) early, so the tracked files are cleaned on rollback.
      entityManager.flush();
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("run_id", runId);
      result.put("attempt_ids", attemptIds(manifest));
      result.put("manifest_hash", aggregateManifestHash);
      result.put("matrix_hash", matrix.path("matrix_hash").asText());
      result.put("saved", true);
      result.put("duplicate", false);
      result.put("artifacts_verified", true);
      result.put("matrix_snapshots", matrixResult.get("snapshots"));
      // The persisted artifact mappings in evidence order (SC-572): every stored artifact UUID bound
      // to its durable external evidence reference, returned so the producer can verify the
      // certification mapping without a separate query.
      result.put("artifacts", artifactMappings(storedArtifacts));
      return result;
    } catch (RuntimeException e) {
      // On any artifact, validation, database or commit failure, delete every promoted and
      // staged artifact; no catch path may lose an id created before a later failure.
      resourceTracker.rollback(artifactStore);
      throw e;
    }
  }

  private List<String> attemptIds(JsonNode manifest) {
    List<String> ids = new ArrayList<>();
    JsonNode attempts = manifest.path("attempts");
    if (attempts.isArray()) {
      for (JsonNode attempt : attempts) {
        if (attempt.hasNonNull("attempt_id")) {
          ids.add(attempt.path("attempt_id").asText());
        }
      }
    }
    if (ids.isEmpty()) {
      ids.add(manifest.path("attempt_id").asText());
    }
    return ids;
  }

  /** Serializes the persisted artifact mappings in evidence order for the success response
   *  (SC-572). Each mapping binds the durable external evidence reference to the stored artifact
   *  UUID and mirrors the submitted evidence identity fields. */
  private static List<Map<String, Object>> artifactMappings(List<StabilizationArtifact> artifacts) {
    List<Map<String, Object>> mappings = new ArrayList<>();
    if (artifacts == null) {
      return mappings;
    }
    for (StabilizationArtifact artifact : artifacts) {
      if (artifact == null) {
        continue;
      }
      Map<String, Object> mapping = new LinkedHashMap<>();
      mapping.put("external_reference", artifact.getExternalReference());
      mapping.put("artifact_id", artifact.getArtifactId());
      mapping.put("attempt_id", artifact.getAttemptId());
      mapping.put("type", artifact.getEvidenceType());
      mapping.put("checksum", artifact.getSha256());
      mapping.put("size_bytes", artifact.getSizeBytes());
      mapping.put("media_type", artifact.getMediaType());
      mappings.add(mapping);
    }
    return mappings;
  }

  private void validateAggregateManifest(JsonNode manifest, JsonNode plan) {
    if (!HARNESS_MANIFEST_SCHEMA.equals(manifest.path("schema_version").asText(""))) {
      throw new IllegalArgumentException(
          "unsupported harness manifest schema_version '"
              + manifest.path("schema_version").asText("")
              + "'; expected " + HARNESS_MANIFEST_SCHEMA);
    }
    for (String field : AGGREGATE_MANIFEST_REQUIRED) {
      if (!manifest.hasNonNull(field)) {
        throw new IllegalArgumentException("harness manifest missing required field '" + field + "'");
      }
    }
    String outcome = manifest.path("outcome").asText("");
    String terminalState = manifest.path("terminal_state").asText("");
    if (!outcomeConsistent(outcome, terminalState)) {
      throw new IllegalArgumentException(
          "harness manifest outcome '" + outcome + "' is inconsistent with terminal_state '"
              + terminalState + "'");
    }
    String declaredHash = manifest.path("manifest_hash").asText("");
    String computedHash =
        "sha256:" + StabilizationHashUtil.sha256(manifest, StabilizationHashUtil.MANIFEST_EXCLUSIONS);
    if (!declaredHash.equals(computedHash)) {
      throw new IllegalArgumentException(
          "harness manifest hash mismatch: declared " + declaredHash + " but recomputed " + computedHash);
    }
    if ("PASS".equalsIgnoreCase(outcome)
        && !manifest.path("evidence_complete").asBoolean(false)) {
      throw new IllegalArgumentException(
          "harness manifest outcome PASS requires evidence_complete=true");
    }
    if (!manifest.path("first_attempt_pass").isBoolean()) {
      throw new IllegalArgumentException("harness manifest first_attempt_pass must be a boolean");
    }
    // source_snapshot_id stays exactly UNKNOWN until SC-810; anything else is rejected.
    if (!"UNKNOWN".equals(manifest.path("source_snapshot_id").asText(""))) {
      throw new IllegalArgumentException(
          "harness manifest source_snapshot_id must be UNKNOWN");
    }
    // External evidence requires nonblank executor/build/session/recording identities at the
    // aggregate level; a blank identity is never a wildcard for a certified bundle.
    String evidenceMode = manifest.path("evidence").path("mode").asText("EXTERNAL");
    if (!"NOT_APPLICABLE".equalsIgnoreCase(evidenceMode)) {
      for (String field : List.of("micro6_instance_id", "build_id", "artifact_digest",
          "debug_session_id", "recording_id", "client_id")) {
        if (manifest.path(field).asText("").isBlank()) {
          throw new IllegalArgumentException(
              "harness manifest requires a nonblank '" + field + "' for external evidence");
        }
      }
    }
    JsonNode attempts = manifest.path("attempts");
    if (!attempts.isArray() || attempts.isEmpty()) {
      throw new IllegalArgumentException("harness manifest attempts[] must be non-empty");
    }
    // Explicit attempt_count must equal the length of attempts[].
    int declaredAttemptCount = manifest.path("attempt_count").asInt(-1);
    if (declaredAttemptCount < 0 || declaredAttemptCount != attempts.size()) {
      throw new IllegalArgumentException(
          "harness manifest attempt_count=" + declaredAttemptCount
              + " does not match attempts.length=" + attempts.size());
    }
    Set<String> attemptIds = new LinkedHashSet<>();
    JsonNode terminalAttempt = attempts.get(attempts.size() - 1);
    for (JsonNode attempt : attempts) {
      if (!attempt.isObject() || attempt.path("attempt_id").asText("").isBlank()) {
        throw new IllegalArgumentException("harness manifest attempts[] entries must carry attempt_id");
      }
      if (!attemptIds.add(attempt.path("attempt_id").asText())) {
        throw new IllegalArgumentException("harness manifest attempts[] contains duplicate attempt_id");
      }
      if (attempt.hasNonNull("attempt_manifest_hash")) {
        String declared = attempt.path("attempt_manifest_hash").asText("");
        if (!declared.matches("^sha256:[0-9a-f]{64}$")) {
          throw new IllegalArgumentException("harness manifest attempt_manifest_hash must be sha256-prefixed hex");
        }
        String recomputed =
            "sha256:" + StabilizationHashUtil.sha256(attempt, StabilizationHashUtil.ATTEMPT_MANIFEST_EXCLUSIONS);
        if (!declared.equals(recomputed)) {
          throw new IllegalArgumentException(
              "harness manifest attempt_manifest_hash mismatch for "
                  + attempt.path("attempt_id").asText("")
                  + ": declared " + declared + " but recomputed " + recomputed);
        }
      }
      if ("PASS".equalsIgnoreCase(attempt.path("outcome").asText(""))
          && !attempt.path("evidence_complete").asBoolean(false)) {
        throw new IllegalArgumentException(
            "harness manifest attempt PASS requires evidence_complete=true");
      }
      if (!"PASS".equalsIgnoreCase(attempt.path("outcome").asText(""))
          && !"UNKNOWN".equals(attempt.path("source_snapshot_id").asText("UNKNOWN"))) {
        throw new IllegalArgumentException("harness manifest attempt source_snapshot_id must be UNKNOWN");
      }
    }
    // The top-level aggregate identity must match the terminal attempt.
    if (!sameText(manifest.path("attempt_id"), terminalAttempt.path("attempt_id"))
        || !sameText(manifest.path("entry_id"), terminalAttempt.path("entry_id"))
        || !sameText(manifest.path("iteration_id"), terminalAttempt.path("iteration_id"))
        || !sameText(manifest.path("outcome"), terminalAttempt.path("outcome"))
        || !sameText(manifest.path("terminal_state"), terminalAttempt.path("terminal_state"))) {
      throw new IllegalArgumentException(
          "harness manifest top-level identity must match the terminal attempt");
    }
    // Aggregate identity vs the plan: plan_id, task_key, settings, build, catalog and instance must
    // agree EXACTLY (a blank plan field is never a wildcard for a certified bundle), and the plan's
    // own internal hash must equal the manifest's plan_hash.
    if (plan != null) {
      String planTaskKey = plan.path("task").path("taskKey").asText("");
      String planSettingsHash = plan.path("settings").path("settingsHash").asText("");
      String planBuildId = plan.path("buildId").asText("");
      String planInstance = plan.path("micro6InstanceId").asText("");
      String planCatalogHash = plan.path("task").path("catalogHash").asText("");
      if (!sameText(manifest.path("plan_id"), plan.path("planId"))
          || !planTaskKey.equals(manifest.path("task_key").asText(""))
          || !planSettingsHash.equals(manifest.path("settings_hash").asText(""))
          || !planBuildId.equals(manifest.path("build_id").asText(""))
          || !planInstance.equals(manifest.path("micro6_instance_id").asText(""))
          || !planCatalogHash.equals(manifest.path("catalog_hash").asText(""))) {
        throw new IllegalArgumentException(
            "harness manifest aggregate identity does not match the plan");
      }
      // The plan's own declared planHash must equal the manifest plan_hash. The recomputed hash of
      // the bundle plan document is verified separately before this method; requiring the plan's
      // internal planHash field to agree closes the self-hashed-but-mismatched-plan gap.
      String planDeclaredHash = plan.path("planHash").asText("");
      if (!planDeclaredHash.equals(manifest.path("plan_hash").asText(""))) {
        throw new IllegalArgumentException(
            "plan.planHash does not match manifest.plan_hash");
      }
    } else {
      throw new IllegalArgumentException("report bundle requires a plan document");
    }
  }

  private static boolean sameText(JsonNode a, JsonNode b) {
    return a.asText("").equals(b.asText(""));
  }

  /**
   * Validates the plan document schema (not only the manifest/matrix schemas). The plan must be a
   * {@code harness-plan/v1} object carrying a nonblank planId, taskKey, settingsHash, buildId,
   * catalogHash, micro6InstanceId and a sha256 planHash.
   */
  private void validatePlanSchema(JsonNode plan) {
    if (plan == null || !plan.isObject()) {
      throw new IllegalArgumentException("report bundle plan must be a JSON object");
    }
    if (!"harness-plan/v1".equals(plan.path("schemaVersion").asText(""))) {
      throw new IllegalArgumentException(
          "unsupported plan schema_version '"
              + plan.path("schemaVersion").asText("")
              + "'; expected harness-plan/v1");
    }
    String planId = plan.path("planId").asText("");
    String planHash = plan.path("planHash").asText("");
    String taskKey = plan.path("task").path("taskKey").asText("");
    String settingsHash = plan.path("settings").path("settingsHash").asText("");
    String buildId = plan.path("buildId").asText("");
    String catalogHash = plan.path("task").path("catalogHash").asText("");
    String instanceId = plan.path("micro6InstanceId").asText("");
    if (planId.isBlank() || planHash.isBlank() || taskKey.isBlank()
        || settingsHash.isBlank() || buildId.isBlank() || catalogHash.isBlank()
        || instanceId.isBlank()) {
      throw new IllegalArgumentException(
          "report bundle plan is missing required identity fields");
    }
    if (!planHash.matches("^sha256:[0-9a-f]{64}$")) {
      throw new IllegalArgumentException(
          "report bundle plan.planHash must be sha256:<64 hex>");
    }
    if (!settingsHash.matches("^sha256:[0-9a-f]{64}$")) {
      throw new IllegalArgumentException(
          "report bundle plan.settings.settingsHash must be sha256:<64 hex>");
    }
    if (!catalogHash.matches("^sha256:[0-9a-f]{64}$")) {
      throw new IllegalArgumentException(
          "report bundle plan.task.catalogHash must be sha256:<64 hex>");
    }
  }

  /**
   * Requires {@code manifest.artifact_digest} to be a real {@code sha256:<64 lowercase hex>}
   * digest and never {@code UNVERIFIED}. A certified ingestion must be tied to verifiable
   * packaged bytes.
   */
  private void validateManifestArtifactDigest(JsonNode manifest) {
    String digest = manifest.path("artifact_digest").asText("");
    if (digest.isBlank() || "UNVERIFIED".equalsIgnoreCase(digest)
        || !digest.matches("^sha256:[0-9a-f]{64}$")) {
      throw new IllegalArgumentException(
          "harness manifest artifact_digest must be a verified sha256:<64 hex> digest, got '"
              + digest + "'");
    }
  }

  /**
   * Validates every evidence record against the aggregate ownership and requires every
   * plan-required evidence type for every evidence-required attempt. Recorder-owned records must
   * carry nonblank provenance, opaque artifact references, valid raw checksums, exact
   * attempt/entry/iteration/session/recording ownership, positive size and a media type; no
   * duplicate reference or checksum ownership is permitted. Evidence for an attempt that is not
   * part of the manifest is rejected, and a record's session/recording ids must equal the owning
   * attempt's values (nonblank is never enough). A PASS is rejected whenever any required attempt
   * evidence is incomplete, even when a later retry passed. In {@code NOT_APPLICABLE} plan mode
   * zero evidence records/artifacts are permitted and any supplied evidence is rejected.
   */
  private void validateEvidenceAgainstManifest(JsonNode manifest, JsonNode evidence, JsonNode plan) {
    String runId = manifest.path("run_id").asText("");
    String buildId = manifest.path("build_id").asText("");
    String settingsHash = manifest.path("settings_hash").asText("");
    String taskKey = manifest.path("task_key").asText("");
    String clientId = manifest.path("client_id").asText("");
    Set<String> requiredTypes = new LinkedHashSet<>();
    JsonNode planEvidence = plan == null ? null : plan.path("evidence");
    JsonNode planRequired = planEvidence == null ? null : planEvidence.path("required");
    if (planEvidence == null || !planEvidence.isObject()) {
      throw new IllegalArgumentException(
          "plan must declare evidence requirements explicitly");
    }
    if (planRequired != null && planRequired.isArray()) {
      for (JsonNode type : planRequired) {
        if (type.isTextual()) {
          requiredTypes.add(type.asText().toUpperCase());
        }
      }
    }
    String evidenceMode = planEvidence.path("mode").asText("EXTERNAL").toUpperCase();
    if ("NOT_APPLICABLE".equals(evidenceMode)) {
      // NOT_APPLICABLE admits only an explicit empty required-evidence plan; any supplied evidence
      // is rejected (never accepted without validation).
      if (!requiredTypes.isEmpty()) {
        throw new IllegalArgumentException(
            "plan evidence mode NOT_APPLICABLE requires an empty evidence.required list");
      }
      if (evidence != null && evidence.isArray() && !evidence.isEmpty()) {
        throw new IllegalArgumentException(
            "plan evidence mode NOT_APPLICABLE rejects supplied evidence records");
      }
      return;
    }
    if (!"EXTERNAL".equals(evidenceMode)) {
      throw new IllegalArgumentException("unsupported plan evidence mode '" + evidenceMode + "'");
    }
    if (requiredTypes.isEmpty()) {
      throw new IllegalArgumentException(
          "plan evidence mode EXTERNAL requires a non-empty evidence.required list");
    }

    Set<String> manifestAttemptIds = new LinkedHashSet<>();
    Map<String, JsonNode> attemptsById = new LinkedHashMap<>();
    JsonNode attempts = manifest.path("attempts");
    if (attempts.isArray()) {
      for (JsonNode attempt : attempts) {
        String attemptId = attempt.path("attempt_id").asText("");
        if (!attemptId.isBlank()) {
          manifestAttemptIds.add(attemptId);
          attemptsById.put(attemptId, attempt);
        }
      }
    }

    Map<String, List<JsonNode>> recordsByAttempt = new LinkedHashMap<>();
    Set<String> seenReferences = new LinkedHashSet<>();
    Set<String> seenChecksums = new LinkedHashSet<>();
    for (JsonNode record : evidence) {
      String attemptId = record.path("attempt_id").asText("");
      if (attemptId.isBlank()) {
        throw new IllegalArgumentException("evidence record is missing attempt_id");
      }
      // Evidence for an attempt that is not part of the manifest is foreign and rejected.
      JsonNode owningAttempt = attemptsById.get(attemptId);
      if (owningAttempt == null) {
        throw new IllegalArgumentException(
            "evidence record references unknown attempt_id '" + attemptId + "'");
      }
      String type = record.path("type").asText("");
      String reference = record.path("reference").asText("");
      String checksum = record.path("checksum").asText("");
      String provenance = record.path("provenance").asText("");
      if (!requiredTypes.contains(type.toUpperCase())) {
        throw new IllegalArgumentException("evidence record has unknown type '" + type + "'");
      }
      if (provenance.isBlank() || "UNKNOWN".equalsIgnoreCase(provenance)) {
        throw new IllegalArgumentException("evidence record has blank or unknown provenance");
      }
      if (!checksum.matches("^sha256:[0-9a-f]{64}$")) {
        throw new IllegalArgumentException("evidence record checksum must be sha256-prefixed hex");
      }
      if (reference.isBlank()) {
        throw new IllegalArgumentException("evidence record is missing a reference");
      }
      // SC-572: the same external reference may never be reused for distinct evidence types within
      // one run; the durable (run_id, external_reference) mapping must be unambiguous, so the
      // reference is the sole uniqueness key (never type|reference).
      if (!seenReferences.add(reference)) {
        throw new IllegalArgumentException("duplicate evidence reference '" + reference + "'");
      }
      if (!seenChecksums.add(checksum)) {
        throw new IllegalArgumentException("duplicate evidence checksum '" + checksum + "'");
      }
      // Exact ownership: run/attempt/entry/iteration/settings/build/task/client must be pinned to
      // this execution's exact attempt.
      if (!runId.equals(record.path("run_id").asText(""))
          || !buildId.equals(record.path("build_id").asText(""))
          || !settingsHash.equals(record.path("settings_hash").asText(""))
          || !taskKey.equals(record.path("task_key").asText(""))
          || !clientId.equals(record.path("client_id").asText(""))
          || !owningAttempt.path("entry_id").asText("").equals(record.path("entry_id").asText(""))
          || !owningAttempt.path("iteration_id").asText("").equals(record.path("iteration_id").asText(""))) {
        throw new IllegalArgumentException("evidence record ownership does not match the aggregate attempt");
      }
      boolean recorderOwned = "RECORDER".equalsIgnoreCase(provenance)
          || "DEBUG_RECORDER".equalsIgnoreCase(provenance);
      if (recorderOwned) {
        if (!reference.startsWith("artifact://")) {
          throw new IllegalArgumentException("recorder evidence reference must be an opaque artifact:// reference");
        }
        long sizeBytes = record.path("size_bytes").asLong(0);
        if (sizeBytes <= 0) {
          throw new IllegalArgumentException("recorder evidence record requires positive size_bytes");
        }
        if (record.path("media_type").asText("").isBlank()) {
          throw new IllegalArgumentException("recorder evidence record requires media_type");
        }
      }
      recordsByAttempt.computeIfAbsent(attemptId, k -> new ArrayList<>()).add(record);
    }

    for (JsonNode attempt : attempts) {
      String attemptId = attempt.path("attempt_id").asText("");
      boolean attemptEvidenceComplete = attempt.path("evidence_complete").asBoolean(false);
      if ("PASS".equalsIgnoreCase(attempt.path("outcome").asText("")) && !attemptEvidenceComplete) {
        throw new IllegalArgumentException(
            "attempt PASS requires evidence_complete=true: " + attemptId);
      }
      // EXTERNAL (recorder-backed) evidence requires every attempt to own a nonblank
      // entry/iteration/debug-session/recording identity. Blank-to-blank equality is never
      // accepted as evidence of ownership: the identities must actually exist.
      if (!"NOT_APPLICABLE".equals(evidenceMode)) {
        for (String field : List.of("entry_id", "iteration_id", "debug_session_id", "recording_id")) {
          if (attempt.path(field).asText("").isBlank()) {
            throw new IllegalArgumentException(
                "harness manifest attempt " + attemptId + " requires a nonblank '" + field + "'");
          }
        }
      }
      List<JsonNode> records = recordsByAttempt.get(attemptId);
      if (attemptEvidenceComplete || "PASS".equalsIgnoreCase(attempt.path("outcome").asText(""))) {
        if (records == null) {
          throw new IllegalArgumentException(
              "evidence-required attempt has no evidence records: " + attemptId);
        }
      }
      // Ownership of any provided records is always validated, including session/recording ids
      // that must equal the owning attempt's values and be nonblank for recorder evidence
      // (nonblank is never a substitute for an exact match, and blank-to-blank equality is
      // invalid).
      if (records != null) {
        String expectedSessionId = attempt.path("debug_session_id").asText("");
        String expectedRecordingId = attempt.path("recording_id").asText("");
        // EXACT evidence cardinality (SC-572): an evidence-required attempt owns exactly one record
        // per plan-required type. A second record for the same required type is rejected even when
        // its reference and checksum differ, and the record count must equal the plan's required
        // type count.
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (JsonNode record : records) {
          String recordType = record.path("type").asText().toUpperCase();
          typeCounts.merge(recordType, 1, Integer::sum);
          if (!attemptId.equals(record.path("attempt_id").asText(""))) {
            throw new IllegalArgumentException("evidence record attempt_id does not match its attempt");
          }
          boolean recorderOwned = "RECORDER".equalsIgnoreCase(record.path("provenance").asText(""))
              || "DEBUG_RECORDER".equalsIgnoreCase(record.path("provenance").asText(""));
          if (recorderOwned
              && (expectedSessionId.isBlank() || record.path("debug_session_id").asText("").isBlank())) {
            throw new IllegalArgumentException(
                "recorder evidence requires a nonblank debug_session_id for attempt " + attemptId);
          }
          if (recorderOwned
              && (expectedRecordingId.isBlank() || record.path("recording_id").asText("").isBlank())) {
            throw new IllegalArgumentException(
                "recorder evidence requires a nonblank recording_id for attempt " + attemptId);
          }
          if (!expectedSessionId.equals(record.path("debug_session_id").asText(""))) {
            throw new IllegalArgumentException(
                "evidence record debug_session_id does not match attempt " + attemptId);
          }
          if (!expectedRecordingId.equals(record.path("recording_id").asText(""))) {
            throw new IllegalArgumentException(
                "evidence record recording_id does not match attempt " + attemptId);
          }
        }
        if (typeCounts.size() != requiredTypes.size()) {
          throw new IllegalArgumentException(
              "evidence-required attempt " + attemptId + " carries " + typeCounts.size()
                  + " distinct evidence types but the plan requires " + requiredTypes.size());
        }
        for (String required : requiredTypes) {
          Integer count = typeCounts.get(required);
          if (count == null) {
            throw new IllegalArgumentException(
                "evidence-required attempt is missing required evidence type '" + required
                    + "' for attempt " + attemptId);
          }
          if (count != 1) {
            throw new IllegalArgumentException(
                "evidence-required attempt " + attemptId + " owns " + count
                    + " records of required evidence type '" + required
                    + "'; exactly one is permitted");
          }
        }
      }
    }
    // The aggregate evidence must be the exact deduplicated ordered union of the attempts'
    // evidence: every attempt record appears exactly once top-level and every top-level record is
    // owned by exactly one attempt whose evidence contains the exact same record.
    validateEvidenceUnion(manifest, evidence);
  }

  /**
   * Requires the aggregate evidence array to be the exact ORDERED union of the manifest attempts'
   * evidence: attempt-0 evidence in order, then attempt-1 evidence in order, then each subsequent
   * attempt's evidence in order. A top-level record that has no owning attempt record, an attempt
   * record missing from the aggregate, a record owned by two attempts, or a reordering is
   * rejected. Records are compared by canonical JSON with volatile timestamps excluded.
   */
  private void validateEvidenceUnion(JsonNode manifest, JsonNode evidence) {
    List<JsonNode> expectedUnion = new ArrayList<>();
    Map<String, String> ownerByCanonical = new LinkedHashMap<>();
    Map<String, String> referenceOwner = new LinkedHashMap<>();
    Map<String, String> checksumOwner = new LinkedHashMap<>();
    JsonNode attempts = manifest.path("attempts");
    if (attempts.isArray()) {
      for (JsonNode attempt : attempts) {
        if (!attempt.isObject()) {
          continue;
        }
        String attemptId = attempt.path("attempt_id").asText("");
        JsonNode attemptEvidence = attempt.path("evidence");
        if (attemptEvidence.isArray()) {
          for (JsonNode record : attemptEvidence) {
            String canonical = canonicalEvidence(record);
            expectedUnion.add(record);
            String priorOwner = ownerByCanonical.putIfAbsent(canonical, attemptId);
            if (priorOwner != null && !priorOwner.equals(attemptId)) {
              throw new IllegalArgumentException(
                  "the same evidence record is owned by attempts '" + priorOwner + "' and '" + attemptId + "'");
            }
            if (!attemptId.equals(record.path("attempt_id").asText(""))) {
              throw new IllegalArgumentException(
                  "evidence record attempt_id does not match its owning attempt " + attemptId);
            }
            // Reference/checksum ownership conflicts are aggregate across the whole run: no two
            // attempts may claim the same artifact reference or raw checksum.
            String reference = record.path("reference").asText("");
            String checksum = record.path("checksum").asText("");
            if (!reference.isBlank()) {
              String priorRef = referenceOwner.putIfAbsent(
                  record.path("type").asText("") + "|" + reference, attemptId);
              if (priorRef != null && !priorRef.equals(attemptId)) {
                throw new IllegalArgumentException(
                    "duplicate evidence reference '" + reference + "' across attempts " + priorRef
                        + " and " + attemptId);
              }
            }
            if (!checksum.isBlank()) {
              String priorChecksum = checksumOwner.putIfAbsent(checksum, attemptId);
              if (priorChecksum != null && !priorChecksum.equals(attemptId)) {
                throw new IllegalArgumentException(
                    "duplicate evidence checksum '" + checksum + "' across attempts " + priorChecksum
                        + " and " + attemptId);
              }
            }
          }
        }
      }
    }
    List<JsonNode> actualRecords = new ArrayList<>();
    for (JsonNode record : evidence) {
      actualRecords.add(record);
    }
    if (expectedUnion.size() != actualRecords.size()) {
      throw new IllegalArgumentException(
          "ordered evidence union length mismatch: expected " + expectedUnion.size()
              + " (attempts in order) but top-level has " + actualRecords.size());
    }
    for (int i = 0; i < expectedUnion.size(); i++) {
      if (!canonicalEvidence(expectedUnion.get(i)).equals(canonicalEvidence(actualRecords.get(i)))) {
        throw new IllegalArgumentException(
            "ordered evidence union mismatch at index " + i + ": expected "
                + canonicalEvidence(expectedUnion.get(i)) + " but found "
                + canonicalEvidence(actualRecords.get(i)));
      }
    }
  }

  /** Canonical, volatile-timestamp-free identity of one evidence record for union comparison. */
  private static String canonicalEvidence(JsonNode record) {
    return StabilizationHashUtil.canonicalize(
        record, Set.of("source_timestamp", "ingest_timestamp", "generated_at"));
  }

  private JsonNode validateReliabilityMatrix(JsonNode matrix, JsonNode manifest) {
    String schemaVersion = matrix.path("schema_version").asText("");
    if (!RELIABILITY_MATRIX_SCHEMA.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "unsupported reliability matrix schema_version '" + schemaVersion + "'; expected "
              + RELIABILITY_MATRIX_SCHEMA);
    }
    String declaredHash = matrix.path("matrix_hash").asText("matrix_unknown");
    String computedHash =
        "sha256:" + StabilizationHashUtil.sha256(matrix, StabilizationHashUtil.MATRIX_EXCLUSIONS);
    if (!declaredHash.equals(computedHash)) {
      throw new IllegalArgumentException(
          "reliability matrix hash mismatch: declared " + declaredHash + " but recomputed "
              + computedHash);
    }
    if (!matrix.path("groups").isArray()) {
      throw new IllegalArgumentException("reliability matrix groups must be an array");
    }
    // The uploaded aggregate must be represented by a verified matrix group: at least one group
    // must match the aggregate's full comparability tuple (plan, task, settings, build, artifact,
    // catalog, snapshot, tier, environment) and be consistent with its run/attempt counts and
    // outcome metrics. A self-hashed unrelated matrix is never accepted as certified data.
    if (manifest != null && !matrixHasMatchingGroup(matrix, manifest)) {
      throw new IllegalArgumentException(
          "reliability matrix has no group matching the uploaded aggregate's comparability tuple");
    }
    return matrix;
  }

  /** True when at least one group matches the aggregate tuple and its counts/outcome metrics. */
  private static boolean matrixHasMatchingGroup(JsonNode matrix, JsonNode manifest) {
    String aggregateOutcome = manifest.path("outcome").asText("").toUpperCase();
    int aggregateAttempts = manifest.path("attempt_count").asInt(1);
    JsonNode groups = matrix.path("groups");
    if (!groups.isArray()) {
      return false;
    }
    for (JsonNode group : groups) {
      if (!group.isObject()) {
        continue;
      }
      if (!tupleFieldMatches(group, "plan_hash", manifest, "plan_hash")
          || !tupleFieldMatches(group, "task_key", manifest, "task_key")
          || !tupleFieldMatches(group, "settings_hash", manifest, "settings_hash")
          || !tupleFieldMatches(group, "build_id", manifest, "build_id")
          || !tupleFieldMatches(group, "artifact_digest", manifest, "artifact_digest")
          || !tupleFieldMatches(group, "catalog_hash", manifest, "catalog_hash")
          || !tupleFieldMatches(group, "source_snapshot_id", manifest, "source_snapshot_id")
          || !tupleFieldMatches(group, "tier", manifest, "tier")
          || !tupleFieldMatches(group, "environment", manifest, "environment")) {
        continue;
      }
      int groupAttempts = group.path("attempt_count").asInt(0);
      if (groupAttempts < Math.max(1, aggregateAttempts)) {
        continue;
      }
      boolean outcomeConsistent;
      switch (aggregateOutcome) {
        case "PASS":
          outcomeConsistent = group.path("passes").asInt(0) >= 1;
          break;
        case "FAIL":
          outcomeConsistent = group.path("failures").asInt(0) >= 1;
          break;
        case "TIMEOUT":
          outcomeConsistent = group.path("timeouts").asInt(0) >= 1;
          break;
        case "CANCELLED":
          outcomeConsistent = group.path("cancelled").asInt(0) >= 1;
          break;
        default:
          outcomeConsistent = true;
      }
      if (outcomeConsistent) {
        return true;
      }
    }
    return false;
  }

  private static boolean tupleFieldMatches(JsonNode group, String groupField, JsonNode manifest,
      String manifestField) {
    String a = group.path(groupField).asText("");
    String b = manifest.path(manifestField).asText("");
    return a.equals(b) && !a.isBlank();
  }

  /**
   * Verifies each evidence record is backed by an attached checksum-addressed artifact whose raw
   * bytes hash to the record checksum, that the record's media type equals the uploaded artifact
   * part's normalized media type, and that no uploaded artifact is unused by the evidence.
   * Matching artifacts are staged into the artifact store's staging namespace and every staged id
   * is tracked immediately so a later failure cleans earlier stages exactly. Nothing is promoted
   * here: promotion happens only inside the persistence transaction. Any missing/incorrect/
   * mismatched artifact rejects the bundle (HTTP 400).
   */
  /** One staged evidence artifact paired with its durable external evidence reference. */
  private record StagedEvidence(
      StabilizationArtifactStore.StoredArtifact stored, String externalReference) {}

  /** Finds the durable external evidence reference bound to a promoted artifact UUID. */
  private static String findExternalReference(
      List<StagedEvidence> staged, StabilizationArtifactStore.StoredArtifact stored) {
    if (staged == null || stored == null) {
      return null;
    }
    for (StagedEvidence evidence : staged) {
      if (evidence != null && evidence.stored() != null
          && evidence.stored().id().equals(stored.id())) {
        return evidence.externalReference();
      }
    }
    return null;
  }

  private List<StagedEvidence> stageEvidenceArtifacts(
      List<ArtifactPart> parts, JsonNode manifest, JsonNode evidence,
      StabilizationIngestionResourceTracker resourceTracker) {
    if (parts == null) {
      parts = List.of();
    }
    Map<String, ArtifactPart> partsByChecksum = new HashMap<>();
    Set<String> seenFilenames = new HashSet<>();
    for (ArtifactPart part : parts) {
      if (part == null || part.filename() == null || part.path() == null) {
        continue;
      }
      if (!seenFilenames.add(part.filename())) {
        throw new IllegalArgumentException(
            "duplicate artifact filename/checksum collision: " + part.filename());
      }
      String hex = checksumHexFromFilename(part.filename());
      if (hex == null) {
        throw new IllegalArgumentException("artifact part filename must be checksum-addressed sha256_<64>.<ext>: "
            + part.filename());
      }
      String actual = rawSha256(part.path());
      if (!actual.endsWith(hex)) {
        throw new IllegalArgumentException("artifact part bytes do not match checksum-addressed filename "
            + part.filename());
      }
      if (part.mediaType() == null || normalizedMediaType(part.mediaType()).isEmpty()) {
        throw new IllegalArgumentException("artifact part is missing a media type: " + part.filename());
      }
      ArtifactPart prior = partsByChecksum.putIfAbsent(hex, part);
      if (prior != null) {
        throw new IllegalArgumentException(
            "artifact part checksum collision: " + part.filename());
      }
    }

    List<StagedEvidence> staged = new ArrayList<>();
    Set<String> usedHexes = new HashSet<>();
    Set<String> seenReferences = new HashSet<>();
    for (JsonNode record : evidence) {
      String reference = record.path("reference").asText("");
      // The durable (run_id, external_reference) mapping must be unambiguous (SC-572): a blank or
      // repeated reference is rejected before any artifact is staged.
      if (reference.isBlank()) {
        throw new IllegalArgumentException("evidence record is missing a reference");
      }
      if (!seenReferences.add(reference)) {
        throw new IllegalArgumentException("duplicate evidence reference '" + reference + "'");
      }
      String checksum = record.path("checksum").asText("");
      String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
      ArtifactPart part = partsByChecksum.get(hex);
      if (part == null) {
        throw new IllegalArgumentException(
            "evidence record checksum does not match any uploaded artifact: "
                + record.path("reference").asText(""));
      }
      String recordMediaType = record.path("media_type").asText("application/octet-stream");
      if (!normalizedMediaType(recordMediaType).equals(normalizedMediaType(part.mediaType()))) {
        throw new IllegalArgumentException(
            "evidence record media_type '" + recordMediaType + "' does not match the uploaded artifact part "
                + part.filename() + " media type '" + part.mediaType() + "'");
      }
      // Exact byte-count verification (SC-572): the evidence record's declared size_bytes and the
      // staged part's claimed size must both equal the actual uploaded bytes' length. A mismatch is
      // a tamper, never a warning.
      long actualSize;
      try {
        actualSize = Files.size(part.path());
      } catch (IOException e) {
        throw new IllegalArgumentException(
            "cannot read staged artifact size: " + part.filename());
      }
      long declaredSize = record.path("size_bytes").asLong(-1);
      if (declaredSize < 0) {
        throw new IllegalArgumentException(
            "evidence record requires a positive size_bytes: " + record.path("reference").asText(""));
      }
      if (declaredSize != actualSize) {
        throw new IllegalArgumentException(
            "evidence record size_bytes does not match uploaded artifact: "
                + record.path("reference").asText("") + " declared " + declaredSize
                + " but uploaded " + actualSize);
      }
      if (part.sizeBytes() != actualSize) {
        throw new IllegalArgumentException(
            "staged part size mismatch: " + part.filename() + " declared " + part.sizeBytes()
                + " but staged " + actualSize);
      }
      usedHexes.add(hex);
      StabilizationArtifactStore.StoredArtifact stagedArtifact = artifactStore.stage(
          part.path(), part.mediaType(), record.path("type").asText("EVIDENCE"),
          record.path("run_id").asText(""), record.path("attempt_id").asText(""));
      // The persisted artifact metadata must come from the promoted bytes, never from input
      // metadata: the staged digest/size must match the uploaded artifact's actual digest/size.
      if (stagedArtifact.sizeBytes() != actualSize) {
        throw new IllegalArgumentException(
            "staged artifact size does not match the uploaded bytes: " + part.filename());
      }
      if (!stagedArtifact.sha256().equals(checksum)) {
        throw new IllegalArgumentException(
            "staged artifact digest does not match the evidence checksum: " + part.filename());
      }
      // Track immediately: if any later artifact fails to stage, this id is still cleaned up.
      resourceTracker.trackStaged(stagedArtifact.id());
      // The durable external evidence reference is bound to the stored artifact UUID here so the
      // contract mapping is persisted, never reconstructed by a checksum lookup (SC-572).
      staged.add(new StagedEvidence(stagedArtifact, record.path("reference").asText("")));
    }
    // Every attached artifact must be consumed by an evidence record; unused artifacts are a
    // supplemental-media smuggling vector and are rejected.
    for (ArtifactPart part : parts) {
      String hex = checksumHexFromFilename(part.filename());
      if (hex != null && !usedHexes.contains(hex)) {
        throw new IllegalArgumentException(
            "unused attached evidence artifact is not referenced by any evidence record: " + part.filename());
      }
    }
    return staged;
  }

  /** Matches the checksum-addressed part filename {@code sha256_<64hex>.<ext>}. */
  private static String checksumHexFromFilename(String filename) {
    String name = filename == null ? "" : filename.trim();
    int dot = name.indexOf('.');
    String base = dot >= 0 ? name.substring(0, dot) : name;
    if (!base.startsWith("sha256_") || base.length() != "sha256_".length() + 64) {
      return null;
    }
    String hex = base.substring("sha256_".length());
    return hex.matches("[0-9a-f]{64}") ? hex : null;
  }

  /**
   * Normalizes a media type to its lowercased base type (the media type before any {@code ;}
   * parameter), mirroring the bundle stream reader so substring/parameter tricks can never bypass
   * the exact media-type comparison.
   */
  private static String normalizedMediaType(String mediaType) {
    if (mediaType == null) {
      return "";
    }
    int semicolon = mediaType.indexOf(';');
    String base = semicolon >= 0 ? mediaType.substring(0, semicolon) : mediaType;
    return base.trim().toLowerCase(java.util.Locale.ENGLISH);
  }

  private void persistAggregateManifest(JsonNode manifest, JsonNode transitions, JsonNode evidence,
      JsonNode matrix, List<StabilizationArtifact> storedArtifacts) {
    String runId = manifest.path("run_id").asText();
    String aggregateManifestHash = manifest.path("manifest_hash").asText();
    String transitionsJson;
    String evidenceJson;
    String matrixJson;
    try {
      transitionsJson = MAPPER.writeValueAsString(transitions);
      evidenceJson = MAPPER.writeValueAsString(evidence);
      matrixJson = MAPPER.writeValueAsString(matrix);
    } catch (Exception e) {
      throw new IllegalArgumentException("report bundle artifacts are not serializable", e);
    }
    String baseRef = "micro6-stabilization/" + runId + "/";
    JsonNode attempts = manifest.path("attempts");
    for (JsonNode attempt : attempts) {
      ScriptStabilizationRun run = new ScriptStabilizationRun();
      run.setReportId("harness-" + runId + "-" + attempt.path("attempt_id").asText());
      run.setVerified(true);
      run.setSchemaVersion(HARNESS_MANIFEST_SCHEMA);
      run.setScriptKey(attempt.path("task_key").asText(manifest.path("task_key").asText("unknown")));
      run.setOutcome(attempt.path("outcome").asText(manifest.path("outcome").asText("UNKNOWN")));
      run.setStatus(attempt.path("terminal_state").asText(manifest.path("terminal_state").asText("UNKNOWN")));
      run.setDurationMs(attempt.path("latency_ms").isNumber() ? attempt.path("latency_ms").asLong() : null);
      run.setFailureSignature(nullIfBlank(attempt.path("failure_signature").asText(manifest.path("failure_signature").asText(""))));
      run.setErrorMessage(nullIfBlank(attempt.path("primary_failure_code").asText("")));
      run.setPlanHash(nullIfBlank(manifest.path("plan_hash").asText("")));
      run.setRunId(runId);
      run.setAttemptId(attempt.path("attempt_id").asText());
      run.setEntryId(nullIfBlank(attempt.path("entry_id").asText("")));
      run.setIterationId(nullIfBlank(attempt.path("iteration_id").asText("")));
      run.setDebugSessionId(nullIfBlank(attempt.path("debug_session_id").asText("")));
      run.setRecordingId(nullIfBlank(attempt.path("recording_id").asText("")));
      run.setSettingsHash(nullIfBlank(manifest.path("settings_hash").asText("")));
      run.setBuildId(nullIfBlank(manifest.path("build_id").asText("")));
      run.setCatalogHash(nullIfBlank(manifest.path("catalog_hash").asText("")));
      run.setArtifactDigest(nullIfBlank(manifest.path("artifact_digest").asText("")));
      run.setExecutorInstanceId(nullIfBlank(manifest.path("micro6_instance_id").asText("")));
      run.setSourceSnapshotId(nullIfBlank(manifest.path("source_snapshot_id").asText("")));
      run.setPrimaryFailureCode(nullIfBlank(attempt.path("primary_failure_code").asText("")));
      run.setEvidenceComplete(attempt.path("evidence_complete").asBoolean(false));
      run.setManifestHash(aggregateManifestHash);
      run.setDeterministicContentHash(nullIfBlank(manifest.path("deterministic_content_hash").asText("")));
      run.setEnvironment(nullIfBlank(manifest.path("environment").asText("")));
      run.setTier(nullIfBlank(manifest.path("tier").asText("")));
      run.setManifestReference(baseRef + "run-manifest.json");
      run.setTransitionsReference(baseRef + "transitions.json");
      run.setEvidenceReference(baseRef + "evidence.json");
      run.setChecksumsReference(baseRef + "checksums.json");
      run.setMatrixReference(baseRef + "reliability-matrix.json");
      run.setTransitionsJson(transitionsJson);
      run.setEvidenceJson(evidenceJson);
      runRepository.save(run);
    }
    artifactRepository.saveAll(storedArtifacts);

    for (ScriptStabilizationRun run : runRepository.findByRunIdOrderByAttemptIdAsc(runId)) {
      if (!"PASS".equalsIgnoreCase(run.getOutcome())
          && run.getPrimaryFailureCode() != null
          && !run.getPrimaryFailureCode().isBlank()
          && !"none".equalsIgnoreCase(run.getPrimaryFailureCode())) {
        upsertTriage(run, run.getReportId());
      }
    }
  }

  /** Persists the dedicated aggregate row keyed by run_id from the top-level manifest identity. */
  private void persistRunAggregate(JsonNode manifest) {
    StabilizationRunAggregate aggregate = new StabilizationRunAggregate();
    aggregate.setRunId(manifest.path("run_id").asText(""));
    aggregate.setPlanId(nullIfBlank(manifest.path("plan_id").asText("")));
    aggregate.setPlanHash(nullIfBlank(manifest.path("plan_hash").asText("")));
    aggregate.setManifestHash(manifest.path("manifest_hash").asText(""));
    aggregate.setDeterministicContentHash(nullIfBlank(manifest.path("deterministic_content_hash").asText("")));
    aggregate.setTaskKey(nullIfBlank(manifest.path("task_key").asText("")));
    aggregate.setSettingsHash(nullIfBlank(manifest.path("settings_hash").asText("")));
    aggregate.setBuildId(nullIfBlank(manifest.path("build_id").asText("")));
    aggregate.setArtifactDigest(nullIfBlank(manifest.path("artifact_digest").asText("")));
    aggregate.setCatalogHash(nullIfBlank(manifest.path("catalog_hash").asText("")));
    aggregate.setSourceSnapshotId(nullIfBlank(manifest.path("source_snapshot_id").asText("")));
    aggregate.setMicro6InstanceId(nullIfBlank(manifest.path("micro6_instance_id").asText("")));
    aggregate.setOutcome(manifest.path("outcome").asText(""));
    aggregate.setTerminalState(nullIfBlank(manifest.path("terminal_state").asText("")));
    aggregate.setAttemptCount(manifest.path("attempt_count").asInt(0));
    aggregate.setEvidenceComplete(manifest.path("evidence_complete").asBoolean(false));
    aggregate.setTier(nullIfBlank(manifest.path("tier").asText("")));
    aggregate.setEnvironment(nullIfBlank(manifest.path("environment").asText("")));
    aggregateRepository.save(aggregate);
  }

  private Map<String, Object> persistMatrixSnapshots(JsonNode matrix) {
    String declaredHash = matrix.path("matrix_hash").asText("matrix_unknown");
    String matrixJson;
    try {
      matrixJson = MAPPER.writeValueAsString(matrix);
    } catch (Exception e) {
      throw new IllegalArgumentException("reliability matrix is not serializable", e);
    }
    String incomparableJson;
    try {
      incomparableJson = MAPPER.writeValueAsString(matrix.path("incomparable"));
    } catch (Exception e) {
      incomparableJson = "[]";
    }
    JsonNode groups = matrix.path("groups");
    int stored = 0;
    int skipped = 0;
    if (groups.isArray()) {
      for (JsonNode group : groups) {
        String groupKey = group.path("group_key").asText(null);
        if (groupKey == null || snapshotRepository.existsByMatrixHashAndGroupKey(declaredHash, groupKey)) {
          skipped++;
          continue;
        }
        ScriptStabilizationSnapshot snapshot = new ScriptStabilizationSnapshot();
        snapshot.setReportId("matrix-" + declaredHash);
        snapshot.setScriptKey(group.path("task_key").asText("unknown"));
        snapshot.setFamily("harness");
        snapshot.setMatrixHash(declaredHash);
        snapshot.setGroupKey(groupKey);
        snapshot.setPlanHash(nullIfBlank(group.path("plan_hash").asText("")));
        snapshot.setBuildId(nullIfBlank(group.path("build_id").asText("")));
        snapshot.setCatalogHash(nullIfBlank(group.path("catalog_hash").asText("")));
        snapshot.setArtifactDigest(nullIfBlank(group.path("artifact_digest").asText("")));
        snapshot.setExecutorInstanceId(nullIfBlank(group.path("executor_instance_id").asText("")));
        snapshot.setSourceSnapshotId(nullIfBlank(group.path("source_snapshot_id").asText("")));
        snapshot.setRetryCount(group.path("retry_count").asInt(0));
        snapshot.setWindow24hCount(group.path("total_runs").asInt(0));
        snapshot.setWindow7dCount(group.path("total_runs").asInt(0));
        snapshot.setPassCount24h(group.path("passes").asInt(0));
        snapshot.setFailCount24h(group.path("failures").asInt(0));
        snapshot.setTimeoutCount24h(group.path("timeouts").asInt(0));
        snapshot.setPassRate24h(group.path("pass_rate").asDouble(0));
        snapshot.setPassRate7d(group.path("pass_rate").asDouble(0));
        snapshot.setTimeoutRate24h(group.path("timeout_rate").asDouble(0));
        snapshot.setRegression(group.has("regression") ? group.path("regression").asBoolean() : false);
        snapshot.setBaselineReportId(nullIfBlank(group.path("compared_to").asText("")));
        snapshot.setBaselinePassRate24h(0.0);
        snapshot.setFailureClusterCount(group.path("failure_signatures").size());
        snapshot.setTopFailureSignature(topFailureSignature(group.path("failure_signatures")));
        snapshot.setFirstAttemptPassRate(group.path("first_attempt_pass_rate").asDouble(0));
        snapshot.setEventualPassRate(group.path("eventual_pass_rate").asDouble(0));
        snapshot.setEvidenceCompleteRate(group.path("evidence_complete_rate").asDouble(0));
        snapshot.setCleanupSuccessRate(group.path("cleanup_success_rate").asDouble(0));
        snapshot.setComparisonStatus(group.path("comparison_status").asText("BASELINE"));
        snapshot.setMatrixJson(matrixJson);
        snapshot.setIncomparableJson(incomparableJson);
        snapshotRepository.save(snapshot);
        stored++;
      }
    }
    return Map.of("matrixHash", declaredHash, "snapshots", stored, "skipped", skipped,
        "schemaVersion", matrix.path("schema_version").asText(""));
  }

  /**
   * Reads a staged JSON part under the 5 MiB bound. Optional artifacts are never loaded into heap;
   * only the seven report documents (JSON + plain-text marker) are read in full.
   */
  private byte[] readPartBytes(String name, Path path) {
    if (path == null) {
      throw new IllegalArgumentException("report bundle " + name + " is missing");
    }
    try {
      long size = Files.size(path);
      if (size > StabilizationBundleStreamReader.MAX_JSON_PART_BYTES) {
        throw new IllegalArgumentException("report bundle " + name + " exceeds the 5 MiB bound");
      }
      return Files.readAllBytes(path);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("report bundle " + name + " cannot be read");
    }
  }

  private JsonNode parseBundleJson(String name, Path path) {
    byte[] bytes = readPartBytes(name, path);
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("report bundle " + name + " is empty");
    }
    try {
      return MAPPER.readTree(bytes);
    } catch (Exception e) {
      throw new IllegalArgumentException("report bundle " + name + " is invalid JSON");
    }
  }

  private static String rawSha256(byte[] bytes) {
    if (bytes == null) {
      return "";
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder("sha256:");
      for (byte value : digest) {
        result.append(String.format("%02x", value));
      }
      return result.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /** Streams a staged file through SHA-256 without loading it into heap memory. */
  private static String rawSha256(Path path) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(path)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      StringBuilder result = new StringBuilder("sha256:");
      for (byte value : digest.digest()) {
        result.append(String.format("%02x", value));
      }
      return result.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * Ingests a versioned {@code reliability-matrix/v1} document, persisting one snapshot per
   * comparable group idempotently by {@code matrix_hash + group_key}, and preserving the full
   * matrix document and its {@code incomparable[]} entries. Unknown schema versions fail clearly.
   */
  @Transactional
  public Map<String, Object> ingestReliabilityMatrix(JsonNode matrix) {
    JsonNode verified = validateReliabilityMatrix(matrix, null);
    return persistMatrixSnapshots(verified);
  }

  private static String topFailureSignature(JsonNode signatures) {
    String top = null;
    int topCount = -1;
    if (signatures != null && signatures.isObject()) {
      var it = signatures.fields();
      while (it.hasNext()) {
        var entry = it.next();
        int count = entry.getValue().asInt();
        if (count > topCount) {
          topCount = count;
          top = entry.getKey();
        }
      }
    }
    return top;
  }

  /** Returns the aggregate run metadata (never a single attempt row). */
  public Optional<StabilizationRunAggregate> getRunByRunId(String runId) {
    if (runId == null || runId.isBlank()) {
      return Optional.empty();
    }
    return aggregateRepository.findByRunId(runId);
  }

  public Optional<ScriptStabilizationRun> getRunByRunIdAndAttemptId(String runId, String attemptId) {
    if (runId == null || runId.isBlank() || attemptId == null || attemptId.isBlank()) {
      return Optional.empty();
    }
    return runRepository.findFirstByRunIdAndAttemptIdOrderByExecutedAtDesc(runId, attemptId);
  }

  public List<ScriptStabilizationRun> getRunAttempts(String runId) {
    if (runId == null || runId.isBlank()) {
      return List.of();
    }
    return runRepository.findByRunIdOrderByAttemptIdAsc(runId);
  }

  /** Returns the aggregate transitions document for a run, or empty when absent. */
  public Optional<JsonNode> getRunTransitions(String runId) {
    if (runId == null || runId.isBlank()) {
      return Optional.empty();
    }
    return runRepository.findByRunIdOrderByExecutedAtAsc(runId).stream()
        .map(ScriptStabilizationRun::getTransitionsJson)
        .filter(json -> json != null && !json.isBlank())
        .findFirst()
        .flatMap(this::parseJson);
  }

  /** Returns the aggregate evidence document for a run, or empty when absent. */
  public Optional<JsonNode> getRunEvidence(String runId) {
    if (runId == null || runId.isBlank()) {
      return Optional.empty();
    }
    return runRepository.findByRunIdOrderByExecutedAtAsc(runId).stream()
        .map(ScriptStabilizationRun::getEvidenceJson)
        .filter(json -> json != null && !json.isBlank())
        .findFirst()
        .flatMap(this::parseJson);
  }

  /** Returns verified artifact metadata for a run. */
  public List<StabilizationArtifact> getRunArtifacts(String runId) {
    if (runId == null || runId.isBlank()) {
      return List.of();
    }
    return artifactRepository.findByRunIdOrderByCreatedAtAsc(runId);
  }

  /** Returns a stored artifact by opaque id. */
  public Optional<StabilizationArtifact> getArtifact(String artifactId) {
    if (artifactId == null || artifactId.isBlank()) {
      return Optional.empty();
    }
    return artifactRepository.findByArtifactId(artifactId);
  }

  /** Resolves a stored artifact by its durable external evidence reference within a run, without
   *  reconstructing a checksum lookup (SC-572). Lookup ALWAYS requires the run id: the global
   *  reference fallback is forbidden because the same reference can legitimately repeat across
   *  distinct runs. */
  public Optional<StabilizationArtifact> getArtifactByExternalReference(String runId, String externalReference) {
    if (runId == null || runId.isBlank() || externalReference == null || externalReference.isBlank()) {
      return Optional.empty();
    }
    return artifactRepository.findByRunIdAndExternalReference(runId, externalReference);
  }

  private Optional<JsonNode> parseJson(String json) {
    try {
      return Optional.of(MAPPER.readTree(json));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public Optional<ScriptStabilizationSnapshot> getMatrixByMatrixHash(String matrixHash) {
    if (matrixHash == null || matrixHash.isBlank()) {
      return Optional.empty();
    }
    return snapshotRepository.findFirstByMatrixHashOrderByCapturedAtDesc(matrixHash);
  }

  /**
   * Reconstructs the full reliability matrix document (groups + incomparable) for a matrix hash.
   */
  public Optional<JsonNode> getMatrixDocumentByHash(String matrixHash) {
    List<ScriptStabilizationSnapshot> snapshots =
        snapshotRepository.findByMatrixHashOrderByGroupKeyAsc(matrixHash);
    if (snapshots.isEmpty()) {
      return Optional.empty();
    }
    try {
      JsonNode matrix = MAPPER.readTree(snapshots.get(0).getMatrixJson());
      return Optional.of(matrix);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public Map<String, Object> computeKpis() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime start24h = now.minusHours(24);
    LocalDateTime start7d = now.minusDays(7);
    // Legacy /ingest-report rows are explicitly unverified and are excluded from certified KPIs.
    List<ScriptStabilizationRun> rows24 = runRepository.findByVerifiedTrueAndExecutedAtBetween(start24h, now);
    List<ScriptStabilizationRun> rows7d = runRepository.findByVerifiedTrueAndExecutedAtBetween(start7d, now);

    Map<String, Object> byScript = new HashMap<>();
    Map<String, List<ScriptStabilizationRun>> grouped24 = groupByScript(rows24);
    Map<String, List<ScriptStabilizationRun>> grouped7d = groupByScript(rows7d);
    for (Map.Entry<String, List<ScriptStabilizationRun>> entry : grouped24.entrySet()) {
      String script = entry.getKey();
      double pass24 = passRate(entry.getValue());
      double pass7 = passRate(grouped7d.getOrDefault(script, List.of()));
      long timeouts =
          entry.getValue().stream().filter(r -> "TIMEOUT".equalsIgnoreCase(r.getOutcome())).count();
      Map<String, Object> data = new HashMap<>();
      data.put("pass_rate_24h", pass24);
      data.put("pass_rate_7d", pass7);
      data.put(
          "timeout_rate_24h",
          entry.getValue().isEmpty() ? 0 : ((double) timeouts / entry.getValue().size()));
      data.put("regression", pass24 + 0.10 < pass7);
      byScript.put(script, data);
    }

    List<ScriptFailureTriage> open = triageRepository.findByStatusOrderByLastSeenAtDesc("OPEN");
    return Map.of(
        "window_24h_count",
        rows24.size(),
        "window_7d_count",
        rows7d.size(),
        "open_triage_count",
        open.size(),
        "scripts",
        byScript);
  }

  public List<ScriptFailureTriage> listOpenTriage() {
    return triageRepository.findByStatusOrderByLastSeenAtDesc("OPEN");
  }

  @Transactional
  public ScriptFailureTriage resolveTriage(Long id) {
    ScriptFailureTriage triage =
        triageRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Triage item not found: " + id));
    triage.setStatus("RESOLVED");
    triage.setLastSeenAt(LocalDateTime.now());
    return triageRepository.save(triage);
  }

  private static boolean outcomeConsistent(String outcome, String terminalState) {
    switch (outcome) {
      case "PASS":
        return "PASSED".equals(terminalState);
      case "FAIL":
        return "FAILED".equals(terminalState) || "INVALID".equals(terminalState);
      case "TIMEOUT":
        return "TIMED_OUT".equals(terminalState);
      case "CANCELLED":
        return "CANCELLED".equals(terminalState);
      default:
        return false;
    }
  }

  private void upsertTriage(ScriptStabilizationRun run, String reportId) {
    String triageKey = run.getScriptKey() + "::" + run.getFailureSignature();
    ScriptFailureTriage triage =
        triageRepository
            .findByTriageKey(triageKey)
            .orElseGet(
                () -> {
                  ScriptFailureTriage created = new ScriptFailureTriage();
                  created.setTriageKey(triageKey);
                  created.setScriptKey(run.getScriptKey());
                  created.setFailureSignature(run.getFailureSignature());
                  return created;
                });
    triage.setOccurrences((triage.getOccurrences() == null ? 0 : triage.getOccurrences()) + 1);
    triage.setLastSeenAt(LocalDateTime.now());
    triage.setLastReportId(reportId);
    triage.setLastErrorMessage(run.getErrorMessage());
    triageRepository.save(triage);
  }

  private Map<String, List<ScriptStabilizationRun>> groupByScript(
      List<ScriptStabilizationRun> rows) {
    Map<String, List<ScriptStabilizationRun>> grouped = new HashMap<>();
    for (ScriptStabilizationRun row : rows) {
      grouped.computeIfAbsent(row.getScriptKey(), k -> new ArrayList<>()).add(row);
    }
    return grouped;
  }

  private double passRate(List<ScriptStabilizationRun> rows) {
    if (rows == null || rows.isEmpty()) {
      return 0.0;
    }
    long pass = rows.stream().filter(r -> "PASS".equalsIgnoreCase(r.getOutcome())).count();
    return (double) pass / rows.size();
  }

  private String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
