package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.artifact.ArtifactPublicationReceipt;
import com.chromascape.web.replay.artifact.ArtifactRegistration;
import com.chromascape.web.replay.artifact.PreparedArtifact;
import com.chromascape.web.replay.artifact.PublishedArtifact;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.chromascape.web.replay.run.entity.ReplayEvent;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayEventRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single transactional persistence boundary for worker-owned durable writes.
 *
 * <p>Lock order — EVERY method locks in this exact order inside its own transaction:
 *
 * <ol>
 *   <li>the attempt/fence row through {@code findValidFencedAttempt} (native FOR UPDATE OF a,
 *       database-clock predicate)
 *   <li>the run row through {@code findByIdForUpdate} (PESSIMISTIC_WRITE)
 * </ol>
 *
 * Because {@code cancel()} locks only the run row before checking state/phase, cancellation and
 * finalization serialize on the same row and no read-then-save race remains.
 *
 * <p>Execution evidence (events, hashes, checkpoints, mismatches, runtime identity, artifact
 * registration, normal phase changes) requires {@code RUNNING}; when the locked run is {@code
 * CANCELLING} these methods throw the shared {@link ReplayCancellationRequested} instead of
 * writing. Failure reservation/finalization accepts {@code CLAIMED}, {@code STARTING}, {@code
 * RUNNING}, or {@code CANCELLING}.
 */
@Service
public class ReplayWorkerPersistenceService {

  private static final Logger log = LoggerFactory.getLogger(ReplayWorkerPersistenceService.class);
  private static final String HASH_PREFIX = "sha256:";

  private final ReplayRunService runService;
  private final ReplayRunRepository runRepository;
  private final ReplayAttemptRepository attemptRepository;
  private final ReplayEventRepository eventRepository;
  private final StateHashSampleRepository hashSampleRepository;
  private final ReplayCheckpointRepository checkpointRepository;
  private final ReplayMismatchRepository mismatchRepository;
  private final ArtifactRefRepository artifactRefRepository;
  private final ReplayReportRepository reportRepository;
  private final ReplayProperties properties;
  private final EntityManager entityManager;

  public ReplayWorkerPersistenceService(
      ReplayRunService runService,
      ReplayRunRepository runRepository,
      ReplayAttemptRepository attemptRepository,
      ReplayEventRepository eventRepository,
      StateHashSampleRepository hashSampleRepository,
      ReplayCheckpointRepository checkpointRepository,
      ReplayMismatchRepository mismatchRepository,
      ArtifactRefRepository artifactRefRepository,
      ReplayReportRepository reportRepository,
      ReplayProperties properties,
      EntityManager entityManager) {
    this.runService = runService;
    this.runRepository = runRepository;
    this.attemptRepository = attemptRepository;
    this.eventRepository = eventRepository;
    this.hashSampleRepository = hashSampleRepository;
    this.checkpointRepository = checkpointRepository;
    this.mismatchRepository = mismatchRepository;
    this.artifactRefRepository = artifactRefRepository;
    this.reportRepository = reportRepository;
    this.properties = properties;
    this.entityManager = entityManager;
  }

  // ── transitions ──

  /** CLAIMED → STARTING under the fence. */
  @Transactional
  public void transitionStarting(ReplayFence fence) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    if (run.getState() != ReplayRunState.CLAIMED) {
      throw stale(fence);
    }
    LocalDateTime dbNow = runRepository.currentTimestamp();
    runService.applyTransition(run, ReplayRunState.STARTING, ReplayRunPhase.START_KERNEL, dbNow);
    runRepository.save(run);
  }

  /** STARTING → RUNNING with the initial phase, under the fence. */
  @Transactional
  public void transitionRunning(ReplayFence fence, ReplayRunPhase initialPhase) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    if (run.getState() != ReplayRunState.STARTING) {
      throw stale(fence);
    }
    runService.applyTransition(
        run, ReplayRunState.RUNNING, initialPhase, runRepository.currentTimestamp());
    runRepository.save(run);
  }

  /**
   * Phase-only change while RUNNING under the fence. Requires the exact expected phase. When the
   * locked run is CANCELLING the shared cancellation signal is thrown — never a silent phase change
   * on a cancelled run.
   */
  @Transactional
  public void updatePhase(
      ReplayFence fence, ReplayRunPhase expectedPhase, ReplayRunPhase targetPhase) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    requireRunning(fence, run);
    if (expectedPhase != null && run.getPhase() != expectedPhase) {
      throw stale(fence);
    }
    run.setPhase(targetPhase);
    runRepository.save(run);
  }

  /**
   * Atomically enters FINALIZE_ARTIFACTS from COMPARE and captures the finalization reservation.
   * This is THE cancellation cutoff: once this commits, {@code cancel()} observes the phase under
   * the run lock and returns RUN_IMMUTABLE. The returned {@code reservedAt} is ONE PostgreSQL
   * capture that must be used for report JSON, report entity, run terminal time, attempt terminal
   * time, and artifact {@code finalized_at}.
   */
  @Transactional
  public FinalizationReservation reserveSuccessfulFinalization(
      ReplayFence fence, ReplayOutcome outcome) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    requireRunning(fence, run);
    if (run.getPhase() != ReplayRunPhase.COMPARE) {
      throw stale(fence);
    }
    LocalDateTime dbNow = runRepository.currentTimestamp();
    run.setPhase(ReplayRunPhase.FINALIZE_ARTIFACTS);
    run.setFinalizationReservedAt(dbNow);
    runRepository.save(run);
    return new FinalizationReservation(fence.runId(), fence.attemptId(), outcome, dbNow);
  }

  /** Persists the live kernel runtime identity on the run; execution write, requires RUNNING. */
  @Transactional
  public void storeRuntimeIdentity(ReplayFence fence, RuntimeIdentity identity) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    requireRunning(fence, run);
    run.setXrspsAdapterVersion(identity.xrspsAdapterVersion());
    run.setXrspsBuildCommit(identity.xrspsBuildCommit());
    run.setCacheRevision(identity.cacheRevision());
    run.setContentRevision(identity.contentRevision());
    runRepository.save(run);
  }

  // ── execution evidence ──

  /**
   * Persists kernel events fenced and batched: events are partitioned by {@code max-event-batch},
   * every partition is saved within THIS transaction, so any failure rolls back every partition.
   * Events whose run or attempt differs from the fence are rejected, as are negative ordinals.
   */
  @Transactional
  public void appendEvents(ReplayFence fence, List<ReplayEvent> events) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    requireRunning(fence, run);
    savePartitions(fence, events);
  }

  /**
   * Persists one action result atomically: event partitions first, then the matching state-hash
   * sample ONLY after every partition saved. A failure anywhere rolls back the partitions and the
   * sample together, so the hash chain can never reference missing events.
   */
  @Transactional
  public void appendActionResult(
      ReplayFence fence, List<ReplayEvent> events, StateHashSample sample) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    requireRunning(fence, run);
    if (!fence.runId().equals(sample.getId().getRunId())) {
      throw stale(fence);
    }
    savePartitions(fence, events);
    hashSampleRepository.save(sample);
  }

  /**
   * Registers a checkpoint snapshot reference AND inserts the checkpoint row inside ONE
   * transaction. A failed insert may leave staged bytes, but a registered SNAPSHOT reference can
   * never exist without its checkpoint row.
   *
   * @return the live snapshot reference (existing or newly inserted)
   */
  @Transactional
  public ArtifactRegistration registerCheckpoint(
      ReplayFence fence, PreparedArtifact preparedSnapshot, ReplayCheckpoint checkpoint) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    requireRunning(fence, run);
    if (preparedSnapshot.kind() != ArtifactKind.SNAPSHOT) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Checkpoint registration requires a SNAPSHOT artifact",
          null,
          null,
          null);
    }
    if (!fence.runId().equals(checkpoint.getRunId())
        || !fence.attemptId().equals(checkpoint.getAttemptId())
        || !fence.runId().equals(preparedSnapshot.runId())) {
      throw stale(fence);
    }
    if (checkpoint.getSnapshotChecksum() == null || checkpoint.getSnapshotChecksum().isBlank()) {
      throw new ReplayException(
          ReplayErrorCode.CHECKSUM_MISMATCH,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Checkpoint is missing its kernel-internal snapshot checksum",
          null,
          null,
          null);
    }
    // ONE PostgreSQL timestamp for BOTH rows created at this fenced boundary.
    LocalDateTime dbNow = runRepository.currentTimestamp();
    java.time.Instant createdAtUtc = utcInstant(dbNow);
    ArtifactRegistration registration = registerReference(fence, preparedSnapshot, createdAtUtc);
    checkpoint.setSnapshotArtifactChecksum(registration.reference().getChecksum());
    checkpoint.setCreatedAt(createdAtUtc);
    checkpointRepository.save(checkpoint);
    return registration;
  }

  /** Persists mismatch candidates produced by the pure evaluator; requires RUNNING. */
  @Transactional
  public void saveMismatches(ReplayFence fence, List<ReplayMismatch> mismatches) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    requireRunning(fence, run);
    if (mismatches == null || mismatches.isEmpty()) {
      return;
    }
    // Mismatches never self-stamp: ONE PostgreSQL timestamp applies to every row of this
    // action so first-mismatch durable ordering can never inherit JVM-clock skew. The capture
    // happens ONCE per batch — querying inside the loop would give each row its own timestamp
    // and break the intended ordering contract.
    Instant createdAt = utcInstant(runRepository.currentTimestamp());
    for (ReplayMismatch mismatch : mismatches) {
      if (!fence.runId().equals(mismatch.getRunId())
          || !fence.attemptId().equals(mismatch.getAttemptId())) {
        throw stale(fence);
      }
      mismatch.stampCreatedAt(createdAt);
    }
    mismatchRepository.saveAll(mismatches);
  }

  /**
   * Inserts the STAGING reference for a prepared artifact, idempotently reusing an existing (run,
   * kind, checksum) reference. The existing row's size and media type are verified against the
   * prepared content; the deterministic staging path makes reuse orphan-free.
   *
   * @return the live reference row (existing or newly inserted)
   */
  @Transactional
  public ArtifactRegistration registerArtifact(ReplayFence fence, PreparedArtifact prepared) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    requireRunning(fence, run);
    if (!fence.runId().equals(prepared.runId())) {
      throw stale(fence);
    }
    return registerReference(fence, prepared, utcInstant(runRepository.currentTimestamp()));
  }

  /**
   * Dedicated failure-protocol door for registering ONLY the failure REPORT artifact AFTER {@code
   * reserveFailureFinalization} established the FINALIZE_REPORT cutoff. General artifact
   * registration stays RUNNING-only; this method deliberately accepts CLAIMED / STARTING / RUNNING
   * / CANCELLING so a failing worker can still durably attach its report when storage is healthy.
   */
  @Transactional
  public ArtifactRegistration registerFailureReportArtifact(
      ReplayFence fence, PreparedArtifact prepared) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    if (run.isTerminal()
        || !EnumSet.of(
                ReplayRunState.CLAIMED,
                ReplayRunState.STARTING,
                ReplayRunState.RUNNING,
                ReplayRunState.CANCELLING)
            .contains(run.getState())
        || run.getPhase() != ReplayRunPhase.FINALIZE_REPORT
        || prepared.kind() != ArtifactKind.REPORT
        || !fence.runId().equals(prepared.runId())) {
      throw stale(fence);
    }
    return registerReference(fence, prepared, utcInstant(runRepository.currentTimestamp()));
  }

  // ── failure reservation protocol ──

  /**
   * Immutable effective decision captured under the attempt+run locks at the cancellation cutoff.
   *
   * @param state effective terminal state (cancellation precedence applied)
   * @param outcome effective outcome
   * @param reason effective terminal reason
   * @param terminalAt the single database-clock timestamp shared by report row, report JSON, run,
   *     and attempt
   */
  public record TerminalDecision(
      ReplayRunState state, ReplayOutcome outcome, String reason, LocalDateTime terminalAt) {}

  /**
   * Immutable successful-finalization cutoff record. {@code reservedAt} is ONE PostgreSQL capture
   * that must flow into report JSON {@code terminalAt}, the report entity, run terminal time,
   * attempt terminal time, and artifact {@code finalized_at} — never the JVM clock.
   */
  public record FinalizationReservation(
      UUID runId, UUID attemptId, ReplayOutcome outcome, LocalDateTime reservedAt) {}

  /**
   * Step 1 of the two-step failure protocol. Locks attempt and run, resolves cancellation
   * precedence ({@code CANCELLING} forces {@code CANCELLED/CANCELLED}), captures ONE database
   * timestamp, and sets phase {@code FINALIZE_REPORT} — establishing the cancellation cutoff —
   * WITHOUT terminalizing the run.
   *
   * @return the effective decision, or null when the run is already terminal (recovery owns it)
   */
  @Transactional
  public TerminalDecision reserveFailureFinalization(
      ReplayFence fence,
      ReplayRunState requestedState,
      ReplayOutcome requestedOutcome,
      String requestedReason) {
    lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    if (run.isTerminal()) {
      return null; // recovery owns the final state
    }
    if (!EnumSet.of(
            ReplayRunState.CLAIMED,
            ReplayRunState.STARTING,
            ReplayRunState.RUNNING,
            ReplayRunState.CANCELLING)
        .contains(run.getState())) {
      throw stale(fence);
    }
    boolean cancelled = run.getState() == ReplayRunState.CANCELLING;
    TerminalDecision decision =
        new TerminalDecision(
            cancelled ? ReplayRunState.CANCELLED : requestedState,
            cancelled ? ReplayOutcome.CANCELLED : requestedOutcome,
            cancelled ? "CANCEL_REQUESTED" : requestedReason,
            runRepository.currentTimestamp());
    run.setPhase(ReplayRunPhase.FINALIZE_REPORT);
    run.setFinalizationReservedAt(decision.terminalAt());
    runRepository.save(run);
    log.info(
        "Run {} reserved failure finalization: {} / {} ({})",
        fence.runId(),
        decision.state(),
        decision.outcome(),
        decision.reason());
    return decision;
  }

  /**
   * Step 2 of the two-step failure protocol. The report is MANDATORY: a worker-driven terminal run
   * can never commit without an immutable report row.
   *
   * <ul>
   *   <li>{@code receipt} present: verified completely (identity, coverage, REPORT checksum, media
   *       types) and receipted references marked FINALIZED with the decision timestamp.
   *   <li>{@code receipt} absent (artifact storage unavailable): NO reference is finalized and all
   *       unresolved STAGING references are TOMBSTONED inside this transaction — files stay on disk
   *       for retention/forensics but are never advertised as published evidence.
   * </ul>
   */
  @Transactional
  public void finalizeFailure(
      ReplayFence fence,
      TerminalDecision decision,
      ReplayReport report,
      ArtifactPublicationReceipt receipt) {
    if (decision == null) {
      throw new IllegalArgumentException("decision is required");
    }
    if (report == null) {
      // Programming/report-contract error, not a storage condition: leave FINALIZE_REPORT so
      // lease-expiry recovery plus missing-report reconciliation complete the run truthfully.
      throw new ReplayException(
          ReplayErrorCode.REPORT_PERSISTENCE_FAILED,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Failure finalization requires a prepared report",
          null,
          null,
          null);
    }
    verifyReportIdentity(fence, report, decision);
    ReplayAttempt locked = lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    if (run.isTerminal()) {
      return; // recovery owns the final state
    }
    if (!fence.runId().equals(report.getRunId())) {
      throw forgedReceipt(fence, "report belongs to another run");
    }
    boolean artifactsFinalizedHere = false;
    if (receipt != null) {
      verifyReceiptAndMarkFinalized(
          fence, run, receipt, report, decision.outcome(), utcInstant(decision.terminalAt()));
      artifactsFinalizedHere = true;
    }
    if (!artifactsFinalizedHere) {
      tombstoneUnresolvedStaging(run.getId());
    }
    if (reportRepository.findByRunId(fence.runId()).isPresent()) {
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_IMMUTABLE,
          "Run " + fence.runId() + " already has an immutable report");
    }
    stampReportTimestamps(report, decision.terminalAt(), decision.terminalAt());
    reportRepository.save(report);
    run.setState(decision.state());
    run.setOutcome(decision.outcome());
    run.setTerminalReason(decision.reason());
    run.setPhase(ReplayRunPhase.FINALIZE_REPORT);
    run.setTerminalAt(decision.terminalAt());
    runRepository.save(run);
    completeAttempt(locked, decision.state().name(), decision.terminalAt());
    log.info(
        "Run {} finalized failure: {} outcome={} reason={} reportRow=mandatory",
        fence.runId(),
        decision.state(),
        decision.outcome(),
        decision.reason());
  }

  /** Marks unresolved STAGING references TOMBSTONED; staged files remain for forensics. */
  private void tombstoneUnresolvedStaging(UUID runId) {
    List<ArtifactRef> stagingRefs =
        artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING);
    for (ArtifactRef ref : stagingRefs) {
      ref.setState(ArtifactRef.STATE_TOMBSTONED);
      artifactRefRepository.save(ref);
    }
  }

  /** Verifies the immutable report matches the effective terminal decision exactly. */
  private void verifyReportIdentity(
      ReplayFence fence, ReplayReport report, TerminalDecision decision) {
    if (!fence.runId().equals(report.getRunId())
        || !fence.attemptId().equals(report.getAttemptId())
        || report.getOutcome() != decision.outcome()
        || report.getTerminalReason() != null
            && !report.getTerminalReason().equals(decision.reason())
        || report.getTerminalAt() == null
        || !report.getTerminalAt().equals(utcInstant(decision.terminalAt()))) {
      throw new ReplayException(
          ReplayErrorCode.REPORT_PERSISTENCE_FAILED,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Report does not match the reserved terminal decision",
          null,
          null,
          null);
    }
  }

  // ── successful terminalization ──

  /**
   * Atomic successful finalization under the live fence:
   *
   * <ol>
   *   <li>validate the fence; require RUNNING / FINALIZE_ARTIFACTS;
   *   <li>verify the reservation: run, attempt, outcome, and the exact persisted PostgreSQL {@code
   *       finalization_reserved_at} capture;
   *   <li>verify the publication receipt completely (identity, coverage, REPORT checksum, media
   *       types);
   *   <li>mark those rows FINALIZED with the reserved timestamp;
   *   <li>insert exactly one immutable report;
   *   <li>set run SUCCEEDED + outcome + FINALIZE_REPORT phase + the reserved terminal time;
   *   <li>set the attempt SUCCEEDED and clear its active lease with the same timestamp.
   * </ol>
   *
   * A hard comparison mismatch is NOT an infrastructure failure: it lands here with {@code
   * outcome=FAILED} while the run still SUCCEEDED.
   */
  @Transactional
  public void finalizeSuccessfulRun(
      ReplayFence fence,
      FinalizationReservation reservation,
      ArtifactPublicationReceipt receipt,
      ReplayReport report,
      ReplayOutcome outcome) {
    if (reservation == null) {
      throw new IllegalArgumentException("reservation is required");
    }
    if (report == null) {
      throw new ReplayException(
          ReplayErrorCode.REPORT_PERSISTENCE_FAILED,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Successful finalization requires a prepared report",
          null,
          null,
          null);
    }
    ReplayAttempt locked = lockedAttempt(fence);
    ReplayRun run = ownedRunForUpdate(fence);
    if (run.getState() != ReplayRunState.RUNNING
        || run.getPhase() != ReplayRunPhase.FINALIZE_ARTIFACTS) {
      throw stale(fence);
    }
    // Reservation integrity: identity, outcome, and the persisted database-clock capture must all
    // match what the live worker reserved before report bytes were created.
    if (!fence.runId().equals(reservation.runId())
        || !fence.attemptId().equals(reservation.attemptId())
        || reservation.outcome() != outcome
        || run.getFinalizationReservedAt() == null
        || !run.getFinalizationReservedAt().equals(reservation.reservedAt())) {
      throw stale(fence);
    }
    verifyReceiptAndMarkFinalized(
        fence, run, receipt, report, outcome, utcInstant(reservation.reservedAt()));

    if (reportRepository.findByRunId(fence.runId()).isPresent()) {
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_IMMUTABLE,
          "Run " + fence.runId() + " already has an immutable report");
    }
    stampReportTimestamps(report, null, reservation.reservedAt());
    reportRepository.save(report);

    runService.applyTransition(
        run, ReplayRunState.SUCCEEDED, ReplayRunPhase.FINALIZE_REPORT, reservation.reservedAt());
    run.setOutcome(outcome); // PASSED, or FAILED for a hard deterministic mismatch
    run.setTerminalAt(reservation.reservedAt());
    runRepository.save(run);
    completeAttempt(locked, ReplayRunState.SUCCEEDED.name(), reservation.reservedAt());
    log.info("Run {} finalized successfully: outcome={}", fence.runId(), outcome);
  }

  // ── helpers ──

  /** Locks and validates the fence; throws ATTEMPT_STALE_FENCE when lost. */
  private ReplayAttempt lockedAttempt(ReplayFence fence) {
    return runService.requireValidFence(
        fence.runId(), fence.attemptId(), fence.fencingGeneration(), fence.workerIdentity());
  }

  private ReplayRun ownedRunForUpdate(ReplayFence fence) {
    return runRepository.findByIdForUpdate(fence.runId()).orElseThrow(() -> stale(fence));
  }

  /** Execution-evidence guard: CANCELLING raises the signal; anything non-RUNNING is stale. */
  private void requireRunning(ReplayFence fence, ReplayRun run) {
    if (run.getState() == ReplayRunState.CANCELLING) {
      throw new ReplayCancellationRequested();
    }
    if (run.getState() != ReplayRunState.RUNNING) {
      throw stale(fence);
    }
  }

  /**
   * Shared idempotent reference registration. Reuse rules by existing state:
   *
   * <ul>
   *   <li>STAGING: reuse only when size AND media type match exactly; publication still required.
   *   <li>FINALIZED: reuse as already-published evidence — the row is NEVER mutated (no missing
   *       media-type backfill); publication NOT required.
   *   <li>CORRUPT / TOMBSTONED / unknown: rejected outright.
   * </ul>
   */
  private ArtifactRegistration registerReference(
      ReplayFence fence, PreparedArtifact prepared, java.time.Instant createdAt) {
    var existing =
        artifactRefRepository.findByRunIdAndKindAndChecksum(
            prepared.runId(), prepared.kind(), prepared.checksum());
    if (existing.isPresent()) {
      ArtifactRef ref = existing.get();
      switch (ref.getState() == null ? "" : ref.getState()) {
        case ArtifactRef.STATE_STAGING -> {
          if (ref.getSizeBytes() != prepared.sizeBytes()
              || ref.getMediaType() == null
              || !ref.getMediaType().equals(prepared.mediaType())) {
            throw new ReplayException(
                ReplayErrorCode.CHECKSUM_MISMATCH,
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                false,
                "Existing staged artifact reference does not match prepared content metadata",
                null,
                "kind=" + prepared.kind(),
                null);
          }
          return new ArtifactRegistration(ref, true);
        }
        case ArtifactRef.STATE_FINALIZED -> {
          // Already published evidence: never mutate finalized rows.
          return new ArtifactRegistration(ref, false);
        }
        case ArtifactRef.STATE_CORRUPT, ArtifactRef.STATE_TOMBSTONED -> throw new ReplayException(
            ReplayErrorCode.CHECKSUM_MISMATCH,
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            false,
            "Artifact reference exists in a non-reusable state",
            null,
            "kind=" + prepared.kind() + ",state=" + ref.getState(),
            null);
        default -> throw new ReplayException(
            ReplayErrorCode.CHECKSUM_MISMATCH,
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            false,
            "Artifact reference carries an unknown state",
            null,
            "kind=" + prepared.kind(),
            null);
      }
    }
    ArtifactRef created =
        new ArtifactRef(
            prepared.proposedReferenceId(),
            prepared.runId(),
            prepared.kind(),
            prepared.checksum(),
            prepared.sizeBytes());
    created.setMediaType(prepared.mediaType());
    created.setCreatedAt(createdAt);
    return new ArtifactRegistration(artifactRefRepository.save(created), true);
  }

  /**
   * Partitions events by {@code replay.worker.max-event-batch} with REAL memory bounding: every
   * event is validated BEFORE the first write, each partition is flushed without committing, and
   * the persistence context is cleared between partitions so a large backlog cannot accumulate.
   * Everything stays inside the caller's transaction — any later failure still rolls back all
   * partitions and the state-hash sample.
   */
  private void savePartitions(ReplayFence fence, List<ReplayEvent> events) {
    if (events == null || events.isEmpty()) {
      return;
    }
    for (ReplayEvent event : events) {
      if (!fence.runId().equals(event.getRunId())
          || !fence.attemptId().equals(event.getAttemptId())) {
        throw stale(fence);
      }
      if (event.getOrdinal() < 0) {
        throw new ReplayException(
            ReplayErrorCode.VALIDATION_ERROR,
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            false,
            "Kernel event carried a negative ordinal",
            null,
            null,
            null);
      }
    }
    int batchSize = Math.max(1, properties.getWorker().getMaxEventBatch());
    // One database timestamp for every event in this call: replay event rows never carry
    // JVM-clock stamps.
    LocalDateTime dbNow = runRepository.currentTimestamp();
    for (ReplayEvent event : events) {
      if (event.getCreatedAt() == null) {
        event.setCreatedAt(dbNow);
      }
    }
    for (int from = 0; from < events.size(); from += batchSize) {
      int to = Math.min(from + batchSize, events.size());
      eventRepository.saveAll(events.subList(from, to));
      eventRepository.flush();
      entityManager.clear();
    }
  }

  /**
   * Full receipt verification before any FINALIZED marking:
   *
   * <ul>
   *   <li>receipt run equals fence run; every entry's runId AND its reference row belong to that
   *       run
   *   <li>every receipted reference ID exists with matching kind, checksum, size, AND media type
   *   <li>receipt IDs exactly cover every current STAGING reference of the run — nothing missing,
   *       nothing unexpected
   *   <li>exactly one REPORT entry whose checksum equals SHA-256 of {@code report.reportJson}
   *   <li>report run/attempt/manifest checksum/outcome/controlAllowed match finalization
   * </ul>
   *
   * Only then are references marked FINALIZED, stamped with the authoritative database-clock
   * capture.
   */
  private void verifyReceiptAndMarkFinalized(
      ReplayFence fence,
      ReplayRun run,
      ArtifactPublicationReceipt receipt,
      ReplayReport report,
      ReplayOutcome expectedOutcome,
      Instant finalizedAtUtc) {
    if (!fence.runId().equals(receipt.runId())) {
      throw forgedReceipt(fence, "receipt belongs to another run");
    }
    Set<UUID> receiptIds = new HashSet<>();
    int reportEntries = 0;
    String reportArtifactChecksum = null;
    for (PublishedArtifact published : receipt.published()) {
      if (!receiptIds.add(published.artifactRefId())) {
        throw forgedReceipt(fence, "duplicate receipt entry");
      }
      if (!fence.runId().equals(published.runId())) {
        throw forgedReceipt(fence, "receipt entry belongs to another run");
      }
      ArtifactRef ref =
          artifactRefRepository
              .findById(published.artifactRefId())
              .orElseThrow(() -> forgedReceipt(fence, "receipt reference does not exist"));
      if (!fence.runId().equals(ref.getRunId())) {
        throw forgedReceipt(fence, "receipt reference belongs to another run");
      }
      if (ref.getKind() != published.kind()
          || !ref.getChecksum().equals(published.checksum())
          || ref.getSizeBytes() != published.sizeBytes()
          || ref.getMediaType() == null
          || !ref.getMediaType().equals(published.mediaType())) {
        throw forgedReceipt(fence, "receipt entry does not match its reference row");
      }
      if (published.kind() == ArtifactKind.REPORT) {
        reportEntries++;
        reportArtifactChecksum = published.checksum();
      }
    }
    List<ArtifactRef> stagingRefs =
        artifactRefRepository.findByRunIdAndState(fence.runId(), ArtifactRef.STATE_STAGING);
    Set<UUID> stagingIds = new HashSet<>();
    for (ArtifactRef ref : stagingRefs) {
      stagingIds.add(ref.getId());
    }
    if (!stagingIds.equals(receiptIds)) {
      throw forgedReceipt(
          fence,
          "receipt does not exactly cover the run's staged references (staged="
              + stagingIds.size()
              + ", receipt="
              + receiptIds.size()
              + ")");
    }
    if (report != null) {
      verifyReportArtifactChecksum(fence, report, reportEntries, reportArtifactChecksum);
      if (!fence.attemptId().equals(report.getAttemptId())
          || report.getManifestChecksum() != null
              && !report.getManifestChecksum().equals(run.getManifestChecksum())
          || report.isControlAllowed()) {
        throw forgedReceipt(fence, "report identity does not match finalization arguments");
      }
      if (report.getOutcome() != expectedOutcome) {
        throw forgedReceipt(fence, "report outcome does not match the effective outcome");
      }
    }
    for (ArtifactRef ref : stagingRefs) {
      ref.setState(ArtifactRef.STATE_FINALIZED);
      ref.setFinalizedAt(finalizedAtUtc);
      artifactRefRepository.save(ref);
    }
  }

  /** REPORT artifact checksum rule: SHA-256 of the exact canonical report JSON bytes. */
  private void verifyReportArtifactChecksum(
      ReplayFence fence, ReplayReport report, int reportEntries, String reportArtifactChecksum) {
    if (reportEntries != 1) {
      throw forgedReceipt(fence, "expected exactly one REPORT artifact, found " + reportEntries);
    }
    String computed =
        HASH_PREFIX + sha256Hex(report.getReportJson().getBytes(StandardCharsets.UTF_8));
    if (reportArtifactChecksum == null || !reportArtifactChecksum.equals(computed)) {
      throw forgedReceipt(fence, "REPORT artifact checksum does not match report JSON");
    }
  }

  /**
   * Forces the report row onto the single effective terminal timestamp using UTC only — never
   * {@code ZoneId.systemDefault()} or {@code Instant.now()}.
   */
  private void stampReportTimestamps(
      ReplayReport report, LocalDateTime startedAtDb, LocalDateTime terminalAtDb) {
    Instant terminalInstant = utcInstant(terminalAtDb);
    report.setTerminalAt(terminalInstant);
    if (startedAtDb != null) {
      report.setStartedAt(utcInstant(startedAtDb));
    }
    if (report.getStartedAt() == null) {
      report.setStartedAt(terminalInstant);
    }
    report.setFinalizedAt(terminalInstant);
  }

  /** Database timestamps are UTC by contract. */
  private static Instant utcInstant(LocalDateTime dbTimestamp) {
    return dbTimestamp != null ? dbTimestamp.atZone(java.time.ZoneOffset.UTC).toInstant() : null;
  }

  private ReplayException forgedReceipt(ReplayFence fence, String why) {
    return new ReplayException(
        ReplayErrorCode.CHECKSUM_MISMATCH,
        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
        false,
        "Artifact publication receipt failed verification: " + why,
        null,
        "attemptNo=" + fence.attemptNo(),
        null);
  }

  private void completeAttempt(ReplayAttempt locked, String targetStateName, LocalDateTime at) {
    locked.setState(targetStateName);
    locked.setTerminalAt(at);
    locked.setLeaseExpiresAt(null);
    attemptRepository.save(locked);
  }

  private ReplayException stale(ReplayFence fence) {
    return new ReplayException(
        ReplayErrorCode.ATTEMPT_STALE_FENCE,
        org.springframework.http.HttpStatus.CONFLICT,
        false,
        "This attempt no longer holds the lease",
        "The run was reclaimed by another worker or the lease expired",
        "attemptNo=" + fence.attemptNo(),
        null);
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(content);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
