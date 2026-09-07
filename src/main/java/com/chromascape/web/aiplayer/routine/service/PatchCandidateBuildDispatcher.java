package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable worker-claim and result-persistence for the isolated candidate build. All mutations are
 * transactional and optimistic-locking; only a {@code BUILD_QUEUED} record can be claimed, only the
 * claiming worker may heartbeat or persist results, and {@code build()} can never begin from {@code
 * APPROVED}.
 */
@Service
public class PatchCandidateBuildDispatcher {

  private static final int MAX_FAILURE_CODE_LENGTH = 160;

  private final PatchCandidateRunRepository repository;

  public PatchCandidateBuildDispatcher(PatchCandidateRunRepository repository) {
    this.repository = repository;
  }

  /**
   * Claims a queued build in a new transaction. Returns the worker attempt id, or {@code null} when
   * the record is not claimable (already building, already terminal, or missing).
   */
  @Transactional
  public String claim(String id, Instant leaseExpiry) {
    PatchCandidateRun entity = locked(id);
    if (entity == null || !"BUILD_QUEUED".equals(entity.getState())) {
      return null;
    }
    String attemptId = java.util.UUID.randomUUID().toString();
    entity.setState("BUILDING");
    entity.setWorkerAttemptId(attemptId);
    entity.setWorkerLeaseExpiresAt(leaseExpiry);
    entity.setWorkerStartedAt(Instant.now());
    entity.setLastFailureCode(null);
    repository.save(entity);
    return attemptId;
  }

  /** Refreshes the worker lease; a stale or foreign attempt is rejected. */
  @Transactional
  public boolean heartbeat(String id, String attemptId, Instant leaseExpiry) {
    PatchCandidateRun entity = locked(id);
    if (entity == null
        || !"BUILDING".equals(entity.getState())
        || !attemptId.equals(entity.getWorkerAttemptId())) {
      return false;
    }
    entity.setWorkerLeaseExpiresAt(leaseExpiry);
    repository.save(entity);
    return true;
  }

  /** Persists a terminal build result in a new transaction. */
  @Transactional
  public boolean persistBuildResult(
      String id, String attemptId, IsolatedPatchWorker.BuildResult result, String resultJson) {
    PatchCandidateRun entity = locked(id);
    if (entity == null
        || !"BUILDING".equals(entity.getState())
        || !attemptId.equals(entity.getWorkerAttemptId())) {
      return false;
    }
    entity.setWorkerCompletedAt(Instant.now());
    entity.setWorkerLeaseExpiresAt(null);
    entity.setLastFailureCode(
        boundedFailureCode(result == null ? "BUILD_FAILED" : result.failureCode()));
    entity.setResultJson(resultJson);
    entity.setState(result != null && "BUILT".equals(result.status()) ? "BUILT" : "BUILD_FAILED");
    repository.save(entity);
    return true;
  }

  /** Marks a build as failed after an unhandled worker error. */
  @Transactional
  public boolean persistFailure(String id, String attemptId, String code) {
    PatchCandidateRun entity = locked(id);
    if (entity == null
        || !"BUILDING".equals(entity.getState())
        || !attemptId.equals(entity.getWorkerAttemptId())) {
      return false;
    }
    entity.setWorkerCompletedAt(Instant.now());
    entity.setWorkerLeaseExpiresAt(null);
    String failureCode = code == null ? "BUILD_FAILED" : code;
    entity.setLastFailureCode(boundedFailureCode(failureCode));
    entity.setState("BUILD_FAILED");
    entity.setResultJson("{\"status\":\"FAILED\",\"failure_code\":\"" + failureCode + "\"}");
    repository.save(entity);
    return true;
  }

  /**
   * Commits the durably prepared registration evidence in one transaction: candidate identity,
   * canonical manifest, exact manifest file hash, component hashes/sizes, final run identity,
   * staging directory, registration request fingerprint, attempt id and {@code REGISTERING}.
   * Requires the candidate to still be {@code BUILT}; a stale state is never overwritten.
   */
  @Transactional
  public boolean commitRegistration(
      String id,
      String candidateId,
      String candidateHash,
      String manifestJson,
      String finalIdentityJson,
      String stagingDirectory,
      String requestHash,
      String registrationAttemptId,
      String manifestFileHash,
      String jarSha256,
      long jarSize,
      String sbomSha256,
      long sbomSize,
      String certificationDocumentHash) {
    PatchCandidateRun entity = locked(id);
    if (entity == null || !"BUILT".equals(entity.getState())) {
      return false;
    }
    entity.setCandidateId(candidateId);
    entity.setCandidateHash(candidateHash);
    entity.setCandidateManifestJson(manifestJson);
    entity.setFinalIdentityJson(finalIdentityJson);
    entity.setCandidateStagingDirectory(stagingDirectory);
    entity.setRegistrationRequestHash(requestHash);
    entity.setRegistrationAttemptId(registrationAttemptId);
    entity.setRegistrationPreparedAt(Instant.now());
    entity.setRegistrationLeaseExpiresAt(null);
    entity.setManifestFileHash(manifestFileHash);
    entity.setJarSha256(jarSha256);
    entity.setJarSize(jarSize);
    entity.setSbomSha256(sbomSha256);
    entity.setSbomSize(sbomSize);
    entity.setCertificationDocumentHash(certificationDocumentHash);
    entity.setState("REGISTERING");
    repository.save(entity);
    return true;
  }

  private PatchCandidateRun locked(String id) {
    return repository.findWithLockByProposalId(id).orElse(null);
  }

  private static String boundedFailureCode(String code) {
    if (code == null || code.length() <= MAX_FAILURE_CODE_LENGTH) {
      return code;
    }
    return code.substring(0, MAX_FAILURE_CODE_LENGTH);
  }
}
