package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns compare-and-set transitions after candidate work has left the request thread.
 *
 * <p>In particular, REGISTERED is not a normal state update: the candidate row and the run
 * identity are locked and changed by this class in one database transaction. A conflict is
 * recorded as a durable candidate failure inside that transaction, so a run can never claim a
 * candidate whose registration state was not committed.
 */
@Service
public class PatchCandidateRegistrationDispatcher {
  private static final String REGISTERING = "REGISTERING";
  private static final String REGISTERING_UNCERTAIN = "REGISTERING_UNCERTAIN";
  private static final java.time.Duration REGISTRATION_LEASE = java.time.Duration.ofMinutes(5);

  private final PatchCandidateRunRepository candidates;
  private final CurrentSliceRunRepository runs;
  private final ObjectMapper mapper;
  private final CurrentSliceIdentityValidator identities;

  public PatchCandidateRegistrationDispatcher(
      PatchCandidateRunRepository candidates,
      CurrentSliceRunRepository runs,
      ObjectMapper mapper,
      CurrentSliceIdentityValidator identities) {
    this.candidates = candidates;
    this.runs = runs;
    this.mapper = mapper;
    this.identities = identities;
  }

  /** Applies a locked candidate transition and returns the managed row's committed view. */
  @Transactional
  public PatchCandidateRun transition(
      String proposalId, String targetState, Map<String, Object> suppliedResult,
      RegistryEvidence evidence) {
    PatchCandidateRun candidate = candidates.findWithLockByProposalId(proposalId).orElse(null);
    if (candidate == null) return null;

    return transitionLocked(candidate, targetState, suppliedResult, evidence, null);
  }

  /**
   * Claims the external registration/reconciliation lease. The lock is held only for this CAS
   * update; registry I/O happens after the transaction commits. A second worker therefore cannot
   * issue a concurrent POST/GET for the same candidate, while an abandoned lease remains
   * recoverable after its expiry.
   */
  @Transactional
  public RegistrationClaim claimRegistration(String proposalId, Instant now) {
    PatchCandidateRun candidate = candidates.findWithLockByProposalId(proposalId).orElse(null);
    if (candidate == null
        || (!REGISTERING.equals(candidate.getState())
            && !REGISTERING_UNCERTAIN.equals(candidate.getState()))) {
      return null;
    }
    if (candidate.getRegistrationLeaseExpiresAt() != null
        && candidate.getRegistrationLeaseExpiresAt().isAfter(now)) {
      return null;
    }
    String attemptId = UUID.randomUUID().toString();
    candidate.setRegistrationAttemptId(attemptId);
    candidate.setRegistrationLeaseExpiresAt(now.plus(REGISTRATION_LEASE));
    candidates.save(candidate);
    return new RegistrationClaim(attemptId, candidate.getState());
  }

  /** Applies a result only if the caller still owns the registration lease. */
  @Transactional
  public PatchCandidateRun transitionClaimed(
      String proposalId, String attemptId, String targetState,
      Map<String, Object> suppliedResult, RegistryEvidence evidence) {
    PatchCandidateRun candidate = candidates.findWithLockByProposalId(proposalId).orElse(null);
    if (candidate == null || attemptId == null
        || !Objects.equals(attemptId, candidate.getRegistrationAttemptId())
        || (!REGISTERING.equals(candidate.getState())
            && !REGISTERING_UNCERTAIN.equals(candidate.getState()))) {
      return candidate;
    }
    return transitionLocked(candidate, targetState, suppliedResult, evidence, attemptId);
  }

  private PatchCandidateRun transitionLocked(
      PatchCandidateRun candidate, String targetState, Map<String, Object> suppliedResult,
      RegistryEvidence evidence, String attemptId) {
    if (attemptId != null && !Objects.equals(attemptId, candidate.getRegistrationAttemptId())) {
      return candidate;
    }

    persistEvidence(candidate, evidence);
    Map<String, Object> result = new LinkedHashMap<>();
    if (suppliedResult != null) result.putAll(suppliedResult);

    if ("REGISTERED".equals(candidate.getState())) {
      // Registration reconciliation is idempotent. Do not attempt to advance the run twice.
      return candidate;
    }
    if ("REGISTERED".equals(targetState)) {
      if (!REGISTERING.equals(candidate.getState())
          && !REGISTERING_UNCERTAIN.equals(candidate.getState())) {
        return candidate;
      }
      try {
        commitRegistered(candidate);
        result.put("registered", true);
      } catch (Exception conflict) {
        // This save intentionally happens in the same transaction as the locked checks. No run
        // mutation occurs before all validation succeeds, so the failed candidate is durable while
        // the run remains unchanged.
        String code = conflict.getMessage() == null || conflict.getMessage().isBlank()
            ? "CANDIDATE_RUN_IDENTITY_CONFLICT" : conflict.getMessage();
        candidate.setState("FAILED");
        candidate.setLastFailureCode(code.substring(0, Math.min(160, code.length())));
        result.put("registered", false);
        result.put("failure_code", code);
      }
    } else {
      if (isTerminal(candidate.getState()) && !candidate.getState().equals(targetState)) {
        return candidate;
      }
      candidate.setState(targetState);
    }
    candidate.setRegistrationLeaseExpiresAt(null);
    candidate.setResultJson(write(result));
    candidates.save(candidate);
    return candidate;
  }

  /** Requeues only an expired BUILDING lease after locking the current row. */
  @Transactional
  public boolean requeueExpiredBuild(String proposalId, Instant now) {
    PatchCandidateRun candidate = candidates.findWithLockByProposalId(proposalId).orElse(null);
    if (candidate == null || !"BUILDING".equals(candidate.getState())
        || candidate.getWorkerLeaseExpiresAt() == null
        || candidate.getWorkerLeaseExpiresAt().isAfter(now)) {
      return false;
    }
    candidate.setState("BUILD_QUEUED");
    candidate.setWorkerAttemptId(null);
    candidate.setWorkerLeaseExpiresAt(null);
    candidate.setLastFailureCode(null);
    candidates.save(candidate);
    return true;
  }

  public record RegistrationClaim(String attemptId, String state) {}

  private void commitRegistered(PatchCandidateRun candidate) throws Exception {
    if (candidate.getCandidateId() == null || candidate.getCandidateHash() == null
        || candidate.getManifestFileHash() == null || candidate.getJarSha256() == null
        || candidate.getCertificationDocumentHash() == null
        || candidate.getRegistryResponseHash() == null
        || candidate.getRegistryManifestHash() == null
        || candidate.getRegistryDownloadHash() == null) {
      throw new IllegalStateException("CANDIDATE_REGISTRATION_EVIDENCE_MISSING");
    }
    if (!candidate.getManifestFileHash().equals(candidate.getRegistryManifestHash())) {
      throw new IllegalStateException("REGISTRY_MANIFEST_HASH_HEADER_MISMATCH");
    }
    if (!candidate.getJarSha256().equals(candidate.getRegistryDownloadHash())) {
      throw new IllegalStateException("REGISTRY_ARTIFACT_HASH_MISMATCH");
    }
    if (candidate.getFinalIdentityJson() == null || candidate.getFinalIdentityJson().isBlank()) {
      throw new IllegalStateException("CANDIDATE_FINAL_IDENTITY_MISSING");
    }
    CurrentSliceIdentity finalIdentity = identities.validate(
        mapper.readValue(candidate.getFinalIdentityJson(), CurrentSliceIdentity.class));
    if (finalIdentity.run() == null || finalIdentity.run().runId() == null
        || finalIdentity.candidate() == null) {
      throw new IllegalStateException("CANDIDATE_FINAL_IDENTITY_INVALID");
    }
    if (!candidate.getCandidateId().equals(finalIdentity.candidate().candidateId())
        || !candidate.getCandidateHash().equals(finalIdentity.candidate().candidateHash())
        || !candidate.getCertificationDocumentHash().equals(finalIdentity.candidate().certificationHash())) {
      throw new IllegalStateException("CANDIDATE_FINAL_IDENTITY_MISMATCH");
    }
    CurrentSliceRun run = runs.findWithLockByRunId(finalIdentity.run().runId())
        .orElseThrow(() -> new IllegalStateException("CANDIDATE_RUN_NOT_FOUND"));
    CurrentSliceIdentity current = identities.validate(
        mapper.readValue(run.getIdentityJson(), CurrentSliceIdentity.class));
    current.requireSameIdentity(finalIdentity);
    run.setIdentityJson(mapper.writeValueAsString(finalIdentity));
    run.setStage("CANDIDATE_REGISTERED");
    runs.save(run);
    candidate.setState("REGISTERED");
    candidate.setRegistryReconciledAt(Instant.now());
  }

  private void persistEvidence(PatchCandidateRun candidate, RegistryEvidence evidence) {
    if (evidence == null) return;
    if (evidence.rawResponseBytes() != null) {
      candidate.setRegistryResponseBytes(evidence.rawResponseBytes());
      candidate.setRegistryResponseHash(CandidateRegistryClient.hash(evidence.rawResponseBytes()));
    }
    if (evidence.responseBody() != null) candidate.setRegistryResponseJson(evidence.responseBody());
    if (evidence.manifestHash() != null) candidate.setRegistryManifestHash(evidence.manifestHash());
    if (evidence.downloadHash() != null) candidate.setRegistryDownloadHash(evidence.downloadHash());
    candidate.setRegistryVerifiedAt(evidence.verifiedAt() == null ? Instant.now() : evidence.verifiedAt());
  }

  private static boolean isTerminal(String state) {
    return "REGISTERED".equals(state) || "REJECTED".equals(state)
        || "FAILED".equals(state) || "BUILD_FAILED".equals(state);
  }

  private String write(Map<String, Object> value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception exception) {
      return "{\"failure_code\":\"CANDIDATE_RESULT_SERIALIZATION_FAILED\"}";
    }
  }

  public record RegistryEvidence(
      byte[] rawResponseBytes,
      String responseBody,
      String manifestHash,
      String downloadHash,
      Instant verifiedAt) {
    public RegistryEvidence {
      rawResponseBytes = rawResponseBytes == null ? null : rawResponseBytes.clone();
    }
  }
}
