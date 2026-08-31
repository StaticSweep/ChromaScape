package com.chromascape.web.replay.run;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.dto.*;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.chromascape.web.replay.run.entity.ReplayEvent;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the full lifecycle of a {@link ReplayRun} with enforced state
 * transitions, atomic multi-instance-safe claiming, attempt numbering with
 * fencing generations, leases, and per-owner authorization.
 */
@Service
@Transactional
public class ReplayRunService {

    private static final Logger log = LoggerFactory.getLogger(ReplayRunService.class);
    private static final Set<ReplayRunState> TERMINAL_STATES = EnumSet.of(
            ReplayRunState.SUCCEEDED, ReplayRunState.FAILED,
            ReplayRunState.CANCELLED, ReplayRunState.TIMED_OUT);
    public static final String MAIN_BRANCH = "main";

    private final ReplayRunRepository runRepository;
    private final ReplayAttemptRepository attemptRepository;
    private final ReplayCheckpointRepository checkpointRepository;
    private final ReplayEventRepository eventRepository;
    private final ReplayReportRepository reportRepository;
    private final ScenarioManifestRepository manifestRepository;

    private final int leaseDurationSeconds;

    public ReplayRunService(ReplayRunRepository runRepository,
                            ReplayAttemptRepository attemptRepository,
                            ReplayCheckpointRepository checkpointRepository,
                            ReplayEventRepository eventRepository,
                            ReplayReportRepository reportRepository,
                            ScenarioManifestRepository manifestRepository,
                            org.springframework.core.env.Environment environment) {
        this.runRepository = runRepository;
        this.attemptRepository = attemptRepository;
        this.checkpointRepository = checkpointRepository;
        this.eventRepository = eventRepository;
        this.reportRepository = reportRepository;
        this.manifestRepository = manifestRepository;
        Integer configured = environment.getProperty("replay.worker.lease-duration-seconds", Integer.class);
        this.leaseDurationSeconds = configured != null ? configured : 30;
    }

    // ── creation ──

    public ReplayRunResponse create(CreateReplayRunRequest request, String ownerId) {
        Optional<ReplayRun> existing = runRepository.findByIdempotencyKeyAndOwnerId(
                request.idempotencyKey(), ownerId);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        if (request.mode() != ReplayMode.CAUSAL_SIMULATION && request.mode() != ReplayMode.POLICY_REPLAY) {
            throw ReplayException.invalid(ReplayErrorCode.RUN_STATE_INVALID,
                    "Runs can only be created for POLICY_REPLAY or CAUSAL_SIMULATION");
        }

        ScenarioManifest manifest = requireManifest(UUID.fromString(request.manifestId()));
        if (!"PUBLISHED".equals(manifest.getPublishState())) {
            throw ReplayException.invalid(ReplayErrorCode.CHECKSUM_MISMATCH,
                    "Manifest is not published");
        }

        ReplayRun run = new ReplayRun();
        run.setMode(request.mode());
        run.setManifestId(manifest.getId());
        run.setManifestChecksum(manifest.getManifestChecksum());
        run.setOwnerId(ownerId);
        run.setIdempotencyKey(request.idempotencyKey());
        run.setState(ReplayRunState.QUEUED);
        run.setPhase(ReplayRunPhase.PREFLIGHT);
        run.setStopPolicy(request.stopPolicy() != null ? request.stopPolicy() : ReplayStopPolicy.STOP_ON_FIRST_HARD);
        run.setControlAllowed(false);
        run.setBranchKey(MAIN_BRANCH);
        run.setCreatedAt(LocalDateTime.now());
        copyManifestMetadata(run, manifest);

        run = runRepository.save(run);
        log.info("Created replay run {} mode={} owner={}", run.getId(), run.getMode(), ownerId);
        return toResponse(run);
    }

    /**
     * Creates the v2 queued run after the compiler has produced an immutable published manifest.
     * The request fingerprint makes a reused idempotency key fail closed when its semantic
     * request differs, including conversion mode and viewer intent.
     */
    public ReplayRun createV2(
            ScenarioManifest manifest,
            CreateXrspsRunRequest request,
            String ownerId,
            String requestFingerprint) {
        Optional<ReplayRun> existing =
                runRepository.findByIdempotencyKeyAndOwnerId(request.idempotencyKey(), ownerId);
        if (existing.isPresent()) {
            return requireMatchingIdempotency(existing.get(), requestFingerprint);
        }

        ReplayRun run = new ReplayRun();
        run.setMode(ReplayMode.CAUSAL_SIMULATION);
        run.setManifestId(manifest.getId());
        run.setManifestChecksum(manifest.getManifestChecksum());
        run.setOwnerId(ownerId);
        run.setIdempotencyKey(request.idempotencyKey());
        run.setRequestFingerprint(requestFingerprint);
        run.setViewerRequested(request.viewerRequested());
        run.setState(ReplayRunState.QUEUED);
        run.setPhase(ReplayRunPhase.PREFLIGHT);
        run.setStopPolicy(request.stopPolicy());
        run.setControlAllowed(false);
        run.setBranchKey(MAIN_BRANCH);
        run.setCreatedAt(LocalDateTime.now());
        copyManifestMetadata(run, manifest);
        try {
            return runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException race) {
            ReplayRun winner =
                    runRepository
                            .findByIdempotencyKeyAndOwnerId(request.idempotencyKey(), ownerId)
                            .orElseThrow(() -> race);
            return requireMatchingIdempotency(winner, requestFingerprint);
        }
    }

    private ReplayRun requireMatchingIdempotency(ReplayRun existing, String requestFingerprint) {
        if (requestFingerprint != null
                && existing.getRequestFingerprint() != null
                && !requestFingerprint.equals(existing.getRequestFingerprint())) {
            throw ReplayException.conflict(
                    ReplayErrorCode.RUN_IDEMPOTENCY_CONFLICT,
                    "The idempotency key was already used for a different replay request");
        }
        return existing;
    }

    private void copyManifestMetadata(ReplayRun run, ScenarioManifest manifest) {
        run.setManifestVersion(manifest.getManifestVersion());
        run.setFidelity(manifest.getFidelity());
        run.setConversionQuality(manifest.getConversionQuality());
        run.setActionCoveragePct(manifest.getActionCoveragePct());
        run.setGapCount(manifest.getGapCount());
        run.setApproximationCount(manifest.getApproximationCount());
        run.setXrspsAdapterVersion(manifest.getXrspsAdapterVersion());
        run.setXrspsBuildCommit(manifest.getXrspsBuildCommit());
        run.setCacheRevision(manifest.getCacheRevision());
        run.setContentRevision(manifest.getContentRevision());
    }

    // ── query ──

    @Transactional(readOnly = true)
    public ReplayRunResponse getOwned(UUID runId, String ownerId) {
        return toResponse(requireOwnedRun(runId, ownerId));
    }

    /** Owned entity access for additional public projections; it preserves uniform 404 behavior. */
    @Transactional(readOnly = true)
    public ReplayRun getOwnedEntity(UUID runId, String ownerId) {
        return requireOwnedRun(runId, ownerId);
    }

    @Transactional(readOnly = true)
    public Optional<ReplayRun> findByIdempotencyKey(String idempotencyKey, String ownerId) {
        return runRepository.findByIdempotencyKeyAndOwnerId(idempotencyKey, ownerId);
    }

    @Transactional(readOnly = true)
    public Page<ReplayRunSummary> list(String ownerId, ReplayMode mode, int page, int size) {
        PageRequest pr = PageRequest.of(page, size);
        Page<ReplayRun> runs;
        if (mode != null) {
            runs = runRepository.findByOwnerIdAndModeOrderByCreatedAtDesc(ownerId, mode, pr);
        } else {
            runs = runRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId, pr);
        }
        return runs.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public ReplayEventPageResponse events(UUID runId, Long afterOrdinal, int limit, String ownerId) {
        requireOwnedRun(runId, ownerId);

        long after = afterOrdinal != null ? afterOrdinal : -1L;
        int boundedLimit = Math.max(1, Math.min(1000, limit));
        List<ReplayEvent> events = eventRepository
                .findByRunIdAndOrdinalGreaterThanOrderByOrdinalAsc(
                        runId, after, PageRequest.of(0, boundedLimit));
        long nextAfter = events.isEmpty() ? after : events.get(events.size() - 1).getOrdinal();
        boolean hasMore = eventRepository.countByRunId(runId) > nextAfter + 1;

        return new ReplayEventPageResponse(runId.toString(), events, nextAfter, hasMore);
    }

    // ── lifecycle mutations ──

    public CancelReplayRunResponse cancel(UUID runId, String ownerId) {
        ReplayRun run = requireOwnedRun(runId, ownerId);
        if (TERMINAL_STATES.contains(run.getState())) {
            throw ReplayException.conflict(ReplayErrorCode.RUN_IMMUTABLE,
                    "Run " + runId + " is already terminal");
        }
        if (run.getState() == ReplayRunState.RUNNING || run.getState() == ReplayRunState.STARTING
                || run.getState() == ReplayRunState.CLAIMED) {
            run.setState(ReplayRunState.CANCELLING);
            run.setTerminalReason("CANCEL_REQUESTED");
        } else if (run.getState() == ReplayRunState.CANCELLING) {
            // already pending cancellation
        } else {
            run.setState(ReplayRunState.CANCELLED);
            run.setOutcome(com.chromascape.web.replay.api.dto.ReplayOutcome.CANCELLED);
            run.setTerminalAt(LocalDateTime.now());
        }
        runRepository.save(run);
        return new CancelReplayRunResponse(run.getId().toString(), !TERMINAL_STATES.contains(run.getState()));
    }

    public ReplayRunResponse retry(UUID runId, RetryReplayRunRequest request, String ownerId) {
        ReplayRun parent = requireOwnedRun(runId, ownerId);
        if (!TERMINAL_STATES.contains(parent.getState())) {
            throw ReplayException.conflict(ReplayErrorCode.RUN_STATE_INVALID,
                    "Run " + runId + " is not terminal; cannot retry");
        }
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw ReplayException.badRequest(ReplayErrorCode.VALIDATION_ERROR,
                    "A client-supplied idempotency key is required to retry a run");
        }
        Optional<ReplayRun> existing = runRepository.findByIdempotencyKeyAndOwnerId(
                request.idempotencyKey(), ownerId);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        ReplayRun retry = new ReplayRun();
        retry.setMode(parent.getMode());
        retry.setManifestId(parent.getManifestId());
        retry.setManifestChecksum(parent.getManifestChecksum());
        retry.setOwnerId(ownerId);
        retry.setIdempotencyKey(request.idempotencyKey());
        retry.setState(ReplayRunState.QUEUED);
        retry.setPhase(ReplayRunPhase.PREFLIGHT);
        retry.setStopPolicy(parent.getStopPolicy());
        retry.setControlAllowed(false);
        retry.setParentRunId(parent.getId());
        retry.setCreatedAt(LocalDateTime.now());
        retry = runRepository.save(retry);
        return toResponse(retry);
    }

    /**
     * Branches a completed run from one of its checkpoints. The new run
     * records lineage; execution resumes from the checkpoint snapshot.
     */
    public ReplayRunResponse branch(UUID runId, BranchReplayRunRequest request, String ownerId) {
        ReplayRun parent = requireOwnedRun(runId, ownerId);
        if (!TERMINAL_STATES.contains(parent.getState())) {
            throw ReplayException.conflict(ReplayErrorCode.RUN_STATE_INVALID,
                    "Branching requires a terminal parent run");
        }
        if (request.checkpointId() == null || request.branchKey() == null || request.branchKey().isBlank()) {
            throw ReplayException.badRequest(ReplayErrorCode.VALIDATION_ERROR,
                    "checkpointId and branchKey are required");
        }
        UUID checkpointUuid;
        try {
            checkpointUuid = UUID.fromString(request.checkpointId());
        } catch (IllegalArgumentException e) {
            throw ReplayException.badRequest(ReplayErrorCode.VALIDATION_ERROR, "checkpointId must be a UUID");
        }
        ReplayCheckpoint checkpoint = checkpointRepository.findById(checkpointUuid)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND,
                        "Checkpoint not found"));
        if (!checkpoint.getRunId().equals(parent.getId())) {
            // Safe 404: the checkpoint exists but belongs to someone else's run.
            throw ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND, "Checkpoint not found");
        }

        Optional<ReplayRun> existing = runRepository.findByIdempotencyKeyAndOwnerId(
                request.idempotencyKey(), ownerId);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        ScenarioManifest manifest = requireManifest(parent.getManifestId());
        ReplayRun branch = new ReplayRun();
        branch.setMode(parent.getMode());
        branch.setManifestId(manifest.getId());
        branch.setManifestChecksum(manifest.getManifestChecksum());
        branch.setOwnerId(ownerId);
        branch.setIdempotencyKey(request.idempotencyKey());
        branch.setState(ReplayRunState.QUEUED);
        branch.setPhase(ReplayRunPhase.PREFLIGHT);
        branch.setStopPolicy(parent.getStopPolicy());
        branch.setControlAllowed(false);
        branch.setBranchKey(request.branchKey());
        branch.setParentRunId(parent.getId());
        branch.setParentCheckpointId(checkpoint.getId());
        branch.setCreatedAt(LocalDateTime.now());
        branch = runRepository.save(branch);
        log.info("Created branch run {} from run {} checkpoint {}", branch.getId(), runId, checkpoint.getId());
        return toResponse(branch);
    }

    // ── worker transitions (internal, worker-identity guarded) ──

    /**
     * Atomically claims a queued run and opens a new fenced attempt.
     * Returns the refreshed run plus the created attempt.
     *
     * @return null when another worker won the claim race.
     */
    public ClaimResult claimAtomically(UUID runId, String workerIdentity) {
        int updated = runRepository.claimQueuedRun(runId);
        if (updated == 0) {
            return null; // lost the race or already claimed
        }
        ReplayRun run = runRepository.findById(runId).orElseThrow();

        int nextAttemptNo = attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId)
                .map(a -> a.getAttemptNo() + 1).orElse(1);
        int nextGeneration = attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId)
                .map(a -> Math.max(a.getFencingGeneration() + 1, nextAttemptNo)).orElse(1);

        LocalDateTime now = LocalDateTime.now();
        ReplayAttempt attempt = new ReplayAttempt();
        attempt.setRunId(runId);
        attempt.setAttemptNo(nextAttemptNo);
        attempt.setFencingGeneration(nextGeneration);
        attempt.setLeaseOwner(workerIdentity);
        attempt.setWorkerIdentity(workerIdentity);
        attempt.setLeaseExpiresAt(now.plusSeconds(leaseDurationSeconds));
        attempt.setHeartbeatAt(now);
        attempt.setState("RUNNING");
        attempt.setCreatedAt(now);
        attemptRepository.save(attempt);

        run.setXrspsAdapterVersion(run.getXrspsAdapterVersion());
        runRepository.save(run);
        log.info("Worker {} claimed run {} attempt {} fence {}", workerIdentity, runId, nextAttemptNo, nextGeneration);
        return new ClaimResult(run, attempt);
    }

    /** Renews the lease on the current attempt; rejects stale workers. */
    public void heartbeatOrThrow(UUID runId, ReplayAttempt attempt, String workerIdentity) {
        if (!workerIdentity.equals(attempt.getLeaseOwner())) {
            throw staleFence(runId, attempt);
        }
        LocalDateTime now = LocalDateTime.now();
        if (attempt.getLeaseExpiresAt() != null && now.isAfter(attempt.getLeaseExpiresAt())) {
            throw staleFence(runId, attempt);
        }
        attempt.setHeartbeatAt(now);
        attempt.setLeaseExpiresAt(now.plusSeconds(leaseDurationSeconds));
        attemptRepository.save(attempt);
    }

    /** Returns the active attempt when its lease has expired at {@code now}. */
    @Transactional(readOnly = true)
    public Optional<ReplayAttempt> findExpiredActiveAttempt(UUID runId, LocalDateTime now) {
        return attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId)
                .filter(a -> a.getLeaseExpiresAt() != null && now.isAfter(a.getLeaseExpiresAt()));
    }

    /** Verifies the caller still holds the lease before any write. */
    public void assertFenceValid(UUID runId, ReplayAttempt attempt, String workerIdentity) {
        ReplayAttempt current = attemptRepository.findById(attempt.getId()).orElse(null);
        if (current == null || !workerIdentity.equals(current.getLeaseOwner())) {
            throw staleFence(runId, attempt);
        }
    }

    private ReplayException staleFence(UUID runId, ReplayAttempt attempt) {
        return new ReplayException(ReplayErrorCode.ATTEMPT_STALE_FENCE,
                org.springframework.http.HttpStatus.CONFLICT, false,
                "This attempt no longer holds the lease",
                "The run was reclaimed by another worker or the lease expired",
                "attempt=" + attempt.getAttemptNo(), null);
    }

    public ReplayRun transition(UUID runId, ReplayRunState targetState, ReplayRunPhase targetPhase) {
        ReplayRun run = runRepository.findById(runId)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND,
                        "Run " + runId + " not found"));

        validateTransition(run.getState(), targetState);
        applyTransition(run, targetState, targetPhase);
        return runRepository.save(run);
    }

    public void applyTransition(ReplayRun run, ReplayRunState targetState, ReplayRunPhase targetPhase) {
        validateTransition(run.getState(), targetState);
        run.setState(targetState);
        if (targetPhase != null) {
            run.setPhase(targetPhase);
        }
        if (targetState == ReplayRunState.STARTING && run.getStartedAt() == null) {
            run.setStartedAt(LocalDateTime.now());
        }
        if (TERMINAL_STATES.contains(targetState)) {
            run.setTerminalAt(LocalDateTime.now());
        }
    }

    // ── report ──

    @Transactional(readOnly = true)
    public ReplayReport getReportForOwner(UUID runId, String ownerId) {
        requireOwnedRun(runId, ownerId);
        return reportRepository.findByRunId(runId)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND,
                        "Report not found"));
    }

    // ── helpers ──

    private ReplayRun requireOwnedRun(UUID runId, String ownerId) {
        ReplayRun run = runRepository.findById(runId)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND,
                        "Run " + runId + " not found"));
        if (ownerId == null || !ownerId.equals(run.getOwnerId())) {
            // Uniform 404: existence is not disclosed across owners.
            throw ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND,
                    "Run " + runId + " not found");
        }
        return run;
    }

    private ScenarioManifest requireManifest(UUID manifestId) {
        return manifestRepository.findById(manifestId)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND,
                        "Manifest " + manifestId + " not found"));
    }

    private void validateTransition(ReplayRunState from, ReplayRunState to) {
        boolean legal = switch (from) {
            case QUEUED -> to == ReplayRunState.CLAIMED || to == ReplayRunState.CANCELLED;
            case CLAIMED -> to == ReplayRunState.STARTING || to == ReplayRunState.FAILED
                    || to == ReplayRunState.CANCELLED;
            case STARTING -> to == ReplayRunState.RUNNING || to == ReplayRunState.FAILED
                    || to == ReplayRunState.CANCELLED || to == ReplayRunState.CANCELLING;
            case RUNNING -> to == ReplayRunState.SUCCEEDED || to == ReplayRunState.FAILED
                    || to == ReplayRunState.CANCELLING || to == ReplayRunState.TIMED_OUT
                    || to == ReplayRunState.CANCELLED;
            case CANCELLING -> to == ReplayRunState.CANCELLED || to == ReplayRunState.FAILED
                    || to == ReplayRunState.TIMED_OUT;
            case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT -> false;
        };
        if (!legal) {
            throw ReplayException.conflict(ReplayErrorCode.RUN_STATE_INVALID,
                    "Illegal state transition: " + from + " -> " + to);
        }
    }

    private ReplayRunResponse toResponse(ReplayRun run) {
        List<ReplayAttempt> attempts = attemptRepository.findByRunIdOrderByAttemptNoAsc(run.getId());
        return new ReplayRunResponse(
                run.getId(), run.getMode(), run.getManifestId(),
                run.getOwnerId(), run.getState(), run.getPhase(),
                run.getStopPolicy(), run.isControlAllowed(),
                run.getCreatedAt(), run.getStartedAt(), run.getTerminalAt(),
                attempts);
    }

    private ReplayRunSummary toSummary(ReplayRun run) {
        return new ReplayRunSummary(
                run.getId(), run.getMode(), run.getState(),
                run.getManifestId(), run.getCreatedAt(), run.getTerminalAt());
    }

    /** Claim outcome bundle. */
    public record ClaimResult(ReplayRun run, ReplayAttempt attempt) {}
}
