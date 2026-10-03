package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.aiplayer.routine.service.IsolatedPatchWorker;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryStates;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6CandidateArtifact;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchApproval;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchProposal;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchValidationJob;
import com.chromascape.web.debug.buildergoal.recovery.repository.BuilderGoalRecoveryIncidentRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchApprovalRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchProposalRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchValidationJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable isolated validation: queues, leases, fences, cancels, and terminalizes jobs. Commands are
 * entirely server-owned and delegated to the isolated worker; caller-supplied commands, roots, and
 * targets are structurally impossible on this path.
 */
@Service
public class PatchValidationJobService {

  private final Micro6PatchValidationJobRepository jobRepository;
  private final Micro6PatchProposalRepository proposalRepository;
  private final Micro6PatchApprovalRepository approvalRepository;
  private final BuilderGoalRecoveryIncidentRepository incidentRepository;
  private final PatchValidationDispatcher dispatcher;
  private final Micro6SourceSnapshotService sourceSnapshotService;
  private final CandidateArtifactService candidateArtifactService;
  private final IsolatedPatchWorker worker;
  private final BuilderGoalRecoveryProperties properties;
  private final ObjectMapper objectMapper;
  private final String expectedArtifactName;

  private final ThreadPoolExecutor validationExecutor =
      new ThreadPoolExecutor(
          0,
          1,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          new ThreadPoolExecutor.AbortPolicy());

  public PatchValidationJobService(
      Micro6PatchValidationJobRepository jobRepository,
      Micro6PatchProposalRepository proposalRepository,
      Micro6PatchApprovalRepository approvalRepository,
      BuilderGoalRecoveryIncidentRepository incidentRepository,
      PatchValidationDispatcher dispatcher,
      Micro6SourceSnapshotService sourceSnapshotService,
      CandidateArtifactService candidateArtifactService,
      IsolatedPatchWorker worker,
      BuilderGoalRecoveryProperties properties,
      ObjectMapper objectMapper,
      @Value("${micro6.expected-artifact-name:}") String expectedArtifactName) {
    this.jobRepository = jobRepository;
    this.proposalRepository = proposalRepository;
    this.approvalRepository = approvalRepository;
    this.incidentRepository = incidentRepository;
    this.dispatcher = dispatcher;
    this.sourceSnapshotService = sourceSnapshotService;
    this.candidateArtifactService = candidateArtifactService;
    this.worker = worker;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.expectedArtifactName = expectedArtifactName;
  }

  public void requireEnabled() {
    if (!properties.isIsolatedValidationEnabled()) {
      throw RecoveryException.forbidden(
          RecoveryFailureCodes.ISOLATED_VALIDATION_DISABLED,
          "Isolated validation is disabled (builder-goal.recovery.isolated-validation-enabled=false)");
    }
  }

  @Transactional
  public Micro6PatchValidationJob queue(
      String incidentId, String proposalId, String approvalId, String idempotencyKey) {
    requireEnabled();
    Micro6PatchProposal proposal =
        proposalRepository
            .findByProposalIdAndIncidentId(proposalId, incidentId)
            .orElseThrow(
                () ->
                    RecoveryException.notFound(
                        "PROPOSAL_NOT_FOUND", "No such proposal under the requested run"));
    Micro6PatchApproval approval =
        approvalRepository
            .findByApprovalIdAndProposalId(approvalId, proposalId)
            .orElseThrow(
                () ->
                    RecoveryException.notFound(
                        "APPROVAL_NOT_FOUND", "No such approval for the requested proposal"));
    if (!PatchApprovalService.STATUS_ACTIVE.equals(approval.getStatus())
        || (approval.getExpiresAt() != null && approval.getExpiresAt().isBefore(Instant.now()))) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.APPROVAL_INVALIDATED, "The approval is no longer active");
    }
    String toolchainHash = toolchainHash();
    String inputHash =
        RecoveryHashes.sha256(
            String.join(
                "|",
                proposal.getProposalHash(),
                approval.getApprovalHash(),
                toolchainHash,
                proposal.getValidationPlanHash()));

    requireNonBlankIdempotencyKey(idempotencyKey);
    Micro6PatchValidationJob existing =
        jobRepository.findByIncidentIdAndIdempotencyKey(incidentId, idempotencyKey).orElse(null);
    if (existing != null) {
      if (!existing.getInputHash().equals(inputHash)) {
        throw RecoveryException.conflict(
            RecoveryFailureCodes.DUPLICATE_OR_CONFLICTING_DISPATCH,
            "The idempotency key was already used for a different validation input");
      }
      return existing;
    }
    Micro6PatchValidationJob active =
        jobRepository
            .findByStateIn(
                List.of(
                    PatchValidationDispatcher.STATE_QUEUED,
                    PatchValidationDispatcher.STATE_CLAIMED,
                    PatchValidationDispatcher.STATE_RUNNING,
                    PatchValidationDispatcher.STATE_CANCELLING))
            .stream()
            .filter(job -> inputHash.equals(job.getInputHash()))
            .findFirst()
            .orElse(null);
    if (active != null) {
      return active;
    }

    Micro6PatchValidationJob job = new Micro6PatchValidationJob();
    job.setJobId(RecoveryHashes.newId("job"));
    job.setProposalId(proposalId);
    job.setApprovalId(approvalId);
    job.setIncidentId(incidentId);
    job.setState(PatchValidationDispatcher.STATE_QUEUED);
    job.setIdempotencyKey(idempotencyKey);
    job.setInputHash(inputHash);
    job.setToolchainHash(toolchainHash);
    job.setAttemptNumber(0);
    job.setMaxAttempts(properties.getMaxAttemptsPerJob());
    job.setCreatedAt(Instant.now());
    Micro6PatchValidationJob saved = jobRepository.save(job);
    incidentRepository
        .findWithLockByIncidentId(incidentId)
        .ifPresent(
            incident -> {
              incident.setState(RecoveryStates.VALIDATION_QUEUED);
              incidentRepository.save(incident);
            });
    offerAfterCommit(saved.getJobId());
    return saved;
  }

  private void offerAfterCommit(String jobId) {
    Runnable offer = () -> offer(jobId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              offer.run();
            }
          });
    } else {
      offer.run();
    }
  }

  public void offer(String jobId) {
    if (!properties.isIsolatedValidationEnabled()) {
      return;
    }
    try {
      validationExecutor.execute(() -> run(jobId));
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // The recovery scanner re-offers queued jobs; never roll back the queue commit.
    }
  }

  /** Executes the claimed job in the isolated worker; never runs inside a transaction. */
  public void run(String jobId) {
    PatchValidationDispatcher.Claim claim =
        dispatcher.claim(jobId, Instant.now().plus(properties.getLeaseDuration()));
    if (claim == null) {
      return;
    }
    Thread heartbeat = heartbeat(jobId, claim.leaseToken(), claim.attemptNumber());
    heartbeat.start();
    String leaseToken = claim.leaseToken();
    int attemptNumber = claim.attemptNumber();
    try {
      Micro6PatchValidationJob job = jobRepository.findByJobId(jobId).orElse(null);
      if (job == null) {
        return;
      }
      if (job.getCancelRequestedAt() != null) {
        dispatcher.terminalizeCancelled(job);
        return;
      }
      transitionIncident(
          job.getIncidentId(),
          List.of(RecoveryStates.VALIDATION_QUEUED),
          RecoveryStates.VALIDATING);
      Micro6PatchProposal proposal =
          proposalRepository.findByProposalId(job.getProposalId()).orElse(null);
      Micro6PatchApproval approval =
          approvalRepository.findByApprovalId(job.getApprovalId()).orElse(null);
      if (proposal == null || approval == null) {
        dispatcher.fail(
            jobId,
            leaseToken,
            RecoveryFailureCodes.POLICY_FAILURE,
            "Validation lineage could not be loaded",
            attemptNumber);
        return;
      }
      requireStage(jobId, leaseToken, attemptNumber, "ISOLATED_CHECKOUT");
      Micro6SourceSnapshotService.SourceSnapshot snapshot =
          sourceSnapshotService.resolve(readList(proposal.getAffectedFilesJson()));
      if (!proposal.getBaseCommit().equals(snapshot.commitId())
          || !proposal.getBaseTree().equals(snapshot.treeId())) {
        dispatcher.fail(
            jobId,
            leaseToken,
            RecoveryFailureCodes.BASE_MISMATCH,
            "The pinned base no longer matches the approved source identity",
            attemptNumber);
        return;
      }
      if (!proposal.getSourceManifestHash().equals(snapshot.sourceManifestHash())) {
        dispatcher.fail(
            jobId,
            leaseToken,
            RecoveryFailureCodes.PATCH_PREIMAGE_MISMATCH,
            "Approved pre-images no longer match the pinned base",
            attemptNumber);
        return;
      }
      requireStage(jobId, leaseToken, attemptNumber, "STRICT_PATCH_APPLY");
      IsolatedPatchWorker.BuildRequest request =
          new IsolatedPatchWorker.BuildRequest(
              sourceSnapshotService.micro6Root(),
              proposal.getBaseCommit(),
              proposal.getCanonicalDiff(),
              new java.util.HashSet<>(readList(proposal.getAffectedFilesJson())),
              List.of(),
              proposal.getSourceSnapshotId(),
              null,
              0,
              0,
              null,
              job.getIncidentId(),
              proposal.getProposalHash(),
              approval.getApprovalHash(),
              proposal.getPatchHash(),
              proposal.getSourceManifestHash(),
              rebuildManifest(proposal, snapshot),
              RecoveryHashes.sha256(proposal.getGeneratorJson()),
              requireExpectedArtifactName());
      requireStage(jobId, leaseToken, attemptNumber, "COMPILE_STATIC_TESTS");
      IsolatedPatchWorker.BuildResult result = worker.run(request);
      if ("BUILT".equals(result.status())) {
        requireStage(jobId, leaseToken, attemptNumber, "ARTIFACT_FINALIZATION");
        Micro6CandidateArtifactServiceFinalization finalization =
            finalizeCandidate(job, proposal, approval, result);
        dispatcher.complete(
            jobId,
            leaseToken,
            finalization.candidateId(),
            finalization.resultingTreeHash(),
            attemptNumber);
        transitionIncident(
            job.getIncidentId(),
            List.of(RecoveryStates.VALIDATING),
            RecoveryStates.BUILD_VALIDATED);
      } else {
        dispatcher.fail(
            jobId,
            leaseToken,
            mapWorkerFailure(result.failureCode()),
            "Isolated validation failed",
            attemptNumber);
        transitionIncident(
            job.getIncidentId(),
            List.of(RecoveryStates.VALIDATING),
            RecoveryStates.VALIDATION_FAILED);
      }
    } catch (Exception e) {
      String code =
          e.getMessage() == null
              ? RecoveryFailureCodes.PACKAGE_FAILURE
              : mapWorkerFailure(e.getMessage());
      dispatcher.fail(jobId, leaseToken, code, "Isolated validation failed", attemptNumber);
    } finally {
      heartbeat.interrupt();
    }
  }

  @Transactional
  public Micro6PatchValidationJob cancel(String jobId, String incidentId) {
    Micro6PatchValidationJob job =
        jobRepository
            .findByJobId(jobId)
            .orElseThrow(
                () -> RecoveryException.notFound("JOB_NOT_FOUND", "Validation job is unavailable"));
    if (!incidentId.equals(job.getIncidentId())) {
      throw RecoveryException.notFound(
          "JOB_NOT_FOUND", "No such validation job under the requested run");
    }
    dispatcher.requestCancel(jobId);
    return jobRepository.findByJobId(jobId).orElse(job);
  }

  private record Micro6CandidateArtifactServiceFinalization(
      String candidateId, String resultingTreeHash) {}

  @Transactional
  public void transitionIncident(
      String incidentId, List<String> expectedStates, String targetState) {
    var incident =
        incidentRepository
            .findWithLockByIncidentId(incidentId)
            .orElseThrow(
                () ->
                    RecoveryException.notFound(
                        "INCIDENT_NOT_FOUND", "Recovery incident is unavailable"));
    if (!expectedStates.contains(incident.getState())
        || !RecoveryStates.canTransition(incident.getState(), targetState)) {
      throw RecoveryException.conflict(
          "INCIDENT_STATE_CONFLICT", "The recovery incident is no longer in the expected state");
    }
    incident.setState(targetState);
    incidentRepository.save(incident);
  }

  private void requireStage(String jobId, String leaseToken, int attemptNumber, String stage) {
    if (!dispatcher.stage(jobId, leaseToken, attemptNumber, stage)) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.WORKER_LOST, "The validation job is no longer owned by this worker");
    }
  }

  private Micro6CandidateArtifactServiceFinalization finalizeCandidate(
      Micro6PatchValidationJob job,
      Micro6PatchProposal proposal,
      Micro6PatchApproval approval,
      IsolatedPatchWorker.BuildResult result)
      throws Exception {
    Micro6CandidateArtifact candidate =
        candidateArtifactService.finalizeCandidate(job, proposal, approval, result, null);
    return new Micro6CandidateArtifactServiceFinalization(
        candidate.getCandidateId(), candidate.getResultingTreeHash());
  }

  private String rebuildManifest(
      Micro6PatchProposal proposal, Micro6SourceSnapshotService.SourceSnapshot snapshot) {
    return sourceSnapshotService.buildManifestJson(snapshot);
  }

  private String requireExpectedArtifactName() {
    if (expectedArtifactName == null || expectedArtifactName.isBlank()) {
      throw new IllegalStateException("EXPECTED_JAR_NAME_REQUIRED");
    }
    return expectedArtifactName;
  }

  static String mapWorkerFailure(String raw) {
    if (raw == null) {
      return RecoveryFailureCodes.PACKAGE_FAILURE;
    }
    if (raw.contains("CLEANUP_FAILED")) {
      return RecoveryFailureCodes.CLEANUP_UNCERTAIN;
    }
    if (raw.contains("TIMEOUT") || raw.contains("OUTPUT_LIMIT_EXCEEDED")) {
      return RecoveryFailureCodes.TIMEOUT;
    }
    if (raw.startsWith("ISOLATED_TREE_DIRTY")
        || raw.startsWith("SOURCE_MANIFEST_MISMATCH")
        || raw.startsWith("ISOLATED_BASE")
        || raw.startsWith("BASE_")) {
      return RecoveryFailureCodes.BASE_MISMATCH;
    }
    if (raw.startsWith("SOURCE_PREIMAGE_MISMATCH")
        || raw.startsWith("SOURCE_MANIFEST_FILE_MISMATCH")
        || raw.startsWith("SOURCE_FILE_MISSING")
        || raw.startsWith("SOURCE_SELECTED_FILE_MISMATCH")) {
      return RecoveryFailureCodes.PATCH_PREIMAGE_MISMATCH;
    }
    if (raw.startsWith("PATCH_CHECK_FAILED") || raw.startsWith("PATCH_WHITESPACE_FAILED")) {
      return RecoveryFailureCodes.PATCH_CHECK_FAILED;
    }
    if (raw.startsWith("PATCH_APPLY_FAILED")) {
      return RecoveryFailureCodes.PATCH_APPLY_FAILED;
    }
    if (raw.startsWith("PATCH_ALLOWLIST_VIOLATION")) {
      return RecoveryFailureCodes.PATCH_ALLOWLIST_VIOLATION;
    }
    if (raw.startsWith("ISOLATED_CLONE_FAILED")
        || raw.startsWith("ISOLATED_SOURCE")
        || raw.startsWith("ISOLATED_CHECKOUT")
        || raw.startsWith("JAVA_HOME_INVALID")) {
      return RecoveryFailureCodes.ISOLATED_CLONE_FAILED;
    }
    if (raw.startsWith("BUILD_FAILED") || raw.startsWith("GRADLE_")) {
      return RecoveryFailureCodes.COMPILE_FAILURE;
    }
    if (raw.startsWith("SBOM")) {
      return RecoveryFailureCodes.SBOM_FAILURE;
    }
    if (raw.startsWith("REGRESSION_TEST") || raw.startsWith("TEST")) {
      return RecoveryFailureCodes.TEST_FAILURE;
    }
    if (raw.startsWith("CALLER_COMMANDS_FORBIDDEN") || raw.startsWith("POLICY")) {
      return RecoveryFailureCodes.POLICY_FAILURE;
    }
    return RecoveryFailureCodes.ARTIFACT_FINALIZATION_FAILURE;
  }

  private Thread heartbeat(String jobId, String leaseToken, int attemptNumber) {
    Thread thread =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(60_000L);
                  dispatcher.heartbeat(
                      jobId,
                      leaseToken,
                      attemptNumber,
                      Instant.now().plus(properties.getLeaseDuration()));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                } catch (Exception ignored) {
                  // Ownership is revalidated on every stage and terminal transition.
                }
              }
            },
            "recovery-validation-lease-" + jobId);
    thread.setDaemon(true);
    return thread;
  }

  private String toolchainHash() {
    return RecoveryHashes.sha256(
        String.join(
            "|",
            properties.getGradleCommandKey(),
            properties.getSanitizerVersion(),
            properties.getPolicyVersion(),
            properties.getSigningKeyId() == null ? "" : properties.getSigningKeyId(),
            String.valueOf(properties.getMaxAttemptsPerJob())));
  }

  private static void requireNonBlankIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw RecoveryException.badRequest(
          "IDEMPOTENCY_KEY_REQUIRED", "A nonblank idempotency key is required");
    }
  }

  private List<String> readList(String json) {
    try {
      return objectMapper.readValue(
          json == null ? "[]" : json, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  public Map<String, Object> toStatus(Micro6PatchValidationJob job) {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("jobId", job.getJobId());
    status.put("state", job.getState());
    status.put("currentStage", job.getCurrentStage());
    status.put("attemptNumber", job.getAttemptNumber());
    status.put("maxAttempts", job.getMaxAttempts());
    status.put("failureCode", job.getFailureCode());
    status.put("candidateId", job.getCandidateId());
    status.put(
        "statusUrl",
        "/api/debug/builder-goals/runs-by-incident/"
            + job.getIncidentId()
            + "/compile-recovery/validation-jobs/"
            + job.getJobId());
    status.put("cancelRequested", job.getCancelRequestedAt() != null);
    return status;
  }

  public Duration leaseDuration() {
    return properties.getLeaseDuration();
  }
}
