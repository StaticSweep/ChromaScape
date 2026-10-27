package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.config.Sc2106ExecutionProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Executes one registered, approved candidate through the Micro6 host (SC-2106 R9.1, R9.3).
 *
 * <p>The request is built only from the re-verified registration bundle: proposal and approval
 * digests, base commit and candidate hash come from the signed manifest, and the runner, adapter,
 * protocol, core entry, package, core and dependency digests from the signed execution closure. The
 * manifest and closure are named by content digest. Every bundle file is copied into the
 * content-addressed store the host resolves from, so the host verifies the same bytes that were
 * registered. The result is accepted only through {@link CandidateExecutionResultValidator}.
 *
 * <p>Ownership is durable (R4): each (proposal, idempotency key) is reserved in the {@link
 * CandidateAttemptStore} before anything is staged or launched, and every later write is fenced on
 * the reservation's lease and epoch. A duplicate request with the same inputs reattaches to the
 * recorded result instead of launching again; changed inputs conflict. An attempt whose owner
 * disappeared is reconciled to UNCERTAIN at startup and is never relaunched automatically.
 *
 * <p>Control is durable too (R9.2, R9.5): {@link #submit} reserves and returns at once, the run
 * goes on in the background, and {@link #status} reads the attempt back by its idempotency key from
 * any process, after a refresh or a restart. Cancel, pause and resume are recorded on the attempt;
 * the owning process reads them every {@link #CONTROL_TICK} and steers its run, so a request
 * reaches the run whichever process received it. A pause parks the runner between steps.
 */
@Service
public class CandidateExecutionJobService {
  private final PatchCandidateWorkflowService workflow;
  private final Sc2106ExecutionProperties properties;
  private final CandidateHostLauncher host;
  private final CandidateAttemptStore attempts;

  /** Runs owned by this process, by attempt id. */
  private final Map<String, Live> active = new ConcurrentHashMap<>();

  private volatile Map<String, Object> fault;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ScheduledExecutorService leaseRenewal =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "sc2106-attempt-lease");
            thread.setDaemon(true);
            return thread;
          });
  private volatile ExecutorService jobs;

  /** How often an owner renews its lease and reads the attempt's controls. */
  static final Duration CONTROL_TICK = Duration.ofSeconds(1);

  /** One run this process owns: its live control flags and its lease/control tick. */
  private static final class Live {
    final String jobId;
    final AtomicBoolean cancel = new AtomicBoolean();
    final AtomicBoolean pause = new AtomicBoolean();
    volatile ScheduledFuture<?> tick;

    Live(String jobId) {
      this.jobId = jobId;
    }
  }

  @Autowired
  public CandidateExecutionJobService(
      PatchCandidateWorkflowService workflow,
      Sc2106ExecutionProperties properties,
      CandidateAttemptStore attempts) {
    this(workflow, properties, null, attempts);
  }

  /** Test seam: a supplied launcher replaces the configured host process. */
  CandidateExecutionJobService(
      PatchCandidateWorkflowService workflow,
      Sc2106ExecutionProperties properties,
      CandidateHostLauncher host,
      CandidateAttemptStore attempts) {
    this.workflow = workflow;
    this.properties = properties;
    this.host = host;
    this.attempts = attempts;
  }

  /**
   * Test seam for the SC-2106 R6 fault matrix: every later job carries this fault to the runner,
   * which accepts it only for a development worker. No request or endpoint can set it.
   */
  void injectFault(Map<String, Object> fault) {
    this.fault = fault;
  }

  /**
   * An attempt whose owner went away (process exit, crash) cannot be known to have done nothing, so
   * its expired lease is reconciled to UNCERTAIN; it is never relaunched automatically.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void reconcileAbandonedAttempts() {
    if (properties != null && properties.isEnabled() && attempts != null) {
      attempts.reconcileExpired(Instant.now());
    }
  }

  /**
   * What the caller chooses for one run. Everything about the candidate itself comes from the
   * registration, never from here.
   *
   * @param task {@code taskKey} and {@code settings}; the runner re-derives the task hash and
   *     refuses a mismatch before dispatch
   * @param start the start manifest and facts the runner opens the session from
   */
  public record JobSpec(
      String jobId,
      String idempotencyKey,
      String baselineId,
      String scenarioId,
      String checkpointId,
      String contentVersion,
      String cacheVersion,
      String profileId,
      String recordingId,
      String initialProvenance,
      Map<String, Object> task,
      String taskHash,
      Map<String, Object> start,
      String executionMode,
      String worldSeed,
      String taskSeed,
      String mouseSeed,
      Budgets budgets) {}

  /** The request budgets; {@code -1} means unbounded. */
  public record Budgets(
      long maxSteps,
      long maxTicks,
      long maxIntents,
      long maxWallTimeMillis,
      long maxMemoryBytes,
      long maxOutputBytes) {}

  /**
   * The finished job. {@code outcome} is the validated meaning of the result; {@code certifiable}
   * also requires the host's certification verdict and a certifiable worker.
   */
  public record ExecutionJob(
      String jobId,
      String attemptId,
      String requestDigest,
      Map<String, Object> request,
      CandidateExecutionResultValidator.Outcome outcome,
      List<String> reasons,
      boolean certifiable,
      Map<String, Object> result,
      Map<String, Object> hostOutcome) {}

  /** Builds the request, stages the candidate and runs it to a validated outcome. */
  public ExecutionJob execute(String proposalId, JobSpec spec) {
    return run(proposalId, spec, null);
  }

  /**
   * Reserves the job and returns its status at once; the run continues in the background (R9.2). A
   * repeated submission with the same inputs returns the existing attempt's status and never
   * launches again; changed inputs conflict.
   */
  public JobStatus submit(String proposalId, JobSpec spec) {
    return start(proposalId, spec, null);
  }

  /**
   * As {@link #submit}, for an explicit recovery of the attempt named by {@code
   * previousIdempotencyKey} (R6). A recovery of a paused attempt starts paused.
   */
  public JobStatus submitRecovery(String proposalId, String previousIdempotencyKey, JobSpec spec) {
    CandidateAttemptStore.Attempt previous = attemptByKey(proposalId, previousIdempotencyKey);
    return start(proposalId, spec, recoveryFrom(proposalId, previous.attemptId(), spec));
  }

  /**
   * Explicitly recovers an attempt that ended UNCERTAIN or FAILED (SC-2106 R6). The recovery is a
   * new, separately fenced attempt under a new idempotency key; it resumes from the previous
   * attempt's last durable cut, which the runner verifies before any intent. Nothing is ever
   * recovered automatically.
   */
  public ExecutionJob recover(String proposalId, String previousAttemptId, JobSpec spec) {
    return run(proposalId, spec, recoveryFrom(proposalId, previousAttemptId, spec));
  }

  /** Checks that {@code previousAttemptId} may be recovered and names its durable journal. */
  private Map<String, Object> recoveryFrom(
      String proposalId, String previousAttemptId, JobSpec spec) {
    if (attempts == null) {
      throw failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "EXECUTION_OWNERSHIP_UNAVAILABLE",
          "no durable attempt store is configured");
    }
    // An owner that died, or could not record its result, leaves the attempt open until its lease
    // runs out; reconciling first makes it UNCERTAIN here instead of only at the next startup.
    attempts.reconcileExpired(Instant.now());
    CandidateAttemptStore.Attempt previous =
        attempts
            .find(previousAttemptId == null ? "" : previousAttemptId)
            .orElseThrow(
                () ->
                    failure(
                        HttpStatus.NOT_FOUND,
                        "EXECUTION_ATTEMPT_NOT_FOUND",
                        "no attempt " + previousAttemptId));
    if (!previous.ownerId().equals("proposal:" + proposalId)) {
      throw failure(
          HttpStatus.CONFLICT,
          "EXECUTION_RECOVERY_FOREIGN",
          "attempt " + previousAttemptId + " belongs to another proposal");
    }
    if (previous.state() != CandidateAttemptStore.State.UNCERTAIN
        && previous.state() != CandidateAttemptStore.State.FAILED) {
      throw failure(
          HttpStatus.CONFLICT,
          "EXECUTION_RECOVERY_NOT_NEEDED",
          "attempt " + previousAttemptId + " is " + previous.state());
    }
    if (spec != null && previous.idempotencyKey().equals(spec.idempotencyKey())) {
      throw failure(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "EXECUTION_JOB_INVALID",
          "a recovery is a new attempt and needs its own idempotencyKey");
    }
    Path journal;
    try {
      journal =
          directory(properties.getRunRoot(), "runRoot")
              .resolve(previousAttemptId)
              .resolve("journal");
    } catch (IOException e) {
      throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "EXECUTION_STAGING_FAILED", e.getMessage());
    }
    if (!Files.isDirectory(journal, LinkOption.NOFOLLOW_LINKS)) {
      throw failure(
          HttpStatus.CONFLICT,
          "EXECUTION_RECOVERY_UNAVAILABLE",
          "attempt " + previousAttemptId + " published no durable journal");
    }
    Map<String, Object> resume = new LinkedHashMap<>();
    resume.put("root", journal.toString());
    resume.put("attemptId", previousAttemptId);
    return resume;
  }

  private ExecutionJob run(String proposalId, JobSpec spec, Map<String, Object> resume) {
    Admitted admitted = admit(proposalId, spec, resume);
    if (!admitted.reservation().created()) {
      return reattach(admitted.reservation().attempt(), admitted.fingerprint(), spec);
    }
    CandidateAttemptStore.Attempt attempt = admitted.reservation().attempt();
    return launch(proposalId, spec, resume, attempt, own(attempt, spec, admitted));
  }

  private JobStatus start(String proposalId, JobSpec spec, Map<String, Object> resume) {
    Admitted admitted = admit(proposalId, spec, resume);
    CandidateAttemptStore.Attempt attempt = admitted.reservation().attempt();
    if (!admitted.reservation().created()) {
      if (!attempt.fingerprint().equals(admitted.fingerprint())) {
        throw idempotencyConflict(spec);
      }
      return status(attempt);
    }
    Live live = own(attempt, spec, admitted);
    try {
      jobs()
          .execute(
              () -> {
                try {
                  launch(proposalId, spec, resume, attempt, live);
                } catch (RuntimeException e) {
                  // The attempt record already says what happened; nothing is retried here.
                }
              });
    } catch (RuntimeException e) {
      release(live, attempt);
      settleBeforeLaunch(attempt, null, "EXECUTION_QUEUE_UNAVAILABLE: " + e.getMessage());
      throw failure(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_QUEUE_UNAVAILABLE", e.getMessage());
    }
    return status(attempt);
  }

  /** What admission produced: the reservation, its lease and the inputs' fingerprint. */
  private record Admitted(
      CandidateAttemptStore.Reservation reservation, Duration lease, String fingerprint) {}

  /** Validates the job and reserves (or finds) its attempt. Nothing is staged or launched. */
  private Admitted admit(String proposalId, JobSpec spec, Map<String, Object> resume) {
    if (properties == null || !properties.isEnabled()) {
      throw failure(
          HttpStatus.FORBIDDEN,
          "CANDIDATE_EXECUTION_DISABLED",
          "candidate execution is disabled (sc2106.execution.enabled=false)");
    }
    if (spec == null || spec.jobId() == null || spec.jobId().isBlank()) {
      throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "EXECUTION_JOB_INVALID", "jobId is required");
    }
    if (causal(spec) && !PINNED_PROFILE.matcher(String.valueOf(spec.profileId())).matches()) {
      throw failure(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "EXECUTION_PROFILE_UNPINNED",
          "a causal run needs profileId as a pinned SC-1914 profile reference"
              + " <profileId>@<revision>#sha256:<hex>, got "
              + spec.profileId());
    }
    if (spec.idempotencyKey() == null || spec.idempotencyKey().isBlank()) {
      throw failure(
          HttpStatus.UNPROCESSABLE_ENTITY, "EXECUTION_JOB_INVALID", "idempotencyKey is required");
    }
    if (attempts == null) {
      throw failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "EXECUTION_OWNERSHIP_UNAVAILABLE",
          "no durable attempt store is configured");
    }
    Duration lease = attemptLease();
    CandidateAttemptStore.Reservation reservation;
    try {
      reservation =
          resume == null
              ? attempts.reserve(
                  "proposal:" + proposalId,
                  spec.idempotencyKey(),
                  spec.jobId(),
                  reservationFingerprint(proposalId, spec, null),
                  lease)
              : attempts.reserveRecovery(
                  "proposal:" + proposalId,
                  spec.idempotencyKey(),
                  spec.jobId(),
                  reservationFingerprint(proposalId, spec, resume),
                  lease,
                  String.valueOf(resume.get("attemptId")));
    } catch (CandidateAttemptStore.RecoveryClaimedException e) {
      // Two recoveries can never both continue one attempt (R6.5).
      throw failure(
          HttpStatus.CONFLICT,
          "EXECUTION_RECOVERY_CLAIMED",
          e.getMessage() + "; recover that attempt instead if it is uncertain");
    }
    if (reservation.created()
        && resume != null
        && attempts.control(String.valueOf(resume.get("attemptId"))).pauseRequested()) {
      // Restored work stays paused until it is explicitly resumed (R4.4).
      attempts.requestPause(reservation.attempt().attemptId(), true);
    }
    return new Admitted(reservation, lease, reservationFingerprint(proposalId, spec, resume));
  }

  /** Registers a run this process now owns and starts its lease and control tick. */
  private Live own(CandidateAttemptStore.Attempt attempt, JobSpec spec, Admitted admitted) {
    Live live = new Live(spec.jobId());
    active.put(attempt.attemptId(), live);
    live.tick = controlWhileOwned(attempt, admitted.lease(), live);
    return live;
  }

  private void release(Live live, CandidateAttemptStore.Attempt attempt) {
    if (live.tick != null) {
      live.tick.cancel(false);
    }
    active.remove(attempt.attemptId(), live);
  }

  private ExecutionJob launch(
      String proposalId,
      JobSpec spec,
      Map<String, Object> resume,
      CandidateAttemptStore.Attempt attempt,
      Live live) {
    String attemptId = attempt.attemptId();
    CandidateExecutionRequestV2 request = null;
    try {
      CandidateAttemptStore.Control control = attempts.control(attemptId);
      if (control.cancelRequested() || live.cancel.get()) {
        // Cancelled while queued: nothing was staged or launched.
        ExecutionJob cancelled =
            new ExecutionJob(
                spec.jobId(),
                attemptId,
                null,
                null,
                CandidateExecutionResultValidator.Outcome.CANCELLED,
                List.of("CANCELLED_BEFORE_LAUNCH"),
                false,
                null,
                null);
        attempts.finish(
            attemptId,
            attempt.leaseId(),
            attempt.epoch(),
            CandidateAttemptStore.State.CANCELLED,
            mapper.convertValue(cancelled, MAP),
            "cancelled before launch");
        return cancelled;
      }
      live.pause.set(control.pauseRequested());
      PatchCandidateWorkflowService.RegisteredCandidate candidate =
          workflow.registeredCandidate(proposalId);
      Staged staged = stage(candidate);
      request = request(staged, spec, attemptId);
      Path runDirectory = directory(properties.getRunRoot(), "runRoot").resolve(attemptId);
      Files.createDirectory(runDirectory);
      Map<String, Object> job = job(request, spec, runDirectory);
      if (resume != null) {
        job.put("resume", resume);
      }
      if (live.pause.get()) {
        // The runner parks before its first step until it is resumed (R9.2).
        job.put("startPaused", true);
      }
      // Persist the launch identity before any effect; a fenced owner launches nothing.
      attempts.markRunning(attemptId, attempt.leaseId(), attempt.epoch(), request.requestDigest());
      CandidateHostLauncher.HostOutcome outcome =
          launcher()
              .run(
                  job,
                  properties.getHostTimeout(),
                  new CandidateHostLauncher.HostControl(
                      live.cancel, live.pause, paused -> observePause(attempt, live, paused)));
      ExecutionJob result = interpret(spec.jobId(), attemptId, request, outcome);
      try {
        attempts.finish(
            attemptId,
            attempt.leaseId(),
            attempt.epoch(),
            stateOf(result.outcome()),
            mapper.convertValue(result, MAP),
            String.join("; ", result.reasons()));
      } catch (CandidateAttemptStore.StaleOwnerException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new ResultUnrecorded(attemptId, e);
      }
      return result;
    } catch (ResultUnrecorded e) {
      // The run happened but its result is not durable (R6 metadata commit). It is not reported
      // as final and the attempt is not settled here: it stays open until its lease runs out, then
      // becomes UNCERTAIN, and an explicit recovery resumes from its sealed journal.
      throw failure(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_RESULT_UNRECORDED", e.getMessage());
    } catch (CandidateAttemptStore.StaleOwnerException e) {
      // Another owner (or reconciliation) holds this attempt now; never report our view as final.
      throw failure(HttpStatus.CONFLICT, "EXECUTION_OWNER_FENCED", e.getMessage());
    } catch (IOException e) {
      settleBeforeLaunch(attempt, request, "EXECUTION_STAGING_FAILED: " + e.getMessage());
      throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "EXECUTION_STAGING_FAILED", e.getMessage());
    } catch (IllegalArgumentException e) {
      settleBeforeLaunch(attempt, request, "EXECUTION_REQUEST_INVALID: " + e.getMessage());
      throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "EXECUTION_REQUEST_INVALID", e.getMessage());
    } catch (PatchCandidateWorkflowService.PatchWorkflowException e) {
      settleBeforeLaunch(attempt, request, e.code() + ": " + e.getMessage());
      throw e;
    } finally {
      release(live, attempt);
    }
  }

  /** Records that the runner parked or resumed; a fenced owner stops its run instead. */
  private void observePause(CandidateAttemptStore.Attempt attempt, Live live, boolean paused) {
    try {
      attempts.recordPaused(attempt.attemptId(), attempt.leaseId(), attempt.epoch(), paused);
    } catch (CandidateAttemptStore.StaleOwnerException e) {
      live.cancel.set(true);
    } catch (RuntimeException e) {
      // Status shows the requested pause; the observed flag catches up on the next report.
    }
  }

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** A finished run whose result could not be written to the attempt store. */
  private static final class ResultUnrecorded extends RuntimeException {
    ResultUnrecorded(String attemptId, RuntimeException cause) {
      super("attempt " + attemptId + " ran but its result was not recorded: " + cause.getMessage());
    }
  }

  /**
   * A duplicate request reattaches to the attempt it names; it never launches a second run. Only a
   * finished attempt returns its recorded result.
   */
  private static String reservationFingerprint(
      String proposalId, JobSpec spec, Map<String, Object> resume) {
    return resume == null
        ? fingerprint(proposalId, spec)
        : Sc2106CanonicalJson.sha256(
            Map.of("recovers", resume.get("attemptId"), "spec", fingerprint(proposalId, spec)));
  }

  private ExecutionJob reattach(
      CandidateAttemptStore.Attempt attempt, String fingerprint, JobSpec spec) {
    if (!attempt.fingerprint().equals(fingerprint)) {
      throw idempotencyConflict(spec);
    }
    if (attempt.state() == CandidateAttemptStore.State.UNCERTAIN) {
      throw failure(
          HttpStatus.CONFLICT,
          "EXECUTION_RECONCILIATION_REQUIRED",
          "attempt " + attempt.attemptId() + " is uncertain: " + attempt.detail());
    }
    if (attempt.state().open()) {
      throw failure(
          HttpStatus.CONFLICT,
          "EXECUTION_JOB_ACTIVE",
          "attempt " + attempt.attemptId() + " is " + attempt.state() + " under another owner");
    }
    if (attempt.job() == null) {
      throw failure(
          HttpStatus.CONFLICT,
          "EXECUTION_JOB_UNAVAILABLE",
          "attempt " + attempt.attemptId() + " ended before launch: " + attempt.detail());
    }
    return mapper.convertValue(attempt.job(), ExecutionJob.class);
  }

  /** Nothing was launched, so the attempt ends FAILED; a fenced owner leaves it untouched. */
  private void settleBeforeLaunch(
      CandidateAttemptStore.Attempt attempt, CandidateExecutionRequestV2 request, String detail) {
    try {
      attempts.finish(
          attempt.attemptId(),
          attempt.leaseId(),
          attempt.epoch(),
          CandidateAttemptStore.State.FAILED,
          null,
          detail);
    } catch (CandidateAttemptStore.StaleOwnerException ignored) {
      // Reconciliation already owns it; its record stands.
    }
  }

  private static PatchCandidateWorkflowService.PatchWorkflowException idempotencyConflict(
      JobSpec spec) {
    return failure(
        HttpStatus.CONFLICT,
        "EXECUTION_IDEMPOTENCY_CONFLICT",
        "idempotencyKey " + spec.idempotencyKey() + " was used with different inputs");
  }

  /**
   * Every {@link #CONTROL_TICK} while this process owns the attempt (queued or running): applies
   * the attempt's durable cancel and pause requests to the run, and renews the lease every third of
   * its length. A lost lease means another owner may act, so the run is stopped.
   */
  private ScheduledFuture<?> controlWhileOwned(
      CandidateAttemptStore.Attempt attempt, Duration lease, Live live) {
    long renewEvery = Math.max(CONTROL_TICK.toMillis(), lease.toMillis() / 3);
    long[] nextRenewal = {System.currentTimeMillis() + renewEvery};
    return leaseRenewal.scheduleAtFixedRate(
        () -> {
          try {
            CandidateAttemptStore.Control control = attempts.control(attempt.attemptId());
            if (control.cancelRequested()) {
              live.cancel.set(true);
            }
            live.pause.set(control.pauseRequested());
            if (System.currentTimeMillis() >= nextRenewal[0]) {
              nextRenewal[0] = System.currentTimeMillis() + renewEvery;
              if (!attempts.renew(attempt.attemptId(), attempt.leaseId(), attempt.epoch(), lease)) {
                live.cancel.set(true);
              }
            }
          } catch (RuntimeException e) {
            // A failed read is retried next tick; an unrenewed lease ends in reconciliation.
          }
        },
        CONTROL_TICK.toMillis(),
        CONTROL_TICK.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private ExecutorService jobs() {
    ExecutorService pool = jobs;
    if (pool == null) {
      synchronized (this) {
        if (jobs == null) {
          int size = properties == null ? 1 : properties.getMaxConcurrentJobs();
          jobs =
              Executors.newFixedThreadPool(
                  size,
                  runnable -> {
                    Thread thread = new Thread(runnable, "sc2106-job");
                    thread.setDaemon(true);
                    return thread;
                  });
        }
        pool = jobs;
      }
    }
    return pool;
  }

  private Duration attemptLease() {
    Duration hostTimeout = properties.getHostTimeout();
    Duration lease = Duration.ofMinutes(2);
    return hostTimeout != null && hostTimeout.compareTo(lease) < 0 ? hostTimeout : lease;
  }

  private static CandidateAttemptStore.State stateOf(CandidateExecutionResultValidator.Outcome o) {
    return switch (o) {
      case PASS, BLOCKED -> CandidateAttemptStore.State.COMPLETED;
      case CANCELLED -> CandidateAttemptStore.State.CANCELLED;
      case UNCERTAIN -> CandidateAttemptStore.State.UNCERTAIN;
      case FAILED, INVALID -> CandidateAttemptStore.State.FAILED;
    };
  }

  /** Everything that decides what would run; a reused key with another fingerprint conflicts. */
  static String fingerprint(String proposalId, JobSpec spec) {
    Map<String, Object> inputs = new LinkedHashMap<>();
    inputs.put("proposalId", proposalId);
    inputs.put("jobId", spec.jobId());
    inputs.put("baselineId", spec.baselineId());
    inputs.put("scenarioId", spec.scenarioId());
    inputs.put("checkpointId", spec.checkpointId());
    inputs.put("contentVersion", spec.contentVersion());
    inputs.put("cacheVersion", spec.cacheVersion());
    inputs.put("profileId", spec.profileId());
    inputs.put("recordingId", spec.recordingId());
    inputs.put("initialProvenance", spec.initialProvenance());
    inputs.put("task", spec.task());
    inputs.put("taskHash", spec.taskHash());
    inputs.put("start", spec.start());
    inputs.put("executionMode", spec.executionMode());
    inputs.put("worldSeed", spec.worldSeed());
    inputs.put("taskSeed", spec.taskSeed());
    inputs.put("mouseSeed", spec.mouseSeed());
    CandidateExecutionJobService.Budgets b = spec.budgets();
    inputs.put(
        "budgets",
        b == null
            ? null
            : List.of(
                b.maxSteps(),
                b.maxTicks(),
                b.maxIntents(),
                b.maxWallTimeMillis(),
                b.maxMemoryBytes(),
                b.maxOutputBytes()));
    return Sc2106CanonicalJson.sha256(inputs);
  }

  /**
   * A content-addressed SC-1914 profile reference. It travels in the request's {@code profileId},
   * so the request digest covers the exact profile revision and digest; the runner and the worker
   * both re-check it.
   */
  static final Pattern PINNED_PROFILE =
      Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}@[1-9][0-9]{0,8}#sha256:[0-9a-f]{64}");

  private static boolean causal(JobSpec spec) {
    return spec.executionMode() == null || "CAUSAL_CANDIDATE".equals(spec.executionMode());
  }

  /** Compares a baseline and a candidate job only when their conditions are equivalent. */
  public CandidateExecutionComparison.Comparison compare(
      ExecutionJob baseline, ExecutionJob candidate) {
    return CandidateExecutionComparison.compare(baseline, candidate);
  }

  /** Asks a running job to stop; false when no such job is running here. */
  public boolean cancel(String jobId) {
    for (Live live : active.values()) {
      if (live.jobId.equals(jobId)) {
        live.cancel.set(true);
        return true;
      }
    }
    return false;
  }

  /** Where a job is in its life, as an SC-815 caller sees it (R9.2). */
  public enum Lifecycle {
    QUEUED,
    RUNNING,
    PAUSED,
    RECONCILING,
    COMPLETED,
    FAILED,
    CANCELLED
  }

  /**
   * The durable view of one job, rebuilt from the attempt store alone, so it reads the same after a
   * refresh, from another process, or after a restart.
   *
   * @param lifecycle RECONCILING covers an uncertain attempt and an open one whose owner's lease
   *     ran out; neither is ever relaunched automatically
   * @param resultRefs the result's {@code resultDigest}, {@code journalRef} and {@code
   *     decisionsRef}, once there is a result
   */
  public record JobStatus(
      String attemptId,
      String jobId,
      String idempotencyKey,
      Lifecycle lifecycle,
      CandidateAttemptStore.State attemptState,
      boolean cancelRequested,
      boolean pauseRequested,
      boolean paused,
      Instant leaseExpiresAt,
      String requestDigest,
      String outcome,
      List<String> reasons,
      Map<String, Object> resultRefs,
      String detail) {}

  /** The status of the job a proposal submitted under {@code idempotencyKey}. */
  public JobStatus status(String proposalId, String idempotencyKey) {
    return status(attemptByKey(proposalId, idempotencyKey));
  }

  /** Records a durable cancel request; the owning process stops the run on its next tick. */
  public JobStatus requestCancel(String proposalId, String idempotencyKey) {
    CandidateAttemptStore.Attempt attempt = attemptByKey(proposalId, idempotencyKey);
    if (!attempts.requestCancel(attempt.attemptId())) {
      throw notActive(attempt);
    }
    Live live = active.get(attempt.attemptId());
    if (live != null) {
      live.cancel.set(true);
    }
    return status(attempts.find(attempt.attemptId()).orElse(attempt));
  }

  /** Records a durable pause ({@code true}) or resume; the run parks or continues between steps. */
  public JobStatus requestPause(String proposalId, String idempotencyKey, boolean pause) {
    CandidateAttemptStore.Attempt attempt = attemptByKey(proposalId, idempotencyKey);
    if (!attempts.requestPause(attempt.attemptId(), pause)) {
      throw notActive(attempt);
    }
    Live live = active.get(attempt.attemptId());
    if (live != null) {
      live.pause.set(pause);
    }
    return status(attempts.find(attempt.attemptId()).orElse(attempt));
  }

  private CandidateAttemptStore.Attempt attemptByKey(String proposalId, String idempotencyKey) {
    if (attempts == null) {
      throw failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "EXECUTION_OWNERSHIP_UNAVAILABLE",
          "no durable attempt store is configured");
    }
    return attempts
        .findByKey("proposal:" + proposalId, idempotencyKey == null ? "" : idempotencyKey)
        .orElseThrow(
            () ->
                failure(
                    HttpStatus.NOT_FOUND,
                    "EXECUTION_JOB_NOT_FOUND",
                    "no job " + idempotencyKey + " for proposal " + proposalId));
  }

  private static PatchCandidateWorkflowService.PatchWorkflowException notActive(
      CandidateAttemptStore.Attempt attempt) {
    return failure(
        HttpStatus.CONFLICT,
        "EXECUTION_JOB_NOT_ACTIVE",
        "attempt " + attempt.attemptId() + " is " + attempt.state());
  }

  JobStatus status(CandidateAttemptStore.Attempt attempt) {
    CandidateAttemptStore.Control control = attempts.control(attempt.attemptId());
    Map<String, Object> job = attempt.job();
    Map<String, Object> refs = new LinkedHashMap<>();
    if (job != null && job.get("result") instanceof Map<?, ?> result) {
      for (String ref : List.of("resultDigest", "journalRef", "decisionsRef")) {
        if (result.get(ref) != null) {
          refs.put(ref, result.get(ref));
        }
      }
    }
    List<String> reasons = new ArrayList<>();
    if (job != null && job.get("reasons") instanceof List<?> listed) {
      for (Object reason : listed) {
        reasons.add(String.valueOf(reason));
      }
    }
    return new JobStatus(
        attempt.attemptId(),
        attempt.jobId(),
        attempt.idempotencyKey(),
        lifecycle(attempt, control, Instant.now()),
        attempt.state(),
        control.cancelRequested(),
        control.pauseRequested(),
        control.paused(),
        attempt.leaseExpiresAt(),
        attempt.requestDigest(),
        job == null || job.get("outcome") == null ? null : String.valueOf(job.get("outcome")),
        reasons,
        refs,
        attempt.detail());
  }

  static Lifecycle lifecycle(
      CandidateAttemptStore.Attempt attempt, CandidateAttemptStore.Control control, Instant now) {
    if (attempt.state().open()
        && attempt.leaseExpiresAt() != null
        && attempt.leaseExpiresAt().isBefore(now)) {
      return Lifecycle.RECONCILING;
    }
    return switch (attempt.state()) {
      case RESERVED -> Lifecycle.QUEUED;
      case RUNNING -> control.paused() ? Lifecycle.PAUSED : Lifecycle.RUNNING;
      case UNCERTAIN -> Lifecycle.RECONCILING;
      case COMPLETED -> Lifecycle.COMPLETED;
      case FAILED -> Lifecycle.FAILED;
      case CANCELLED -> Lifecycle.CANCELLED;
    };
  }

  /** The verified bundle bytes, the parsed signed documents and their content digests. */
  record Staged(JsonNode manifest, JsonNode closure, String manifestRef, String closureRef) {}

  /**
   * Copies every bundle file into the content-addressed store. An existing entry is reused only
   * when its bytes hash to its name.
   */
  Staged stage(PatchCandidateWorkflowService.RegisteredCandidate candidate) throws IOException {
    Path bundle = candidate.bundle();
    Path store = directory(properties.getStoreRoot(), "storeRoot").resolve("sha256");
    Files.createDirectories(store);
    byte[] manifestBytes = Files.readAllBytes(bundle.resolve("manifest.json"));
    byte[] closureBytes = Files.readAllBytes(bundle.resolve("execution-closure.json"));
    JsonNode closure = Sc967CanonicalJson.parse(closureBytes);
    List<String> files = new ArrayList<>();
    files.add("certification.json");
    files.add(candidate.jarName());
    files.add(candidate.sbomName());
    files.add(closure.path("package").path("name").asText());
    files.add(closure.path("core").path("name").asText());
    for (JsonNode dependency : closure.path("dependencies")) {
      files.add(dependency.path("name").asText());
    }
    String manifestRef = put(store, manifestBytes);
    String closureRef = put(store, closureBytes);
    for (String name : files) {
      Path file = bundle.resolve(name).normalize();
      if (name.isBlank() || !file.getParent().equals(bundle.normalize())) {
        throw new IOException("BUNDLE_ENTRY_INVALID: " + name);
      }
      put(store, Files.readAllBytes(file));
    }
    return new Staged(Sc967CanonicalJson.parse(manifestBytes), closure, manifestRef, closureRef);
  }

  CandidateExecutionRequestV2 request(Staged staged, JobSpec spec, String attemptId) {
    JsonNode manifest = staged.manifest();
    JsonNode closure = staged.closure();
    List<String> dependencies = new ArrayList<>();
    for (JsonNode dependency : closure.path("dependencies")) {
      dependencies.add(dependency.path("sha256").asText());
    }
    Map<String, Object> settings = settings(spec.task());
    Budgets budgets = spec.budgets();
    return CandidateExecutionRequestV2.builder()
        .requestId("request-" + attemptId.substring("attempt-".length()))
        .idempotencyKey(spec.idempotencyKey())
        .certificationRunId(spec.jobId())
        .attemptId(attemptId)
        .jobId(spec.jobId())
        .proposalDigest(manifest.path("proposal_hash").asText(null))
        .approvalDigest(manifest.path("approval_hash").asText(null))
        .baseCommit(manifest.path("base_commit").asText(null))
        .candidateDigest(manifest.path("candidate").path("candidate_hash").asText(null))
        .packageDigest(closure.path("package").path("sha256").asText(null))
        .coreDigest(closure.path("core").path("sha256").asText(null))
        .signedManifestRef(staged.manifestRef())
        .executionClosureRef(staged.closureRef())
        .verifierPolicyVersion(properties.getVerifierPolicyVersion())
        .taskCoreEntry(closure.path("task_core_entry").asText(null))
        .runnerVersion(closure.path("runner_version").asText(null))
        .adapterVersion(closure.path("adapter_version").asText(null))
        .protocolVersion(closure.path("protocol_version").asText(null))
        .dependencyClosure(dependencies)
        .baselineId(spec.baselineId())
        .scenarioId(spec.scenarioId())
        .checkpointId(spec.checkpointId())
        .contentVersion(spec.contentVersion())
        .cacheVersion(spec.cacheVersion())
        .profileId(spec.profileId())
        .recordingId(spec.recordingId())
        .initialProvenance(spec.initialProvenance())
        .taskHash(spec.taskHash())
        .settingsHash(Sc2106CanonicalJson.sha256(settings))
        .executionMode(
            spec.executionMode() == null
                ? CandidateExecutionRequestV2.CAUSAL_CANDIDATE
                : spec.executionMode())
        .worldSeed(spec.worldSeed())
        .taskSeed(spec.taskSeed())
        .mouseSeed(spec.mouseSeed())
        .maxSteps(budgets == null ? -1L : budgets.maxSteps())
        .maxTicks(budgets == null ? -1L : budgets.maxTicks())
        .maxIntents(budgets == null ? -1L : budgets.maxIntents())
        .maxWallTimeMillis(budgets == null ? -1L : budgets.maxWallTimeMillis())
        .maxMemoryBytes(budgets == null ? -1L : budgets.maxMemoryBytes())
        .maxOutputBytes(budgets == null ? -1L : budgets.maxOutputBytes())
        .build();
  }

  private Map<String, Object> job(
      CandidateExecutionRequestV2 request, JobSpec spec, Path runDirectory) throws IOException {
    Map<String, Object> worker = new LinkedHashMap<>();
    worker.put("command", properties.getWorkerCommand());
    worker.put(
        "directory",
        properties.getWorkerDirectory().isBlank() ? null : properties.getWorkerDirectory());
    worker.put("developmentIdentity", properties.isDevelopmentWorker());
    Map<String, Object> job = new LinkedHashMap<>();
    // v2 carries pause control; a host without it refuses the job before launching (R9.2).
    job.put("schema", "sc2106-host-job/v2");
    job.put("request", request.toMap());
    job.put("task", spec.task());
    job.put("start", spec.start());
    job.put("worker", worker);
    job.put("storeRoot", directory(properties.getStoreRoot(), "storeRoot").toString());
    job.put("workspaceRoot", directory(properties.getWorkspaceRoot(), "workspaceRoot").toString());
    job.put("workingDirectory", runDirectory.toString());
    job.put("environment", properties.getChildEnvironment());
    if (fault != null) {
      job.put("fault", fault);
    }
    return job;
  }

  static ExecutionJob interpret(
      String jobId,
      String attemptId,
      CandidateExecutionRequestV2 request,
      CandidateHostLauncher.HostOutcome outcome) {
    Map<String, Object> document = outcome.document();
    if (document == null) {
      // A host that never received the job ran nothing; one that was lost may have.
      return new ExecutionJob(
          jobId,
          attemptId,
          request.requestDigest(),
          request.toMap(),
          outcome.lost()
              ? CandidateExecutionResultValidator.Outcome.UNCERTAIN
              : CandidateExecutionResultValidator.Outcome.FAILED,
          List.of(String.valueOf(outcome.detail())),
          false,
          null,
          null);
    }
    if (!"sc2106-host-outcome/v1".equals(document.get("schema"))) {
      return new ExecutionJob(
          jobId,
          attemptId,
          request.requestDigest(),
          request.toMap(),
          CandidateExecutionResultValidator.Outcome.UNCERTAIN,
          List.of("HOST_OUTCOME_SCHEMA_UNKNOWN"),
          false,
          null,
          document);
    }
    if (document.get("rejected") instanceof Map<?, ?> rejected) {
      // The host refuses a job before resolving it, so nothing was launched.
      return new ExecutionJob(
          jobId,
          attemptId,
          request.requestDigest(),
          request.toMap(),
          CandidateExecutionResultValidator.Outcome.FAILED,
          List.of("HOST_REJECTED:" + rejected.get("failureCode")),
          false,
          null,
          document);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        document.get("result") instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    CandidateExecutionResultValidator.Verdict verdict =
        CandidateExecutionResultValidator.validate(result, request);
    List<String> reasons = new ArrayList<>(verdict.reasons());
    boolean hostCertifiable = false;
    if (document.get("certification") instanceof Map<?, ?> certification) {
      hostCertifiable = Boolean.TRUE.equals(certification.get("certifiable"));
      if (certification.get("reasons") instanceof List<?> hostReasons) {
        for (Object reason : hostReasons) {
          String text = "HOST_CERTIFICATION:" + reason;
          if (!reasons.contains(text)) {
            reasons.add(text);
          }
        }
      }
    }
    return new ExecutionJob(
        jobId,
        attemptId,
        request.requestDigest(),
        request.toMap(),
        verdict.outcome(),
        reasons,
        verdict.certifiable() && hostCertifiable,
        result,
        document);
  }

  private CandidateHostLauncher launcher() throws IOException {
    if (host != null) {
      return host;
    }
    return new ProcessCandidateHostLauncher(
        properties.getHostCommand(),
        properties.getHostEnvironment(),
        directory(properties.getRunRoot(), "runRoot"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> settings(Map<String, Object> task) {
    if (task == null || !(task.get("settings") instanceof Map<?, ?> settings)) {
      throw new IllegalArgumentException("REQUEST_INVALID: task.settings is required");
    }
    return (Map<String, Object>) settings;
  }

  /** Writes {@code bytes} under its SHA-256 name; returns {@code sha256:<hex>}. */
  static String put(Path store, byte[] bytes) throws IOException {
    String hex = HexFormat.of().formatHex(Sc967CanonicalJson.digest(bytes));
    Path target = store.resolve(hex);
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      Path temporary = Files.createTempFile(store, ".staging-", ".tmp");
      try {
        Files.write(temporary, bytes);
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temporary, target);
        }
      } catch (FileAlreadyExistsException e) {
        // Another job stored the same bytes first; it is verified below like any existing entry.
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
    if (Files.isSymbolicLink(target)
        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        || !hex.equals(
            HexFormat.of().formatHex(Sc967CanonicalJson.digest(Files.readAllBytes(target))))) {
      throw new IOException("STORE_ENTRY_CORRUPT: " + hex);
    }
    return "sha256:" + hex;
  }

  private static Path directory(String configured, String name) throws IOException {
    if (configured == null || configured.isBlank()) {
      throw new IOException("sc2106.execution." + name + " is not configured");
    }
    Path path = Path.of(configured).toAbsolutePath().normalize();
    Files.createDirectories(path);
    return path;
  }

  private static PatchCandidateWorkflowService.PatchWorkflowException failure(
      HttpStatus status, String code, String message) {
    return new PatchCandidateWorkflowService.PatchWorkflowException(status, code, message);
  }
}
