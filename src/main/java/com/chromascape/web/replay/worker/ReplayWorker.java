package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.artifact.ArtifactPublicationReceipt;
import com.chromascape.web.replay.artifact.ArtifactRegistration;
import com.chromascape.web.replay.artifact.PreparedArtifact;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.ReplayRunService.ClaimResult;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.chromascape.web.replay.run.entity.ReplayEvent;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.worker.ReplayWorkerPersistenceService.FinalizationReservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for queued causal-simulation runs and executes them inside isolated xRSPS kernel processes.
 *
 * <p>Durability contract: this worker performs NO direct repository writes for execution evidence
 * or terminal state. Every durable write goes through {@link ReplayWorkerPersistenceService}, which
 * locks the attempt fence and then the run row inside one transaction per write, and lease liveness
 * is maintained independently by {@link ReplayLeaseHeartbeat} — so a recovered or expired worker
 * can never persist stale results.
 *
 * <p>Failure protocol: the heartbeat stays ACTIVE through kernel shutdown, report preparation, and
 * REPORT publication; it is stopped only after terminalization or a confirmed stale fence, closing
 * the old lease-expiry window during failure handling.
 */
@Component
@ConditionalOnProperty(prefix = "replay.worker", name = "enabled", havingValue = "true")
public class ReplayWorker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ReplayWorker.class);
  private static final String WORKER_ID = "worker-" + UUID.randomUUID();
  private static final int STEP_MAX_TICKS = 10_000;
  private static final int CHECKPOINT_INTERVAL_ACTIONS = 25;
  private static final int RECOVERY_BATCH_LIMIT = 50;
  private static final List<String> REQUIRED_WORKER_ACTIONS =
      CapabilityManifest.V1_SUPPORTED_ACTIONS;

  /** Deterministic kernel rejections: execution failed, infrastructure did not. */
  private static final Set<ReplayErrorCode> CONTRACT_ERROR_CODES =
      Set.of(
          ReplayErrorCode.TICK_MISMATCH,
          ReplayErrorCode.CHECKSUM_MISMATCH,
          ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
          ReplayErrorCode.SNAPSHOT_INCOMPATIBLE,
          ReplayErrorCode.ACTION_UNSUPPORTED,
          ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE);

  private final ReplayRunRepository runRepository;
  private final com.chromascape.web.replay.run.repository.ReplayAttemptRepository attemptRepository;
  private final ReplayRunService runService;
  private final ScenarioManifestRepository manifestRepository;
  private final ReplayCheckpointRepository checkpointRepository;
  private final ArtifactRefRepository artifactRefRepository;
  private final ReplayMismatchRepository mismatchRepository;
  private final ReplayWorkerPersistenceService persistence;
  private final ReplayLeaseHeartbeat heartbeatService;
  private final ReplayExpiredAttemptRecoveryService recoveryService;
  private final ReplayArtifactService artifactService;
  private final MismatchEngine mismatchEngine;
  private final ReplayReportService reportService;
  private final CanonicalProjectionService projectionService;
  private final ReplayProperties properties;
  private final ObjectMapper mapper;

  /**
   * Test-only escape hatch for exercising durable lifecycle behavior with the current synthetic
   * kernel. The Spring-managed constructor always leaves this false; a synthetic worker can never
   * authorize production causal simulation.
   */
  private final boolean allowSyntheticContractForDiagnostics;

  private final ExecutorService executor;
  private final Semaphore concurrencySemaphore;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Object lifecycleMonitor = new Object();
  private final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
  private final CountDownLatch shutdownComplete = new CountDownLatch(1);

  /** Genesis chain anchor: SHA-256 of the domain-separated literal. */
  private final String genesisChainHash;

  public ReplayWorker(
      ReplayRunRepository runRepository,
      com.chromascape.web.replay.run.repository.ReplayAttemptRepository attemptRepository,
      ReplayRunService runService,
      ScenarioManifestRepository manifestRepository,
      ReplayCheckpointRepository checkpointRepository,
      ArtifactRefRepository artifactRefRepository,
      ReplayMismatchRepository mismatchRepository,
      ReplayWorkerPersistenceService persistence,
      ReplayLeaseHeartbeat heartbeatService,
      ReplayExpiredAttemptRecoveryService recoveryService,
      ReplayArtifactService artifactService,
      MismatchEngine mismatchEngine,
      ReplayReportService reportService,
      CanonicalProjectionService projectionService,
      ReplayProperties properties,
      ObjectMapper mapper) {
    this(
        runRepository,
        attemptRepository,
        runService,
        manifestRepository,
        checkpointRepository,
        artifactRefRepository,
        mismatchRepository,
        persistence,
        heartbeatService,
        recoveryService,
        artifactService,
        mismatchEngine,
        reportService,
        projectionService,
        properties,
        mapper,
        false);
  }

  /**
   * Package-private constructor used only by the replay certification test fixture. Keeping the
   * switch out of {@link ReplayProperties} prevents an application configuration from enabling a
   * synthetic worker accidentally.
   */
  ReplayWorker(
      ReplayRunRepository runRepository,
      com.chromascape.web.replay.run.repository.ReplayAttemptRepository attemptRepository,
      ReplayRunService runService,
      ScenarioManifestRepository manifestRepository,
      ReplayCheckpointRepository checkpointRepository,
      ArtifactRefRepository artifactRefRepository,
      ReplayMismatchRepository mismatchRepository,
      ReplayWorkerPersistenceService persistence,
      ReplayLeaseHeartbeat heartbeatService,
      ReplayExpiredAttemptRecoveryService recoveryService,
      ReplayArtifactService artifactService,
      MismatchEngine mismatchEngine,
      ReplayReportService reportService,
      CanonicalProjectionService projectionService,
      ReplayProperties properties,
      ObjectMapper mapper,
      boolean allowSyntheticContractForDiagnostics) {
    this.runRepository = runRepository;
    this.attemptRepository = attemptRepository;
    this.runService = runService;
    this.manifestRepository = manifestRepository;
    this.checkpointRepository = checkpointRepository;
    this.artifactRefRepository = artifactRefRepository;
    this.mismatchRepository = mismatchRepository;
    this.persistence = persistence;
    this.heartbeatService = heartbeatService;
    this.recoveryService = recoveryService;
    this.artifactService = artifactService;
    this.mismatchEngine = mismatchEngine;
    this.reportService = reportService;
    this.projectionService = projectionService;
    this.properties = properties;
    this.mapper = mapper;
    this.allowSyntheticContractForDiagnostics = allowSyntheticContractForDiagnostics;
    this.executor = Executors.newFixedThreadPool(properties.getWorker().getConcurrency());
    this.concurrencySemaphore = new Semaphore(properties.getWorker().getConcurrency());
    this.genesisChainHash =
        projectionService.sha256Hex(
            "chromascape-replay-chain-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Scheduled(fixedDelayString = "${replay.worker.poll-interval-ms:1000}")
  public void poll() {
    synchronized (lifecycleMonitor) {
      if (closed.get() || !properties.isEnabled()) {
        return;
      }
      try {
        List<UUID> candidates =
            runRepository.findClaimableRunIds(
                ReplayMode.CAUSAL_SIMULATION.name(), properties.getWorker().getConcurrency() * 2);
        for (UUID runId : candidates) {
          if (closed.get() || !concurrencySemaphore.tryAcquire()) {
            return;
          }
          executor.submit(
              () -> {
                try {
                  executeOne(runId);
                } catch (Exception e) {
                  log.error("Worker {} run {} crashed: {}", WORKER_ID, runId, e.getMessage(), e);
                } finally {
                  concurrencySemaphore.release();
                }
              });
        }
      } catch (Exception e) {
        log.warn("Worker poll error: {}", e.getMessage());
      }
    }
  }

  /**
   * Recovers runs whose worker lost its lease (crash / GC pause / kill). The bounded scan compares
   * against CURRENT_TIMESTAMP in SQL — never the application clock — and each attempt is expired
   * and its run terminalized inside ONE recovery transaction.
   */
  @Scheduled(fixedDelayString = "${replay.worker.poll-interval-ms:5000}")
  public void recoverExpiredLeases() {
    synchronized (lifecycleMonitor) {
      if (closed.get() || !properties.isEnabled()) {
        return;
      }
      try {
        List<UUID> expired = attemptIdsForRecovery(RECOVERY_BATCH_LIMIT);
        for (UUID attemptId : expired) {
          try {
            recoveryService.recover(attemptId);
          } catch (Exception e) {
            log.debug("Recovery of attempt {} skipped: {}", attemptId, e.getMessage());
          }
        }
      } catch (Exception e) {
        log.debug("Lease recovery skipped: {}", e.getMessage());
      }
    }
  }

  // ── main execution sequence ──

  private void executeOne(UUID runId) {
    ClaimResult claim = runService.claimAtomically(runId, WORKER_ID);
    if (claim == null) {
      return; // another instance won the claim race
    }
    ReplayRun run = claim.run();
    ReplayFence fence = claim.fence();
    XrspsKernelProcess kernel = null;
    ReplayLeaseHeartbeat.Handle heartbeat = heartbeatService.start(fence);
    ExecutionState es = new ExecutionState(genesisChainHash);
    try {
      persistence.transitionStarting(fence);

      ScenarioManifest manifestEntity =
          manifestRepository
              .findById(run.getManifestId())
              .orElseThrow(() -> new IllegalStateException("Manifest not found"));
      JsonNode manifestDoc = requireManifestObject(manifestEntity);
      JsonNode actions = manifestDoc.path("actions");
      if (!actions.isArray()) {
        throw new IllegalStateException("Manifest actions missing");
      }

      ensureNotCancelled(run.getId());
      kernel = new XrspsKernelProcess(properties.getXrsps(), mapper);
      kernel.start();

      persistence.transitionRunning(fence, ReplayRunPhase.RESET);

      // Capability handshake; runtime identity stored under the fence.
      KernelMessage caps = sendWithinRunDeadline(heartbeat, kernel, run, "capabilities", null);
      WorkerCapability workerCapability = parseCapability(caps);
      RuntimeIdentity runtimeIdentity = workerCapability.identity();
      // Persist the live identity before validating the manifest pin. A well-formed manifest with
      // a foreign runtime must still produce a complete, immutable failure report; delaying this
      // write would leave the report contract without the identity needed to finalize the run and
      // would strand it in FINALIZE_REPORT until lease recovery.
      persistence.storeRuntimeIdentity(fence, runtimeIdentity);
      verifyManifestRuntime(manifestDoc, runtimeIdentity);
      verifyManifestActions(manifestDoc, workerCapability.supportedActions());

      // reset with manifest as a JSON object (never an escaped string).
      ObjectNode resetParams = mapper.createObjectNode();
      resetParams.set("manifest", manifestDoc);
      KernelMessage resetMsg = sendWithinRunDeadline(heartbeat, kernel, run, "reset", resetParams);
      assertOk(resetMsg, "reset");
      long expectedTick = resetMsg.result().path("initialTick").asLong(-1);
      if (expectedTick < 0) {
        throw new IllegalStateException("reset returned no initialTick");
      }
      persistence.appendEvents(fence, extractEvents(fence, resetMsg.result().path("events"), es));

      // ── branch resume: restore parent checkpoint instead of tick zero ──
      int startIndex = 0;
      if (run.getParentCheckpointId() != null) {
        var restored =
            restoreFromCheckpoint(heartbeat, kernel, run, manifestDoc, runtimeIdentity, es);
        startIndex = restored.actionIndex();
        expectedTick = restored.tick();
      }

      persistence.updatePhase(fence, ReplayRunPhase.RESET, ReplayRunPhase.EXECUTE);

      // ── action iteration ──
      for (int i = startIndex; i < actions.size(); i++) {
        ensureNotCancelled(run.getId());
        ensureWithinDeadline(run);
        heartbeat.assertHealthy();

        JsonNode action = actions.get(i);
        ObjectNode stepParams = mapper.createObjectNode();
        stepParams.put("expectedTick", expectedTick);
        stepParams.set("action", action);
        stepParams.put("maxTicks", STEP_MAX_TICKS);
        // sendWithinRunDeadline guards heartbeat + deadline BEFORE the send and rechecks the
        // database deadline IMMEDIATELY after the response.
        KernelMessage stepMsg = sendWithinRunDeadline(heartbeat, kernel, run, "step", stepParams);
        assertStepOk(stepMsg, i);
        JsonNode result = stepMsg.result();

        List<ReplayEvent> events = extractEvents(fence, result.path("events"), es);
        JsonNode observation = result.path("observation");

        String stateHash = projectionService.stateHash(observation);
        es.lastStateHash = stateHash;
        String actionDigest = projectionService.actionDigest(action);
        es.chainHash = projectionService.chainHash(es.chainHash, actionDigest, stateHash);
        long tick = result.path("terminalTick").asLong(expectedTick);
        StateHashSample sample =
            new StateHashSample(run.getId(), tick, 0, 1, stateHash, es.chainHash);
        // Events and their hash sample persist atomically under one fence.
        persistence.appendActionResult(fence, events, sample);
        expectedTick = tick;
        es.executedActions++;

        boolean isLast = i == actions.size() - 1;
        if ((i + 1) % CHECKPOINT_INTERVAL_ACTIONS == 0 || isLast) {
          takeCheckpoint(kernel, heartbeat, fence, run, tick, i, es);
          ensureNotCancelled(run.getId());
          ensureWithinDeadline(run);
        }
      }

      persistence.updatePhase(fence, ReplayRunPhase.EXECUTE, ReplayRunPhase.COMPARE);

      // Final observation; evaluation itself writes nothing.
      KernelMessage finalObserve = sendWithinRunDeadline(heartbeat, kernel, run, "observe", null);
      assertOk(finalObserve, "observe");
      JsonNode actualObservation = finalObserve.result().path("observation");

      var comparison =
          mismatchEngine.evaluate(
              run.getId(),
              fence.attemptId(),
              manifestDoc.path("expectations"),
              actualObservation,
              expectedTick,
              0,
              run.getStopPolicy());

      persistence.saveMismatches(fence, comparison.candidates());

      // Trace + manifest artifacts: prepare (bytes only) then fenced register.
      KernelMessage traceMsg =
          sendWithinRunDeadline(
              heartbeat, kernel, run, "trace", mapper.createObjectNode().put("afterOrdinal", -1));
      assertOk(traceMsg, "trace");
      byte[] traceBytes = mapper.writeValueAsBytes(traceMsg.result());
      registerUnderFence(
          fence,
          artifactService.prepareArtifact(
              run.getId(), ArtifactKind.TRACE, traceBytes, "application/json"),
          es);
      byte[] manifestBytes = mapper.writeValueAsBytes(manifestDoc);
      registerUnderFence(
          fence,
          artifactService.prepareArtifact(
              run.getId(), ArtifactKind.MANIFEST, manifestBytes, "application/json"),
          es);

      // Cancellation cutoff AND authoritative timestamp capture: entering FINALIZE_ARTIFACTS is
      // atomic, fenced, and locks the same run row as cancel(); afterwards cancellation is
      // rejected with RUN_IMMUTABLE. The reserved database clock value becomes THE terminal time
      // for report JSON, report row, run row, attempt row, and artifacts.
      boolean hardStop = comparison.shouldStop();
      FinalizationReservation reservation =
          persistence.reserveSuccessfulFinalization(
              fence, hardStop ? ReplayOutcome.FAILED : ReplayOutcome.PASSED);
      ensureNotCancelled(run.getId());

      // Publish every non-report artifact; receipt verified, no DB marking.
      artifactService.publishArtifacts(run.getId());

      // Prepare schema-shaped report bytes WITHOUT inserting any row.
      var prepared =
          reportService.prepareReport(
              refreshed(run),
              claim.attempt(),
              reservation.outcome(),
              hardStop ? "MISMATCH_HARD_STOP" : null,
              es.executedActions,
              es.checkpointCount,
              es.lastStateHash,
              es.chainHash,
              es.artifactChecksums,
              reservation.reservedAt(),
              firstHardMismatch(run.getId()));
      PreparedArtifact reportPrepared =
          artifactService.prepareArtifact(
              run.getId(), ArtifactKind.REPORT, prepared.canonicalBytes(), "application/json");
      try {
        ArtifactRegistration reportRegistration =
            persistence.registerArtifact(fence, reportPrepared);
        if (!reportRegistration.publicationRequired()) {
          artifactService.discardPrepared(reportPrepared);
        }
      } catch (RuntimeException registrationFailure) {
        artifactService.discardPrepared(reportPrepared);
        throw registrationFailure;
      }

      // Publish the REPORT artifact (second pass re-verifies earlier winners).
      ArtifactPublicationReceipt receipt = artifactService.publishArtifacts(run.getId());

      // One fenced transaction: verify reservation + receipt + REPORT checksum, mark artifacts
      // FINALIZED at the reserved instant, insert the immutable report, terminalize run + attempt
      // using exactly the reserved database timestamp.
      ReplayReport reportRow = reportService.materialize(prepared, refreshed(run), claim.attempt());
      persistence.finalizeSuccessfulRun(
          fence, reservation, receipt, reportRow, reservation.outcome());
      log.info(
          "Run {} finished: {} actions={} checkpoints={}",
          run.getId(),
          reservation.outcome(),
          es.executedActions,
          es.checkpointCount);

    } catch (ReplayCancellationRequested e) {
      failTerminal(
          fence,
          run,
          claim.attempt(),
          heartbeat,
          es,
          kernel,
          ReplayRunState.CANCELLED,
          ReplayOutcome.CANCELLED,
          "CANCEL_REQUESTED");
    } catch (ReplayException e) {
      if (e.getErrorCode() == ReplayErrorCode.ATTEMPT_STALE_FENCE) {
        heartbeat.stop();
        log.warn("Run {} lost its fence; abandoning without further writes", run.getId());
        safeClose(kernel);
        return;
      }
      if (e.getErrorCode() == ReplayErrorCode.RUN_DEADLINE_EXCEEDED) {
        failTerminal(
            fence,
            run,
            claim.attempt(),
            heartbeat,
            es,
            kernel,
            ReplayRunState.TIMED_OUT,
            ReplayOutcome.TIMED_OUT,
            "RUN_DEADLINE_EXCEEDED");
        return;
      }
      if (CONTRACT_ERROR_CODES.contains(e.getErrorCode())) {
        failTerminal(
            fence,
            run,
            claim.attempt(),
            heartbeat,
            es,
            kernel,
            ReplayRunState.FAILED,
            ReplayOutcome.FAILED,
            "REPLAY_ERROR:" + e.getErrorCode().name());
      } else {
        failTerminal(
            fence,
            run,
            claim.attempt(),
            heartbeat,
            es,
            kernel,
            ReplayRunState.FAILED,
            ReplayOutcome.INFRASTRUCTURE_FAILURE,
            "INFRASTRUCTURE_FAILURE");
      }
    } catch (Exception e) {
      log.error("Run {} failed: {}", run.getId(), e.getMessage());
      failTerminal(
          fence,
          run,
          claim.attempt(),
          heartbeat,
          es,
          kernel,
          ReplayRunState.FAILED,
          ReplayOutcome.INFRASTRUCTURE_FAILURE,
          "INFRASTRUCTURE_FAILURE");
    } finally {
      heartbeat.stop();
      safeClose(kernel);
    }
  }

  // ── lifecycle helpers ──

  /** Registers a prepared artifact under the fence, discarding staged bytes on failure. */
  private void registerUnderFence(ReplayFence fence, PreparedArtifact prepared, ExecutionState es) {
    try {
      ArtifactRegistration registration = persistence.registerArtifact(fence, prepared);
      es.artifactChecksums.add(registration.reference().getChecksum());
      if (!registration.publicationRequired()) {
        // Already-finalized evidence: drop the redundant deterministic staged copy and keep it
        // out of the next publication receipt (it remains listed in report evidence).
        artifactService.discardPrepared(prepared);
      }
    } catch (RuntimeException registrationFailure) {
      artifactService.discardPrepared(prepared);
      throw registrationFailure;
    }
  }

  /**
   * Two-step failure protocol with a MANDATORY report:
   *
   * <ol>
   *   <li>dispose the kernel while the heartbeat is STILL active;
   *   <li>reserve the effective terminal decision (cancellation precedence applied at the atomic
   *       FINALIZE_REPORT cutoff) together with its database timestamp;
   *   <li>build the report from the EFFECTIVE decision — never from the originally requested one;
   *   <li>artifact storage healthy: stage/register/publish the REPORT artifact and finalize with
   *       the receipt; storage unavailable (narrowly classified): fall back to the database-only
   *       report and TOMBSTONE unresolved STAGING references during finalization;
   *   <li>report preparation/persistence errors are NOT storage failures: the run stays reserved in
   *       FINALIZE_REPORT, renewal stops, and lease-expiry recovery plus missing-report
   *       reconciliation complete it truthfully.
   * </ol>
   */
  private void failTerminal(
      ReplayFence fence,
      ReplayRun run,
      com.chromascape.web.replay.run.entity.ReplayAttempt attempt,
      ReplayLeaseHeartbeat.Handle heartbeat,
      ExecutionState es,
      XrspsKernelProcess kernel,
      ReplayRunState requestedState,
      ReplayOutcome requestedOutcome,
      String requestedReason) {
    try {
      safeClose(kernel); // heartbeat deliberately still active here

      var decision =
          persistence.reserveFailureFinalization(
              fence, requestedState, requestedOutcome, requestedReason);
      if (decision == null) {
        log.warn("Run {} already terminal; recovery owns the final state", run.getId());
        return;
      }

      // Storage-healthy path: full REPORT artifact + receipt + fenced finalization.
      try {
        var prepared =
            reportService.prepareReport(
                refreshed(run),
                attempt,
                decision.outcome(),
                decision.reason(),
                es.executedActions,
                es.checkpointCount,
                es.lastStateHash,
                es.chainHash,
                finalizedChecksums(run.getId()),
                decision.terminalAt(),
                firstHardMismatch(run.getId()));
        PreparedArtifact reportPrepared =
            artifactService.prepareArtifact(
                run.getId(), ArtifactKind.REPORT, prepared.canonicalBytes(), "application/json");
        try {
          ArtifactRegistration registration =
              persistence.registerFailureReportArtifact(fence, reportPrepared);
          if (!registration.publicationRequired()) {
            artifactService.discardPrepared(reportPrepared);
          }
        } catch (RuntimeException registrationFailure) {
          artifactService.discardPrepared(reportPrepared);
          throw registrationFailure;
        }
        ArtifactPublicationReceipt receipt = artifactService.publishArtifacts(run.getId());
        ReplayReport reportRow = reportService.materialize(prepared, refreshed(run), attempt);
        persistence.finalizeFailure(fence, decision, reportRow, receipt);
        return;
      } catch (ReplayException e) {
        if (e.getErrorCode() == ReplayErrorCode.ATTEMPT_STALE_FENCE
            || e.getErrorCode() == ReplayErrorCode.RUN_IMMUTABLE) {
          throw e; // fence lost mid-failure-handling or recovery already won: abandon
        }
        if (!isArtifactStorageError(e)) {
          // Report-contract/checksum/programming error: NOT a storage condition. Leave the run
          // reserved in FINALIZE_REPORT for lease-expiry recovery + reconciliation.
          log.warn(
              "Run {}: failure report could not be completed ({}); leaving FINALIZE_REPORT for recovery",
              run.getId(),
              e.getErrorCode());
          return;
        }
        log.warn(
            "Run {}: REPORT artifact unavailable ({}); falling back to database-only failure report",
            run.getId(),
            e.getErrorCode());
      } catch (RuntimeException unexpected) {
        // An unclassified runtime failure can be a report-contract, mapping, or programming
        // failure. It is not evidence that artifact storage is unavailable; falling back here
        // would silently weaken the mandatory report contract. Leave the reservation in
        // FINALIZE_REPORT so recovery/reconciliation can handle it explicitly.
        log.warn(
            "Run {}: failure report path failed unexpectedly; leaving FINALIZE_REPORT for recovery ({})",
            run.getId(),
            unexpected.getClass().getSimpleName());
        return;
      }

      // Database-only fallback: no REPORT artifact exists; list only already-FINALIZED refs.
      try {
        var prepared =
            reportService.prepareReport(
                refreshed(run),
                attempt,
                decision.outcome(),
                decision.reason(),
                es.executedActions,
                es.checkpointCount,
                es.lastStateHash,
                es.chainHash,
                finalizedChecksums(run.getId()),
                decision.terminalAt(),
                firstHardMismatch(run.getId()));
        ReplayReport reportRow = reportService.materialize(prepared, refreshed(run), attempt);
        persistence.finalizeFailure(fence, decision, reportRow, null);
      } catch (ReplayException e) {
        if (e.getErrorCode() == ReplayErrorCode.ATTEMPT_STALE_FENCE
            || e.getErrorCode() == ReplayErrorCode.RUN_IMMUTABLE) {
          throw e;
        }
        log.warn(
            "Run {}: database-only failure report failed ({}); leaving FINALIZE_REPORT for recovery",
            run.getId(),
            e.getErrorCode());
      } catch (Exception e) {
        log.warn(
            "Run {}: database-only failure report failed; leaving FINALIZE_REPORT for recovery: {}",
            run.getId(),
            e.getMessage());
      }
    } catch (ReplayException e) {
      if (e.getErrorCode() == ReplayErrorCode.ATTEMPT_STALE_FENCE) {
        log.warn(
            "Run {} terminalization lost the fence; recovery owns the final state", run.getId());
        return;
      }
      log.warn("Failed to terminalize run {}: {}", run.getId(), e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to terminalize run {}: {}", run.getId(), e.getMessage());
    } finally {
      heartbeat.stop(); // only after terminalization or confirmed loss/reservation
    }
  }

  /**
   * The error classes eligible for the database-only report fallback. A checksum failure raised by
   * the artifact publisher is a storage-boundary failure and must be finalized fail-closed; kernel
   * checksum failures are handled before this method and already carry the durable contract error.
   */
  private static boolean isArtifactStorageError(ReplayException e) {
    return e.getErrorCode() == ReplayErrorCode.ARTIFACT_STORAGE_UNAVAILABLE
        || e.getErrorCode() == ReplayErrorCode.ARTIFACT_PUBLICATION_FAILED
        || e.getErrorCode() == ReplayErrorCode.CHECKSUM_MISMATCH;
  }

  /** First HARD mismatch in authoritative order; read-only evidence for report-v1. */
  private com.chromascape.web.replay.run.entity.ReplayMismatch firstHardMismatch(UUID runId) {
    return mismatchRepository
        .findFirstByRunIdAndLevelOrderByFirstTickAscTickSequenceAscCreatedAtAscIdAsc(
            runId, com.chromascape.web.replay.api.dto.MismatchLevel.HARD)
        .orElse(null);
  }

  /** Checksums of references actually marked FINALIZED — the only honest failure-report list. */
  private List<String> finalizedChecksums(UUID runId) {
    return artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_FINALIZED).stream()
        .map(ArtifactRef::getChecksum)
        .distinct()
        .sorted()
        .toList();
  }

  private List<UUID> attemptIdsForRecovery(int limit) {
    return attemptRepository.findExpiredActiveAttempts(limit).stream()
        .map(com.chromascape.web.replay.run.entity.ReplayAttempt::getId)
        .toList();
  }

  // ── branch resume ──

  public record RestoredCheckpoint(long tick, int actionIndex) {}

  /**
   * Checkpoint continuation with complete verification before execution resumes:
   *
   * <ol>
   *   <li>the checkpoint belongs to the parent run;
   *   <li>the finalized parent SNAPSHOT artifact is found via {@code snapshotArtifactChecksum};
   *   <li>its complete bytes are re-hashed against the content address;
   *   <li>{@code snapshotVersion=1}; the internal snapshot checksum is recomputed over the
   *       canonical document with {@code checksum} omitted and compared with the checkpoint row;
   *   <li>snapshot-embedded runtime identity matches the live kernel capability;
   *   <li>restore lands exactly on the checkpoint tick;
   *   <li>a post-restore observe reproduces the checkpoint state hash exactly;
   *   <li>execution continues from the checkpoint chain hash and next-action index, preserving
   *       branch lineage.
   * </ol>
   *
   * Failures map to {@code SNAPSHOT_INCOMPATIBLE}-style stable errors ({@code CHECKSUM_MISMATCH} or
   * {@code SCHEMA_VERSION_UNSUPPORTED}), never generic infrastructure failure.
   */
  /**
   * Package-private for the branch-restoration test: the checkpoint contract must be exercised
   * through THIS method (real bytes, real hashes, real kernel messages), not through helper mocks.
   */
  public RestoredCheckpoint restoreFromCheckpoint(
      ReplayLeaseHeartbeat.Handle heartbeat,
      XrspsKernelProcess kernel,
      ReplayRun run,
      JsonNode manifestDoc,
      RuntimeIdentity liveIdentity,
      ExecutionState es) {
    if (run == null || run.getParentRunId() == null || run.getParentCheckpointId() == null) {
      throw snapshotIncompatible();
    }
    ReplayCheckpoint checkpoint =
        checkpointRepository
            .findById(run.getParentCheckpointId())
            .orElseThrow(this::snapshotIncompatible);
    if (!checkpoint.getRunId().equals(run.getParentRunId())) {
      throw snapshotIncompatible();
    }
    String artifactChecksum = checkpoint.getSnapshotArtifactChecksum();
    if (artifactChecksum == null || artifactChecksum.isBlank()) {
      throw snapshotIncompatible();
    }
    // The stored value is a content address (sha256:<hex>), never a row id.
    ArtifactRef snapRef =
        artifactRefRepository
            .findByRunIdAndKindAndChecksum(
                checkpoint.getRunId(), ArtifactKind.SNAPSHOT, artifactChecksum)
            .filter(r -> ArtifactRef.STATE_FINALIZED.equals(r.getState()))
            .orElseThrow(this::snapshotIncompatible);

    byte[] snapBytes = artifactService.readFinalized(snapRef);
    // sha256Hex already returns the prefixed "sha256:<hex>" content address; never prepend again.
    String recomputed = projectionService.sha256Hex(snapBytes);
    if (!recomputed.equals(snapRef.getChecksum())) {
      throw checksumMismatch("Parent snapshot bytes failed content-address verification");
    }
    JsonNode snapshot;
    try {
      snapshot = mapper.readTree(snapBytes);
    } catch (Exception e) {
      throw checksumMismatch("Stored checkpoint snapshot is not valid JSON");
    }
    if (!snapshot.isObject()) {
      throw snapshotIncompatible();
    }
    if (snapshot.path("snapshotVersion").asInt(-1) != 1) {
      throw new ReplayException(
          ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          false,
          "Checkpoint snapshot version is unsupported",
          null,
          null,
          null);
    }
    // Internal checksum: canonical form with the checksum field omitted. sha256Hex already
    // carries the "sha256:" prefix — a second prefix made every valid snapshot fail restore.
    ObjectNode withoutChecksum = snapshot.deepCopy();
    withoutChecksum.remove("checksum");
    String internalComputed = projectionService.sha256Hex(canonicalBytes(withoutChecksum));
    if (checkpoint.getSnapshotChecksum() == null
        || !checkpoint.getSnapshotChecksum().equals(internalComputed)) {
      throw checksumMismatch("Snapshot internal checksum does not match the checkpoint record");
    }
    verifySnapshotRuntime(snapshot, liveIdentity);

    // Contract: restore into the freshly reset compatible kernel. sendWithinRunDeadline guards
    // every kernel request around restore (heartbeat before/after, deadline before AND after).
    ObjectNode restoreParams = mapper.createObjectNode();
    restoreParams.set("snapshot", snapshot);
    KernelMessage restoreMsg =
        sendWithinRunDeadline(heartbeat, kernel, run, "restore", restoreParams);
    assertOk(restoreMsg, "restore");
    long restoredTick = restoreMsg.result().path("restoredTick").asLong(-1);
    if (restoredTick != checkpoint.getTick()) {
      throw snapshotIncompatible();
    }

    KernelMessage postObserve = sendWithinRunDeadline(heartbeat, kernel, run, "observe", null);
    assertOk(postObserve, "observe");
    String observedHash = projectionService.stateHash(postObserve.result().path("observation"));
    if (!observedHash.equals(checkpoint.getStateHash())) {
      throw checksumMismatch("Post-restore observation hash differs from the checkpoint record");
    }

    // Inherit the parent's chain so child hashes continue the lineage.
    es.chainHash =
        checkpoint.getChainHash() != null && !checkpoint.getChainHash().isBlank()
            ? checkpoint.getChainHash()
            : genesisChainHash;
    es.lastStateHash = checkpoint.getStateHash();

    // Parent events belong to the parent run: skip their ordinals entirely.
    long nextOrdinal = snapshot.path("eventQueue").path("nextOrdinal").asLong(0);
    es.lastPersistedOrdinal = Math.max(es.lastPersistedOrdinal, nextOrdinal - 1);

    int actionIndex = checkpoint.getActionIndex() != null ? checkpoint.getActionIndex() : 0;
    log.info(
        "Branch run {} resumed from checkpoint {} at tick {} action index {}",
        run.getId(),
        checkpoint.getId(),
        restoredTick,
        actionIndex);
    return new RestoredCheckpoint(restoredTick, actionIndex);
  }

  /**
   * Compares the snapshot's embedded runtime identity (when present) with the live kernel
   * capability captured after the handshake.
   */
  private void verifySnapshotRuntime(JsonNode snapshot, RuntimeIdentity live) {
    JsonNode identity = snapshot.path("runtimeIdentity");
    if (identity.isMissingNode() || identity.isNull()) {
      return; // older snapshots carry no embedded identity
    }
    requireFieldMatches(identity, "xrspsAdapterVersion", live);
    requireFieldMatches(identity, "xrspsBuildCommit", live);
    requireFieldMatches(identity, "cacheRevision", live);
    requireFieldMatches(identity, "contentRevision", live);
  }

  /**
   * Verifies manifest-pinned runtime fields against the live kernel capability BEFORE any reset.
   * ALL FOUR fields must be present and non-blank — blank pins are not wildcards — and each must
   * equal the live value exactly.
   */
  private void verifyManifestRuntime(JsonNode manifestDoc, RuntimeIdentity live) {
    JsonNode runtime = manifestDoc.path("runtime");
    if (!runtime.isObject()) {
      throw pinnedRuntimeFailure("manifest.runtime", "missing");
    }
    for (String field : RUNTIME_IDENTITY_FIELDS) {
      String pinned = runtime.path(field).asText(null);
      if (pinned == null || pinned.isBlank()) {
        throw pinnedRuntimeFailure(field, "blank or missing pin");
      }
      requireFieldMatches(runtime, field, live);
    }
  }

  private static final List<String> RUNTIME_IDENTITY_FIELDS =
      List.of("xrspsAdapterVersion", "xrspsBuildCommit", "cacheRevision", "contentRevision");

  private void requireFieldMatches(JsonNode node, String field, RuntimeIdentity live) {
    String pinned = node.path(field).asText(null);
    if (pinned == null || pinned.isBlank()) {
      throw pinnedRuntimeFailure(field, "blank or missing pin");
    }
    String liveValue =
        switch (field) {
          case "xrspsAdapterVersion" -> live.xrspsAdapterVersion();
          case "xrspsBuildCommit" -> live.xrspsBuildCommit();
          case "cacheRevision" -> live.cacheRevision();
          case "contentRevision" -> live.contentRevision();
          default -> null;
        };
    if (liveValue == null || liveValue.isBlank()) {
      throw pinnedRuntimeFailure(field, "blank live worker identity");
    }
    if (!pinned.equals(liveValue)) {
      throw pinnedRuntimeFailure(field, "does not match the live kernel");
    }
  }

  private ReplayException pinnedRuntimeFailure(String field, String why) {
    return new ReplayException(
        ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
        false,
        "Manifest runtime identity is incompatible: field '" + field + "' " + why,
        "Recompile the manifest against the certified xRSPS build identity",
        field,
        null);
  }

  private ReplayException snapshotIncompatible() {
    return new ReplayException(
        ReplayErrorCode.SNAPSHOT_INCOMPATIBLE,
        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
        false,
        "Parent checkpoint snapshot is incompatible with this branch run",
        null,
        null,
        null);
  }

  private ReplayException checksumMismatch(String message) {
    return new ReplayException(
        ReplayErrorCode.CHECKSUM_MISMATCH,
        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
        false,
        message,
        null,
        null,
        null);
  }

  // ── helpers ──

  private void takeCheckpoint(
      XrspsKernelProcess kernel,
      ReplayLeaseHeartbeat.Handle heartbeat,
      ReplayFence fence,
      ReplayRun run,
      long tick,
      int actionIndex,
      ExecutionState es) {
    KernelMessage snapMsg = sendWithinRunDeadline(heartbeat, kernel, run, "snapshot", null);
    assertOk(snapMsg, "snapshot");
    JsonNode snapshot = snapMsg.result().path("snapshot");
    byte[] snapBytes = canonicalBytes(snapshot);
    PreparedArtifact prepared =
        artifactService.prepareArtifact(
            fence.runId(), ArtifactKind.SNAPSHOT, snapBytes, "application/json");
    ArtifactRef ref;
    try {
      ReplayCheckpoint checkpoint =
          new ReplayCheckpoint(
              UUID.randomUUID(),
              fence.runId(),
              fence.attemptId(),
              tick,
              snapshot.path("checksum").asText(null),
              es.lastStateHash != null ? es.lastStateHash : "",
              es.chainHash,
              runBranchKey(fence));
      checkpoint.setActionIndex(actionIndex + 1);
      // Full serialized artifact content address (distinct from the kernel-internal snapshot
      // checksum stored above); set inside the same transaction by registerCheckpoint.
      checkpoint.setParentCheckpointId(resolveParentCheckpointId(fence));
      // Reference registration + checkpoint insert commit together.
      ArtifactRegistration registration =
          persistence.registerCheckpoint(fence, prepared, checkpoint);
      ref = registration.reference();
    } catch (RuntimeException registrationFailure) {
      artifactService.discardPrepared(prepared);
      throw registrationFailure;
    }
    es.artifactChecksums.add(ref.getChecksum());
    es.checkpointCount++;
  }

  private String runBranchKey(ReplayFence fence) {
    ReplayRun run = runRepository.findById(fence.runId()).orElse(null);
    return run != null && run.getBranchKey() != null ? run.getBranchKey() : "default";
  }

  private UUID resolveParentCheckpointId(ReplayFence fence) {
    ReplayRun run = runRepository.findById(fence.runId()).orElse(null);
    return run != null ? run.getParentCheckpointId() : null;
  }

  /** Pure conversion of kernel events to entities; persistence happens fenced. */
  private List<ReplayEvent> extractEvents(
      ReplayFence fence, JsonNode eventsNode, ExecutionState es) {
    List<ReplayEvent> batch = new ArrayList<>();
    if (eventsNode == null || !eventsNode.isArray()) {
      return batch;
    }
    for (JsonNode ev : eventsNode) {
      long ordinal = ev.path("ordinal").asLong(-1);
      if (ordinal <= es.lastPersistedOrdinal) {
        continue;
      }
      batch.add(toEvent(fence, ev));
    }
    eventsNode.forEach(
        ev -> {
          long ordinal = ev.path("ordinal").asLong(-1);
          if (ordinal > es.lastPersistedOrdinal) {
            es.lastPersistedOrdinal = ordinal;
          }
        });
    return batch;
  }

  private ReplayEvent toEvent(ReplayFence fence, JsonNode ev) {
    ReplayEvent event = new ReplayEvent();
    event.setRunId(fence.runId());
    event.setAttemptId(fence.attemptId());
    event.setOrdinal(ev.path("ordinal").asLong(-1));
    event.setTick(ev.path("tick").asLong(0));
    event.setSequence(ev.path("sequence").asInt(0));
    event.setEventType(ev.path("eventType").asText("UNKNOWN"));
    event.setCategory(ev.path("category").asText("DIAGNOSTIC"));
    event.setPayload(ev.has("payload") && !ev.get("payload").isNull() ? ev.get("payload") : null);
    // Durable stamping happens inside the persistence transaction using the database clock.
    event.setCreatedAt(null);
    return event;
  }

  /** Parses the capability envelope into the pinned runtime identity, rejecting placeholders. */
  private RuntimeIdentity parseCapabilityIdentity(KernelMessage caps) {
    return parseCapability(caps).identity();
  }

  /**
   * Parses the complete worker capability boundary. A clean build identity is insufficient: the
   * production worker requires authoritative fidelity and a closed, explicitly declared action
   * surface before it is allowed to reset or step a manifest.
   */
  private WorkerCapability parseCapability(KernelMessage caps) {
    assertOk(caps, "capabilities");
    JsonNode capability = caps.result().path("capabilities");
    if (capability.isMissingNode()) {
      capability = caps.result();
    }
    if (!capability.isObject()) {
      throw capabilityIncompatible("capability document is missing or not an object");
    }
    if (!capability.path("modes").path("causalSimulation").asBoolean(false)) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          false,
          "Kernel does not offer causal simulation",
          null,
          null,
          null);
    }
    if (capability.path("modes").path("observedPlayback").asBoolean(true)
        || capability.path("modes").path("policyReplay").asBoolean(true)
        || capability.path("controlAllowed").asBoolean(true)
        || capability.path("openRuneAvailable").asBoolean(true)) {
      throw capabilityIncompatible("worker advertised a forbidden capability surface");
    }
    // A non-certifiable build (or one that never attests certifiability) can never back a
    // certified replay run: a MISSING field is treated as false, never defaulted to true.
    if (!capability.path("certifiable").asBoolean(false)) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          false,
          "Kernel does not attest a certifiable build identity",
          "Rebuild the xRSPS worker with real certification identity",
          null,
          null);
    }
    String fidelity = capability.path("fidelity").asText(null);
    if (!"SYNTHETIC_CONTRACT".equals(fidelity) && !"XRSPS_AUTHORITATIVE".equals(fidelity)) {
      throw capabilityIncompatible(
          "worker fidelity is missing or unsupported: " + String.valueOf(fidelity));
    }
    if (!"XRSPS_AUTHORITATIVE".equals(fidelity)
        && !(allowSyntheticContractForDiagnostics && "SYNTHETIC_CONTRACT".equals(fidelity))) {
      throw capabilityIncompatible(
          "worker fidelity "
              + fidelity
              + " is not eligible for production simulation"
              + (allowSyntheticContractForDiagnostics ? " or diagnostic execution" : ""));
    }
    JsonNode supportedActionsNode = capability.get("supportedActions");
    if (supportedActionsNode == null || !supportedActionsNode.isArray()) {
      throw capabilityIncompatible("worker supportedActions is missing or not an array");
    }
    List<String> advertisedActions = new ArrayList<>();
    for (JsonNode action : supportedActionsNode) {
      if (!action.isTextual() || !REQUIRED_WORKER_ACTIONS.contains(action.asText())) {
        throw capabilityIncompatible(
            "worker supportedActions contains an unknown action: " + action);
      }
      if (!advertisedActions.add(action.asText())) {
        throw capabilityIncompatible(
            "worker supportedActions contains a duplicate action: " + action.asText());
      }
    }
    if (!REQUIRED_WORKER_ACTIONS.equals(advertisedActions)) {
      throw capabilityIncompatible(
          "worker supportedActions must be exactly " + REQUIRED_WORKER_ACTIONS);
    }
    JsonNode xrsps = capability.path("xrsps");
    if (!xrsps.isObject()) {
      throw capabilityIncompatible("worker xrsps runtime identity is missing or not an object");
    }
    RuntimeIdentity identity =
        new RuntimeIdentity(
            xrsps.path("adapterVersion").asText(null),
            xrsps.path("buildCommit").asText(null),
            xrsps.path("cacheRevision").asText(null),
            xrsps.path("contentRevision").asText(null));
    rejectPlaceholderIdentity("xrspsAdapterVersion", identity.xrspsAdapterVersion());
    rejectPlaceholderIdentity("xrspsBuildCommit", identity.xrspsBuildCommit());
    rejectPlaceholderIdentity("cacheRevision", identity.cacheRevision());
    rejectPlaceholderIdentity("contentRevision", identity.contentRevision());
    if (identity.xrspsBuildCommit() == null
        || !identity.xrspsBuildCommit().matches("[0-9a-f]{40}")) {
      throw capabilityIncompatible("worker buildCommit is not a 40-character lowercase commit");
    }
    for (String revision : List.of(identity.cacheRevision(), identity.contentRevision())) {
      if (revision == null || !revision.matches("sha256:[0-9a-f]{64}")) {
        throw capabilityIncompatible("worker cache/content revision is not a sha256 address");
      }
    }
    return new WorkerCapability(identity, Set.copyOf(advertisedActions));
  }

  /** Manifest action declarations must be a subset of the live worker's advertised action set. */
  private void verifyManifestActions(JsonNode manifestDoc, Set<String> supportedActions) {
    JsonNode actions = manifestDoc.path("actions");
    if (!actions.isArray()) {
      throw capabilityIncompatible("manifest actions are missing or not an array");
    }
    for (int i = 0; i < actions.size(); i++) {
      JsonNode action = actions.get(i);
      String type = action.path("type").asText(null);
      if (type == null || !supportedActions.contains(type)) {
        throw capabilityIncompatible(
            "manifest action at index " + i + " is not declared by the worker: " + type);
      }
    }
  }

  private ReplayException capabilityIncompatible(String message) {
    return new ReplayException(
        ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
        false,
        message,
        "Use an authoritative xRSPS worker with a matching supported action declaration",
        null,
        null);
  }

  private record WorkerCapability(RuntimeIdentity identity, Set<String> supportedActions) {}

  /** Blank and placeholder identities never enter replay: they cannot be certified. */
  private void rejectPlaceholderIdentity(String field, String value) {
    if (value == null
        || value.isBlank()
        || "dev".equals(value)
        || "headless-deterministic".equals(value)) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          false,
          "Kernel runtime identity field '" + field + "' is blank or a placeholder",
          "Build the xRSPS worker with generated runtime identity metadata",
          field,
          null);
    }
  }

  private void assertOk(KernelMessage msg, String op) {
    if (msg.isError()) {
      String code =
          msg.error() != null
              ? msg.error().path("code").asText("INTERNAL_ERROR")
              : "INTERNAL_ERROR";
      String message = msg.error() != null ? msg.error().path("message").asText("") : "";
      throw new ReplayException(
          mapErrorCode(code),
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          false,
          "Kernel " + op + " failed (" + code + ")",
          null,
          message,
          null);
    }
  }

  private void assertStepOk(KernelMessage msg, int actionIndex) {
    assertOk(msg, "step[" + actionIndex + "]");
  }

  private static ReplayErrorCode mapErrorCode(String code) {
    return switch (code) {
      case "TICK_MISMATCH" -> ReplayErrorCode.TICK_MISMATCH;
      case "CHECKSUM_MISMATCH" -> ReplayErrorCode.CHECKSUM_MISMATCH;
      case "SCHEMA_VERSION_UNSUPPORTED" -> ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED;
      case "SNAPSHOT_INCOMPATIBLE" -> ReplayErrorCode.SNAPSHOT_INCOMPATIBLE;
      case "ACTION_UNSUPPORTED" -> ReplayErrorCode.ACTION_UNSUPPORTED;
      case "ACTION_TARGET_NOT_FOUND", "ACTION_AMBIGUOUS" -> ReplayErrorCode
          .ACTION_TARGET_UNRESOLVED;
      case "XRSPS_VERSION_INCOMPATIBLE" -> ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE;
      default -> ReplayErrorCode.INTERNAL_ERROR;
    };
  }

  private JsonNode requireManifestObject(ScenarioManifest entity) {
    JsonNode json = entity.getManifestJson();
    if (json == null || !json.isObject()) {
      throw new IllegalStateException("Stored manifest JSON is not an object");
    }
    return json;
  }

  private void ensureNotCancelled(UUID runId) {
    ReplayRun current = runRepository.findById(runId).orElseThrow();
    if (current.getState() == ReplayRunState.CANCELLING
        || current.getState() == ReplayRunState.CANCELLED) {
      throw new ReplayCancellationRequested();
    }
  }

  /**
   * Database-clock deadline check. Never compares the JVM clock: the same PostgreSQL predicate that
   * governs recovery decides whether this live worker has exceeded its deadline.
   */
  private void ensureWithinDeadline(ReplayRun run) {
    Boolean exceeded =
        runRepository.isDeadlineExceeded(
            run.getId(), properties.getWorker().getRunTimeoutSeconds());
    if (Boolean.TRUE.equals(exceeded)) {
      throw runDeadlineExceeded();
    }
  }

  /**
   * THE single door for kernel requests during a run. Every capability, reset, step, observe,
   * trace, snapshot, and restore request is routed through here so that:
   *
   * <ol>
   *   <li>the heartbeat is asserted healthy immediately BEFORE the send;
   *   <li>the remaining run-deadline budget comes from the PostgreSQL clock; zero-or-less budget
   *       fails fast with {@code RUN_DEADLINE_EXCEEDED} without touching the kernel;
   *   <li>the kernel request timeout is {@code min(requestTimeoutMs, remainingDeadlineMs)} — a slow
   *       kernel call can therefore never outlive the run deadline;
   *   <li>the heartbeat is asserted healthy after the response AND the database deadline is
   *       rechecked IMMEDIATELY after the response, before any result is consumed.
   * </ol>
   */
  private KernelMessage sendWithinRunDeadline(
      ReplayLeaseHeartbeat.Handle heartbeat,
      XrspsKernelProcess kernel,
      ReplayRun run,
      String method,
      JsonNode params) {
    heartbeat.assertHealthy();
    long remainingDeadlineMs = remainingDeadlineMs(run);
    if (remainingDeadlineMs <= 0) {
      throw runDeadlineExceeded();
    }
    long effectiveTimeoutMs =
        Math.min(properties.getXrsps().getRequestTimeoutMs(), Math.max(1, remainingDeadlineMs));
    KernelMessage response = kernel.send(method, params, effectiveTimeoutMs);
    heartbeat.assertHealthy();
    // Recheck the database deadline immediately after the response arrives.
    ensureWithinDeadline(run);
    return response;
  }

  /** Remaining run-deadline milliseconds per PostgreSQL; a vanished run row has none left. */
  private long remainingDeadlineMs(ReplayRun run) {
    Long remaining =
        runRepository.remainingDeadlineMs(
            run.getId(), properties.getWorker().getRunTimeoutSeconds());
    return remaining == null ? -1L : remaining;
  }

  private ReplayException runDeadlineExceeded() {
    return new ReplayException(
        ReplayErrorCode.RUN_DEADLINE_EXCEEDED,
        org.springframework.http.HttpStatus.REQUEST_TIMEOUT,
        false,
        "Run exceeded its deadline",
        null,
        null,
        null);
  }

  private ReplayRun refreshed(ReplayRun stale) {
    return runRepository.findById(stale.getId()).orElse(stale);
  }

  private void safeClose(XrspsKernelProcess kernel) {
    if (kernel != null) {
      kernel.close();
    }
  }

  private byte[] canonicalBytes(JsonNode node) {
    return projectionService.canonicalJson(node).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Stops accepting polls and releases the worker executor. A worker owns its executor rather than
   * borrowing an application-wide pool, so certification and embedded callers must close it after
   * the last run; otherwise a successful test can leave non-daemon worker threads behind.
   */
  @Override
  @PreDestroy
  public void close() {
    boolean closeOwner;
    synchronized (lifecycleMonitor) {
      closeOwner = closed.compareAndSet(false, true);
    }
    if (!closeOwner) {
      // A concurrent @PreDestroy/close caller must observe the same bounded shutdown operation,
      // rather than returning while the owner is still leaving live executor threads behind.
      try {
        shutdownComplete.await(
            Math.max(1L, properties.getXrsps().getShutdownTimeoutMs()) + 1_000L,
            TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return;
    }
    try {
      executor.shutdown();
      long gracefulWaitMs = Math.max(1L, properties.getXrsps().getShutdownTimeoutMs());
      try {
        if (!executor.awaitTermination(gracefulWaitMs, TimeUnit.MILLISECONDS)) {
          executor.shutdownNow();
          long forcedWaitMs = Math.min(1000L, gracefulWaitMs);
          if (!executor.awaitTermination(forcedWaitMs, TimeUnit.MILLISECONDS)) {
            shutdownFailure.compareAndSet(
                null,
                new IllegalStateException(
                    "Replay worker executor remained active after bounded shutdown"));
            log.error("Replay worker executor remained active after forced shutdown");
          }
        }
      } catch (InterruptedException forcedInterrupted) {
        executor.shutdownNow();
        try {
          if (!executor.awaitTermination(Math.min(1000L, gracefulWaitMs), TimeUnit.MILLISECONDS)) {
            shutdownFailure.compareAndSet(
                null,
                new IllegalStateException(
                    "Replay worker executor remained active after interrupted shutdown"));
            log.error("Replay worker executor remained active after interrupted shutdown");
          }
        } catch (InterruptedException secondInterrupt) {
          shutdownFailure.compareAndSet(
              null,
              new IllegalStateException(
                  "Replay worker executor shutdown was interrupted before termination",
                  secondInterrupt));
          log.error("Replay worker executor shutdown was interrupted before termination");
        }
        Thread.currentThread().interrupt();
      }
    } finally {
      // Every caller waiting in the idempotent close path is released even if executor shutdown
      // itself throws an unchecked failure. isShutdownClean() remains false in that case.
      shutdownComplete.countDown();
    }
  }

  /** True only when close completed without a live executor remaining. */
  public boolean isShutdownClean() {
    return closed.get() && shutdownFailure.get() == null && executor.isTerminated();
  }

  /** Mutable per-execution accumulator. Public so the certification harness can drive it. */
  public static class ExecutionState {
    public String chainHash;
    public String lastStateHash;
    public long lastPersistedOrdinal = -1;
    public int executedActions;
    public int checkpointCount;
    public final List<String> artifactChecksums = new ArrayList<>();
    public final List<UUID> recordedMismatchIds = new ArrayList<>();

    public ExecutionState(String genesisChainHash) {
      this.chainHash = genesisChainHash;
    }
  }
}
