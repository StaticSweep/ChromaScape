package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObject;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.chromascape.web.config.SourceTrustProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verifies, durably records, caches, and binds authoritative source identities. */
@Service
public class SourceManifestBindingService {
  private final ObjectMapper mapper;
  private final SourceManifestReferenceRepository manifests;
  private final SourceRunBindingRepository bindings;
  private final SourceContentObjectRepository contentObjects;
  private final VerifiedSourceContentCache cache;
  private final CurrentSliceRunService runs;
  private final CurrentSliceIdentityValidator identities;
  /** The single canonical trust configuration; services never read legacy trust prefixes. */
  private final SourceTrustProperties trust;

  @org.springframework.beans.factory.annotation.Autowired
  public SourceManifestBindingService(
      ObjectMapper mapper,
      SourceManifestReferenceRepository manifests,
      SourceRunBindingRepository bindings,
      SourceContentObjectRepository contentObjects,
      VerifiedSourceContentCache cache,
      CurrentSliceRunService runs,
      CurrentSliceIdentityValidator identities,
      SourceTrustProperties trust) {
    this.mapper = mapper;
    this.manifests = manifests;
    this.bindings = bindings;
    this.contentObjects = contentObjects;
    this.cache = cache;
    this.runs = runs;
    this.identities = identities;
    this.trust = trust;
  }

  /**
   * Verifies and persists one manifest within the caller's transaction. The remote fetch that
   * produced {@code manifestJson} must already have happened outside any transaction via
   * {@link SourceManifestFetchService}; this method only verifies against the expected identity and
   * writes.
   */
  @Transactional
  public TaskSourceManifestVerifier.Verified verifyAndPersist(
      String taskKey, String manifestJson, TaskSourceManifestVerifier.ExpectedIdentity expected) {
    try {
      if (taskKey == null || taskKey.isBlank() || manifestJson == null || manifestJson.isBlank()) {
        throw new SourceManifestException(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
            "source task and manifest are required");
      }
      JsonNode manifest = SourceManifestCanonicalJson.parse(
          manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      TaskSourceManifestVerifier.Verified verified = verifier().verify(manifest, expected);
      // The public verifier accepts only signed v1; this guard keeps a future non-authoritative
      // result from ever reaching the manifest table even if a new decode path is added.
      if (verified == null || !verified.signatureVerified()
          || !SourceManifestContract.SCHEMA.equals(verified.manifest().schema())) {
        throw new SourceManifestException(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
            "source binding requires a signed v1 manifest");
      }
      if (!taskKey.equals(verified.manifest().taskKey())) {
        throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
            "task identity differs from the requested task");
      }
      enforceEnvironment(verified.rawManifest());
      requireAvailable(verified.manifest().status());
      String hash = verified.manifest().manifestHash();
      String canonicalManifest = SourceManifestCanonicalJson.canonical(manifest);
      String manifestEnvironment = declaredEnvironment(verified.rawManifest());
      SourceManifestReference existing = manifests.findByManifestHash(hash).orElse(null);
      if (existing != null) {
        if (!canonicalManifest.equals(existing.getManifestJson())
            || !same(existing.getTaskKey(), verified.manifest().taskKey())
            || !same(existing.getSnapshotId(), verified.manifest().sourceSnapshotId())
            || !same(existing.getRepositoryId(), nested(manifest, "repository", "repository_id"))
            || !same(existing.getCommitId(), nested(manifest, "repository", "commit_id"))
            || !same(existing.getTreeId(), nested(manifest, "repository", "tree_id"))
            || !same(existing.getBuildId(), nested(manifest, "build", "build_id"))
            || !same(existing.getArtifactDigest(), nested(manifest, "build", "artifact_digest"))
            || !same(existing.getEnvironment(), manifestEnvironment)) {
          throw new SourceManifestException(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
              "manifest identity was reused with different bytes");
        }
      } else {
        SourceManifestReference sameSnapshot = manifests.findBySnapshotId(
            verified.manifest().sourceSnapshotId()).orElse(null);
        if (sameSnapshot != null && !hash.equals(sameSnapshot.getManifestHash())) {
          throw new SourceManifestException(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
              "snapshot identity was reused with different manifest bytes");
        }
        SourceManifestReference reference = new SourceManifestReference();
        reference.setTaskKey(verified.manifest().taskKey());
        reference.setSchemaVersion(verified.manifest().schema());
        reference.setManifestHash(hash);
        reference.setSnapshotId(verified.manifest().sourceSnapshotId());
        reference.setRepositoryId(nested(manifest, "repository", "repository_id"));
        reference.setCommitId(nested(manifest, "repository", "commit_id"));
        reference.setTreeId(nested(manifest, "repository", "tree_id"));
        reference.setBuildId(nested(manifest, "build", "build_id"));
        reference.setArtifactDigest(nested(manifest, "build", "artifact_digest"));
        reference.setVerificationState(verificationState(verified));
        reference.setSourceProvenance(provenance(verified));
        reference.setRetentionClass(verified.manifest().retentionClass());
        reference.setConfidentiality(verified.manifest().confidentiality());
        reference.setEnvironment(manifestEnvironment);
        reference.setManifestJson(canonicalManifest);
        // The insert intentionally does NOT catch DataIntegrityViolationException here: a unique
        // violation marks the current transaction rollback-only, so continuing to read or write
        // inside it would fail at commit. The conflict propagates to the caller (orchestration),
        // which rolls back and retries the whole short transaction; the retry then finds the
        // winner and validates exact bytes through the existing-identity path above.
        manifests.saveAndFlush(reference);
      }
      return verified;
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source manifest could not be persisted", exception);
    }
  }

  /**
   * Binds a run to an already-persisted verified manifest. Joins the caller's transaction (usually
   * the single orchestration transaction) so manifest, run, binding and run-stage writes commit or
   * roll back together; when called without an active transaction it opens its own.
   */
  @Transactional
  public SourceRunBinding bindRun(
      String runId, String recordingId, String taskKey,
      TaskSourceManifestVerifier.Verified verified) {
    if (runId == null || runId.isBlank() || verified == null
        || taskKey == null || taskKey.isBlank()) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "run and verified source are required");
    }
    // Run binding is authoritative-only: an unsigned or unsupported result can never bind a run,
    // even when it was constructed in-process rather than through verifyAndPersist.
    if (!verified.signatureVerified()
        || !SourceManifestContract.SCHEMA.equals(verified.manifest().schema())) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "run binding requires a signed v1 manifest");
    }
    if (!taskKey.equals(verified.manifest().taskKey())) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "run task identity differs from the source manifest");
    }
    enforceEnvironment(verified.rawManifest());
    requireAvailable(verified.manifest().status());
    String normalizedRecordingId = normalizeOptionalIdentity(recordingId, "recording identity");
    SourceManifestReference reference = manifests.findByManifestHash(
        verified.manifest().manifestHash()).orElseThrow(() ->
        new SourceManifestException(SourceManifestFailureCode.SOURCE_UNAVAILABLE,
            "verified source manifest is not persisted"));
    if (!same(reference.getTaskKey(), taskKey)
        || !same(reference.getSnapshotId(), verified.manifest().sourceSnapshotId())
        || !same(reference.getEnvironment(), declaredEnvironment(verified.rawManifest()))) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "persisted source manifest identity differs from the run binding");
    }
    String sourceEnvironment = reference.getEnvironment();
    SourceRunBinding existing = bindings.findByRunId(runId).orElse(null);
    String repositoryId = nested(verified.rawManifest(), "repository", "repository_id");
    String commitId = nested(verified.rawManifest(), "repository", "commit_id");
    String treeId = nested(verified.rawManifest(), "repository", "tree_id");
    String buildId = nested(verified.rawManifest(), "build", "build_id");
    String artifact = nested(verified.rawManifest(), "build", "artifact_digest");
    if (artifact == null || !SourceManifestContract.isHash(artifact)) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "run binding requires a verified artifact digest");
    }
    if (normalizedRecordingId != null) {
      SourceRunBinding recordingBinding = bindings.findByRecordingId(normalizedRecordingId).orElse(null);
      if (recordingBinding != null && !runId.equals(recordingBinding.getRunId())) {
        throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
            "recording is already bound to a different run");
      }
    }
    if (existing != null) {
      if (!same(existing.getTaskKey(), taskKey)
          || !same(existing.getManifestHash(), verified.manifest().manifestHash())
          || !same(existing.getSnapshotId(), verified.manifest().sourceSnapshotId())
          || !same(existing.getRepositoryId(), repositoryId)
          || !same(existing.getCommitId(), commitId)
          || !same(existing.getTreeId(), treeId)
          || !same(existing.getBuildId(), buildId)
          || !same(existing.getArtifactDigest(), artifact)
          || !same(existing.getEnvironment(), sourceEnvironment)
          || !same(existing.getVerificationState(), verificationState(verified))
          || !same(existing.getSourceProvenance(), provenance(verified))
          || (existing.getRecordingId() != null && !same(existing.getRecordingId(), normalizedRecordingId))) {
        throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
            "run is already bound to a different source identity");
      }
      if (existing.getRecordingId() == null && normalizedRecordingId != null) {
        existing.setRecordingId(normalizedRecordingId);
        bindings.save(existing);
      }
      return existing;
    }
    SourceRunBinding binding = new SourceRunBinding();
    binding.setRunId(runId);
    binding.setRecordingId(normalizedRecordingId);
    binding.setTaskKey(taskKey);
    binding.setManifestHash(verified.manifest().manifestHash());
    binding.setSnapshotId(verified.manifest().sourceSnapshotId());
    binding.setRepositoryId(repositoryId);
    binding.setCommitId(commitId);
    binding.setTreeId(treeId);
    binding.setBuildId(buildId);
    binding.setArtifactDigest(artifact);
    binding.setEnvironment(sourceEnvironment);
    binding.setVerificationState(verificationState(verified));
    binding.setSourceProvenance(provenance(verified));
    SourceRunBinding saved = bindings.save(binding);
    advanceCurrentSliceRun(runId, verified, artifact);
    return saved;
  }

  public void cacheFile(TaskSourceManifest.File file, byte[] content) {
    if (file == null) throw new SourceManifestException(SourceManifestFailureCode.SOURCE_FILE_NOT_FOUND, "source file is not allowlisted");
    if (content == null || content.length != file.sizeBytes()) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
          "source content size does not match the verified identity");
    }
    persistContent(file, content);
    cache.put(file.contentHash(), content);
  }

  /**
   * Stores one verified content-addressed object without permitting identity reuse. A database
   * object survives a process restart; the bounded in-memory cache remains only an acceleration
   * layer and never becomes the source of truth.
   */
  @Transactional
  public void persistContent(TaskSourceManifest.File file, byte[] content) {
    if (file == null) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_FILE_NOT_FOUND,
          "source file is not allowlisted");
    }
    if (content == null || content.length != file.sizeBytes()
        || !file.contentHash().equals(hash(content))) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
          "source content does not match the verified identity");
    }
    if (contentObjects == null) return;
    SourceContentObject existing = contentObjects.findByContentHash(file.contentHash()).orElse(null);
    if (existing != null) {
      if (existing.getPrunedAt() != null) {
        throw new SourceManifestException(SourceManifestFailureCode.SOURCE_PRUNED,
            "source snapshot has been pruned");
      }
      if (existing.getRedactedAt() != null
          || !java.util.Arrays.equals(existing.getContentBytes(), content)) {
        throw new SourceManifestException(SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
            "source content identity was reused with different bytes");
      }
      return;
    }
    contentObjects.save(SourceContentObject.create(file.contentHash(), content));
  }

  public VerifiedSourceContentCache cache() { return cache; }

  @Transactional(readOnly = true)
  public Map<String, Object> binding(String runId) {
    SourceRunBinding binding = bindings.findByRunId(runId)
        .orElseThrow(() -> new SourceManifestException(SourceManifestFailureCode.SOURCE_UNAVAILABLE,
            "source binding is unavailable"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("run_id", binding.getRunId());
    result.put("recording_id", binding.getRecordingId());
    result.put("task_key", binding.getTaskKey());
    result.put("source_manifest_hash", binding.getManifestHash());
    result.put("source_snapshot_id", binding.getSnapshotId());
    result.put("repository_id", binding.getRepositoryId());
    result.put("commit_id", binding.getCommitId());
    result.put("tree_id", binding.getTreeId());
    result.put("build_id", binding.getBuildId());
    result.put("artifact_digest", binding.getArtifactDigest());
    result.put("environment", binding.getEnvironment());
    result.put("verification_state", binding.getVerificationState());
    result.put("source_provenance", binding.getSourceProvenance());
    result.put("created_at", binding.getCreatedAt() == null ? Instant.now().toString() : binding.getCreatedAt().toString());
    return result;
  }

  private void advanceCurrentSliceRun(
      String runId, TaskSourceManifestVerifier.Verified verified, String artifact) {
    try {
      Map<String, Object> current = runs.get(runId);
      CurrentSliceIdentity base = mapper.convertValue(current.get("identity"), CurrentSliceIdentity.class);
      CurrentSliceIdentity.Source source = identitySource(verified);
      CurrentSliceIdentity enriched = new CurrentSliceIdentity(
          base.schema(), base.task(), base.run(), base.client(), base.capture(), source,
          base.graph(), base.model(), base.candidate(), false);
      identities.validate(enriched);
      runs.advanceIdentity(runId, enriched, "SOURCE_BOUND");
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "run identity could not be bound to source", exception);
    }
  }

  /** True when the caller may safely retry a whole short transaction after a manifest conflict. */
  static boolean isManifestConflict(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataIntegrityViolationException) return true;
    }
    return false;
  }

  private TaskSourceManifestVerifier verifier() {
    return SourceManifestFetchService.verifierFor(trust);
  }

  private static String verificationState(TaskSourceManifestVerifier.Verified verified) {
    if (verified == null || !verified.signatureVerified()) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source binding requires a verified signature");
    }
    return "VERIFIED";
  }

  private static String provenance(TaskSourceManifestVerifier.Verified verified) {
    if (verified == null || !verified.signatureVerified()) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source binding requires a verified signature");
    }
    return "VERIFIED";
  }

  /** Creates the source identity group from the verified v1 manifest. */
  public CurrentSliceIdentity.Source identitySource(
      TaskSourceManifestVerifier.Verified verified) {
    if (verified == null || !verified.signatureVerified()
        || !SourceManifestContract.SCHEMA.equals(verified.manifest().schema())) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source identity requires a verified signature");
    }
    JsonNode raw = verified.rawManifest();
    return new CurrentSliceIdentity.Source(
        verified.manifest().sourceSnapshotId(), verified.manifest().manifestHash(),
        nested(raw, "repository", "repository_id"),
        nested(raw, "repository", "commit_id"),
        nested(raw, "repository", "tree_id"),
        nested(raw, "build", "build_id"),
        nested(raw, "build", "artifact_digest"),
        Boolean.TRUE);
  }

  private static String normalizeOptionalIdentity(String value, String label) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > 160 || normalized.contains("/") || normalized.contains("\\")
        || normalized.indexOf('\u0000') >= 0) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          label + " is invalid");
    }
    return normalized;
  }

  private void enforceEnvironment(JsonNode manifest) {
    SourceManifestFetchService.enforceEnvironment(trust, manifest);
  }

  private static void requireAvailable(String status) {
    if ("AVAILABLE".equals(status)) return;
    SourceManifestFailureCode code = switch (status) {
      case "INDEX_PARTIAL" -> SourceManifestFailureCode.SOURCE_INDEX_PARTIAL;
      case "UNAVAILABLE" -> SourceManifestFailureCode.SOURCE_UNAVAILABLE;
      case "REDACTED" -> SourceManifestFailureCode.SOURCE_REDACTED;
      case "PRUNED" -> SourceManifestFailureCode.SOURCE_PRUNED;
      case "GENERATED_ONLY" -> SourceManifestFailureCode.SOURCE_GENERATED_ONLY;
      default -> SourceManifestFailureCode.SOURCE_MANIFEST_INVALID;
    };
    throw new SourceManifestException(code, "source manifest is not available for binding");
  }

  private static String declaredEnvironment(JsonNode manifest) {
    return SourceManifestFetchService.declaredEnvironment(manifest);
  }

  private static String nested(JsonNode root, String object, String field, String... alternatives) {
    JsonNode value = root == null ? null : root.get(object);
    if (value == null) return null;
    JsonNode fieldValue = value.get(field);
    if (fieldValue != null && fieldValue.isTextual()) return fieldValue.textValue();
    for (String alternative : alternatives) {
      fieldValue = value.get(alternative);
      if (fieldValue != null && fieldValue.isTextual()) return fieldValue.textValue();
    }
    return null;
  }

  private static boolean same(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }

  private static String hash(byte[] bytes) {
    byte[] digest = SourceManifestCanonicalJson.digest(bytes);
    StringBuilder value = new StringBuilder("sha256:");
    for (byte part : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", part));
    return value.toString();
  }
}
