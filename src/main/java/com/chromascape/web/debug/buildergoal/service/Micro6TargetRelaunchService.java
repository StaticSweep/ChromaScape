package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Micro6TargetRelaunchService {

  private static final Logger logger = LoggerFactory.getLogger(Micro6TargetRelaunchService.class);

  /** Canonical Gradle task from the Micro6 root composite; mirrors the IntelliJ RuneLite config. */
  private static final String DEFAULT_GRADLE_RUN_TASK = ":client:runBuilderGoal";

  private static final String DEFAULT_GRADLE_PREPARE_TASK = ":client:prepareBuilderGoal";
  private static final String CUSTOM_LAUNCH_COMMAND = "custom_launch_command";
  private static final int MAX_LAUNCH_ATTEMPTS = 2;

  /**
   * Windows spawns a fresh PowerShell for every listener probe, and cold start alone routinely
   * exceeds five seconds on a loaded workstation (measured at ~8.4s while a Micro6 client, a second
   * ChromaScape instance and a Docker stack were running). The probe is fail-closed: a timeout
   * yields {@code UNKNOWN}, which both blocks executor replacement and prevents a healthy executor
   * from being reused, so an over-tight bound silently converts transient machine load into
   * spurious dispatch failures. The bound stays finite so a genuinely hung probe still fails closed
   * rather than stalling a launch indefinitely.
   */
  private static final long GRACEFUL_TERMINATION_WAIT_MS = 3_000L;
  private static final long FORCE_TERMINATION_WAIT_MS = 2_000L;
  private static final long OWNED_TREE_SETTLE_MS = 1_500L;

  /**
   * Bounded wait for a listener probe subprocess. {@code Get-NetTCPConnection} is a CIM/WMI cmdlet
   * that can stall for several seconds under load, so the bound must be generous enough that a slow
   * but healthy probe is not misclassified as {@code UNKNOWN}.
   */
  private static final long PORT_PROBE_TIMEOUT_MS = 30_000L;

  /** Delay before retrying the native netstat fallback after an indeterminate listener probe. */
  private static final long PORT_PROBE_FALLBACK_RETRY_DELAY_MS = 750L;
  private static final String FAILURE_KIND_BUILD_OUTPUT_LOCK = "BUILD_OUTPUT_LOCK";
  private static final String FAILURE_KIND_PROCESS_EXITED = "PROCESS_EXITED";
  private static final String FAILURE_KIND_GRADLE_TASK_FAILED = "GRADLE_TASK_FAILED";
  private static final String FAILURE_KIND_LAUNCH_CONFIGURATION_INVALID =
      "LAUNCH_CONFIGURATION_INVALID";
  private static final String FAILURE_KIND_GRADLE_TIMEOUT = "GRADLE_TIMEOUT";
  private static final String FAILURE_KIND_EXECUTOR_REPLACEMENT_BLOCKED =
      "EXECUTOR_REPLACEMENT_BLOCKED";

  /**
   * Terminal executor startup refusal: the process is alive but will never become dispatch-ready.
   */
  private static final String FAILURE_KIND_EXECUTOR_STARTUP_REJECTED = "EXECUTOR_STARTUP_REJECTED";

  /** Launcher contract version ChromaScape requires; version 3 adds the explicit source mode. */
  private static final int LAUNCHER_CONTRACT_VERSION = 3;

  private static final String SOURCE_MODE_ENVIRONMENT_KEY = "MICRO6_SOURCE_MODE";
  private static final String SOURCE_MODE_DISABLED = "disabled";
  private static final String SOURCE_MODE_AUTHORITATIVE = "authoritative";

  /**
   * Authoritative launch task the launcher contract must advertise; mirrors the Gradle composite.
   */
  private static final String AUTHORITATIVE_GRADLE_RUN_TASK = ":client:runBuilderGoalAuthoritative";

  /**
   * Micro6 startup refusals that can never resolve by waiting. Each marker is emitted by the
   * executor (or the launch task) before it exits, and every one of them means the executor will
   * not bind its port for this attempt.
   *
   * <p>{@code source_startup_failed} covers every fail-closed authoritative source violation,
   * including an unknown mode and a promoted bundle that does not match the running artifact.
   * {@code no artifact sidecar configured} and {@code running packaged artifact identity is
   * unavailable} are the artifact-attestation refusals. {@code Local builder executor did not
   * start} is the generic bind refusal.
   *
   * <p>Ordered most specific first: the executor can publish the generic bind refusal on the same
   * startup path as the specific underlying cause, so the artifact-attestation markers and the
   * fail-closed source marker must be matched before the generic one. Otherwise a caller would only
   * ever see {@code Local builder executor did not start} and lose the actionable cause.
   */
  private static final List<String> FATAL_EXECUTOR_STARTUP_MARKERS =
      List.of(
          "no artifact sidecar configured",
          "running packaged artifact identity is unavailable",
          "source_startup_failed",
          "Local builder executor did not start");

  /** Highest-priority marker first: the most specific published cause wins. */
  static String detectFatalExecutorStartupMarker(List<String> lines) {
    if (lines == null || lines.isEmpty()) {
      return null;
    }
    // Artifact-identity diagnostics can also be emitted by a running development executor while
    // answering health. They are fatal only when the same attempt contains an explicit startup
    // refusal. Without this gate a healthy disabled-mode executor can bind 8098 and then be killed
    // solely because its classes-directory launch has no packaged-JAR sidecar.
    boolean startupRefused =
        lines.stream()
            .anyMatch(
                line ->
                    line != null
                        && (line.contains("[Micro6Executor] refusing startup:")
                            || line.contains("source_startup_failed")
                            || line.contains("Local builder executor did not start")));
    if (!startupRefused) {
      return null;
    }
    for (String marker : FATAL_EXECUTOR_STARTUP_MARKERS) {
      if (lines.stream().anyMatch(line -> line != null && line.contains(marker))) {
        return marker;
      }
    }
    return null;
  }

  private enum LaunchMode {
    STRUCTURED_WINDOWS,
    CUSTOM_COMMAND_TEMPLATE,
    WINDOWS_GRADLE,
    NON_WINDOWS_GRADLE
  }

  // Preparation lifecycle phases persisted in target_preparation metadata.
  public static final String PHASE_CHECKING_EXISTING = "CHECKING_EXISTING";
  public static final String PHASE_REUSING_EXECUTOR = "REUSING_EXECUTOR";
  public static final String PHASE_STOPPING_EXISTING = "STOPPING_EXISTING";
  public static final String PHASE_PREPARING_BUILD = "PREPARING_BUILD";
  public static final String PHASE_BUILDING_MICRO6 = "BUILDING_MICRO6";
  public static final String PHASE_LAUNCHING_PROCESS = "LAUNCHING_PROCESS";
  public static final String PHASE_WAITING_FOR_PORT = "WAITING_FOR_PORT";
  public static final String PHASE_WAITING_FOR_HEALTH = "WAITING_FOR_HEALTH";
  public static final String PHASE_WAITING_FOR_ACCOUNT = "WAITING_FOR_ACCOUNT";
  public static final String PHASE_WAITING_FOR_LOGIN =
      Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_LOGIN;
  public static final String PHASE_WAITING_FOR_WORLD =
      Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_WORLD;
  public static final String PHASE_WAITING_FOR_SCRIPT =
      Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_SCRIPT;
  public static final String PHASE_WAITING_FOR_DISPATCH =
      Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_DISPATCH;
  public static final String PHASE_READY = Micro6ExecutorReadinessEvaluator.PHASE_READY;
  public static final String PHASE_BUILD_FAILED = "BUILD_FAILED";
  public static final String PHASE_PROCESS_EXITED = "PROCESS_EXITED";

  /**
   * Terminal executor startup refusal: the client process is still alive but has published a marker
   * proving it will never bind the executor port. Distinct from {@link #PHASE_PROCESS_EXITED}
   * because the process has not exited when this phase is published.
   */
  public static final String PHASE_EXECUTOR_STARTUP_REJECTED = "EXECUTOR_STARTUP_REJECTED";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String micro6ProjectDir;
  private final String gradleRunTask;
  private final String launchCommandTemplate;
  private final String extraRunArgs;
  private final int defaultWorld;
  private final long launchTimeoutMs;
  private final long heartbeatPollIntervalMs;
  private final String launchLogFile;
  private final String defaultExecutorBaseUrl;
  private final String chromaScapeBaseUrl;
  private final String leeuwbotApiUrl;

  @Value("${builder-goal.recovery.validation-timeout-ms:300000}")
  private long validationTimeoutMs = 300_000L;

  public Micro6TargetRelaunchService(
      ObjectMapper objectMapper,
      @Value("${builder-goal.recovery.micro6-project-dir}") String micro6ProjectDir,
      @Value("${builder-goal.recovery.gradle-run-task::client:runBuilderGoal}")
          String gradleRunTask,
      @Value("${builder-goal.recovery.launch-command-template:}") String launchCommandTemplate,
      @Value("${builder-goal.recovery.extra-run-args:}") String extraRunArgs,
      @Value("${builder-goal.recovery.world:360}") int defaultWorld,
      @Value("${builder-goal.recovery.launch-timeout-ms:1800000}") long launchTimeoutMs,
      @Value("${builder-goal.recovery.heartbeat-poll-interval-ms:2000}")
          long heartbeatPollIntervalMs,
      @Value("${builder-goal.recovery.launch-log-file:micro6-recovery-launch.log}")
          String launchLogFile,
      @Value("${chromascape.base-url:http://localhost:8090}") String chromaScapeBaseUrl,
      @Value("${chromascape.patchloop.micro6-executor.base-url:http://127.0.0.1:8098}")
          String defaultExecutorBaseUrl,
      @Value("${builder-goal.recovery.leeuwbot-api-url:http://localhost:8086}")
          String leeuwbotApiUrl) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.micro6ProjectDir = micro6ProjectDir;
    this.launchCommandTemplate = normalizeOptionalString(launchCommandTemplate);
    this.gradleRunTask = resolveEffectiveGradleTask(gradleRunTask);
    this.extraRunArgs = extraRunArgs;
    this.defaultWorld = defaultWorld;
    this.launchTimeoutMs = launchTimeoutMs;
    this.heartbeatPollIntervalMs = heartbeatPollIntervalMs;
    this.launchLogFile = launchLogFile;
    this.chromaScapeBaseUrl = normalizeBaseUrl(chromaScapeBaseUrl);
    this.defaultExecutorBaseUrl = normalizeBaseUrl(defaultExecutorBaseUrl);
    this.leeuwbotApiUrl = normalizeBaseUrl(leeuwbotApiUrl);
    logIgnoredLegacyGradleTaskOverride();
    logIgnoredLegacyLaunchTemplateOverride();
    logger.info(
        "Builder Goal local launch config loaded: projectDir={}, gradleTask={}, launchTemplateConfigured={}, defaultWorld={}, executorBaseUrl={}, chromaScapeBaseUrl={}, leeuwbotApiUrl={}",
        this.micro6ProjectDir,
        this.gradleRunTask,
        this.launchCommandTemplate != null,
        this.defaultWorld,
        this.defaultExecutorBaseUrl,
        this.chromaScapeBaseUrl,
        this.leeuwbotApiUrl);
  }

  private void logIgnoredLegacyGradleTaskOverride() {
    String envOverride =
        normalizeOptionalString(System.getenv("BUILDER_GOAL_MICRO6_GRADLE_RUN_TASK"));
    String propertyOverride =
        normalizeOptionalString(System.getProperty("builder-goal.recovery.gradle-run-task"));
    String legacyOverride = firstNonBlank(envOverride, propertyOverride);
    if (legacyOverride == null) {
      return;
    }
    logger.warn(
        "Ignoring legacy Builder Goal Gradle task override '{}'; local Builder Goal launches always use '{}'",
        legacyOverride,
        DEFAULT_GRADLE_RUN_TASK);
  }

  private void logIgnoredLegacyLaunchTemplateOverride() {
    if (!useStructuredWindowsLaunch() || launchCommandTemplate == null) {
      return;
    }
    logger.warn(
        "Ignoring legacy Builder Goal launch command template on Windows; automated local launches now use the direct PowerShell payload launcher");
  }

  public RelaunchResult restartTarget(
      String accountName,
      String accountHash,
      String accountEmail,
      String accountPassword,
      Integer world,
      String targetBaseUrl,
      BuilderGoalLaunchCancellation cancellation)
      throws Exception {
    return restartTarget(
        accountName,
        accountHash,
        accountEmail,
        accountPassword,
        world,
        targetBaseUrl,
        null,
        cancellation,
        false);
  }

  public RelaunchResult restartTarget(
      String accountName,
      String accountHash,
      String accountEmail,
      String accountPassword,
      Integer world,
      String targetBaseUrl,
      Consumer<RelaunchProgress> progressListener,
      BuilderGoalLaunchCancellation cancellation)
      throws Exception {
    return restartTarget(
        accountName,
        accountHash,
        accountEmail,
        accountPassword,
        world,
        targetBaseUrl,
        progressListener,
        cancellation,
        false);
  }

  public RelaunchResult restartTarget(
      String accountName,
      String accountHash,
      String accountEmail,
      String accountPassword,
      Integer world,
      String targetBaseUrl,
      Consumer<RelaunchProgress> progressListener,
      BuilderGoalLaunchCancellation cancellation,
      boolean forceRelaunch)
      throws Exception {
    if (accountName == null || accountName.isBlank()) {
      throw new IllegalArgumentException("Target account name is required for Micro6 relaunch");
    }
    if (accountEmail == null || accountEmail.isBlank()) {
      throw new IllegalArgumentException(
          "Selected launcher account is missing an email for local Micro6 relaunch");
    }
    if (accountPassword == null || accountPassword.isBlank()) {
      throw new IllegalArgumentException(
          "Selected launcher account is missing a password for local Micro6 relaunch");
    }

    // Validation happens before any temporary payload creation, process creation, or run
    // preparation state changes that imply launch has begun.
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    Path projectDir = Paths.get(micro6ProjectDir).toAbsolutePath().normalize();
    validateMicro6ProjectDir(projectDir);

    String resolvedBaseUrl = firstNonBlank(normalizeBaseUrl(targetBaseUrl), defaultExecutorBaseUrl);
    Integer targetPort = parsePort(resolvedBaseUrl);
    String localExecutorBaseUrl = resolveLocalExecutorBaseUrl(targetPort);
    File logFile = projectDir.resolve(launchLogFile).toFile();
    if (!AccountIdentityContract.isCanonicalHash(accountHash)) {
      throw new IllegalArgumentException(
          "Selected launcher account is missing the canonical game-account hash");
    }
    List<String> clientArgs =
        buildClientArgs(accountName, accountHash, accountEmail, accountPassword, world, null);
    String maskedLaunchArgs = maskLaunchArgs(clientArgs);
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    boolean reusedExistingExecutor = false;

    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    // Step 1: Report that we are inspecting any existing executor before any cleanup.
    emitProgress(
        progressListener,
        new HealthCheckResult(
            Optional.empty(),
            PHASE_CHECKING_EXISTING,
            "Checking for an existing matching Micro6 executor",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null),
        false,
        localExecutorBaseUrl,
        gradleRunTask,
        null,
        null,
        null,
        null);
    HealthCheckResult initialHealthCheck =
        readHealthyExecutorTarget(
            localExecutorBaseUrl,
            accountHash,
            accountName,
            resolveWorld(world),
            null,
            -1L,
            null,
            cancellation);
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    Instant deadline = resolveLaunchDeadline();

    // Step 2: Reuse only when the executor is account-matched and dispatch-ready.
    // Waiting states are never reused; they are stopped and replaced.
    if (!forceRelaunch && canReuseExistingExecutor(initialHealthCheck)) {
      reusedExistingExecutor = true;
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      emitProgress(
          progressListener,
          new HealthCheckResult(
              Optional.empty(),
              PHASE_REUSING_EXECUTOR,
              "Reusing existing dispatch-ready Micro6 executor",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              true,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null),
          reusedExistingExecutor,
          localExecutorBaseUrl,
          gradleRunTask,
          null,
          null,
          null,
          null);
      // Reuse bypasses the readiness wait; return immediately with the existing target.
      return buildSuccessResult(
          initialHealthCheck,
          List.of(),
          -1L,
          null,
          null,
          resolveWorld(world),
          localExecutorBaseUrl,
          true);
    }

    // Step 3: Replacement required. Verify the launcher contract, then validate and compile the
    // Micro6 launch configuration non-destructively before stopping anything, so a broken Gradle
    // task, an incompatible launcher, or a compile failure never kills a running client.
    emitProgress(
        progressListener,
        new HealthCheckResult(
            Optional.empty(),
            PHASE_PREPARING_BUILD,
            "Validating Micro6 launch configuration before stopping existing processes",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null),
        false,
        localExecutorBaseUrl,
        gradleRunTask,
        null,
        null,
        null,
        null);
    verifyLauncherContract(projectDir, logFile.toPath(), cancellation);
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    validateLaunchConfiguration(projectDir, logFile.toPath(), cancellation);
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    compileMicro6ProductionClasses(projectDir, logFile.toPath(), cancellation);
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }

    // Step 4: Executor is not dispatch-ready or not account-matched — stop and launch fresh.
    emitProgress(
        progressListener,
        new HealthCheckResult(
            Optional.empty(),
            PHASE_STOPPING_EXISTING,
            "Stopping existing Micro6 processes before launching a replacement",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null),
        false,
        localExecutorBaseUrl,
        gradleRunTask,
        null,
        null,
        null,
        null);
    RelaunchAttemptResult attemptResult = null;
    for (int attemptNumber = 1; attemptNumber <= MAX_LAUNCH_ATTEMPTS; attemptNumber++) {
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      // Attempt-scoped failure analysis: record the byte offset at the start of this
      // launch attempt so only bytes appended by this attempt are ever classified.
      String attemptId = UUID.randomUUID().toString();
      long attemptStartOffset = logFile.isFile() ? logFile.length() : 0L;
      emitProgress(
          progressListener,
          new HealthCheckResult(
              Optional.empty(),
              PHASE_STOPPING_EXISTING,
              "Stopping existing Micro6 processes before launching a replacement",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              false,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null),
          false,
          localExecutorBaseUrl,
          gradleRunTask,
          null,
          null,
          null,
          null,
          attemptId);
      stopExistingTargetProcess(targetPort, projectDir, initialHealthCheck, cancellation);
      Process process = null;
      List<String> command = null;
      try {
        if (cancellation != null) {
          cancellation.throwIfCancelled();
        }
        // Create a fresh credential-bearing payload only after validation and cleanup succeed.
        // The attempt ID is passed to the client so the executor can report which ChromaScape
        // launch attempt owns this process (used to verify process ownership before replacement).
        List<String> attemptClientArgs =
            buildClientArgs(
                accountName, accountHash, accountEmail, accountPassword, world, attemptId);
        command =
            buildLaunchCommand(
                attemptClientArgs,
                projectDir,
                accountName,
                accountEmail,
                accountPassword,
                resolveWorld(world),
                targetPort,
                localExecutorBaseUrl);
        process =
            startTargetProcess(
                projectDir,
                logFile,
                targetPort,
                accountName,
                world,
                command,
                localExecutorBaseUrl,
                resolvedBaseUrl);
        if (cancellation != null) {
          cancellation.throwIfCancelled();
        }
        emitProgress(
            progressListener,
            new HealthCheckResult(
                Optional.empty(),
                PHASE_BUILDING_MICRO6,
                attemptNumber == 1
                    ? "Building and launching local Micro6 workspace via Gradle"
                    : "Retrying local Micro6 workspace launch after compile cleanup failure",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            false,
            localExecutorBaseUrl,
            gradleRunTask,
            maskedLaunchArgs,
            logFile.toPath(),
            null,
            null,
            attemptId);
        attemptResult =
            waitForTargetReady(
                process,
                deadline,
                accountHash,
                accountName,
                localExecutorBaseUrl,
                logFile.toPath(),
                attemptStartOffset,
                attemptId,
                launchStartedAt(process),
                progressListener,
                resolvedBaseUrl,
                command,
                world,
                maskedLaunchArgs,
                cancellation);
      } catch (BuilderGoalLaunchCancelledException e) {
        // Every cancellation that escapes after the owned process was created must terminate
        // the owned process tree before the lease-owning method returns.
        terminateOwnedProcessTree(process);
        deleteOwnedLaunchFiles(command);
        throw e;
      } catch (InterruptedException e) {
        terminateOwnedProcessTree(process);
        deleteOwnedLaunchFiles(command);
        throw BuilderGoalLaunchCancelledException.fromInterrupted(e);
      } catch (Exception e) {
        // Any other failure after the owned process was created — a readiness watchdog expiry
        // (RelaunchException), a progress-listener failure, or any runtime health failure — must
        // terminate the owned process tree before the lease-owning method returns, then rethrow
        // the original exception. Cleanup completes before BuilderGoalRunnerService's finally
        // block releases the coordinator lease.
        terminateOwnedProcessTree(process);
        deleteOwnedLaunchFiles(command);
        throw e;
      }
      // The production PowerShell launcher normally deletes its payload after parsing. Keep a
      // ChromaScape-owned cleanup boundary for launchers that exit before parsing or retain files.
      deleteOwnedLaunchFiles(command);
      if (attemptResult.success() != null) {
        return attemptResult.success();
      }
      // An unsuccessful attempt can still own a live process tree: waitForTargetReady returns
      // instead of throwing on a fatal executor startup rejection, where the client process stays
      // alive but will never bind the executor port. Terminate the owned tree before retrying or
      // throwing so a rejected RuneLite instance cannot outlive the failed attempt.
      terminateOwnedProcessTree(process);
      LaunchFailure failure = attemptResult.failure();
      if (attemptNumber < MAX_LAUNCH_ATTEMPTS && failure != null && failure.retryable()) {
        logger.warn(
            "Retrying local Micro6 Builder Goal launch for account {} after retryable failure {}: {}",
            accountName,
            failure.kind(),
            failure.detail());
        continue;
      }
      throw buildProcessExitedException(
          progressListener,
          localExecutorBaseUrl,
          logFile.toPath(),
          maskedLaunchArgs,
          failure,
          attemptResult.lastReadinessPhase(),
          attemptResult.lastReadinessReason(),
          attemptId);
    }
    if (attemptResult != null) {
      throw buildProcessExitedException(
          progressListener,
          localExecutorBaseUrl,
          logFile.toPath(),
          maskedLaunchArgs,
          attemptResult.failure(),
          attemptResult.lastReadinessPhase(),
          attemptResult.lastReadinessReason(),
          attemptResult.attemptId());
    }
    throw new RelaunchException(
        PHASE_BUILD_FAILED,
        "Micro6 build failed before the client could launch. Check " + logFile.getAbsolutePath(),
        true,
        logFile.toPath(),
        gradleRunTask,
        maskedLaunchArgs,
        FAILURE_KIND_GRADLE_TASK_FAILED,
        null,
        PHASE_PREPARING_BUILD,
        null);
  }

  private RelaunchAttemptResult waitForTargetReady(
      Process process,
      Instant deadline,
      String accountHash,
      String accountName,
      String localExecutorBaseUrl,
      Path logFile,
      long attemptStartOffset,
      String attemptId,
      Instant launchStartedAt,
      Consumer<RelaunchProgress> progressListener,
      String resolvedBaseUrl,
      List<String> command,
      Integer world,
      String maskedLaunchArgs,
      BuilderGoalLaunchCancellation cancellation)
      throws Exception {
    String lastReadinessReason = null;
    String lastReadinessPhase = PHASE_LAUNCHING_PROCESS;
    while (Instant.now().isBefore(deadline)) {
      // A terminal startup refusal is checked before the health probe: the executor publishes these
      // markers only on paths that end startup, so waiting cannot make one resolve. Returning now
      // keeps the launch failure prompt and stops the owned process tree rather than leaving
      // RuneLite open with nothing listening on the executor port.
      LaunchFailure fatalStartupFailure =
          detectFatalExecutorStartupFailure(logFile, attemptStartOffset);
      if (fatalStartupFailure != null) {
        emitProgress(
            progressListener,
            new HealthCheckResult(
                Optional.empty(),
                PHASE_EXECUTOR_STARTUP_REJECTED,
                fatalStartupFailure.detail(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            false,
            localExecutorBaseUrl,
            gradleRunTask,
            maskedLaunchArgs,
            logFile,
            fatalStartupFailure.kind(),
            fatalStartupFailure.detail(),
            attemptId);
        return new RelaunchAttemptResult(
            null, fatalStartupFailure, lastReadinessPhase, lastReadinessReason, attemptId);
      }
      HealthCheckResult healthCheck =
          readHealthyExecutorTarget(
              localExecutorBaseUrl,
              accountHash,
              accountName,
              resolveWorld(world),
              launchStartedAt.minusSeconds(2),
              process.pid(),
              attemptId,
              cancellation);
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      emitProgress(
          progressListener,
          healthCheck,
          false,
          localExecutorBaseUrl,
          gradleRunTask,
          maskedLaunchArgs,
          logFile,
          null,
          null,
          attemptId);
      if (healthCheck.targetClient().isPresent()) {
        if (cancellation != null) {
          cancellation.throwIfCancelled();
        }
        return new RelaunchAttemptResult(
            buildSuccessResult(
                healthCheck,
                command,
                process.pid(),
                logFile,
                maskedLaunchArgs,
                resolveWorld(world),
                localExecutorBaseUrl,
                false,
                attemptId),
            null,
            healthCheck.readinessPhase(),
            healthCheck.readinessReason(),
            attemptId);
      }
      lastReadinessReason = firstNonBlank(healthCheck.readinessReason(), lastReadinessReason);
      lastReadinessPhase = firstNonBlank(healthCheck.readinessPhase(), lastReadinessPhase);
      if (!process.isAlive()) {
        boolean markerSeen = hasSeenLaunchingProcessMarker(logFile, attemptStartOffset);
        LaunchFailure failure = analyzeLaunchFailure(logFile, attemptStartOffset);
        // A published startup refusal outranks the generic process-exit classification: the exit is
        // a consequence of the refusal, and the refusal is the actionable cause. It is re-checked
        // here because the refusal can be written after this iteration's earlier log read.
        LaunchFailure startupRejection =
            detectFatalExecutorStartupFailure(logFile, attemptStartOffset);
        if (startupRejection != null) {
          failure = startupRejection;
        } else if (markerSeen) {
          failure =
              new LaunchFailure(
                  FAILURE_KIND_PROCESS_EXITED,
                  firstNonBlank(
                      failure.detail(),
                      "Micro6 relaunch process exited after the client launched but before the local executor became ready. Check "
                          + logFile.toAbsolutePath()),
                  false);
        }
        emitProgress(
            progressListener,
            new HealthCheckResult(
                Optional.empty(),
                PHASE_PROCESS_EXITED,
                firstNonBlank(
                    failure.detail(),
                    "Micro6 relaunch process exited before the local executor became ready"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            false,
            localExecutorBaseUrl,
            gradleRunTask,
            maskedLaunchArgs,
            logFile,
            failure.kind(),
            failure.detail(),
            attemptId);
        return new RelaunchAttemptResult(
            null, failure, lastReadinessPhase, lastReadinessReason, attemptId);
      }
      sleepPoll(heartbeatPollIntervalMs, cancellation);
    }
    // The launch deadline expired. Classify based on whether the client process ever launched.
    boolean markerSeen = hasSeenLaunchingProcessMarker(logFile, attemptStartOffset);
    if (!markerSeen && process.isAlive()) {
      // The launch deadline expired before phase=LAUNCHING_PROCESS; Gradle never reached the Java
      // launch. Classify as GRADLE_TIMEOUT with a BUILD_FAILED readiness phase.
      emitBuildFailedProgress(
          progressListener,
          localExecutorBaseUrl,
          maskedLaunchArgs,
          logFile,
          FAILURE_KIND_GRADLE_TIMEOUT,
          "Micro6 Gradle build did not reach the client launch before the local launch watchdog expired. Check "
              + logFile.toAbsolutePath(),
          attemptId);
      throw new RelaunchException(
          PHASE_BUILD_FAILED,
          "Micro6 build timed out before the client could launch. Check "
              + logFile.toAbsolutePath(),
          true,
          logFile,
          gradleRunTask,
          maskedLaunchArgs,
          FAILURE_KIND_GRADLE_TIMEOUT,
          "Micro6 build timed out before the client could launch. Check "
              + logFile.toAbsolutePath(),
          PHASE_PREPARING_BUILD,
          attemptId);
    }
    // The client launched but readiness was never reached; preserve the last observed phase.
    throw new RelaunchException(
        firstNonBlank(lastReadinessPhase, PHASE_WAITING_FOR_HEALTH),
        firstNonBlank(
                lastReadinessReason,
                "Micro6 local executor did not become ready for the selected account before the local launch watchdog expired.")
            + " Check "
            + logFile.toAbsolutePath(),
        true,
        logFile,
        gradleRunTask,
        maskedLaunchArgs,
        null,
        null,
        lastReadinessPhase,
        attemptId);
  }

  /** Emits a BUILD_FAILED progress callback before a build-related terminal failure is thrown. */
  private void emitBuildFailedProgress(
      Consumer<RelaunchProgress> progressListener,
      String localExecutorBaseUrl,
      String maskedLaunchArgs,
      Path logFile,
      String failureKind,
      String failureDetail,
      String attemptId) {
    emitProgress(
        progressListener,
        new HealthCheckResult(
            Optional.empty(),
            PHASE_BUILD_FAILED,
            firstNonBlank(failureDetail, "Micro6 build failed before the client could launch"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null),
        false,
        localExecutorBaseUrl,
        gradleRunTask,
        maskedLaunchArgs,
        logFile,
        failureKind,
        failureDetail,
        attemptId);
  }

  /**
   * Returns true when the log bytes appended by the current attempt contain the sanitized {@code
   * [buildergoal] phase=LAUNCHING_PROCESS} marker emitted by {@code :client:runBuilderGoal}
   * immediately before Java execution.
   */
  private boolean hasSeenLaunchingProcessMarker(Path logFile, long attemptStartOffset) {
    List<String> lines = readLogTail(logFile, 400, attemptStartOffset);
    for (String line : lines) {
      if (line != null && line.contains("[buildergoal] phase=LAUNCHING_PROCESS")) {
        return true;
      }
    }
    return false;
  }

  private RelaunchResult buildSuccessResult(
      HealthCheckResult healthCheck,
      List<String> launchCommand,
      long launcherPid,
      Path logFile,
      String maskedLaunchArgs,
      int world,
      String localExecutorBaseUrl,
      boolean reusedExistingExecutor) {
    return buildSuccessResult(
        healthCheck,
        launchCommand,
        launcherPid,
        logFile,
        maskedLaunchArgs,
        world,
        localExecutorBaseUrl,
        reusedExistingExecutor,
        null);
  }

  private RelaunchResult buildSuccessResult(
      HealthCheckResult healthCheck,
      List<String> launchCommand,
      long launcherPid,
      Path logFile,
      String maskedLaunchArgs,
      int world,
      String localExecutorBaseUrl,
      boolean reusedExistingExecutor,
      String attemptId) {
    return new RelaunchResult(
        healthCheck.targetClient().orElseThrow(),
        reusedExistingExecutor ? List.of() : launchCommand,
        launcherPid,
        logFile,
        reusedExistingExecutor ? null : maskedLaunchArgs,
        gradleRunTask,
        world,
        localExecutorBaseUrl,
        healthCheck.readinessPhase(),
        healthCheck.readinessReason(),
        healthCheck.resolvedAccountName(),
        healthCheck.resolvedAccountHash(),
        healthCheck.requestedAccountName(),
        healthCheck.requestedAccountHash(),
        healthCheck.portOwnerPid(),
        firstNonBlank(
            healthCheck
                .targetClient()
                .map(Micro6OnlineClientRegistryService.TargetClient::status)
                .orElse(null),
            healthCheck.dispatchHold() != null && healthCheck.dispatchHold() ? "PAUSED" : null),
        healthCheck.dispatchHold(),
        healthCheck.dispatchHoldReason(),
        healthCheck.startupState(),
        healthCheck.loginState(),
        healthCheck.loginAttempt(),
        healthCheck.loginAttemptMax(),
        healthCheck.loginTerminalReason(),
        reusedExistingExecutor,
        attemptId);
  }

  private Instant launchStartedAt(Process process) {
    return Instant.now();
  }

  /**
   * Resolves the launch watchdog deadline. Overridable in tests so readiness-timeout cleanup can be
   * exercised without waiting the full production watchdog.
   */
  protected Instant resolveLaunchDeadline() {
    return Instant.now().plusMillis(Math.max(60_000L, launchTimeoutMs));
  }

  private Process startTargetProcess(
      Path projectDir,
      File logFile,
      Integer targetPort,
      String accountName,
      Integer world,
      List<String> command,
      String localExecutorBaseUrl,
      String resolvedBaseUrl)
      throws Exception {
    ProcessBuilder processBuilder =
        new ProcessBuilder(command)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
    applyBuilderGoalEnvironment(processBuilder.environment(), targetPort);

    Process process = processBuilder.start();
    logger.info(
        "Started Micro6 relaunch for account {} using launch target {} with world {} and command {} (pid={}, localHealthUrl={}, targetBaseUrl={})",
        accountName,
        gradleRunTask,
        resolveWorld(world),
        maskSensitiveCommand(command),
        process.pid(),
        localExecutorBaseUrl,
        resolvedBaseUrl);
    return process;
  }

  /**
   * An existing executor can only be reused when it is account-matched, world-matched, and fully
   * dispatch-ready. Waiting states (WAITING_FOR_ACCOUNT, WAITING_FOR_WORLD, WAITING_FOR_LOGIN,
   * WAITING_FOR_SCRIPT, WAITING_FOR_DISPATCH) are never reused; they are stopped and replaced.
   */
  private boolean canReuseExistingExecutor(HealthCheckResult healthCheck) {
    if (healthCheck == null) {
      return false;
    }
    // Only reuse when the executor is account- and world-matched and dispatch-ready (targetClient
    // present).
    return healthCheck.targetClient().isPresent()
        && healthCheck.accountMatched()
        && Boolean.TRUE.equals(healthCheck.worldMatched());
  }

  /**
   * Terminates the process tree owned by this workflow — the direct child process returned by
   * {@link ProcessBuilder#start()} and every descendant reachable through it — without ever
   * touching an arbitrary port owner. The owned root is kept alive as an anchor while descendants
   * are captured and gracefully terminated, so descendants spawned shortly after the initial
   * capture (for example by a launcher script) cannot be orphaned and outlive the failed worker.
   * Graceful termination is attempted deepest-first within a bounded window, then the root;
   * survivors are force-killed after a bounded grace period. On Windows the {@code taskkill /PID
   * <rootPid> /T /F} backstop runs while the owned root is still alive so descendants that {@link
   * ProcessHandle} could not reach cannot outlive the failed worker. Every wait is bounded and
   * interrupt status is preserved after cleanup.
   */
  private void terminateOwnedProcessTree(Process process) {
    if (process == null) {
      return;
    }
    boolean interrupted = false;
    try {
      // Keep the owned root alive as an anchor while descendants are captured and terminated so
      // the tree cannot grow unseen; late-spawned descendants are caught by re-collecting during
      // the bounded graceful window. The tree is considered empty only after it stays empty for a
      // short settle window, so a launcher that has not yet spawned its children is not killed
      // before it can.
      long gracefulDeadline =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACEFUL_TERMINATION_WAIT_MS);
      long lastObservedAlive = System.nanoTime();
      List<ProcessHandle> observed = collectOwnedDescendants(process);
      while (process.isAlive() && System.nanoTime() < gracefulDeadline) {
        observed = collectOwnedDescendants(process);
        if (anyAlive(observed)) {
          lastObservedAlive = System.nanoTime();
          destroyHandles(observed, false);
        } else if (System.nanoTime() - lastObservedAlive
            >= TimeUnit.MILLISECONDS.toNanos(OWNED_TREE_SETTLE_MS)) {
          break;
        }
        Thread.sleep(
            Math.min(
                250L,
                Math.max(
                    10L, TimeUnit.NANOSECONDS.toMillis(gracefulDeadline - System.nanoTime()))));
      }
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(GRACEFUL_TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS)) {
          // Windows backstop must run while the owned root is still alive so taskkill /T can
          // reach descendants ProcessHandle could not capture (reparented or otherwise hidden).
          if (isWindows()) {
            runTaskkillTree(process.pid());
          }
          // Force remaining owned processes after the grace period.
          List<ProcessHandle> survivors = collectOwnedDescendants(process);
          destroyHandles(survivors, true);
          process.destroyForcibly();
          awaitOwnedTreeExit(process, survivors, FORCE_TERMINATION_WAIT_MS);
        }
      }
    } catch (InterruptedException e) {
      // Preserve interrupt status and force whatever is still owned so no process leaks.
      Thread.currentThread().interrupt();
      interrupted = true;
      forceOwnedTree(process, collectOwnedDescendants(process));
    }
    if (isWindows() && process.isAlive()) {
      runTaskkillTree(process.pid());
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean anyAlive(List<ProcessHandle> handles) {
    for (ProcessHandle handle : handles) {
      if (handle.isAlive()) {
        return true;
      }
    }
    return false;
  }

  /** Captures the owned root PID's descendants, ordered deepest-first. */
  private List<ProcessHandle> collectOwnedDescendants(Process process) {
    if (process == null) {
      return List.of();
    }
    try {
      List<ProcessHandle> descendants = new ArrayList<>();
      process.descendants().forEach(descendants::add);
      descendants.sort(
          Comparator.comparingInt((ProcessHandle handle) -> ownedTreeDepth(handle, process.pid()))
              .reversed());
      return descendants;
    } catch (Exception e) {
      logger.debug(
          "Failed to enumerate owned process descendants for pid {}: {}",
          process.pid(),
          e.getMessage());
      return List.of();
    }
  }

  /** Distance (in owned-parent hops) from the owned root; deepest handles sort first. */
  private int ownedTreeDepth(ProcessHandle handle, long rootPid) {
    int depth = 0;
    Optional<ProcessHandle> parent = handle.parent();
    while (parent.isPresent()) {
      if (parent.get().pid() == rootPid) {
        return depth;
      }
      parent = parent.get().parent();
      depth++;
    }
    return Integer.MAX_VALUE;
  }

  private void destroyHandles(List<ProcessHandle> descendants, boolean force) {
    for (ProcessHandle handle : descendants) {
      if (handle.isAlive()) {
        if (force) {
          handle.destroyForcibly();
        } else {
          handle.destroy();
        }
      }
    }
  }

  /** Bounded wait for the owned root and every captured descendant to exit. */
  private boolean awaitOwnedTreeExit(
      Process process, List<ProcessHandle> descendants, long timeoutMs)
      throws InterruptedException {
    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
      return false;
    }
    for (ProcessHandle handle : descendants) {
      if (handle.isAlive()) {
        return false;
      }
    }
    return true;
  }

  private void forceOwnedTree(Process process, List<ProcessHandle> descendants) {
    destroyHandles(descendants, true);
    process.destroyForcibly();
    try {
      process.waitFor(FORCE_TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Windows backstop that kills the entire owned tree rooted at the given PID. */
  protected void runTaskkillTree(long rootPid) {
    try {
      Process process =
          new ProcessBuilder("taskkill", "/PID", String.valueOf(rootPid), "/T", "/F")
              .redirectErrorStream(true)
              .start();
      boundedWaitFor(process, 5_000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warn(
          "Interrupted while force-terminating owned process tree for pid {} via taskkill: {}",
          rootPid,
          e.getMessage());
    } catch (Exception e) {
      logger.warn(
          "Failed to force-terminate owned process tree for pid {} via taskkill: {}",
          rootPid,
          e.getMessage());
    }
  }

  /** Bounded, cancellation-aware sleep used at every launch polling boundary. */
  private void sleepPoll(long millis, BuilderGoalLaunchCancellation cancellation) throws Exception {
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    try {
      Thread.sleep(Math.max(500L, millis));
    } catch (InterruptedException e) {
      throw BuilderGoalLaunchCancelledException.fromInterrupted(e);
    }
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
  }

  private void emitProgress(
      Consumer<RelaunchProgress> progressListener,
      HealthCheckResult healthCheck,
      boolean reusedExistingExecutor,
      String localExecutorBaseUrl,
      String effectiveGradleTask,
      String maskedLaunchArgs,
      Path launchLogFile,
      String launchFailureKind,
      String launchFailureDetail) {
    emitProgress(
        progressListener,
        healthCheck,
        reusedExistingExecutor,
        localExecutorBaseUrl,
        effectiveGradleTask,
        maskedLaunchArgs,
        launchLogFile,
        launchFailureKind,
        launchFailureDetail,
        null);
  }

  private void emitProgress(
      Consumer<RelaunchProgress> progressListener,
      HealthCheckResult healthCheck,
      boolean reusedExistingExecutor,
      String localExecutorBaseUrl,
      String effectiveGradleTask,
      String maskedLaunchArgs,
      Path launchLogFile,
      String launchFailureKind,
      String launchFailureDetail,
      String attemptId) {
    if (progressListener == null || healthCheck == null) {
      return;
    }
    progressListener.accept(
        new RelaunchProgress(
            healthCheck.readinessPhase(),
            healthCheck.readinessReason(),
            localExecutorBaseUrl,
            healthCheck.resolvedAccountName(),
            healthCheck.resolvedAccountHash(),
            healthCheck.requestedAccountName(),
            healthCheck.requestedAccountHash(),
            healthCheck.portOwnerPid(),
            healthCheck.dispatchHold(),
            healthCheck.dispatchHoldReason(),
            healthCheck.startupState(),
            healthCheck.loginState(),
            healthCheck.loginAttempt(),
            healthCheck.loginAttemptMax(),
            healthCheck.loginTerminalReason(),
            effectiveGradleTask,
            maskedLaunchArgs,
            reusedExistingExecutor,
            launchLogFile != null ? launchLogFile.toString() : null,
            launchFailureKind,
            launchFailureDetail,
            attemptId));
  }

  private void emitProgress(
      Consumer<RelaunchProgress> progressListener,
      HealthCheckResult healthCheck,
      boolean reusedExistingExecutor,
      String localExecutorBaseUrl) {
    emitProgress(
        progressListener,
        healthCheck,
        reusedExistingExecutor,
        localExecutorBaseUrl,
        gradleRunTask,
        null,
        null,
        null,
        null);
  }

  private void stopExistingTargetProcess(
      Integer targetPort,
      Path projectDir,
      HealthCheckResult initialHealthCheck,
      BuilderGoalLaunchCancellation cancellation)
      throws Exception {
    Path launchLog = projectDir.resolve(launchLogFile).toAbsolutePath().normalize();
    Long verifiedPid = resolveVerifiedExecutorPid(targetPort, projectDir, initialHealthCheck);
    if (verifiedPid == null) {
      PortProbe probe = probeListeningPort(targetPort);
      if (probe.state() == PortProbeState.FREE) {
        return;
      }
      logger.warn(
          "Blocking Micro6 executor replacement: port {} ownership is unverifiable (owner={}, probe={})",
          targetPort,
          probe.pid() != null ? probe.pid() : "unknown",
          probe.detail());
      throw replacementBlocked(
          launchLog,
          "Unable to verify that the Micro6 executor port "
              + targetPort
              + " is owned by a Builder Goal process; replacement is blocked (owner="
              + (probe.pid() != null ? probe.pid() : "unknown")
              + ", probe="
              + probe.detail()
              + ")");
    }

    // Re-authorize immediately before termination: the process may have exited (its PID must not
    // be reused by an unrelated process), its start time may have changed, the listener may now be
    // owned by someone else, the command-line signature may no longer match, or the health-reported
    // process id may no longer own the listener. ALREADY_GONE skips termination and proceeds
    // directly to the final free-port confirmation.
    Instant expectedPidStartTime = processStartTime(verifiedPid);
    PortOwnershipState ownership =
        verifyTerminationTarget(
            verifiedPid, targetPort, projectDir, initialHealthCheck, expectedPidStartTime);
    if (ownership == PortOwnershipState.ALREADY_GONE) {
      awaitPortFreeOrFail(targetPort, launchLog, cancellation);
      return;
    }
    if (ownership != PortOwnershipState.VERIFIED_OWNER) {
      PortProbe probe = probeListeningPort(targetPort);
      logger.warn(
          "Blocking Micro6 executor termination: port {} ownership is unverifiable before termination"
              + " (owner={}, probe={})",
          targetPort,
          probe.pid() != null ? probe.pid() : "unknown",
          probe.detail());
      throw replacementBlocked(
          launchLog,
          "Unable to verify that the Micro6 executor port "
              + targetPort
              + " is owned by a Builder Goal process before termination; replacement is blocked (owner="
              + (probe.pid() != null ? probe.pid() : "unknown")
              + ", probe="
              + probe.detail()
              + ")");
    }

    // Terminate only the verified PID's process tree. Unrelated Java, Gradle, and Micro6
    // processes are never enumerated or killed.
    try {
      terminateVerifiedExecutorTree(verifiedPid);
    } catch (BuilderGoalLaunchCancelledException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw BuilderGoalLaunchCancelledException.fromInterrupted(e);
    } catch (Exception e) {
      throw replacementBlocked(
          launchLog,
          "Failed to terminate the verified Micro6 executor process "
              + verifiedPid
              + ": "
              + firstNonBlank(e.getMessage(), "unknown error"));
    }

    awaitPortFreeOrFail(targetPort, launchLog, cancellation);
  }

  /**
   * Resolves the single PID whose process tree may be terminated before a replacement launch.
   *
   * <p>Priority order: (1) the PID reported by a matching executor health response — only trusted
   * when the health payload actually confirmed this is a ChromaScape-launched Builder Goal executor
   * (it reported a launch attempt id or reached a dispatch-ready target), the health's reported
   * {@code process_id} matches the locally probed listener PID, AND the PID is verified to own the
   * configured listener; (2) for a stale listener with no usable health, the listener PID whose
   * command-line signature matches a Builder Goal client (Micro6 root + RuneLite/Builder Goal entry
   * point). Ownership that cannot be proven returns {@code null} so the caller blocks the
   * replacement instead of killing an arbitrary process.
   */
  private Long resolveVerifiedExecutorPid(
      Integer targetPort, Path projectDir, HealthCheckResult initialHealthCheck) {
    if (initialHealthCheck != null
        && initialHealthCheck.portOwnerPid() != null
        && initialHealthCheck.portOwnerPid() > 0) {
      long healthPid = initialHealthCheck.portOwnerPid();
      // Never trust the port-owner PID when the health payload was never reached: an unrelated
      // process could be bound to the executor port. The executor is only considered confirmed
      // when it reported a ChromaScape launch attempt id or reached a dispatch-ready target.
      boolean healthConfirmedExecutor =
          initialHealthCheck.launchAttemptId() != null
              || initialHealthCheck.targetClient().isPresent();
      // The health-reported process_id must itself own the listener before health-based
      // termination is authorized; a health response that is inconsistent with the local port
      // owner never authorizes a kill.
      boolean healthProcessIdOwnsListener =
          initialHealthCheck.processId() != null
              && initialHealthCheck.processId() > 0
              && initialHealthCheck.portOwnerPid() != null
              && initialHealthCheck.processId().longValue()
                  == initialHealthCheck.portOwnerPid().longValue();
      if (healthConfirmedExecutor
          && healthProcessIdOwnsListener
          && verifyPortOwnership(healthPid, targetPort) == PortOwnershipState.VERIFIED_OWNER) {
        return healthPid;
      }
    }
    if (targetPort != null && targetPort > 0) {
      PortProbe probe = probeListeningPort(targetPort);
      if (probe.state() == PortProbeState.OCCUPIED
          && probe.pid() != null
          && probe.pid() > 0
          && matchesBuilderGoalCommandSignature(probe.pid(), projectDir)) {
        return probe.pid();
      }
    }
    return null;
  }

  /**
   * Verifies that the executor-reported PID still owns the configured listener. The result is
   * three-state so a port that is already free ({@link PortOwnershipState#ALREADY_GONE}) can skip
   * termination instead of racing a reused Windows PID, while an UNKNOWN probe fails closed.
   */
  private PortOwnershipState verifyPortOwnership(long pid, Integer targetPort) {
    if (targetPort == null || targetPort <= 0) {
      // No port to cross-check; the health response itself proves the executor instance.
      return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
          ? PortOwnershipState.VERIFIED_OWNER
          : PortOwnershipState.ALREADY_GONE;
    }
    PortProbe probe = probeListeningPort(targetPort);
    if (probe.state() == PortProbeState.OCCUPIED && probe.pid() != null) {
      return probe.pid() == pid ? PortOwnershipState.VERIFIED_OWNER : PortOwnershipState.UNVERIFIED;
    }
    if (probe.state() == PortProbeState.FREE) {
      return PortOwnershipState.ALREADY_GONE;
    }
    return PortOwnershipState.UNVERIFIED;
  }

  /**
   * Final pre-termination authorization for a resolved executor PID. Rechecks that the PID is the
   * same process instance captured at resolution time (start time unchanged, so a reused Windows
   * PID is never targeted), that it still owns the listener, that its command line carries the
   * Builder Goal client signature, and that the health-reported process id matches the listener
   * PID. {@code ALREADY_GONE} means the verified process exited and termination must be skipped;
   * {@code UNVERIFIED} fails closed.
   */
  private PortOwnershipState verifyTerminationTarget(
      Long pid,
      Integer targetPort,
      Path projectDir,
      HealthCheckResult initialHealthCheck,
      Instant expectedPidStartTime) {
    if (pid == null || pid <= 0) {
      return PortOwnershipState.UNVERIFIED;
    }
    Optional<ProcessHandle> handle = ProcessHandle.of(pid);
    if (handle.isEmpty() || !handle.get().isAlive()) {
      return PortOwnershipState.ALREADY_GONE;
    }
    if (expectedPidStartTime != null) {
      Instant currentStartTime = processStartTime(pid);
      if (currentStartTime == null || !currentStartTime.equals(expectedPidStartTime)) {
        // The PID was reused by a different process since it was resolved.
        return PortOwnershipState.UNVERIFIED;
      }
    }
    PortOwnershipState portState = verifyPortOwnership(pid, targetPort);
    if (portState != PortOwnershipState.VERIFIED_OWNER) {
      return portState;
    }
    if (projectDir != null && !matchesBuilderGoalCommandSignature(pid, projectDir)) {
      return PortOwnershipState.UNVERIFIED;
    }
    if (initialHealthCheck != null
        && initialHealthCheck.processId() != null
        && initialHealthCheck.processId() > 0
        && initialHealthCheck.portOwnerPid() != null
        && initialHealthCheck.portOwnerPid() > 0
        && initialHealthCheck.processId().longValue()
            != initialHealthCheck.portOwnerPid().longValue()) {
      return PortOwnershipState.UNVERIFIED;
    }
    return PortOwnershipState.VERIFIED_OWNER;
  }

  private Instant processStartTime(long pid) {
    return ProcessHandle.of(pid)
        .map(handle -> handle.info().startInstant().orElse(null))
        .orElse(null);
  }

  /**
   * Returns true only when the given PID's command line carries a Builder Goal client signature:
   * the Micro6 root path and the RuneLite/Builder Goal launch entry point. The port binding is
   * already verified by the caller's port probe. Access errors or missing command lines fail
   * closed.
   */
  private boolean matchesBuilderGoalCommandSignature(long pid, Path projectDir) {
    try {
      Optional<ProcessHandle> handle = ProcessHandle.of(pid);
      if (handle.isEmpty()) {
        return false;
      }
      String commandLine = handle.get().info().commandLine().orElse("");
      if (commandLine == null || commandLine.isBlank()) {
        return false;
      }
      String projectPath = projectDir.toAbsolutePath().normalize().toString();
      String projectPathForwardSlashes = projectPath.replace('\\', '/');
      boolean hasProjectPath =
          commandLine.contains(projectPath)
              || (projectPathForwardSlashes != null
                  && commandLine.contains(projectPathForwardSlashes));
      boolean hasEntryPoint =
          commandLine.contains("net.runelite.client.RuneLite")
              || commandLine.contains("runBuilderGoal");
      return hasProjectPath && hasEntryPoint;
    } catch (Exception e) {
      logger.debug("Failed to inspect command line for pid {}: {}", pid, e.getMessage());
      return false;
    }
  }

  /**
   * Terminates the process tree rooted at a verified executor PID. The root is kept alive as an
   * anchor while descendants are captured and gracefully terminated deepest-first, then the root;
   * survivors are force-killed after a bounded grace period. On Windows the {@code taskkill /T}
   * backstop runs while the owned root is still alive.
   */
  private void terminateVerifiedExecutorTree(long rootPid) throws InterruptedException {
    ProcessHandle root = ProcessHandle.of(rootPid).orElse(null);
    if (root == null || !root.isAlive()) {
      return;
    }
    boolean interrupted = false;
    try {
      long gracefulDeadline =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACEFUL_TERMINATION_WAIT_MS);
      long lastObservedAlive = System.nanoTime();
      List<ProcessHandle> observed = collectRootDescendants(root);
      while (root.isAlive() && System.nanoTime() < gracefulDeadline) {
        observed = collectRootDescendants(root);
        if (anyAlive(observed)) {
          lastObservedAlive = System.nanoTime();
          destroyHandles(observed, false);
        } else if (System.nanoTime() - lastObservedAlive
            >= TimeUnit.MILLISECONDS.toNanos(OWNED_TREE_SETTLE_MS)) {
          break;
        }
        Thread.sleep(
            Math.min(
                250L,
                Math.max(
                    10L, TimeUnit.NANOSECONDS.toMillis(gracefulDeadline - System.nanoTime()))));
      }
      if (root.isAlive()) {
        root.destroy();
        if (!processHandleWaitFor(root, GRACEFUL_TERMINATION_WAIT_MS)) {
          if (isWindows()) {
            runTaskkillTree(root.pid());
          }
          List<ProcessHandle> survivors = collectRootDescendants(root);
          destroyHandles(survivors, true);
          root.destroyForcibly();
          awaitRootTreeExit(root, survivors, FORCE_TERMINATION_WAIT_MS);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      interrupted = true;
      destroyHandles(collectRootDescendants(root), true);
      root.destroyForcibly();
    }
    if (isWindows() && root.isAlive()) {
      runTaskkillTree(root.pid());
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Captures the root PID's descendants, ordered deepest-first. */
  private List<ProcessHandle> collectRootDescendants(ProcessHandle root) {
    if (root == null) {
      return List.of();
    }
    try {
      List<ProcessHandle> descendants = new ArrayList<>();
      root.descendants().forEach(descendants::add);
      descendants.sort(
          Comparator.comparingInt((ProcessHandle handle) -> ownedTreeDepth(handle, root.pid()))
              .reversed());
      return descendants;
    } catch (Exception e) {
      logger.debug(
          "Failed to enumerate owned process descendants for pid {}: {}",
          root.pid(),
          e.getMessage());
      return List.of();
    }
  }

  /** Bounded wait for the verified root and every captured descendant to exit. */
  private boolean awaitRootTreeExit(
      ProcessHandle root, List<ProcessHandle> descendants, long timeoutMs)
      throws InterruptedException {
    if (!processHandleWaitFor(root, timeoutMs)) {
      return false;
    }
    for (ProcessHandle handle : descendants) {
      if (handle.isAlive()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Bounded wait for a {@link ProcessHandle} to exit. {@link ProcessHandle} has no {@code
   * waitFor(long, TimeUnit)} (unlike {@link Process}), so the handle is polled until it exits or
   * the timeout elapses.
   */
  private boolean processHandleWaitFor(ProcessHandle handle, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (handle.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(
          Math.min(50L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
    }
    return !handle.isAlive();
  }

  /**
   * Waits until the targeted port listener is actually gone so a stale executor never shadows the
   * replacement. Unknown probe results fail closed; only a confirmed FREE result allows a
   * replacement process to start.
   */
  private void awaitPortFreeOrFail(
      Integer targetPort, Path launchLog, BuilderGoalLaunchCancellation cancellation)
      throws Exception {
    if (targetPort == null || targetPort <= 0) {
      return;
    }
    long portWaitDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(15_000L);
    while (System.nanoTime() < portWaitDeadline) {
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      PortProbe probe = probeListeningPort(targetPort);
      if (probe.state() == PortProbeState.FREE) {
        return;
      }
      if (probe.state() == PortProbeState.UNKNOWN) {
        throw replacementBlocked(
            launchLog,
            "Unable to verify that Micro6 executor port "
                + targetPort
                + " is free: "
                + probe.detail());
      }
      try {
        Thread.sleep(500L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw BuilderGoalLaunchCancelledException.fromInterrupted(e);
      }
    }
    PortProbe probe = probeListeningPort(targetPort);
    throw replacementBlocked(
        launchLog,
        "Micro6 executor port "
            + targetPort
            + " remains occupied after cleanup (owner="
            + (probe.pid() != null ? probe.pid() : "unknown")
            + ")");
  }

  private RelaunchException replacementBlocked(Path launchLog, String detail) {
    return new RelaunchException(
        PHASE_STOPPING_EXISTING,
        detail,
        true,
        launchLog,
        gradleRunTask,
        null,
        FAILURE_KIND_EXECUTOR_REPLACEMENT_BLOCKED,
        detail,
        PHASE_STOPPING_EXISTING,
        null);
  }

  /**
   * Non-destructively validates the Micro6 launch configuration by invoking the production
   * launcher's {@code -ValidateOnly} mode, which runs {@code gradlew.bat --no-daemon --no-watch-fs
   * help --task :client:runBuilderGoal}. It never launches the Micro6 Java client or stops existing
   * processes; Gradle may configure or build supporting included-build tasks needed to discover the
   * canonical task. A bounded, configurable timeout prevents a wedged invocation from hanging the
   * preparation. Any failure throws {@code LAUNCH_CONFIGURATION_INVALID} (or {@code
   * GRADLE_TIMEOUT}) before existing Micro6 processes are stopped.
   */
  protected void validateLaunchConfiguration(
      Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) throws Exception {
    // Validate-only is a Windows production-launcher capability. Non-Windows Gradle launches have
    // no PowerShell adapter; a broken configuration fails fast as a Gradle task failure instead.
    if (!useStructuredWindowsLaunch()) {
      return;
    }
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    Path launcherScript =
        projectDir.resolve("launch-buildergoal-client.ps1").toAbsolutePath().normalize();
    if (!Files.isRegularFile(launcherScript)) {
      throw new RelaunchException(
          PHASE_BUILD_FAILED,
          "Builder Goal launcher script does not exist: " + launcherScript,
          true,
          logFile,
          gradleRunTask,
          null,
          FAILURE_KIND_LAUNCH_CONFIGURATION_INVALID,
          "Builder Goal launcher script does not exist: " + launcherScript,
          PHASE_PREPARING_BUILD,
          null);
    }
    List<String> command =
        List.of(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            launcherScript.toString(),
            "-ValidateOnly");
    Process process = null;
    try {
      process =
          new ProcessBuilder(command)
              .directory(projectDir.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
              .start();
      long deadline =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1_000L, validationTimeoutMs));
      while (process.isAlive()) {
        if (cancellation != null) {
          cancellation.throwIfCancelled();
        }
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) {
          terminateOwnedProcessTree(process);
          throw new RelaunchException(
              PHASE_BUILD_FAILED,
              "Micro6 launch configuration validation timed out. Check " + logFile.toAbsolutePath(),
              true,
              logFile,
              gradleRunTask,
              null,
              FAILURE_KIND_GRADLE_TIMEOUT,
              "Micro6 launch configuration validation timed out",
              PHASE_PREPARING_BUILD,
              null);
        }
        process.waitFor(
            Math.min(250L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))),
            TimeUnit.MILLISECONDS);
      }
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      if (process.exitValue() != 0) {
        throw new RelaunchException(
            PHASE_BUILD_FAILED,
            "Micro6 launch configuration is invalid. Check " + logFile.toAbsolutePath(),
            true,
            logFile,
            gradleRunTask,
            null,
            FAILURE_KIND_LAUNCH_CONFIGURATION_INVALID,
            "Micro6 launch configuration validation failed with exit code " + process.exitValue(),
            PHASE_PREPARING_BUILD,
            null);
      }
    } catch (BuilderGoalLaunchCancelledException e) {
      terminateOwnedProcessTree(process);
      throw e;
    } catch (InterruptedException e) {
      terminateOwnedProcessTree(process);
      Thread.currentThread().interrupt();
      throw BuilderGoalLaunchCancelledException.fromInterrupted(e);
    } catch (RelaunchException e) {
      throw e;
    } catch (Exception e) {
      terminateOwnedProcessTree(process);
      throw e;
    }
  }

  /**
   * Verifies the production launcher contract by invoking the launcher's {@code -ContractVersion}
   * mode. ChromaScape requires contract version 2 (which guarantees {@code -ValidateOnly}, {@code
   * -CompileOnly}, and the canonical run/prepare Gradle tasks). An incompatible launcher fails
   * before any preflight or process cleanup.
   */
  protected void verifyLauncherContract(
      Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) throws Exception {
    if (!useStructuredWindowsLaunch()) {
      return;
    }
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    Path launcherScript =
        projectDir.resolve("launch-buildergoal-client.ps1").toAbsolutePath().normalize();
    if (!Files.isRegularFile(launcherScript)) {
      throw launcherContractException(
          logFile,
          "Builder Goal launcher script does not exist: " + launcherScript,
          "Builder Goal launcher script does not exist: " + launcherScript);
    }
    List<String> command =
        List.of(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            launcherScript.toString(),
            "-ContractVersion");
    Process process = null;
    try {
      ProcessBuilder builder =
          new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true);
      process = builder.start();
      long deadline =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1_000L, validationTimeoutMs));
      while (process.isAlive()) {
        if (cancellation != null) {
          cancellation.throwIfCancelled();
        }
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) {
          terminateOwnedProcessTree(process);
          throw launcherContractException(
              logFile,
              "Builder Goal launcher contract check timed out. Check " + logFile.toAbsolutePath(),
              "Builder Goal launcher contract check timed out");
        }
        process.waitFor(
            Math.min(250L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))),
            TimeUnit.MILLISECONDS);
      }
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      String output = new String(process.getInputStream().readAllBytes()).trim();
      if (process.exitValue() != 0) {
        throw launcherContractException(
            logFile,
            "Builder Goal launcher contract check failed with exit code " + process.exitValue(),
            "Builder Goal launcher contract check failed with exit code " + process.exitValue());
      }
      JsonNode contract = null;
      try {
        contract = objectMapper.readTree(output);
      } catch (Exception ignored) {
        // Classified as incompatible below.
      }
      // Contract version 3 adds the authoritative launch task and the explicit MICRO6_SOURCE_MODE
      // requirement. It is contract-significant: an older launcher defaults the mode from ambient
      // environment state instead of adopting the value ChromaScape declares, so it must be
      // rejected rather than tolerated.
      if (contract == null
          || !contract.isObject()
          || contract.path("contract_version").asInt(-1) != LAUNCHER_CONTRACT_VERSION) {
        throw launcherContractException(
            logFile,
            "Builder Goal launcher contract is incompatible (expected contract_version "
                + LAUNCHER_CONTRACT_VERSION
                + "). Check "
                + launcherScript,
            "Builder Goal launcher contract is incompatible (expected contract_version "
                + LAUNCHER_CONTRACT_VERSION
                + ")");
      }
      if (!contract.path("validate_only").asBoolean(false)
          || !contract.path("compile_only").asBoolean(false)) {
        throw launcherContractException(
            logFile,
            "Builder Goal launcher contract is missing preflight capabilities. Check "
                + launcherScript,
            "Builder Goal launcher contract is missing preflight capabilities");
      }
      // The launcher must declare that it adopts the explicitly supplied source mode. A launcher
      // that ignores MICRO6_SOURCE_MODE would reintroduce mode inference from ambient values, and a
      // launcher that advertises only some of the modes cannot honor every mode ChromaScape
      // selects.
      List<?> declaredSourceModes =
          contract.path("source_modes").isArray()
              ? objectMapper.convertValue(contract.path("source_modes"), List.class)
              : List.of();
      if (!SOURCE_MODE_ENVIRONMENT_KEY.equals(contract.path("source_mode_env").asText(null))
          || !declaredSourceModes.contains(SOURCE_MODE_DISABLED)
          || !declaredSourceModes.contains(SOURCE_MODE_AUTHORITATIVE)) {
        throw launcherContractException(
            logFile,
            "Builder Goal launcher contract does not support the explicit source mode. Check "
                + launcherScript,
            "Builder Goal launcher contract does not support the explicit source mode");
      }
      // Contract version 3 also promises the dedicated authoritative launch task. A launcher that
      // does not advertise it cannot run authoritative preparation, so it is an incomplete v3
      // contract and must be rejected even though its version number is current.
      if (!AUTHORITATIVE_GRADLE_RUN_TASK.equals(
          contract.path("authoritative_run_task").asText(null))) {
        throw launcherContractException(
            logFile,
            "Builder Goal launcher contract does not advertise the authoritative Gradle task. Check "
                + launcherScript,
            "Builder Goal launcher contract does not advertise the authoritative Gradle task");
      }
      // The launcher must advertise exactly the canonical Gradle tasks ChromaScape will invoke;
      // a launcher that runs a different run/prepare task is incompatible even when the version
      // and capability booleans match.
      if (!DEFAULT_GRADLE_RUN_TASK.equals(contract.path("run_task").asText(null))
          || !DEFAULT_GRADLE_PREPARE_TASK.equals(contract.path("prepare_task").asText(null))) {
        throw launcherContractException(
            logFile,
            "Builder Goal launcher contract does not advertise the canonical Gradle tasks. Check "
                + launcherScript,
            "Builder Goal launcher contract does not advertise the canonical Gradle tasks");
      }
    } catch (BuilderGoalLaunchCancelledException e) {
      terminateOwnedProcessTree(process);
      throw e;
    } catch (InterruptedException e) {
      terminateOwnedProcessTree(process);
      Thread.currentThread().interrupt();
      throw BuilderGoalLaunchCancelledException.fromInterrupted(e);
    } catch (RelaunchException e) {
      throw e;
    } catch (Exception e) {
      terminateOwnedProcessTree(process);
      throw e;
    }
  }

  /**
   * Compiles Micro6 production classes via the launcher's {@code -CompileOnly} mode ({@code
   * :client:prepareBuilderGoal}). This runs only after the launcher contract and configuration
   * validate successfully and always before any existing Micro6 process is stopped, so a compile
   * failure never kills a running client. A bounded, configurable timeout prevents a wedged
   * invocation from hanging the preparation.
   */
  protected void compileMicro6ProductionClasses(
      Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) throws Exception {
    if (!useStructuredWindowsLaunch()) {
      return;
    }
    if (cancellation != null) {
      cancellation.throwIfCancelled();
    }
    Path launcherScript =
        projectDir.resolve("launch-buildergoal-client.ps1").toAbsolutePath().normalize();
    if (!Files.isRegularFile(launcherScript)) {
      throw new RelaunchException(
          PHASE_BUILD_FAILED,
          "Builder Goal launcher script does not exist: " + launcherScript,
          true,
          logFile,
          gradleRunTask,
          null,
          FAILURE_KIND_GRADLE_TASK_FAILED,
          "Builder Goal launcher script does not exist: " + launcherScript,
          PHASE_PREPARING_BUILD,
          null);
    }
    List<String> command = buildCompileOnlyCommand(launcherScript);
    Process process = null;
    try {
      process =
          new ProcessBuilder(command)
              .directory(projectDir.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
              .start();
      long deadline =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1_000L, validationTimeoutMs));
      while (process.isAlive()) {
        if (cancellation != null) {
          cancellation.throwIfCancelled();
        }
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) {
          terminateOwnedProcessTree(process);
          throw new RelaunchException(
              PHASE_BUILD_FAILED,
              "Micro6 production class compilation timed out. Check " + logFile.toAbsolutePath(),
              true,
              logFile,
              gradleRunTask,
              null,
              FAILURE_KIND_GRADLE_TIMEOUT,
              "Micro6 production class compilation timed out",
              PHASE_PREPARING_BUILD,
              null);
        }
        process.waitFor(
            Math.min(250L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))),
            TimeUnit.MILLISECONDS);
      }
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      if (process.exitValue() != 0) {
        throw new RelaunchException(
            PHASE_BUILD_FAILED,
            "Micro6 production class compilation failed. Check " + logFile.toAbsolutePath(),
            true,
            logFile,
            gradleRunTask,
            null,
            FAILURE_KIND_GRADLE_TASK_FAILED,
            "Micro6 production class compilation failed with exit code " + process.exitValue(),
            PHASE_PREPARING_BUILD,
            null);
      }
    } catch (BuilderGoalLaunchCancelledException e) {
      terminateOwnedProcessTree(process);
      throw e;
    } catch (InterruptedException e) {
      terminateOwnedProcessTree(process);
      Thread.currentThread().interrupt();
      throw BuilderGoalLaunchCancelledException.fromInterrupted(e);
    } catch (RelaunchException e) {
      // The compile-only preflight finished (possibly with a nonzero exit) but any descendant
      // process still holding the log file must be reaped so the log is released and the caller
      // can clean up its working directory.
      terminateOwnedProcessTree(process);
      throw e;
    } catch (Exception e) {
      terminateOwnedProcessTree(process);
      throw e;
    }
  }

  /**
   * Builds the PowerShell invocation for the launcher's compile-only preflight mode. Compile-only
   * never passes a payload or client arguments: the launcher runs {@code
   * :client:prepareBuilderGoal} directly.
   */
  protected List<String> buildCompileOnlyCommand(Path launcherScript) {
    return List.of(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        launcherScript.toAbsolutePath().normalize().toString(),
        "-CompileOnly");
  }

  private RelaunchException launcherContractException(Path logFile, String message, String detail) {
    return new RelaunchException(
        PHASE_BUILD_FAILED,
        message,
        true,
        logFile,
        gradleRunTask,
        null,
        FAILURE_KIND_LAUNCH_CONFIGURATION_INVALID,
        detail,
        PHASE_PREPARING_BUILD,
        null);
  }

  private List<String> buildLaunchCommand(
      List<String> clientArgs,
      Path projectDir,
      String accountName,
      String accountEmail,
      String accountPassword,
      int world,
      Integer targetPort,
      String localExecutorBaseUrl)
      throws IOException {
    String runArgs = formatLaunchArgs(clientArgs);
    LaunchMode launchMode = resolveLaunchMode();
    switch (launchMode) {
      case STRUCTURED_WINDOWS:
        return buildStructuredWindowsLaunchCommand(clientArgs, projectDir);
      case CUSTOM_COMMAND_TEMPLATE:
        String expanded =
            expandLaunchCommandTemplate(
                launchCommandTemplate,
                runArgs,
                projectDir,
                accountName,
                accountEmail,
                accountPassword,
                world,
                targetPort,
                localExecutorBaseUrl);
        if (isWindows()) {
          return List.of("cmd.exe", "/c", expanded);
        }
        return List.of("bash", "-lc", expanded);
      case WINDOWS_GRADLE:
        String escapedRunArgs = runArgs.replace("\"", "\\\"");
        String commandLine =
            "call gradlew.bat \"" + gradleRunTask + "\" \"--args=" + escapedRunArgs + "\"";
        return List.of("cmd.exe", "/c", commandLine);
      case NON_WINDOWS_GRADLE:
        return List.of(
            "bash",
            "-lc",
            "./gradlew " + gradleRunTask + " '--args=" + escapeSingleQuotes(runArgs) + "'");
      default:
        throw new IllegalStateException("Unsupported Micro6 launch mode: " + launchMode);
    }
  }

  private List<String> buildStructuredWindowsLaunchCommand(List<String> clientArgs, Path projectDir)
      throws IOException {
    Path launcherScript =
        projectDir.resolve("launch-buildergoal-client.ps1").toAbsolutePath().normalize();
    if (!Files.isRegularFile(launcherScript)) {
      throw new IllegalStateException(
          "Builder Goal launcher script does not exist: " + launcherScript);
    }
    Path payloadFile = Files.createTempFile("buildergoal-launch-", ".json");
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("clientArgs", clientArgs);
      objectMapper.writeValue(payloadFile.toFile(), payload);
      restrictPayloadFile(payloadFile);
      return List.of(
          "powershell.exe",
          "-NoProfile",
          "-ExecutionPolicy",
          "Bypass",
          "-File",
          launcherScript.toString(),
          "-PayloadFile",
          payloadFile.toString());
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(payloadFile);
      throw e;
    }
  }

  /**
   * Restricts a credential-bearing payload to its owning user where the filesystem supports ACLs.
   *
   * <p>The payload is created by ChromaScape and later read by the launcher PowerShell process that
   * runs under the same account. Restricting the file is required because the payload carries
   * client credentials; but the DACL must actually let the current user read it. Java NIO's {@code
   * AclFileAttributeView.setAcl} writes an ACL that Windows denies even to the owning user when the
   * JVM is elevated (owner resolves to the Administrators group and the launcher token is UAC
   * filtered), so the restriction is applied via {@code icacls} granting the explicit current-user
   * principal, which has been verified readable by a separate PowerShell process.
   */
  private void restrictPayloadFile(Path payloadFile) throws IOException {
    String user = currentUserPrincipal();
    if (user == null || user.isBlank()) {
      logger.warn("Could not resolve current user; leaving Builder Goal payload unrestricted");
      return;
    }
    ProcessBuilder pb =
        new ProcessBuilder(
            "icacls",
            payloadFile.toAbsolutePath().toString(),
            "/inheritance:r",
            "/grant:r",
            user + ":(R,W,D)");
    pb.redirectErrorStream(true);
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      logger.warn(
          "icacls failed to start for Builder Goal payload {}: {}", payloadFile, e.getMessage());
      return;
    }
    try (java.io.InputStream in = process.getInputStream()) {
      in.transferTo(java.io.OutputStream.nullOutputStream());
    }
    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        logger.warn(
            "icacls returned {} while restricting Builder Goal payload {}", exitCode, payloadFile);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Resolves the current process user principal in a form {@code icacls} accepts (for example
   * {@code CZC8518C27\Beheerder}), or null when unavailable.
   */
  private String currentUserPrincipal() {
    try {
      java.nio.file.attribute.UserPrincipal principal =
          java.nio.file.FileSystems.getDefault()
              .getUserPrincipalLookupService()
              .lookupPrincipalByName(System.getProperty("user.name"));
      return principal == null ? null : principal.getName();
    } catch (Exception e) {
      logger.debug("Failed to resolve current user principal: {}", e.getMessage());
      return null;
    }
  }

  /** Deletes only the structured-launch payload owned by the supplied command. */
  private void deleteOwnedLaunchFiles(List<String> command) {
    if (command == null) {
      return;
    }
    for (int index = 0; index + 1 < command.size(); index++) {
      if (!"-PayloadFile".equalsIgnoreCase(command.get(index))) {
        continue;
      }
      Path payload = Paths.get(command.get(index + 1));
      try {
        Files.deleteIfExists(payload);
      } catch (Exception e) {
        logger.warn("Failed to remove owned Builder Goal payload {}: {}", payload, e.getMessage());
      }
    }
  }

  private String expandLaunchCommandTemplate(
      String template,
      String runArgs,
      Path projectDir,
      String accountName,
      String accountEmail,
      String accountPassword,
      int world,
      Integer targetPort,
      String localExecutorBaseUrl) {
    String expanded = template;
    expanded = expanded.replace("{micro6ProjectDir}", projectDir.toAbsolutePath().toString());
    expanded = expanded.replace("{runArgs}", runArgs);
    expanded =
        expanded.replace(
            "{maskedRunArgs}", firstNonBlank(maskLaunchArgs(tokenizeExtraRunArgs(runArgs)), ""));
    expanded = expanded.replace("{accountName}", firstNonBlank(accountName, ""));
    expanded = expanded.replace("{accountEmail}", firstNonBlank(accountEmail, ""));
    expanded = expanded.replace("{accountPassword}", firstNonBlank(accountPassword, ""));
    expanded = expanded.replace("{world}", String.valueOf(world));
    expanded =
        expanded.replace(
            "{targetPort}", targetPort != null && targetPort > 0 ? String.valueOf(targetPort) : "");
    expanded = expanded.replace("{localExecutorBaseUrl}", firstNonBlank(localExecutorBaseUrl, ""));
    return expanded;
  }

  private void applyBuilderGoalEnvironment(Map<String, String> environment, Integer targetPort) {
    if (environment == null) {
      return;
    }
    environment.put("MICRO6_LOCAL_EXECUTOR_ENABLED", "true");
    // ChromaScape owns this value as part of the trusted launch environment. The ordinary local
    // Builder Goal launch serves no authoritative source, and declaring that explicitly is what
    // keeps the executor's fail-closed source startup contract from being selected by whatever
    // MICRO6_SOURCE_* values the developer's .env happens to define. Without this the executor
    // refuses startup, never binds its port, and the launcher waits out the full readiness timeout
    // while RuneLite sits open. This is deliberately not a browser-controlled payload field.
    environment.put("MICRO6_SOURCE_MODE", "disabled");
    if (targetPort != null && targetPort > 0) {
      environment.put("MICRO6_EXECUTOR_BIND_ADDR", "127.0.0.1:" + targetPort);
    }
    String resolvedChromaScapeBaseUrl = firstNonBlank(chromaScapeBaseUrl, "http://localhost:8090");
    if (resolvedChromaScapeBaseUrl != null) {
      environment.put("CHROMASCAPE_BASE_URL", resolvedChromaScapeBaseUrl);
    }
    String resolvedLeeuwbotApiUrl = firstNonBlank(leeuwbotApiUrl, "http://localhost:8086");
    if (resolvedLeeuwbotApiUrl != null) {
      environment.put("LEEUWBOT_API_URL", resolvedLeeuwbotApiUrl);
    }
  }

  private List<String> buildClientArgs(
      String accountName,
      String accountHash,
      String accountEmail,
      String accountPassword,
      Integer world,
      String launchAttemptId) {
    List<String> clientArgs = new ArrayList<>();
    clientArgs.add("--clean-jagex-launcher");
    // Builder Goal automation does not need GPU rendering. Keeping it off avoids native OpenGL
    // driver crashes while leaving Account Builder enabled (unlike RuneLite's broad --safe-mode).
    clientArgs.add("--disable-gpu");
    clientArgs.add("--auto-enable-accountbuilder");
    clientArgs.add("--profile=" + accountName.trim());
    clientArgs.add("--expected-account-hash=" + accountHash.trim().toLowerCase(Locale.ENGLISH));
    if (launchAttemptId != null && !launchAttemptId.isBlank()) {
      clientArgs.add("--launch-attempt-id=" + launchAttemptId.trim());
    }
    clientArgs.add("--profile-email=" + accountEmail.trim() + ":" + accountPassword.trim());
    clientArgs.add("--world=" + resolveWorld(world));
    clientArgs.addAll(tokenizeExtraRunArgs(extraRunArgs));
    return clientArgs;
  }

  /** Compatibility helper retained for focused reflection-based tests and older callers. */
  private List<String> buildClientArgs(
      String accountName, String accountEmail, String accountPassword, Integer world) {
    return buildClientArgs(accountName, "", accountEmail, accountPassword, world, null);
  }

  private int resolveWorld(Integer world) {
    if (world != null && world >= 300 && world <= 700) {
      return world;
    }
    return defaultWorld;
  }

  protected HealthCheckResult readHealthyExecutorTarget(
      String baseUrl,
      String expectedAccountHash,
      String expectedAccountName,
      Integer requestedWorld,
      Instant seenAfter,
      long launchedProcessPid,
      String expectedAttemptId,
      BuilderGoalLaunchCancellation cancellation) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return new HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_PORT,
          "Executor base URL is not configured",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
    Integer port = parsePort(baseUrl);
    Long portOwnerPid = resolveListeningPid(port);
    if (portOwnerPid == null) {
      return new HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_PORT,
          "Micro6 local executor port "
              + firstNonBlank(port != null ? String.valueOf(port) : null, "(unknown)")
              + " is not listening yet",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
    try {
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/health"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      if (response.statusCode() >= 400) {
        return new HealthCheckResult(
            Optional.empty(),
            PHASE_WAITING_FOR_HEALTH,
            "Micro6 local executor returned HTTP "
                + response.statusCode()
                + " from /health on "
                + baseUrl,
            portOwnerPid,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }

      JsonNode payload = objectMapper.readTree(response.body());
      String reportedLaunchAttemptId = textValue(payload.path("launch_attempt_id"));
      Long healthProcessId =
          payload.path("process_id").isNumber() ? payload.path("process_id").asLong() : null;
      String resolvedAccountHash = textValue(payload.path("account_hash"));
      String resolvedAccountName = textValue(payload.path("account_name"));
      String accountHashScheme = textValue(payload.path("account_hash_scheme"));
      String requestedAccountName = textValue(payload.path("requested_account_name"));
      String requestedAccountHash = textValue(payload.path("requested_account_hash"));
      // Keep requested launch metadata separate from the identity that the running game client
      // actually resolved.  A requested hash/profile is not evidence of the account currently
      // logged in; backfilling TargetClient.accountHash from requested_account_hash would let a
      // later registry lookup treat an unresolved executor as hash-matched.  The requested values
      // remain available on HealthCheckResult for diagnostics, while TargetClient carries only
      // observed identity.
      String accountHash = resolvedAccountHash;
      String accountName = resolvedAccountName;
      // Identity is a readiness field even for legacy name-fallback callers.  A matching name
      // must never make a missing/malformed account hash or unsupported scheme publish-ready.
      if (!AccountIdentityContract.hasRequiredScheme(accountHashScheme)
          || !AccountIdentityContract.isCanonicalHash(resolvedAccountHash)) {
        return new HealthCheckResult(
            Optional.empty(),
            PHASE_WAITING_FOR_ACCOUNT,
            "Micro6 local executor did not report a canonical account hash and scheme",
            portOwnerPid,
            resolvedAccountName,
            resolvedAccountHash,
            requestedAccountName,
            requestedAccountHash,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            reportedLaunchAttemptId,
            healthProcessId,
            requestedWorld,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }
      if (expectedAccountHash != null
          && (!AccountIdentityContract.hasRequiredScheme(accountHashScheme)
              || !AccountIdentityContract.isCanonicalHash(expectedAccountHash)
              || !AccountIdentityContract.isCanonicalHash(resolvedAccountHash)
              || !AccountIdentityContract.matchesCanonicalHash(
                  resolvedAccountHash, expectedAccountHash)
              || (requestedAccountHash != null
                  && !AccountIdentityContract.matchesCanonicalHash(
                      requestedAccountHash, expectedAccountHash)))) {
        return new HealthCheckResult(
            Optional.empty(),
            PHASE_WAITING_FOR_ACCOUNT,
            "Micro6 local executor did not report the required account hash scheme "
                + AccountIdentityContract.HASH_SCHEME,
            portOwnerPid,
            resolvedAccountName,
            resolvedAccountHash,
            requestedAccountName,
            requestedAccountHash,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            reportedLaunchAttemptId,
            healthProcessId,
            requestedWorld,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }
      // Identity matching uses the resolved identity only; a requested-only identity (launch-args
      // profile) without a resolved counterpart is never sufficient to match the selected account.
      boolean accountMatched =
          matchesAccount(
              resolvedAccountHash, resolvedAccountName, expectedAccountHash, expectedAccountName);
      if (!accountMatched) {
        return new HealthCheckResult(
            Optional.empty(),
            PHASE_WAITING_FOR_ACCOUNT,
            "Micro6 local executor came up but did not identify the selected account yet",
            portOwnerPid,
            resolvedAccountName,
            resolvedAccountHash,
            requestedAccountName,
            requestedAccountHash,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            accountMatched,
            null,
            null,
            requestedWorld,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }

      Instant observedAt = Instant.now();
      if (seenAfter != null && observedAt.isBefore(seenAfter)) {
        return new HealthCheckResult(
            Optional.empty(),
            PHASE_WAITING_FOR_HEALTH,
            "Micro6 local executor health was observed before relaunch start",
            portOwnerPid,
            resolvedAccountName,
            resolvedAccountHash,
            requestedAccountName,
            requestedAccountHash,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            accountMatched,
            null,
            null,
            requestedWorld,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }

      // For a freshly launched executor the health response must prove it belongs to this launch
      // attempt: an exact launch-attempt-id match, plus a health-reported process_id that owns the
      // listener. A stale or competing matching executor with a different attempt id (or a
      // process_id that does not own the port) never satisfies readiness. Looser identity rules
      // apply only to intentional reuse before launching, where expectedAttemptId is null.
      if (expectedAttemptId != null) {
        boolean attemptMatches = expectedAttemptId.equals(reportedLaunchAttemptId);
        boolean processMatchesListener =
            healthProcessId != null
                && portOwnerPid != null
                && healthProcessId.longValue() == portOwnerPid.longValue();
        if (!attemptMatches || !processMatchesListener) {
          return new HealthCheckResult(
              Optional.empty(),
              PHASE_WAITING_FOR_HEALTH,
              "Micro6 local executor health does not report this launch attempt yet"
                  + " (launch-attempt-matched="
                  + attemptMatches
                  + ", listener-matched="
                  + processMatchesListener
                  + ")",
              portOwnerPid,
              resolvedAccountName,
              resolvedAccountHash,
              requestedAccountName,
              requestedAccountHash,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              accountMatched,
              reportedLaunchAttemptId,
              healthProcessId,
              requestedWorld,
              null,
              null,
              null,
              null,
              null,
              null,
              null);
        }
      }

      Boolean dispatchHold =
          payload.path("dispatch_hold").isBoolean()
              ? payload.path("dispatch_hold").asBoolean()
              : null;
      String dispatchHoldReason = textValue(payload.path("dispatch_hold_reason"));
      String startupState = textValue(payload.path("startup_state"));
      String loginState = textValue(payload.path("login_state"));
      Integer loginAttempt =
          payload.path("login_attempt").isNumber() ? payload.path("login_attempt").asInt() : null;
      Integer loginAttemptMax =
          payload.path("login_attempt_max").isNumber()
              ? payload.path("login_attempt_max").asInt()
              : null;
      String loginTerminalReason = textValue(payload.path("login_terminal_reason"));
      // World verification: never label the requested world as the observed world unless Micro6
      // confirms the client is logged into it. Missing observed world is never matched.
      Integer observedWorld = intValue(payload.path("current_world"));
      boolean worldMatched =
          requestedWorld != null
              && observedWorld != null
              && requestedWorld.intValue() == observedWorld.intValue();
      boolean pathfinderReady = payload.path("pathfinder_ready").asBoolean(false);
      String scriptLifecycleState = textValue(payload.path("script_lifecycle_state"));
      boolean scriptLoopAlive = payload.path("script_loop_alive").asBoolean(false);
      String scriptLoopHeartbeatAt = textValue(payload.path("script_loop_heartbeat_at"));
      Long scriptLoopHeartbeatAgeMs =
          payload.path("script_loop_heartbeat_age_ms").isNumber()
              ? payload.path("script_loop_heartbeat_age_ms").asLong()
              : null;
      // Shared readiness classification in the canonical ordering login → world → script →
      // dispatch. A client still logging in is reported as WAITING_FOR_LOGIN, never as
      // WAITING_FOR_WORLD.
      Micro6ExecutorReadinessEvaluator.Evaluation readiness =
          Micro6ExecutorReadinessEvaluator.evaluate(
              dispatchHold,
              dispatchHoldReason,
              startupState,
              loginState,
              loginAttempt,
              loginAttemptMax,
              loginTerminalReason,
              worldMatched,
              requestedWorld,
              observedWorld,
              pathfinderReady,
              scriptLoopAlive,
              scriptLifecycleState);
      if (!readiness.publishReady()) {
        return new HealthCheckResult(
            Optional.empty(),
            readiness.phase(),
            readiness.reason(),
            portOwnerPid,
            resolvedAccountName,
            resolvedAccountHash,
            requestedAccountName,
            requestedAccountHash,
            dispatchHold,
            dispatchHoldReason,
            startupState,
            loginState,
            loginAttempt,
            loginAttemptMax,
            loginTerminalReason,
            accountMatched,
            reportedLaunchAttemptId,
            healthProcessId,
            requestedWorld,
            observedWorld,
            worldMatched,
            pathfinderReady,
            scriptLifecycleState,
            scriptLoopAlive,
            scriptLoopHeartbeatAt,
            scriptLoopHeartbeatAgeMs);
      }

      String advertisedBaseUrl =
          firstNonBlank(textValue(payload.path("advertised_base_url")), baseUrl);
      if (cancellation != null) {
        cancellation.throwIfCancelled();
      }
      return new HealthCheckResult(
          Optional.of(
              new Micro6OnlineClientRegistryService.TargetClient(
                  firstNonBlank(
                      textValue(payload.path("executor_instance_id")),
                      advertisedBaseUrl,
                      expectedAccountHash,
                      expectedAccountName),
                  accountName,
                  accountHash,
                  advertisedBaseUrl,
                  textValue(payload.path("executor_bind_addr")),
                  "micro6",
                  firstNonBlank(
                      textValue(payload.path("executor_profile")), "accountbuilder_builderpro"),
                  firstNonBlank(textValue(payload.path("status")), "HEALTHY"),
                  textValue(payload.path("current_task")),
                  observedAt,
                  requestedWorld,
                  observedWorld,
                  worldMatched,
                  scriptLifecycleState,
                  scriptLoopAlive,
                  dispatchHold,
                  dispatchHoldReason,
                  startupState,
                  loginState,
                  pathfinderReady,
                  reportedLaunchAttemptId,
                  healthProcessId != null ? healthProcessId.intValue() : null,
                  AccountIdentityContract.HASH_SCHEME,
                  requestedAccountHash)),
          PHASE_READY,
          null,
          portOwnerPid,
          resolvedAccountName,
          resolvedAccountHash,
          requestedAccountName,
          requestedAccountHash,
          dispatchHold,
          dispatchHoldReason,
          startupState,
          loginState,
          loginAttempt,
          loginAttemptMax,
          loginTerminalReason,
          accountMatched,
          reportedLaunchAttemptId,
          healthProcessId,
          requestedWorld,
          observedWorld,
          worldMatched,
          pathfinderReady,
          scriptLifecycleState,
          scriptLoopAlive,
          scriptLoopHeartbeatAt,
          scriptLoopHeartbeatAgeMs);
    } catch (BuilderGoalLaunchCancelledException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BuilderGoalLaunchCancelledException();
    } catch (Exception e) {
      return new HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_HEALTH,
          "Micro6 local executor /health on "
              + baseUrl
              + " is not reachable yet"
              + (portOwnerPid != null
                  ? " (port owner pid="
                      + portOwnerPid
                      + ", launched pid="
                      + launchedProcessPid
                      + ")"
                  : ""),
          portOwnerPid,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
  }

  private String resolveLocalExecutorBaseUrl(Integer port) {
    int resolvedPort = port != null && port > 0 ? port : 8098;
    return "http://127.0.0.1:" + resolvedPort;
  }

  /**
   * Validates the resolved Micro6 project directory before any launch work is attempted. The
   * directory must exist and be a directory; the launch entry point is validated according to the
   * command that will actually run.
   */
  private void validateMicro6ProjectDir(Path projectDir) {
    if (!projectDir.toFile().isDirectory()) {
      throw new IllegalStateException(
          "Configured Micro6 project directory does not exist or is not a directory: "
              + projectDir
              + " (launch mode: "
              + resolveLaunchMode()
              + ")");
    }
    LaunchMode launchMode = resolveLaunchMode();
    String requiredEntryPoint = resolveRequiredLaunchEntryPoint(launchMode);
    if (requiredEntryPoint == null) {
      return;
    }
    Path entryPoint = projectDir.resolve(requiredEntryPoint);
    if (!Files.isRegularFile(entryPoint)) {
      throw new IllegalStateException(
          "Configured Micro6 project directory "
              + projectDir
              + " (launch mode: "
              + launchMode
              + ") is missing a regular required entry point: "
              + entryPoint);
    }
    if ("gradlew".equals(requiredEntryPoint) && !isWindows() && !entryPoint.toFile().canExecute()) {
      throw new IllegalStateException(
          "Configured Micro6 project directory "
              + projectDir
              + " (launch mode: "
              + launchMode
              + ") contains gradlew but it is not executable: "
              + entryPoint);
    }
  }

  /**
   * Resolves the launch mode used for both validation and command construction so the two cannot
   * disagree.
   *
   * @return the launch mode used for validation and process construction
   */
  private LaunchMode resolveLaunchMode() {
    if (useStructuredWindowsLaunch()) {
      return LaunchMode.STRUCTURED_WINDOWS;
    }
    if (launchCommandTemplate != null) {
      return LaunchMode.CUSTOM_COMMAND_TEMPLATE;
    }
    return isWindows() ? LaunchMode.WINDOWS_GRADLE : LaunchMode.NON_WINDOWS_GRADLE;
  }

  /**
   * Resolves the effective Gradle task reported by progress responses, run summaries, and launch
   * operation metadata from the resolved launch mode. The structured Windows launch always reports
   * the canonical run task — a configured legacy custom launch template is ignored on Windows and
   * must never relabel the metadata as {@code custom_launch_command}. The template label applies
   * only when the template mode is actually selected (non-Windows); direct Gradle launches report
   * the configured canonical task.
   */
  private String resolveEffectiveGradleTask(String configuredGradleRunTask) {
    LaunchMode launchMode = resolveLaunchMode();
    if (launchMode == LaunchMode.STRUCTURED_WINDOWS) {
      return DEFAULT_GRADLE_RUN_TASK;
    }
    if (launchMode == LaunchMode.CUSTOM_COMMAND_TEMPLATE) {
      return CUSTOM_LAUNCH_COMMAND;
    }
    return firstNonBlank(normalizeOptionalString(configuredGradleRunTask), DEFAULT_GRADLE_RUN_TASK);
  }

  /**
   * Returns the repository entry-point file (relative to the project directory) required by the
   * resolved launch mode, or {@code null} when no repository entry-point requirement applies.
   */
  private String resolveRequiredLaunchEntryPoint(LaunchMode launchMode) {
    if (launchMode == LaunchMode.STRUCTURED_WINDOWS) {
      return "launch-buildergoal-client.ps1";
    }
    if (launchMode == LaunchMode.CUSTOM_COMMAND_TEMPLATE) {
      return null;
    }
    return launchMode == LaunchMode.WINDOWS_GRADLE ? "gradlew.bat" : "gradlew";
  }

  protected Long resolveListeningPid(Integer port) {
    PortProbe probe = probeListeningPort(port);
    return probe.state() == PortProbeState.OCCUPIED ? probe.pid() : null;
  }

  /**
   * Performs a bounded, fail-closed listener probe without reading a potentially open stream first.
   */
  protected PortProbe probeListeningPort(Integer port) {
    if (port == null || port <= 0) {
      return new PortProbe(PortProbeState.FREE, null, "no port configured");
    }
    PortProbe primary = runListenerProbe(port);
    if (primary.state() != PortProbeState.UNKNOWN || !isWindows()) {
      return primary;
    }
    // The PowerShell probe is CIM/WMI-backed and can stall or fail under load. A transient
    // indeterminate result must not be treated as "port occupied by an unverifiable process",
    // because that blocks replacement of an executor that is already gone. Fall back to the
    // native, locale-independent netstat probe (retried once) before failing closed.
    PortProbe fallback = runWindowsNetstatProbe(port);
    if (fallback.state() == PortProbeState.UNKNOWN) {
      sleepQuietly(PORT_PROBE_FALLBACK_RETRY_DELAY_MS);
      fallback = runWindowsNetstatProbe(port);
    }
    if (fallback.state() != PortProbeState.UNKNOWN) {
      logger.warn(
          "PowerShell listener probe for port {} was indeterminate ({}); netstat reported {} ({})",
          port,
          primary.detail(),
          fallback.state(),
          fallback.detail());
      return fallback;
    }
    logger.warn(
        "Listener probe for port {} is indeterminate (powershell={}, netstat={})",
        port,
        primary.detail(),
        fallback.detail());
    return primary;
  }

  private PortProbe runListenerProbe(Integer port) {
    try {
      List<String> command =
          isWindows() ? buildWindowsPortProbeCommand(port) : buildUnixPortProbeCommand(port);
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      boolean finished = boundedWaitFor(process, PORT_PROBE_TIMEOUT_MS);
      if (!finished) {
        return new PortProbe(PortProbeState.UNKNOWN, null, "listener probe timed out");
      }
      String value = new String(process.getInputStream().readAllBytes()).trim();
      int exitCode = process.exitValue();
      return parseListeningPortProbeResult(value, exitCode, isWindows());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new PortProbe(PortProbeState.UNKNOWN, null, "listener probe interrupted");
    } catch (Exception e) {
      return new PortProbe(
          PortProbeState.UNKNOWN,
          null,
          "listener probe failed: " + firstNonBlank(e.getMessage(), "unknown error"));
    }
  }

  /**
   * Windows-only fallback used when the PowerShell listener probe is indeterminate. It parses
   * {@code netstat -ano -p tcp} structurally — a LISTENING socket has the target port in its local
   * endpoint and the all-zero wildcard as its foreign endpoint — instead of matching the localized
   * state column, so it is stable across Windows display languages.
   */
  protected PortProbe runWindowsNetstatProbe(Integer port) {
    try {
      Process process =
          new ProcessBuilder("netstat", "-ano", "-p", "tcp").redirectErrorStream(true).start();
      boolean finished = boundedWaitFor(process, PORT_PROBE_TIMEOUT_MS);
      if (!finished) {
        return new PortProbe(PortProbeState.UNKNOWN, null, "netstat listener probe timed out");
      }
      String output = new String(process.getInputStream().readAllBytes());
      int exitCode = process.exitValue();
      return parseWindowsNetstatProbeOutput(output, exitCode, port);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new PortProbe(PortProbeState.UNKNOWN, null, "netstat listener probe interrupted");
    } catch (Exception e) {
      return new PortProbe(
          PortProbeState.UNKNOWN,
          null,
          "netstat listener probe failed: " + firstNonBlank(e.getMessage(), "unknown error"));
    }
  }

  protected PortProbe parseWindowsNetstatProbeOutput(String output, int exitCode, Integer port) {
    if (exitCode != 0) {
      return new PortProbe(PortProbeState.UNKNOWN, null, "netstat probe exited with " + exitCode);
    }
    if (output == null || output.isBlank()) {
      return new PortProbe(PortProbeState.UNKNOWN, null, "netstat probe returned no result");
    }
    String suffix = ":" + port;
    for (String rawLine : output.split("\\R")) {
      String line = rawLine == null ? "" : rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      String[] parts = line.split("\\s+");
      if (parts.length < 5) {
        continue;
      }
      if (!parts[1].endsWith(suffix) || !isWildcardForeignEndpoint(parts[2])) {
        continue;
      }
      String pidText = parts[parts.length - 1];
      if (pidText.isEmpty() || !pidText.matches("[0-9]+")) {
        continue;
      }
      try {
        long pid = Long.parseLong(pidText);
        if (pid > 0L) {
          return new PortProbe(PortProbeState.OCCUPIED, pid, "netstat listener present");
        }
      } catch (NumberFormatException ignored) {
        // Keep scanning the remaining lines.
      }
    }
    return new PortProbe(PortProbeState.FREE, null, "no listener (netstat)");
  }

  private boolean isWildcardForeignEndpoint(String foreign) {
    return "0.0.0.0:0".equals(foreign) || "[::]:0".equals(foreign) || "*:*".equals(foreign);
  }

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Builds the Windows PowerShell port-probe command. The script emits exactly one protocol line:
   * {@code PORT_FREE} when no listener is present, or {@code PORT_PID=<owner>} for the first owning
   * PID. {@code CmdletizationQuery_NotFound} (the known "no listener" diagnostic from {@code
   * Get-NetTCPConnection}) is handled entirely inside PowerShell through the structured {@code
   * FullyQualifiedErrorId} filter; Java never trusts that text. Unexpected errors throw and exit
   * nonzero, which Java classifies as {@code UNKNOWN} (replacement-blocking).
   */
  protected List<String> buildWindowsPortProbeCommand(Integer port) {
    String script =
        "$ErrorActionPreference='Stop'; $probeErrors=@(); $connections=@(Get-NetTCPConnection -LocalPort "
            + port
            + " -State Listen -ErrorAction SilentlyContinue -ErrorVariable +probeErrors); "
            + "$unexpected=@($probeErrors | Where-Object { $_.FullyQualifiedErrorId -notlike 'CmdletizationQuery_NotFound*' }); "
            + "if ($unexpected.Count -gt 0) { throw $unexpected[0] }; "
            + "$owner=($connections | Select-Object -ExpandProperty OwningProcess | Select-Object -First 1); "
            + "if ($null -ne $owner) { [Console]::Out.Write('PORT_PID=' + $owner) } else { [Console]::Out.Write('PORT_FREE') }";
    return List.of(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
  }

  /**
   * Builds the Unix port-probe command using lsof. Emits exactly one protocol line: {@code
   * PORT_FREE} when no listener is present, or {@code PORT_PID=<pid>} for the first owning PID. A
   * genuine lsof failure exits nonzero so Java classifies the result as {@code UNKNOWN}.
   */
  protected List<String> buildUnixPortProbeCommand(Integer port) {
    return List.of(
        "bash",
        "-lc",
        "pid=$(lsof -ti tcp:"
            + port
            + " -sTCP:LISTEN 2>/dev/null | head -n 1); "
            + "rc=${PIPESTATUS[0]}; "
            + "if [ -n \"$pid\" ]; then printf 'PORT_PID=%s\\n' \"$pid\"; exit 0; fi; "
            + "case \"$rc\" in 0|1) printf 'PORT_FREE\\n'; exit 0;; *) exit 2;; esac");
  }

  /**
   * Classifies bounded listener-probe output using the strict {@code PORT_FREE} / {@code
   * PORT_PID=n} subprocess protocol. Any nonzero exit is {@code UNKNOWN} regardless of output.
   * {@code PORT_FREE} with exit 0 is {@code FREE}; exactly one valid positive {@code PORT_PID=n}
   * with exit 0 is {@code OCCUPIED}. Blank output, diagnostic text, multiple protocol lines, a
   * zero/negative/overflowing PID, or any malformed output is {@code UNKNOWN} (fail-closed).
   */
  protected PortProbe parseListeningPortProbeResult(
      String output, int exitCode, boolean windowsProbe) {
    if (exitCode != 0) {
      return new PortProbe(PortProbeState.UNKNOWN, null, "listener probe exited with " + exitCode);
    }
    String value = output == null ? "" : output.trim();
    if (value.isBlank()) {
      return new PortProbe(PortProbeState.UNKNOWN, null, "listener probe returned no result");
    }
    List<String> lines = new ArrayList<>();
    for (String line : value.split("\\R")) {
      if (line != null && !line.isBlank()) {
        lines.add(line.trim());
      }
    }
    if (lines.size() != 1) {
      return new PortProbe(
          PortProbeState.UNKNOWN, null, "listener probe returned multiple protocol lines");
    }
    String line = lines.get(0);
    if ("PORT_FREE".equals(line)) {
      return new PortProbe(PortProbeState.FREE, null, "no listener");
    }
    if (!line.startsWith("PORT_PID=")) {
      return new PortProbe(PortProbeState.UNKNOWN, null, "listener probe returned invalid PID");
    }
    String pidText = line.substring("PORT_PID=".length());
    if (pidText.isEmpty() || !pidText.matches("[0-9]+")) {
      return new PortProbe(PortProbeState.UNKNOWN, null, "listener probe returned invalid PID");
    }
    try {
      long pid = Long.parseLong(pidText);
      if (pid <= 0L) {
        return new PortProbe(PortProbeState.UNKNOWN, null, "listener probe returned invalid PID");
      }
      return new PortProbe(PortProbeState.OCCUPIED, pid, "listener present");
    } catch (NumberFormatException e) {
      return new PortProbe(PortProbeState.UNKNOWN, null, "listener probe returned invalid PID");
    }
  }

  /** Waits for a short OS helper command to finish within a bounded window. */
  private boolean boundedWaitFor(Process process, long timeoutMs) throws InterruptedException {
    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
      // This helper is also used by runTaskkillTree itself; do not recurse through the full
      // Windows backstop while terminating the backstop process.
      process.destroy();
      if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
      }
      return false;
    }
    return true;
  }

  private boolean matchesAccount(
      String actualAccountHash,
      String actualAccountName,
      String expectedAccountHash,
      String expectedAccountName) {
    // A selected canonical hash is authoritative. Neither the launch profile nor a matching
    // display name may satisfy a different actual game-account hash.
    if (expectedAccountHash != null) {
      return AccountIdentityContract.matchesCanonicalHash(actualAccountHash, expectedAccountHash);
    }
    // Name fallback is retained only for legacy flows that have no expected hash at all, but it
    // still requires a canonical actual hash. A name-only or malformed health response is never
    // evidence that the selected game account is logged in.
    if (!AccountIdentityContract.isCanonicalHash(actualAccountHash)) {
      return false;
    }
    String normalizedActualName = AccountIdentityContract.normalizeName(actualAccountName);
    String normalizedExpectedName = AccountIdentityContract.normalizeName(expectedAccountName);
    return normalizedActualName != null
        && normalizedExpectedName != null
        && normalizedExpectedName.equals(normalizedActualName);
  }

  private String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  private Integer intValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (!node.isNumber()) {
      return null;
    }
    return node.asInt();
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private String normalizeBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.isBlank() ? null : normalized;
  }

  private String formatLaunchArgs(List<String> launchArgs) {
    if (launchArgs == null || launchArgs.isEmpty()) {
      return null;
    }
    List<String> formatted = new ArrayList<>();
    for (String part : launchArgs) {
      if (part == null || part.isBlank()) {
        continue;
      }
      formatted.add(quoteLaunchArg(part));
    }
    return formatted.isEmpty() ? null : String.join(" ", formatted);
  }

  private String maskLaunchArgs(List<String> launchArgs) {
    if (launchArgs == null || launchArgs.isEmpty()) {
      return null;
    }
    List<String> masked = new ArrayList<>();
    for (String part : launchArgs) {
      if (part == null || part.isBlank()) {
        continue;
      }
      if (part.startsWith("--profile-email=")) {
        int colonIndex = part.indexOf(':');
        if (colonIndex > 0) {
          masked.add(part.substring(0, colonIndex + 1) + "********");
        } else {
          masked.add(part);
        }
      } else if (part.toLowerCase(Locale.ENGLISH).startsWith("password=")) {
        masked.add("password=********");
      } else {
        masked.add(part);
      }
    }
    return formatLaunchArgs(masked);
  }

  private String maskSensitiveCommand(List<String> command) {
    if (command == null || command.isEmpty()) {
      return "[]";
    }
    return firstNonBlank(formatLaunchArgs(command), "[]");
  }

  private List<String> tokenizeExtraRunArgs(String rawArgs) {
    String normalized = normalizeOptionalString(rawArgs);
    if (normalized == null) {
      return List.of();
    }
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inSingleQuotes = false;
    boolean inDoubleQuotes = false;
    boolean escaping = false;
    for (int i = 0; i < normalized.length(); i++) {
      char ch = normalized.charAt(i);
      if (escaping) {
        current.append(ch);
        escaping = false;
        continue;
      }
      if (ch == '\\' && !inSingleQuotes) {
        escaping = true;
        continue;
      }
      if (ch == '"' && !inSingleQuotes) {
        inDoubleQuotes = !inDoubleQuotes;
        continue;
      }
      if (ch == '\'' && !inDoubleQuotes) {
        inSingleQuotes = !inSingleQuotes;
        continue;
      }
      if (Character.isWhitespace(ch) && !inSingleQuotes && !inDoubleQuotes) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(ch);
    }
    if (escaping) {
      current.append('\\');
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  private String quoteLaunchArg(String arg) {
    if (arg == null) {
      return "";
    }
    if (!arg.chars().anyMatch(Character::isWhitespace) && arg.indexOf('"') < 0) {
      return arg;
    }
    return "\"" + arg.replace("\"", "\\\"") + "\"";
  }

  private Integer parsePort(String targetBaseUrl) {
    if (targetBaseUrl == null || targetBaseUrl.isBlank()) {
      return null;
    }
    try {
      URI uri = URI.create(targetBaseUrl.trim());
      return uri.getPort() > 0 ? uri.getPort() : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  protected boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("win");
  }

  protected boolean useStructuredWindowsLaunch() {
    return isWindows();
  }

  private String normalizeOptionalString(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * Classifies a terminal executor startup refusal from this attempt's own log bytes. Returns null
   * when the attempt has published no fatal marker, so ordinary readiness polling is unaffected.
   *
   * <p>The detail is built only from a fixed marker list and the log path. Raw log content is never
   * forwarded, so a sanitized reason can be surfaced to the launch-operation response.
   */
  private LaunchFailure detectFatalExecutorStartupFailure(Path logFile, long attemptStartOffset) {
    String marker = detectFatalExecutorStartupMarker(readLogTail(logFile, 240, attemptStartOffset));
    if (marker == null) {
      return null;
    }
    return new LaunchFailure(
        FAILURE_KIND_EXECUTOR_STARTUP_REJECTED,
        "Micro6 local executor refused startup: "
            + marker
            + ". It will not listen on the executor port for this attempt, so the launch was stopped "
            + "instead of waiting for readiness. Check "
            + logFile.toAbsolutePath(),
        false);
  }

  private LaunchFailure analyzeLaunchFailure(Path logFile, long attemptStartOffset) {
    List<String> lines = readLogTail(logFile, 240, attemptStartOffset);
    String combined = String.join("\n", lines);
    if (combined.contains("Unable to delete directory")
        && combined.contains("build\\classes\\java\\main")) {
      return new LaunchFailure(
          FAILURE_KIND_BUILD_OUTPUT_LOCK,
          "Micro6 relaunch compile step failed because build output is locked by an existing Micro6 or Gradle process. Check "
              + logFile.toAbsolutePath(),
          true);
    }
    if (!combined.contains("BUILD FAILED") && !combined.contains("BUILD SUCCESSFUL")) {
      if (combined.contains("Cannot locate tasks that match")) {
        return new LaunchFailure(
            FAILURE_KIND_LAUNCH_CONFIGURATION_INVALID,
            "Micro6 Gradle task is not available. Verify the :client:prepareBuilderGoal and :client:runBuilderGoal tasks exist in the Micro6 root composite build. Check "
                + logFile.toAbsolutePath(),
            false);
      }
    }
    String extractedFailureDetail = extractLaunchFailureDetail(lines);
    if (extractedFailureDetail != null) {
      boolean launchedJvmProcessFailure = isLaunchedJvmProcessFailure(combined);
      return new LaunchFailure(
          combined.contains("BUILD FAILED") && !launchedJvmProcessFailure
              ? FAILURE_KIND_GRADLE_TASK_FAILED
              : FAILURE_KIND_PROCESS_EXITED,
          launchedJvmProcessFailure
              ? addNativeWindowsExitHint(extractedFailureDetail)
              : extractedFailureDetail,
          false);
    }
    if (combined.contains("BUILD FAILED")) {
      return new LaunchFailure(
          FAILURE_KIND_GRADLE_TASK_FAILED,
          "Micro6 relaunch Gradle run failed before the local executor became ready. Check "
              + logFile.toAbsolutePath(),
          false);
    }
    return new LaunchFailure(
        FAILURE_KIND_PROCESS_EXITED,
        "Micro6 relaunch process exited before the local executor became ready. Check "
            + logFile.toAbsolutePath(),
        false);
  }

  /**
   * Distinguishes a Gradle build/configuration failure from the JavaExec child process crashing.
   * Gradle prints {@code BUILD FAILED} for both cases, but once {@code runBuilderGoal} launched
   * RuneLite, a nonzero Java process exit is a runtime failure and must not be presented to the
   * operator as "the client could not launch".
   */
  private boolean isLaunchedJvmProcessFailure(String output) {
    if (output == null || output.isBlank()) {
      return false;
    }
    return output.contains("Execution failed for task ':client:runBuilderGoal'.")
        && output.contains("Process 'command '")
        && output.contains("java")
        && output.contains("finished with non-zero exit value");
  }

  private String addNativeWindowsExitHint(String detail) {
    if (detail.contains("-1073741819") && !detail.contains("0xC0000005")) {
      return detail + "\nNative Windows exit code: 0xC0000005 (EXCEPTION_ACCESS_VIOLATION).";
    }
    return detail;
  }

  private String extractLaunchFailureDetail(List<String> lines) {
    if (lines == null || lines.isEmpty()) {
      return null;
    }
    LaunchFailureExcerpt latestFailure = null;
    latestFailure = preferLaterFailure(latestFailure, extractGradleFailureBlock(lines));
    latestFailure = preferLaterFailure(latestFailure, extractRuneLiteStartupFailure(lines));
    latestFailure = preferLaterFailure(latestFailure, extractThrowableBlock(lines));
    latestFailure = preferLaterFailure(latestFailure, extractKnownLaunchError(lines));
    return latestFailure != null ? latestFailure.detail() : null;
  }

  private LaunchFailureExcerpt extractGradleFailureBlock(List<String> lines) {
    int start = lastIndexContaining(lines, "FAILURE: Build failed with an exception.");
    if (start < 0) {
      return null;
    }
    List<String> excerpt = new ArrayList<>();
    for (int i = start; i < lines.size(); i++) {
      String line = lines.get(i);
      if (!excerpt.isEmpty()
          && (line.startsWith("BUILD FAILED in ") || line.startsWith("> Task "))) {
        break;
      }
      excerpt.add(line);
      if (line.startsWith("* What went wrong:")) {
        for (int j = i + 1; j < lines.size(); j++) {
          String whatWentWrongLine = lines.get(j);
          if (whatWentWrongLine.startsWith("* Try:")
              || whatWentWrongLine.startsWith("BUILD FAILED in ")
              || whatWentWrongLine.startsWith("> Task ")) {
            break;
          }
          excerpt.add(whatWentWrongLine);
          i = j;
        }
      }
    }
    return buildFailureExcerpt(start, compactFailureExcerpt(excerpt));
  }

  private LaunchFailureExcerpt extractRuneLiteStartupFailure(List<String> lines) {
    int start = lastIndexContaining(lines, "Failure during startup");
    if (start < 0) {
      return null;
    }
    List<String> excerpt = new ArrayList<>();
    for (int i = start; i < lines.size(); i++) {
      String line = lines.get(i);
      if (!excerpt.isEmpty()
          && looksLikeTimestampedLogLine(line)
          && !line.contains("Failure during startup")
          && !line.contains("Caused by:")
          && !line.stripLeading().startsWith("at ")) {
        break;
      }
      excerpt.add(stripLogPrefix(line));
    }
    return buildFailureExcerpt(start, compactFailureExcerpt(excerpt));
  }

  private LaunchFailureExcerpt extractKnownLaunchError(List<String> lines) {
    LaunchFailureExcerpt latestFailure = null;
    latestFailure =
        preferLaterFailure(
            latestFailure, lastMatchingLine(lines, "Cannot locate tasks that match"));
    latestFailure =
        preferLaterFailure(latestFailure, lastMatchingLine(lines, "The command line is too long."));
    latestFailure =
        preferLaterFailure(
            latestFailure, lastMatchingLine(lines, "Error: Could not find or load main class"));
    latestFailure =
        preferLaterFailure(latestFailure, lastMatchingLine(lines, "Exception in thread "));
    latestFailure =
        preferLaterFailure(latestFailure, lastMatchingLine(lines, "ClassNotFoundException"));
    latestFailure =
        preferLaterFailure(latestFailure, lastMatchingLine(lines, "NoClassDefFoundError"));
    return latestFailure;
  }

  private LaunchFailureExcerpt extractThrowableBlock(List<String> lines) {
    for (int i = lines.size() - 1; i >= 0; i--) {
      String line = stripLogPrefix(lines.get(i));
      if (!looksLikeThrowableStart(line)) {
        continue;
      }
      List<String> excerpt = new ArrayList<>();
      for (int j = i; j < lines.size(); j++) {
        String current = stripLogPrefix(lines.get(j));
        if (j > i && !looksLikeThrowableContinuation(current)) {
          break;
        }
        excerpt.add(current);
      }
      return buildFailureExcerpt(i, compactFailureExcerpt(excerpt));
    }
    return null;
  }

  private LaunchFailureExcerpt preferLaterFailure(
      LaunchFailureExcerpt current, LaunchFailureExcerpt candidate) {
    if (candidate == null || candidate.detail() == null || candidate.detail().isBlank()) {
      return current;
    }
    if (current == null || candidate.lineIndex() > current.lineIndex()) {
      return candidate;
    }
    return current;
  }

  private LaunchFailureExcerpt buildFailureExcerpt(int lineIndex, String detail) {
    String normalized = normalizeOptionalString(detail);
    if (lineIndex < 0 || normalized == null) {
      return null;
    }
    return new LaunchFailureExcerpt(lineIndex, normalized);
  }

  private String compactFailureExcerpt(List<String> excerpt) {
    if (excerpt == null || excerpt.isEmpty()) {
      return null;
    }
    List<String> cleaned = new ArrayList<>();
    boolean previousBlank = false;
    for (String rawLine : excerpt) {
      String line = rawLine == null ? "" : rawLine.stripTrailing();
      boolean blank = line.isBlank();
      if (blank && (cleaned.isEmpty() || previousBlank)) {
        continue;
      }
      cleaned.add(line);
      previousBlank = blank;
    }
    while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isBlank()) {
      cleaned.remove(cleaned.size() - 1);
    }
    return cleaned.isEmpty() ? null : String.join("\n", cleaned);
  }

  private int lastIndexContaining(List<String> lines, String needle) {
    if (lines == null || lines.isEmpty() || needle == null || needle.isBlank()) {
      return -1;
    }
    for (int i = lines.size() - 1; i >= 0; i--) {
      String line = lines.get(i);
      if (line != null && line.contains(needle)) {
        return i;
      }
    }
    return -1;
  }

  private LaunchFailureExcerpt lastMatchingLine(List<String> lines, String needle) {
    int index = lastIndexContaining(lines, needle);
    return index >= 0 ? buildFailureExcerpt(index, stripLogPrefix(lines.get(index))) : null;
  }

  private boolean looksLikeTimestampedLogLine(String line) {
    return line != null
        && line.matches(
            "^\\d{4}-\\d{2}-\\d{2} .*\\[(?:.+)]\\s+(?:TRACE|DEBUG|INFO|WARN|ERROR)\\s+.*$");
  }

  private boolean looksLikeThrowableStart(String line) {
    if (line == null || line.isBlank()) {
      return false;
    }
    return line.startsWith("Exception in thread ")
        || line.startsWith("Caused by: ")
        || line.matches("^[\\w.$]+(?:Exception|Error)(?::|$).*$");
  }

  private boolean looksLikeThrowableContinuation(String line) {
    if (line == null || line.isBlank()) {
      return false;
    }
    String trimmed = line.stripLeading();
    return looksLikeThrowableStart(line)
        || trimmed.startsWith("at ")
        || trimmed.startsWith("... ")
        || trimmed.startsWith("Suppressed: ");
  }

  private String stripLogPrefix(String line) {
    if (line == null || line.isBlank()) {
      return line;
    }
    int errorIndex = line.indexOf(" ERROR ");
    if (errorIndex > 0) {
      return line.substring(errorIndex + " ERROR ".length()).trim();
    }
    int warnIndex = line.indexOf(" WARN ");
    if (warnIndex > 0) {
      return line.substring(warnIndex + " WARN ".length()).trim();
    }
    int infoIndex = line.indexOf(" INFO ");
    if (infoIndex > 0) {
      return line.substring(infoIndex + " INFO ".length()).trim();
    }
    return line.trim();
  }

  private List<String> readLogTail(Path logFile, int maxLines) {
    return readLogTail(logFile, maxLines, 0L);
  }

  /**
   * Reads up to {@code maxLines} lines from the launch log, considering only bytes at or after
   * {@code startOffset}. This gives attempt-scoped analysis: an earlier BUILD FAILED from a
   * previous launch attempt can never be misclassified as belonging to the current attempt.
   */
  private List<String> readLogTail(Path logFile, int maxLines, long startOffset) {
    if (logFile == null || !Files.isRegularFile(logFile)) {
      return List.of();
    }
    try {
      byte[] fullBytes = Files.readAllBytes(logFile);
      int byteOffset = (int) Math.min(Math.max(startOffset, 0L), fullBytes.length);
      int fromIndex = byteOffset;
      // Skip a partial first line so a line partially written when the offset was captured is not
      // decoded as a fragment of the previous attempt.
      if (fromIndex > 0 && fullBytes[fromIndex - 1] != '\n') {
        while (fromIndex < fullBytes.length && fullBytes[fromIndex] != '\n') {
          fromIndex++;
        }
        if (fromIndex < fullBytes.length) {
          fromIndex++;
        }
      }
      String tailText =
          new String(fullBytes, fromIndex, fullBytes.length - fromIndex, Charset.defaultCharset());
      List<String> lines = new ArrayList<>();
      Collections.addAll(lines, tailText.split("\\r?\\n", -1));
      if (lines.size() > maxLines) {
        return lines.subList(lines.size() - maxLines, lines.size());
      }
      return lines;
    } catch (IOException e) {
      logger.debug("Failed to read launch log tail from {}: {}", logFile, e.getMessage());
      return List.of();
    }
  }

  private RelaunchException buildProcessExitedException(
      Consumer<RelaunchProgress> progressListener,
      String localExecutorBaseUrl,
      Path logFile,
      String maskedLaunchArgs,
      LaunchFailure failure,
      String lastReadinessPhase,
      String lastReadinessReason,
      String attemptId) {
    LaunchFailure resolvedFailure =
        failure != null
            ? failure
            : new LaunchFailure(
                FAILURE_KIND_PROCESS_EXITED,
                "Micro6 relaunch process exited before the local executor became ready. Check "
                    + logFile.toAbsolutePath(),
                false);
    // Use BUILD_FAILED phase for Gradle/build failures, the dedicated startup-refusal phase for a
    // terminal executor refusal, and PROCESS_EXITED otherwise.
    boolean buildRelated =
        FAILURE_KIND_GRADLE_TASK_FAILED.equals(resolvedFailure.kind())
            || FAILURE_KIND_BUILD_OUTPUT_LOCK.equals(resolvedFailure.kind())
            || FAILURE_KIND_LAUNCH_CONFIGURATION_INVALID.equals(resolvedFailure.kind())
            || FAILURE_KIND_GRADLE_TIMEOUT.equals(resolvedFailure.kind());
    String phase;
    if (buildRelated) {
      phase = PHASE_BUILD_FAILED;
    } else if (FAILURE_KIND_EXECUTOR_STARTUP_REJECTED.equals(resolvedFailure.kind())) {
      phase = PHASE_EXECUTOR_STARTUP_REJECTED;
    } else {
      phase = PHASE_PROCESS_EXITED;
    }
    if (buildRelated) {
      emitBuildFailedProgress(
          progressListener,
          localExecutorBaseUrl,
          maskedLaunchArgs,
          logFile,
          resolvedFailure.kind(),
          resolvedFailure.detail(),
          attemptId);
    }
    return new RelaunchException(
        phase,
        firstNonBlank(resolvedFailure.detail(), lastReadinessReason),
        true,
        logFile,
        gradleRunTask,
        maskedLaunchArgs,
        resolvedFailure.kind(),
        resolvedFailure.detail(),
        lastReadinessPhase,
        attemptId);
  }

  private String escapeSingleQuotes(String value) {
    return value.replace("'", "'\"'\"'");
  }

  public record RelaunchResult(
      Micro6OnlineClientRegistryService.TargetClient targetClient,
      List<String> launchCommand,
      long launcherPid,
      Path launchLogFile,
      String maskedLaunchArgs,
      String effectiveGradleTask,
      int world,
      String localExecutorBaseUrl,
      String readinessPhase,
      String readinessReason,
      String resolvedAccountName,
      String resolvedAccountHash,
      String requestedAccountName,
      String requestedAccountHash,
      Long portOwnerPid,
      String observedExecutorStatus,
      Boolean dispatchHold,
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Integer loginAttempt,
      Integer loginAttemptMax,
      String loginTerminalReason,
      boolean reusedExecutor,
      String attemptId) {}

  record HealthCheckResult(
      Optional<Micro6OnlineClientRegistryService.TargetClient> targetClient,
      String readinessPhase,
      String readinessReason,
      Long portOwnerPid,
      String resolvedAccountName,
      String resolvedAccountHash,
      String requestedAccountName,
      String requestedAccountHash,
      Boolean dispatchHold,
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Integer loginAttempt,
      Integer loginAttemptMax,
      String loginTerminalReason,
      boolean accountMatched,
      String launchAttemptId,
      Long processId,
      Integer requestedWorld,
      Integer currentWorld,
      Boolean worldMatched,
      Boolean pathfinderReady,
      String scriptLifecycleState,
      Boolean scriptLoopAlive,
      String scriptLoopHeartbeatAt,
      Long scriptLoopHeartbeatAgeMs) {}

  private record LaunchFailure(String kind, String detail, boolean retryable) {}

  private record LaunchFailureExcerpt(int lineIndex, String detail) {}

  protected enum PortProbeState {
    FREE,
    OCCUPIED,
    UNKNOWN
  }

  protected enum PortOwnershipState {
    VERIFIED_OWNER,
    ALREADY_GONE,
    UNVERIFIED
  }

  protected record PortProbe(PortProbeState state, Long pid, String detail) {}

  private record RelaunchAttemptResult(
      RelaunchResult success,
      LaunchFailure failure,
      String lastReadinessPhase,
      String lastReadinessReason,
      String attemptId) {}

  public record RelaunchProgress(
      String phase,
      String reason,
      String localExecutorBaseUrl,
      String resolvedAccountName,
      String resolvedAccountHash,
      String requestedAccountName,
      String requestedAccountHash,
      Long portOwnerPid,
      Boolean dispatchHold,
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Integer loginAttempt,
      Integer loginAttemptMax,
      String loginTerminalReason,
      String effectiveGradleTask,
      String maskedLaunchArgs,
      boolean reusedExecutor,
      String launchLogFile,
      String launchFailureKind,
      String launchFailureDetail,
      String attemptId) {}

  public static class RelaunchException extends IllegalStateException {
    private final String phase;
    private final boolean terminal;
    private final Path launchLogFile;
    private final String effectiveGradleTask;
    private final String maskedLaunchArgs;
    private final String failureKind;
    private final String failureDetail;
    private final String lastObservedReadinessPhase;
    private final String attemptId;

    public RelaunchException(String phase, String message) {
      this(phase, message, true, null, null, null, null, null, null, null);
    }

    public RelaunchException(String phase, String message, boolean terminal) {
      this(phase, message, terminal, null, null, null, null, null, null, null);
    }

    public RelaunchException(
        String phase,
        String message,
        boolean terminal,
        Path launchLogFile,
        String effectiveGradleTask,
        String maskedLaunchArgs,
        String failureKind,
        String failureDetail,
        String lastObservedReadinessPhase) {
      this(
          phase,
          message,
          terminal,
          launchLogFile,
          effectiveGradleTask,
          maskedLaunchArgs,
          failureKind,
          failureDetail,
          lastObservedReadinessPhase,
          null);
    }

    public RelaunchException(
        String phase,
        String message,
        boolean terminal,
        Path launchLogFile,
        String effectiveGradleTask,
        String maskedLaunchArgs,
        String failureKind,
        String failureDetail,
        String lastObservedReadinessPhase,
        String attemptId) {
      super(message);
      this.phase = phase;
      this.terminal = terminal;
      this.launchLogFile = launchLogFile;
      this.effectiveGradleTask = effectiveGradleTask;
      this.maskedLaunchArgs = maskedLaunchArgs;
      this.failureKind = failureKind;
      this.failureDetail = failureDetail;
      this.lastObservedReadinessPhase = lastObservedReadinessPhase;
      this.attemptId = attemptId;
    }

    public String getPhase() {
      return phase;
    }

    public boolean isTerminal() {
      return terminal;
    }

    public Path getLaunchLogFile() {
      return launchLogFile;
    }

    public String getEffectiveGradleTask() {
      return effectiveGradleTask;
    }

    public String getMaskedLaunchArgs() {
      return maskedLaunchArgs;
    }

    public String getFailureKind() {
      return failureKind;
    }

    public String getFailureDetail() {
      return failureDetail;
    }

    public String getLastObservedReadinessPhase() {
      return lastObservedReadinessPhase;
    }

    public String getAttemptId() {
      return attemptId;
    }
  }
}
