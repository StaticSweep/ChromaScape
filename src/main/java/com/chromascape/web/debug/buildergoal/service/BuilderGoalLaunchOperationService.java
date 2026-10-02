package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryService.TargetClient;
import com.chromascape.web.debug.buildergoal.service.Micro6TargetRelaunchService.RelaunchProgress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * In-memory, credential-free launch-operation registry for asynchronous Builder Goal account
 * launches.
 *
 * <p>The browser-facing launch endpoint returns {@code HTTP 202} immediately and the operator polls
 * the operation through {@link #toResponse}. The long server-side readiness watchdog (90 minutes by
 * default) continues to run on a dedicated executor regardless of browser lifetime.
 *
 * <p>Concurrent requests for the same account are deduplicated atomically via {@code
 * ConcurrentHashMap.compute}. Terminal operations are retained for a configurable duration from
 * {@code completedAt}; active operations are never pruned by retention. An active-operation
 * watchdog transitions stuck operations to {@code FAILED} after a configurable duration.
 *
 * <p>Every mutation is performed through an immutable {@link OperationSnapshot} held in an {@link
 * AtomicReference}, so concurrent progress/terminal transitions can never produce a mixed view in
 * {@link #toResponse}. Terminal transitions (READY, FAILED, CONFLICT) are mutually exclusive and
 * monotonic.
 */
@Service
public class BuilderGoalLaunchOperationService {

  private static final Logger logger =
      LoggerFactory.getLogger(BuilderGoalLaunchOperationService.class);

  public static final String STATUS_QUEUED = "QUEUED";
  public static final String STATUS_CHECKING_EXISTING = "CHECKING_EXISTING";
  public static final String STATUS_REUSING_EXECUTOR = "REUSING_EXECUTOR";
  public static final String STATUS_STOPPING_EXISTING = "STOPPING_EXISTING";
  public static final String STATUS_PREPARING_BUILD = "PREPARING_BUILD";
  public static final String STATUS_BUILDING_MICRO6 = "BUILDING_MICRO6";
  public static final String STATUS_LAUNCHING_PROCESS = "LAUNCHING_PROCESS";
  public static final String STATUS_WAITING_FOR_PORT = "WAITING_FOR_PORT";
  public static final String STATUS_WAITING_FOR_HEALTH = "WAITING_FOR_HEALTH";
  public static final String STATUS_WAITING_FOR_ACCOUNT = "WAITING_FOR_ACCOUNT";
  public static final String STATUS_WAITING_FOR_WORLD = "WAITING_FOR_WORLD";
  public static final String STATUS_WAITING_FOR_LOGIN = "WAITING_FOR_LOGIN";
  public static final String STATUS_WAITING_FOR_DISPATCH = "WAITING_FOR_DISPATCH";
  public static final String STATUS_READY = "READY";
  public static final String STATUS_CONFLICT = "CONFLICT";
  public static final String STATUS_FAILED = "FAILED";

  static final Duration DEFAULT_TERMINAL_RETENTION = Duration.ofMinutes(30);
  static final Duration DEFAULT_ACTIVE_WATCHDOG_MS = Duration.ofMillis(5_700_000);

  private static final String STATUS_URL_PREFIX = "/api/debug/builder-goals/launch-operations/";

  private final ConcurrentHashMap<String, LaunchOperation> operations = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> activeByAccount = new ConcurrentHashMap<>();

  private final Duration terminalRetention;
  private final Duration activeWatchdogMs;

  public BuilderGoalLaunchOperationService(
      @Value("${builder-goal.launch.terminal-retention-ms:1800000}") long terminalRetentionMs,
      @Value("${builder-goal.launch.active-watchdog-ms:5700000}") long activeWatchdogMs) {
    if (terminalRetentionMs <= 0) {
      throw new IllegalStateException(
          "builder-goal.launch.terminal-retention-ms must be a positive number but was "
              + terminalRetentionMs);
    }
    if (activeWatchdogMs <= 0) {
      throw new IllegalStateException(
          "builder-goal.launch.active-watchdog-ms must be a positive number but was "
              + activeWatchdogMs);
    }
    this.terminalRetention = Duration.ofMillis(terminalRetentionMs);
    this.activeWatchdogMs = Duration.ofMillis(activeWatchdogMs);
  }

  /** No-arg constructor for tests that rely on reflection or manual instantiation. */
  BuilderGoalLaunchOperationService() {
    this.terminalRetention = DEFAULT_TERMINAL_RETENTION;
    this.activeWatchdogMs = DEFAULT_ACTIVE_WATCHDOG_MS;
  }

  public record CreateResult(LaunchOperation operation, boolean deduplicated) {}

  /**
   * Immutable snapshot of the mutable state of a launch operation. Stored in an {@link
   * AtomicReference} so that concurrent progress/terminal transitions never produce a mixed view.
   */
  public static final class OperationSnapshot {
    private final Instant updatedAt;
    private final String status;
    private final String message;
    private final String readinessPhase;
    private final String readinessReason;
    private final boolean reusedExecutor;
    private final boolean terminal;
    private final boolean success;
    private final Instant completedAt;
    private final String launcherLogFile;
    private final String launchArgsPreview;
    private final String localExecutorBaseUrl;
    private final String failureKind;
    private final String failureDetail;
    private final String attemptId;
    private final Map<String, Object> targetMetadata;
    private final List<Map<String, Object>> phaseHistory;

    private OperationSnapshot(Builder builder) {
      this.updatedAt = builder.updatedAt;
      this.status = builder.status;
      this.message = builder.message;
      this.readinessPhase = builder.readinessPhase;
      this.readinessReason = builder.readinessReason;
      this.reusedExecutor = builder.reusedExecutor;
      this.terminal = builder.terminal;
      this.success = builder.success;
      this.completedAt = builder.completedAt;
      this.launcherLogFile = builder.launcherLogFile;
      this.launchArgsPreview = builder.launchArgsPreview;
      this.localExecutorBaseUrl = builder.localExecutorBaseUrl;
      this.failureKind = builder.failureKind;
      this.failureDetail = builder.failureDetail;
      this.attemptId = builder.attemptId;
      // Deep-copy, sanitize, and freeze so a caller can never mutate a stored snapshot (or the
      // response it backs) without a CAS transition.
      this.targetMetadata =
          builder.targetMetadata == null
              ? Map.of()
              : (Map<String, Object>)
                  BuilderGoalSensitiveTextSanitizer.sanitizeAndFreeze(builder.targetMetadata);
      this.phaseHistory =
          builder.phaseHistory == null
              ? List.of()
              : (List<Map<String, Object>>)
                  BuilderGoalSensitiveTextSanitizer.sanitizeAndFreeze(builder.phaseHistory);
    }

    static OperationSnapshot initial() {
      return new Builder()
          .updatedAt(Instant.now())
          .status(STATUS_QUEUED)
          .message("Launch queued")
          .readinessPhase(STATUS_QUEUED)
          .targetMetadata(Map.of())
          .phaseHistory(List.of())
          .build();
    }

    Builder toBuilder() {
      return new Builder(this);
    }

    public Instant updatedAt() {
      return updatedAt;
    }

    public String status() {
      return status;
    }

    public String message() {
      return message;
    }

    public String readinessPhase() {
      return readinessPhase;
    }

    public String readinessReason() {
      return readinessReason;
    }

    public boolean reusedExecutor() {
      return reusedExecutor;
    }

    public boolean terminal() {
      return terminal;
    }

    public boolean success() {
      return success;
    }

    public Instant completedAt() {
      return completedAt;
    }

    public String launcherLogFile() {
      return launcherLogFile;
    }

    public String launchArgsPreview() {
      return launchArgsPreview;
    }

    public String localExecutorBaseUrl() {
      return localExecutorBaseUrl;
    }

    public String failureKind() {
      return failureKind;
    }

    public String failureDetail() {
      return failureDetail;
    }

    public String attemptId() {
      return attemptId;
    }

    public Map<String, Object> targetMetadata() {
      return targetMetadata;
    }

    public List<Map<String, Object>> phaseHistory() {
      return phaseHistory;
    }
  }

  /** Mutable builder used internally to produce immutable snapshots. */
  private static final class Builder {
    private Instant updatedAt;
    private String status;
    private String message;
    private String readinessPhase;
    private String readinessReason;
    private boolean reusedExecutor;
    private boolean terminal;
    private boolean success;
    private Instant completedAt;
    private String launcherLogFile;
    private String launchArgsPreview;
    private String localExecutorBaseUrl;
    private String failureKind;
    private String failureDetail;
    private String attemptId;
    private Map<String, Object> targetMetadata = Map.of();
    private List<Map<String, Object>> phaseHistory = List.of();

    private Builder() {}

    private Builder(OperationSnapshot source) {
      this.updatedAt = source.updatedAt;
      this.status = source.status;
      this.message = source.message;
      this.readinessPhase = source.readinessPhase;
      this.readinessReason = source.readinessReason;
      this.reusedExecutor = source.reusedExecutor;
      this.terminal = source.terminal;
      this.success = source.success;
      this.completedAt = source.completedAt;
      this.launcherLogFile = source.launcherLogFile;
      this.launchArgsPreview = source.launchArgsPreview;
      this.localExecutorBaseUrl = source.localExecutorBaseUrl;
      this.failureKind = source.failureKind;
      this.failureDetail = source.failureDetail;
      this.attemptId = source.attemptId;
      this.targetMetadata = source.targetMetadata;
      this.phaseHistory = source.phaseHistory;
    }

    Builder updatedAt(Instant value) {
      this.updatedAt = value;
      return this;
    }

    Builder status(String value) {
      this.status = value;
      return this;
    }

    Builder message(String value) {
      this.message = value;
      return this;
    }

    Builder readinessPhase(String value) {
      this.readinessPhase = value;
      return this;
    }

    Builder readinessReason(String value) {
      this.readinessReason = value;
      return this;
    }

    Builder reusedExecutor(boolean value) {
      this.reusedExecutor = value;
      return this;
    }

    Builder terminal(boolean value) {
      this.terminal = value;
      return this;
    }

    Builder success(boolean value) {
      this.success = value;
      return this;
    }

    Builder completedAt(Instant value) {
      this.completedAt = value;
      return this;
    }

    Builder launcherLogFile(String value) {
      this.launcherLogFile = value;
      return this;
    }

    Builder launchArgsPreview(String value) {
      this.launchArgsPreview = value;
      return this;
    }

    Builder localExecutorBaseUrl(String value) {
      this.localExecutorBaseUrl = value;
      return this;
    }

    Builder failureKind(String value) {
      this.failureKind = value;
      return this;
    }

    Builder failureDetail(String value) {
      this.failureDetail = value;
      return this;
    }

    Builder attemptId(String value) {
      this.attemptId = value;
      return this;
    }

    Builder targetMetadata(Map<String, Object> value) {
      this.targetMetadata = value != null ? value : Map.of();
      return this;
    }

    Builder phaseHistory(List<Map<String, Object>> value) {
      this.phaseHistory = value != null ? value : List.of();
      return this;
    }

    OperationSnapshot build() {
      return new OperationSnapshot(this);
    }
  }

  /**
   * Immutable identity of a launch operation. The mutable state lives in the {@link
   * AtomicReference} snapshot.
   */
  public static class LaunchOperation {
    private final String operationId;
    private final String accountId;
    private final String accountHash;
    private final String accountName;
    private final String launcherMachineUuid;
    private final Integer world;
    private final boolean packetCapture;
    private final Instant createdAt;
    private final AtomicReference<OperationSnapshot> snapshot;

    private LaunchOperation(
        String operationId,
        String accountId,
        String accountHash,
        String accountName,
        String launcherMachineUuid,
        Integer world, boolean packetCapture) {
      this.operationId = operationId;
      this.accountId = accountId;
      this.accountHash = accountHash;
      this.accountName = accountName;
      this.launcherMachineUuid = launcherMachineUuid;
      this.world = world;
      this.packetCapture = packetCapture;
      this.createdAt = Instant.now();
      this.snapshot = new AtomicReference<>(OperationSnapshot.initial());
    }

    public String getOperationId() {
      return operationId;
    }

    public String getAccountId() {
      return accountId;
    }

    public String getAccountHash() {
      return accountHash;
    }

    public String getAccountName() {
      return accountName;
    }

    public String getLauncherMachineUuid() {
      return launcherMachineUuid;
    }

    public Integer getWorld() {
      return world;
    }

    public Instant getCreatedAt() {
      return createdAt;
    }

    public String getStatus() {
      return snapshot.get().status();
    }

    public String getMessage() {
      return snapshot.get().message();
    }

    public boolean isTerminal() {
      return snapshot.get().terminal();
    }

    public Instant getCompletedAt() {
      return snapshot.get().completedAt();
    }

    public Instant getUpdatedAt() {
      return snapshot.get().updatedAt();
    }

    OperationSnapshot snapshot() {
      return snapshot.get();
    }
  }

  /**
   * Creates a new launch operation or returns the existing active one for the same account, using
   * {@link ConcurrentHashMap#compute} for atomicity. Two simultaneous requests for one account
   * produce exactly one operation ID and one executor submission; all other callers receive {@code
   * deduplicated=true}. A retained terminal operation for the account is never deleted by a new
   * operation.
   */
  public CreateResult createOrReuse(
      String accountId,
      String accountHash,
      String accountName,
      String launcherMachineUuid,
      Integer world) {
    return createOrReuse(accountId, accountHash, accountName, launcherMachineUuid, world, false);
  }

  public CreateResult createOrReuse(String accountId, String accountHash, String accountName,
      String launcherMachineUuid, Integer world, boolean packetCapture) {
    pruneExpired();
    String accountKey = normalizeAccountKey(accountHash, accountId);

    String newOperationId = UUID.randomUUID().toString();
    boolean[] deduplicated = {false};

    String assigned =
        activeByAccount.compute(
            accountKey,
            (key, currentId) -> {
              if (currentId != null) {
                LaunchOperation existing = operations.get(currentId);
                if (existing != null && !existing.isTerminal()) {
                  if (existing.packetCapture != packetCapture) {
                    throw new IllegalStateException("An account launch with a different Packet Capture setting is already active");
                  }
                  deduplicated[0] = true;
                  return currentId;
                }
              }
              LaunchOperation operation =
                  new LaunchOperation(
                      newOperationId,
                      accountId,
                      accountHash,
                      accountName,
                      launcherMachineUuid,
                      world, packetCapture);
              operations.put(newOperationId, operation);
              return newOperationId;
            });

    LaunchOperation operation = operations.get(assigned);
    if (operation == null) {
      // The assigned mapping referenced an operation that was removed between compute and get;
      // clear the stale mapping and retry.
      activeByAccount.remove(accountKey, assigned);
      return createOrReuse(accountId, accountHash, accountName, launcherMachineUuid, world, packetCapture);
    }
    return new CreateResult(operation, deduplicated[0]);
  }

  public Optional<LaunchOperation> get(String operationId) {
    pruneExpired();
    if (operationId == null || operationId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(operations.get(operationId));
  }

  public LaunchOperation getOrThrow(String operationId) {
    return get(operationId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Launch operation not found or expired: " + operationId));
  }

  /** Active transition: may change progress/status only while the operation is not terminal. */
  public void updateStatus(String operationId, String status, String message) {
    activeTransition(
        operationId,
        current ->
            current.toBuilder()
                .status(status)
                .message(BuilderGoalSensitiveTextSanitizer.sanitize(message))
                .phaseHistory(appendPhaseHistory(current.phaseHistory(), status, message))
                .updatedAt(Instant.now())
                .build());
  }

  /** Feeds a relaunch progress callback into the operation record (never credentials). */
  public void updateProgress(String operationId, RelaunchProgress progress) {
    if (progress == null) {
      return;
    }
    activeTransition(
        operationId,
        current -> {
          String mappedStatus = statusForPhase(progress.phase());
          Builder builder = current.toBuilder();
          if (mappedStatus != null) {
            builder.status(mappedStatus);
          }
          builder.message(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.reason(), current.message())));
          builder.readinessPhase(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.phase(), current.readinessPhase())));
          builder.readinessReason(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.reason(), current.readinessReason())));
          if (progress.reusedExecutor()) {
            builder.reusedExecutor(true);
          }
          builder.launcherLogFile(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.launchLogFile(), current.launcherLogFile())));
          builder.launchArgsPreview(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.maskedLaunchArgs(), current.launchArgsPreview())));
          builder.localExecutorBaseUrl(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.localExecutorBaseUrl(), current.localExecutorBaseUrl())));
          builder.failureKind(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.launchFailureKind(), current.failureKind())));
          builder.failureDetail(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.launchFailureDetail(), current.failureDetail())));
          builder.attemptId(
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(progress.attemptId(), current.attemptId())));
          builder.phaseHistory(
              appendPhaseHistory(
                  current.phaseHistory(),
                  firstNonBlank(mappedStatus, progress.phase()),
                  progress.reason()));
          builder.updatedAt(Instant.now());
          return builder.build();
        });
  }

  /**
   * Terminal transition to READY. Returns true only when the active-to-READY transition occurred.
   */
  public boolean markReady(
      String operationId,
      TargetClient target,
      Map<String, Object> metadata,
      boolean reusedExecutor) {
    return terminalTransition(
        operationId,
        current -> {
          if (current.terminal()) {
            return null;
          }
          Map<String, Object> targetMetadata = buildSanitizedTargetMetadata(target, metadata);
          return current.toBuilder()
              .targetMetadata(targetMetadata)
              .status(STATUS_READY)
              .message("Micro6 local executor is dispatch-ready")
              .readinessPhase(STATUS_READY)
              .readinessReason(
                  firstNonBlank(stringValue(targetMetadata, "readiness_reason"), "dispatch-ready"))
              .reusedExecutor(
                  reusedExecutor
                      || booleanValue(targetMetadata.get("reused_executor"), reusedExecutor))
              .launcherLogFile(stringValue(targetMetadata, "launcher_log_file"))
              .launchArgsPreview(stringValue(targetMetadata, "launch_args_preview"))
              .localExecutorBaseUrl(
                  firstNonBlank(
                      stringValue(targetMetadata, "local_executor_base_url"),
                      target != null ? target.advertisedBaseUrl() : null))
              .attemptId(
                  BuilderGoalSensitiveTextSanitizer.sanitize(
                      stringValue(targetMetadata, "attempt_id")))
              .terminal(true)
              .success(true)
              .completedAt(Instant.now())
              .updatedAt(Instant.now())
              .build();
        });
  }

  /**
   * Terminal transition to CONFLICT. Returns true only when the active-to-CONFLICT transition
   * occurred.
   */
  public boolean markConflict(String operationId, String reason) {
    return terminalTransition(
        operationId,
        current -> {
          if (current.terminal()) {
            return null;
          }
          String message =
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(
                      reason,
                      "Existing account session blocks this launch; stop that session first"));
          return current.toBuilder()
              .status(STATUS_CONFLICT)
              .message(message)
              .readinessPhase(STATUS_CONFLICT)
              .readinessReason(message)
              .terminal(true)
              .success(false)
              .completedAt(Instant.now())
              .updatedAt(Instant.now())
              .build();
        });
  }

  /**
   * Terminal transition to FAILED. Returns true only when the active-to-FAILED transition occurred.
   */
  public boolean markFailed(
      String operationId, String reason, String phase, String launcherLogFile) {
    return terminalTransition(
        operationId,
        current -> {
          if (current.terminal()) {
            return null;
          }
          String message =
              BuilderGoalSensitiveTextSanitizer.sanitize(
                  firstNonBlank(reason, "Micro6 account launch failed"));
          return current.toBuilder()
              .status(STATUS_FAILED)
              .message(message)
              .readinessPhase(
                  BuilderGoalSensitiveTextSanitizer.sanitize(
                      firstNonBlank(phase, current.readinessPhase())))
              .readinessReason(message)
              .launcherLogFile(
                  BuilderGoalSensitiveTextSanitizer.sanitize(
                      firstNonBlank(launcherLogFile, current.launcherLogFile())))
              .terminal(true)
              .success(false)
              .completedAt(Instant.now())
              .updatedAt(Instant.now())
              .build();
        });
  }

  /**
   * Transitions an active operation to FAILED with a watchdog message. Returns {@code true} only
   * when this call actually performed the active-to-FAILED transition (compare-and-set semantics).
   * Idempotent — a terminal operation is left unchanged and returns {@code false}.
   */
  public boolean markWatchdogFailed(String operationId) {
    return terminalTransition(
        operationId,
        current -> {
          if (current.terminal()) {
            return null;
          }
          String message = "Launch operation timed out waiting for Micro6 readiness";
          return current.toBuilder()
              .status(STATUS_FAILED)
              .message(message)
              .readinessPhase(STATUS_FAILED)
              .readinessReason(message)
              .terminal(true)
              .success(false)
              .completedAt(Instant.now())
              .updatedAt(Instant.now())
              .build();
        });
  }

  /**
   * Copies terminal failure classification onto an already-FAILED snapshot. May enrich an already
   * FAILED snapshot but can never change status, terminal, success, or completedAt.
   */
  public void preserveFailureDetails(String operationId, String failureKind, String failureDetail) {
    preserveFailureDetails(operationId, failureKind, failureDetail, null);
  }

  /** Variant that also preserves the launch-attempt identifier. */
  public void preserveFailureDetails(
      String operationId, String failureKind, String failureDetail, String attemptId) {
    LaunchOperation operation = operations.get(operationId);
    if (operation == null) {
      return;
    }
    AtomicReference<OperationSnapshot> ref = operation.snapshot;
    while (true) {
      OperationSnapshot current = ref.get();
      if (!STATUS_FAILED.equals(current.status())) {
        return;
      }
      OperationSnapshot next =
          current.toBuilder()
              .failureKind(
                  BuilderGoalSensitiveTextSanitizer.sanitize(
                      firstNonBlank(failureKind, current.failureKind())))
              .failureDetail(
                  BuilderGoalSensitiveTextSanitizer.sanitize(
                      firstNonBlank(failureDetail, current.failureDetail())))
              .attemptId(
                  BuilderGoalSensitiveTextSanitizer.sanitize(
                      firstNonBlank(attemptId, current.attemptId())))
              .updatedAt(Instant.now())
              .build();
      if (ref.compareAndSet(current, next)) {
        return;
      }
    }
  }

  /**
   * Transitions the operation to FAILED using the same atomic FAILED transition (used on executor
   * rejection) and removes the active-account mapping using conditional removal so a concurrent
   * replacement operation is never deleted.
   */
  public void clearActiveAndTransition(String operationId, String failureReason) {
    if (operationId == null) {
      return;
    }
    markFailed(operationId, failureReason, STATUS_FAILED, null);
  }

  /**
   * Checks for active operations that have exceeded the watchdog duration and transitions them to
   * FAILED. Returns the list of operation IDs that were newly transitioned so the caller can cancel
   * associated workers.
   */
  public List<String> checkActiveWatchdog() {
    Instant now = Instant.now();
    Duration watchdog = activeWatchdogMs;
    List<String> failedIds = new ArrayList<>();
    for (LaunchOperation operation : operations.values()) {
      if (operation == null || operation.isTerminal()) {
        continue;
      }
      if (Duration.between(operation.getCreatedAt(), now).compareTo(watchdog) > 0) {
        logger.warn(
            "Active operation {} for account {} exceeded watchdog duration; marking FAILED",
            operation.getOperationId(),
            operation.getAccountName());
        // Only add to failedIds when the atomic transition succeeded (avoids racing with READY)
        if (markWatchdogFailed(operation.getOperationId())) {
          failedIds.add(operation.getOperationId());
        }
      }
    }
    return failedIds;
  }

  /**
   * Serializes the operation into a credential-free view for the browser. Reads the {@link
   * AtomicReference} exactly once so the response can never expose mixed terminal/non-terminal
   * fields.
   */
  public Map<String, Object> toResponse(String operationId, boolean deduplicated) {
    LaunchOperation operation = getOrThrow(operationId);
    OperationSnapshot snapshot = operation.snapshot();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("operationId", BuilderGoalSensitiveTextSanitizer.sanitize(operation.operationId));
    response.put("status", snapshot.status());
    response.put("accountId", BuilderGoalSensitiveTextSanitizer.sanitize(operation.accountId));
    response.put("accountName", BuilderGoalSensitiveTextSanitizer.sanitize(operation.accountName));
    response.put(
        "launcherMachineUuid",
        BuilderGoalSensitiveTextSanitizer.sanitize(operation.launcherMachineUuid));
    response.put("world", operation.world);
    response.put("packetCapture", operation.packetCapture);
    response.put("deduplicated", deduplicated);
    response.put(
        "statusUrl",
        BuilderGoalSensitiveTextSanitizer.sanitize(STATUS_URL_PREFIX + operation.operationId));
    response.put("message", snapshot.message());
    response.put("phase", snapshot.status());
    response.put("readinessPhase", snapshot.readinessPhase());
    response.put("readinessReason", snapshot.readinessReason());
    response.put("reusedExecutor", snapshot.reusedExecutor());
    response.put("terminal", snapshot.terminal());
    response.put("success", snapshot.success());
    response.put("createdAt", operation.createdAt.toString());
    response.put("updatedAt", snapshot.updatedAt().toString());
    response.put(
        "completedAt", snapshot.completedAt() != null ? snapshot.completedAt().toString() : null);
    response.put("launcherLogFile", snapshot.launcherLogFile());
    response.put("launchArgsPreview", snapshot.launchArgsPreview());
    response.put("localExecutorBaseUrl", snapshot.localExecutorBaseUrl());
    response.put("failureKind", snapshot.failureKind());
    response.put("failureDetail", snapshot.failureDetail());
    response.put("attemptId", snapshot.attemptId());
    // Defense-in-depth: re-sanitize/freeze the already-immutable snapshot metadata so no caller
    // can ever leak or mutate stored target metadata through the response.
    response.put("phaseHistory", snapshot.phaseHistory());
    response.put(
        "target",
        snapshot.targetMetadata() != null
            ? (Map<String, Object>)
                BuilderGoalSensitiveTextSanitizer.sanitizeAndFreeze(snapshot.targetMetadata())
            : Map.of());
    return response;
  }

  public static String statusForPhase(String phase) {
    if (phase == null) {
      return null;
    }
    switch (phase) {
      case Micro6TargetRelaunchService.PHASE_CHECKING_EXISTING:
        return STATUS_CHECKING_EXISTING;
      case Micro6TargetRelaunchService.PHASE_REUSING_EXECUTOR:
        return STATUS_REUSING_EXECUTOR;
      case Micro6TargetRelaunchService.PHASE_STOPPING_EXISTING:
        return STATUS_STOPPING_EXISTING;
      case Micro6TargetRelaunchService.PHASE_PREPARING_BUILD:
        return STATUS_PREPARING_BUILD;
      case Micro6TargetRelaunchService.PHASE_BUILDING_MICRO6:
        return STATUS_BUILDING_MICRO6;
      case Micro6TargetRelaunchService.PHASE_LAUNCHING_PROCESS:
        return STATUS_LAUNCHING_PROCESS;
      case Micro6TargetRelaunchService.PHASE_WAITING_FOR_PORT:
        return STATUS_WAITING_FOR_PORT;
      case Micro6TargetRelaunchService.PHASE_WAITING_FOR_HEALTH:
        return STATUS_WAITING_FOR_HEALTH;
      case Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT:
        return STATUS_WAITING_FOR_ACCOUNT;
      case Micro6TargetRelaunchService.PHASE_WAITING_FOR_LOGIN:
        return STATUS_WAITING_FOR_LOGIN;
      case Micro6TargetRelaunchService.PHASE_WAITING_FOR_WORLD:
        return STATUS_WAITING_FOR_WORLD;
      case Micro6TargetRelaunchService.PHASE_WAITING_FOR_SCRIPT:
        return STATUS_WAITING_FOR_DISPATCH;
      case Micro6TargetRelaunchService.PHASE_WAITING_FOR_DISPATCH:
        return STATUS_WAITING_FOR_DISPATCH;
      case Micro6TargetRelaunchService.PHASE_READY:
        return STATUS_READY;
      default:
        return null;
    }
  }

  private static List<Map<String, Object>> appendPhaseHistory(
      List<Map<String, Object>> existing, String phase, String reason) {
    List<Map<String, Object>> history =
        existing == null ? new ArrayList<>() : new ArrayList<>(existing);
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("at", Instant.now().toString());
    entry.put("phase", BuilderGoalSensitiveTextSanitizer.sanitize(phase));
    entry.put("reason", BuilderGoalSensitiveTextSanitizer.sanitize(reason));
    history.add(entry);
    if (history.size() > 100) {
      history = new ArrayList<>(history.subList(history.size() - 100, history.size()));
    }
    return history;
  }

  /**
   * Normalize account keys to prevent duplicates when one caller passes hash and another passes ID.
   */
  static String normalizeAccountKey(String accountHash, String accountId) {
    return firstNonBlank(accountHash, accountId);
  }

  /** Applies a CAS loop for active (non-terminal) transitions. */
  private void activeTransition(String operationId, UnaryOperator<OperationSnapshot> mutator) {
    LaunchOperation operation = operations.get(operationId);
    if (operation == null) {
      return;
    }
    AtomicReference<OperationSnapshot> ref = operation.snapshot;
    while (true) {
      OperationSnapshot current = ref.get();
      if (current.terminal()) {
        return;
      }
      OperationSnapshot next = mutator.apply(current);
      if (next == null) {
        return;
      }
      if (ref.compareAndSet(current, next)) {
        return;
      }
    }
  }

  /** Applies a CAS loop for terminal transitions and removes the active mapping on success. */
  private boolean terminalTransition(String operationId, UnaryOperator<OperationSnapshot> mutator) {
    LaunchOperation operation = operations.get(operationId);
    if (operation == null) {
      return false;
    }
    AtomicReference<OperationSnapshot> ref = operation.snapshot;
    while (true) {
      OperationSnapshot current = ref.get();
      if (current.terminal()) {
        return false;
      }
      OperationSnapshot next = mutator.apply(current);
      if (next == null) {
        return false;
      }
      if (ref.compareAndSet(current, next)) {
        activeByAccount.remove(
            normalizeAccountKey(operation.accountHash, operation.accountId), operationId);
        return true;
      }
    }
  }

  /**
   * Builds the complete target metadata structure from the target client and the supplied raw
   * metadata, then recursively sanitizes and freezes it as one immutable structure. The target
   * client identity fields take precedence over any caller-provided values; every remaining
   * caller-supplied key (including nested maps and lists) is retained so the response reflects
   * exactly what was recorded. Individual fields are no longer hand-sanitized: the whole structure
   * passes through {@link BuilderGoalSensitiveTextSanitizer#sanitizeAndFreeze}, so every string
   * leaf is redacted and the returned map is deeply immutable.
   */
  private Map<String, Object> buildSanitizedTargetMetadata(
      TargetClient target, Map<String, Object> metadata) {
    Map<String, Object> targetMetadata = new LinkedHashMap<>();
    if (target != null) {
      targetMetadata.put("client_id", target.clientId());
      targetMetadata.put("account_name", target.accountName());
      targetMetadata.put("account_hash", target.accountHash());
      targetMetadata.put("advertised_base_url", target.advertisedBaseUrl());
      targetMetadata.put("executor_bind_addr", target.executorBindAddr());
      targetMetadata.put("source_client_type", target.sourceClientType());
      targetMetadata.put("executor_profile", target.executorProfile());
      targetMetadata.put("status", target.status());
      targetMetadata.put("current_task", target.currentTask());
    }
    if (metadata != null) {
      for (Map.Entry<String, Object> entry : metadata.entrySet()) {
        if (entry.getKey() == null) {
          continue;
        }
        targetMetadata.putIfAbsent(entry.getKey(), entry.getValue());
      }
    }
    return (Map<String, Object>)
        BuilderGoalSensitiveTextSanitizer.sanitizeAndFreeze(targetMetadata);
  }

  private void pruneExpired() {
    Instant now = Instant.now();
    Duration retention = terminalRetention;
    operations
        .entrySet()
        .removeIf(
            entry -> {
              LaunchOperation operation = entry.getValue();
              if (operation == null) {
                activeByAccount.values().removeIf(id -> id.equals(entry.getKey()));
                return true;
              }
              // Active operations are never pruned by retention
              if (!operation.isTerminal()) {
                return false;
              }
              // Terminal operations expire based on completedAt (fall back to updatedAt)
              Instant expiryBase =
                  operation.getCompletedAt() != null
                      ? operation.getCompletedAt()
                      : operation.getUpdatedAt();
              if (now.isAfter(expiryBase.plus(retention))) {
                activeByAccount.values().removeIf(id -> id.equals(entry.getKey()));
                return true;
              }
              return false;
            });
  }

  private String stringValue(Map<String, Object> metadata, String key) {
    if (metadata == null || key == null) {
      return null;
    }
    Object value = metadata.get(key);
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private boolean booleanValue(Object value, boolean defaultValue) {
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    if (value instanceof String text && !text.isBlank()) {
      return Boolean.parseBoolean(text.trim());
    }
    return defaultValue;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }
}
