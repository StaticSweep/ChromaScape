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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Micro6TargetRelaunchService {

  private static final Logger logger = LoggerFactory.getLogger(Micro6TargetRelaunchService.class);
  private static final String FIXED_GRADLE_RUN_TASK = ":runelite-client:run";
  private static final int MAX_LAUNCH_ATTEMPTS = 2;
  private static final long PRELAUNCH_CLEANUP_WAIT_MS = 4_000L;
  private static final String FAILURE_KIND_BUILD_OUTPUT_LOCK = "BUILD_OUTPUT_LOCK";
  private static final String FAILURE_KIND_PROCESS_EXITED = "PROCESS_EXITED";
  private static final String FAILURE_KIND_GRADLE_TASK_FAILED = "GRADLE_TASK_FAILED";
  public static final String PHASE_LAUNCHING_PROCESS = "LAUNCHING_PROCESS";
  public static final String PHASE_WAITING_FOR_PORT = "WAITING_FOR_PORT";
  public static final String PHASE_WAITING_FOR_HEALTH = "WAITING_FOR_HEALTH";
  public static final String PHASE_WAITING_FOR_ACCOUNT = "WAITING_FOR_ACCOUNT";
  public static final String PHASE_WAITING_FOR_DISPATCH = "WAITING_FOR_DISPATCH";
  public static final String PHASE_READY = "READY_TO_PUBLISH";
  public static final String PHASE_PROCESS_EXITED = "PROCESS_EXITED";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String micro6ProjectDir;
  private final String gradleRunTask;
  private final String extraRunArgs;
  private final int defaultWorld;
  private final long launchTimeoutMs;
  private final long heartbeatPollIntervalMs;
  private final String launchLogFile;
  private final String defaultExecutorBaseUrl;
  private final String chromaScapeBaseUrl;

  public Micro6TargetRelaunchService(
      ObjectMapper objectMapper,
      @Value("${builder-goal.recovery.micro6-project-dir:F:/Dev/IdeaProjects/micro6}")
          String micro6ProjectDir,
      @Value("${builder-goal.recovery.extra-run-args:}") String extraRunArgs,
      @Value("${builder-goal.recovery.world:360}") int defaultWorld,
      @Value("${builder-goal.recovery.launch-timeout-ms:1800000}") long launchTimeoutMs,
      @Value("${builder-goal.recovery.heartbeat-poll-interval-ms:2000}")
          long heartbeatPollIntervalMs,
      @Value("${builder-goal.recovery.launch-log-file:micro6-recovery-launch.log}") String launchLogFile,
      @Value("${chromascape.base-url:http://localhost:8090}") String chromaScapeBaseUrl,
      @Value("${chromascape.patchloop.micro6-executor.base-url:http://127.0.0.1:8098}")
          String defaultExecutorBaseUrl) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.micro6ProjectDir = micro6ProjectDir;
    this.gradleRunTask = FIXED_GRADLE_RUN_TASK;
    this.extraRunArgs = extraRunArgs;
    this.defaultWorld = defaultWorld;
    this.launchTimeoutMs = launchTimeoutMs;
    this.heartbeatPollIntervalMs = heartbeatPollIntervalMs;
    this.launchLogFile = launchLogFile;
    this.chromaScapeBaseUrl = normalizeBaseUrl(chromaScapeBaseUrl);
    this.defaultExecutorBaseUrl = normalizeBaseUrl(defaultExecutorBaseUrl);
    logIgnoredLegacyGradleTaskOverride();
    logger.info(
        "Builder Goal local launch config loaded: projectDir={}, gradleTask={}, defaultWorld={}, executorBaseUrl={}, chromaScapeBaseUrl={}",
        this.micro6ProjectDir,
        this.gradleRunTask,
        this.defaultWorld,
        this.defaultExecutorBaseUrl,
        this.chromaScapeBaseUrl);
  }

  private void logIgnoredLegacyGradleTaskOverride() {
    String envOverride = normalizeOptionalString(System.getenv("BUILDER_GOAL_MICRO6_GRADLE_RUN_TASK"));
    String propertyOverride =
        normalizeOptionalString(System.getProperty("builder-goal.recovery.gradle-run-task"));
    String legacyOverride = firstNonBlank(envOverride, propertyOverride);
    if (legacyOverride == null) {
      return;
    }
    logger.warn(
        "Ignoring legacy Builder Goal Gradle task override '{}'; local Builder Goal launches always use '{}'",
        legacyOverride,
        FIXED_GRADLE_RUN_TASK);
  }

  public RelaunchResult restartTarget(
      String accountName,
      String accountHash,
      String accountEmail,
      String accountPassword,
      Integer world,
      String targetBaseUrl)
      throws Exception {
    return restartTarget(
        accountName,
        accountHash,
        accountEmail,
        accountPassword,
        world,
        targetBaseUrl,
        null);
  }

  public RelaunchResult restartTarget(
      String accountName,
      String accountHash,
      String accountEmail,
      String accountPassword,
      Integer world,
      String targetBaseUrl,
      Consumer<RelaunchProgress> progressListener)
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

    Path projectDir = Paths.get(micro6ProjectDir).toAbsolutePath().normalize();
    if (!projectDir.toFile().isDirectory()) {
      throw new IllegalStateException(
          "Configured Micro6 project directory does not exist: " + projectDir);
    }

    String resolvedBaseUrl = firstNonBlank(normalizeBaseUrl(targetBaseUrl), defaultExecutorBaseUrl);
    Integer targetPort = parsePort(resolvedBaseUrl);
    String localExecutorBaseUrl = resolveLocalExecutorBaseUrl(targetPort);
    File logFile = projectDir.resolve(launchLogFile).toFile();
    String runArgs = buildRunArgs(accountName, accountEmail, accountPassword, world);
    List<String> command = buildLaunchCommand(runArgs);

    boolean reusedExistingExecutor = false;

    HealthCheckResult initialHealthCheck =
        readHealthyExecutorTarget(localExecutorBaseUrl, accountHash, accountName, null, -1L);
    Instant deadline = Instant.now().plusMillis(Math.max(60_000L, launchTimeoutMs));
    if (canReuseExistingExecutor(initialHealthCheck)) {
      reusedExistingExecutor = true;
      emitProgress(
          progressListener,
          initialHealthCheck,
          reusedExistingExecutor,
          localExecutorBaseUrl,
          gradleRunTask,
          null,
          null,
          null,
          null);
      return waitForReusedTargetReady(
          initialHealthCheck,
          deadline,
          accountHash,
          accountName,
          localExecutorBaseUrl,
          progressListener,
          world);
    } else {
      RelaunchAttemptResult attemptResult = null;
      for (int attemptNumber = 1; attemptNumber <= MAX_LAUNCH_ATTEMPTS; attemptNumber++) {
        stopExistingTargetProcess(targetPort, projectDir);
        Process process =
            startTargetProcess(
                projectDir,
                logFile,
                targetPort,
                accountName,
                world,
                command,
                localExecutorBaseUrl,
                resolvedBaseUrl);
        emitProgress(
            progressListener,
            new HealthCheckResult(
                Optional.empty(),
                PHASE_LAUNCHING_PROCESS,
                attemptNumber == 1
                    ? "Launching local Micro6 workspace"
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
                false),
            false,
            localExecutorBaseUrl,
            gradleRunTask,
            maskLaunchArgs(runArgs),
            logFile.toPath(),
            null,
            null);
        attemptResult =
            waitForTargetReady(
                process,
                deadline,
                accountHash,
                accountName,
                localExecutorBaseUrl,
                logFile.toPath(),
                launchStartedAt(process),
                progressListener,
                resolvedBaseUrl,
                command,
                world,
                runArgs);
        if (attemptResult.success() != null) {
          return attemptResult.success();
        }
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
            logFile.toPath(),
            maskLaunchArgs(runArgs),
            failure,
            attemptResult.lastReadinessPhase(),
            attemptResult.lastReadinessReason());
      }
      if (attemptResult != null) {
        throw buildProcessExitedException(
            logFile.toPath(),
            maskLaunchArgs(runArgs),
            attemptResult.failure(),
            attemptResult.lastReadinessPhase(),
            attemptResult.lastReadinessReason());
      }
      throw new RelaunchException(
          PHASE_PROCESS_EXITED,
          "Micro6 relaunch process exited before the local executor became ready. Check "
              + logFile.getAbsolutePath(),
          true,
          logFile.toPath(),
          gradleRunTask,
          maskLaunchArgs(runArgs),
          FAILURE_KIND_PROCESS_EXITED,
          null,
          PHASE_LAUNCHING_PROCESS);
    }
  }

  private RelaunchResult waitForReusedTargetReady(
      HealthCheckResult initialHealthCheck,
      Instant deadline,
      String accountHash,
      String accountName,
      String localExecutorBaseUrl,
      Consumer<RelaunchProgress> progressListener,
      Integer world)
      throws Exception {
    String lastReadinessReason = initialHealthCheck.readinessReason();
    String lastReadinessPhase = initialHealthCheck.readinessPhase();
    while (Instant.now().isBefore(deadline)) {
      HealthCheckResult healthCheck =
          readHealthyExecutorTarget(localExecutorBaseUrl, accountHash, accountName, null, -1L);
      emitProgress(
          progressListener,
          healthCheck,
          true,
          localExecutorBaseUrl,
          gradleRunTask,
          null,
          null,
          null,
          null);
      if (healthCheck.targetClient().isPresent()) {
        return buildSuccessResult(
            healthCheck,
            List.of(),
            -1L,
            null,
            null,
            resolveWorld(world),
            localExecutorBaseUrl,
            true);
      }
      lastReadinessReason = firstNonBlank(healthCheck.readinessReason(), lastReadinessReason);
      lastReadinessPhase = firstNonBlank(healthCheck.readinessPhase(), lastReadinessPhase);
      Thread.sleep(Math.max(500L, heartbeatPollIntervalMs));
    }
    throw new RelaunchException(
        firstNonBlank(lastReadinessPhase, PHASE_WAITING_FOR_HEALTH),
        firstNonBlank(
                lastReadinessReason,
                "Micro6 local executor did not become ready for the selected account before the local launch watchdog expired.")
            + " Check "
            + Paths.get(micro6ProjectDir).resolve(launchLogFile).toAbsolutePath(),
        true,
        Paths.get(micro6ProjectDir).resolve(launchLogFile).toAbsolutePath(),
        gradleRunTask,
        null,
        null,
        null,
        lastReadinessPhase);
  }

  private RelaunchAttemptResult waitForTargetReady(
      Process process,
      Instant deadline,
      String accountHash,
      String accountName,
      String localExecutorBaseUrl,
      Path logFile,
      Instant launchStartedAt,
      Consumer<RelaunchProgress> progressListener,
      String resolvedBaseUrl,
      List<String> command,
      Integer world,
      String runArgs)
      throws Exception {
    String lastReadinessReason = null;
    String lastReadinessPhase = PHASE_LAUNCHING_PROCESS;
    while (Instant.now().isBefore(deadline)) {
      HealthCheckResult healthCheck =
          readHealthyExecutorTarget(
              localExecutorBaseUrl,
              accountHash,
              accountName,
              launchStartedAt.minusSeconds(2),
              process.pid());
      emitProgress(
          progressListener,
          healthCheck,
          false,
          localExecutorBaseUrl,
          gradleRunTask,
          maskLaunchArgs(runArgs),
          logFile,
          null,
          null);
      if (healthCheck.targetClient().isPresent()) {
        return new RelaunchAttemptResult(
            buildSuccessResult(
                healthCheck,
                command,
                process.pid(),
                logFile,
                maskLaunchArgs(runArgs),
                resolveWorld(world),
                localExecutorBaseUrl,
                false),
            null,
            healthCheck.readinessPhase(),
            healthCheck.readinessReason());
      }
      lastReadinessReason = firstNonBlank(healthCheck.readinessReason(), lastReadinessReason);
      lastReadinessPhase = firstNonBlank(healthCheck.readinessPhase(), lastReadinessPhase);
      if (!process.isAlive()) {
        LaunchFailure failure = analyzeLaunchFailure(logFile);
        emitProgress(
            progressListener,
            new HealthCheckResult(
                Optional.empty(),
                PHASE_PROCESS_EXITED,
                firstNonBlank(failure.detail(), "Micro6 relaunch process exited before the local executor became ready"),
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
                false),
            false,
            localExecutorBaseUrl,
            gradleRunTask,
            maskLaunchArgs(runArgs),
            logFile,
            failure.kind(),
            failure.detail());
        return new RelaunchAttemptResult(null, failure, lastReadinessPhase, lastReadinessReason);
      }
      Thread.sleep(Math.max(500L, heartbeatPollIntervalMs));
    }
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
        maskLaunchArgs(runArgs),
        null,
        null,
        lastReadinessPhase);
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
            healthCheck.targetClient().map(Micro6OnlineClientRegistryService.TargetClient::status).orElse(null),
            healthCheck.dispatchHold() != null && healthCheck.dispatchHold() ? "PAUSED" : null),
        healthCheck.dispatchHold(),
        healthCheck.dispatchHoldReason(),
        healthCheck.startupState(),
        healthCheck.loginState(),
        healthCheck.loginAttempt(),
        healthCheck.loginAttemptMax(),
        healthCheck.loginTerminalReason(),
        reusedExistingExecutor);
  }

  private Instant launchStartedAt(Process process) {
    return Instant.now();
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
        "Started Micro6 relaunch for account {} using Gradle task {} with world {} and command {} (pid={}, localHealthUrl={}, targetBaseUrl={})",
        accountName,
        gradleRunTask,
        resolveWorld(world),
        command,
        process.pid(),
        localExecutorBaseUrl,
        resolvedBaseUrl);
    return process;
  }

  private boolean canReuseExistingExecutor(HealthCheckResult healthCheck) {
    if (healthCheck == null) {
      return false;
    }
    if (healthCheck.targetClient().isPresent()) {
      return true;
    }
    return healthCheck.accountMatched()
        && (PHASE_WAITING_FOR_DISPATCH.equalsIgnoreCase(healthCheck.readinessPhase())
            || PHASE_WAITING_FOR_ACCOUNT.equalsIgnoreCase(healthCheck.readinessPhase())
            || PHASE_WAITING_FOR_HEALTH.equalsIgnoreCase(healthCheck.readinessPhase()));
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
            launchFailureDetail));
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

  private void stopExistingTargetProcess(Integer targetPort, Path projectDir) {
    try {
      List<String> command;
      if (isWindows()) {
        String projectPath = projectDir.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "''");
        String script =
            "$projectPath = '" + projectPath + "'; "
                + "$ids = New-Object System.Collections.Generic.List[int]; "
                + (targetPort != null && targetPort > 0
                    ? "$portPids = @(Get-NetTCPConnection -LocalPort " + targetPort
                        + " -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess); "
                        + "foreach ($pid in $portPids) { if ($pid) { [void]$ids.Add([int]$pid) } } "
                    : "")
                + "$procs = Get-CimInstance Win32_Process | Where-Object { "
                + "($_.Name -match '^(java|javaw|gradle)\\.exe$') -and "
                + "((($_.CommandLine -ne $null) -and ($_.CommandLine -like ('*' + $projectPath + '*'))) -or "
                + "(($_.ExecutablePath -ne $null) -and ($_.ExecutablePath -like ('*' + $projectPath + '*')))) }; "
                + "foreach ($proc in $procs) { if ($proc.ProcessId) { [void]$ids.Add([int]$proc.ProcessId) } } "
                + "$ids = $ids | Sort-Object -Unique; "
                + "foreach ($id in $ids) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }; "
                + "Start-Sleep -Milliseconds " + PRELAUNCH_CLEANUP_WAIT_MS + ";";
        command =
            List.of(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script);
      } else {
        command =
            List.of(
                "bash",
                "-lc",
                "pids=$(lsof -ti tcp:"
                    + targetPort
                    + " -sTCP:LISTEN 2>/dev/null); if [ -n \"$pids\" ]; then kill -9 $pids; fi");
      }
      Process process = new ProcessBuilder(command).start();
      process.waitFor();
    } catch (Exception e) {
      logger.warn(
          "Failed to stop existing Micro6 target process for port {} and project {}: {}",
          targetPort,
          projectDir,
          e.getMessage());
    }
  }

  private List<String> buildLaunchCommand(String runArgs) {
    if (isWindows()) {
      String escapedRunArgs = runArgs.replace("\"", "\\\"");
      String commandLine =
          "call gradlew.bat \""
              + gradleRunTask
              + "\" \"--args="
              + escapedRunArgs
              + "\"";
      return List.of("cmd.exe", "/c", commandLine);
    }
    return List.of(
        "bash",
        "-lc",
        "./gradlew " + gradleRunTask + " '--args=" + escapeSingleQuotes(runArgs) + "'");
  }

  private void applyBuilderGoalEnvironment(Map<String, String> environment, Integer targetPort) {
    if (environment == null) {
      return;
    }
    environment.put("MICRO6_LOCAL_EXECUTOR_ENABLED", "true");
    if (targetPort != null && targetPort > 0) {
      environment.put("MICRO6_EXECUTOR_BIND_ADDR", "127.0.0.1:" + targetPort);
    }
    String resolvedChromaScapeBaseUrl = firstNonBlank(chromaScapeBaseUrl, "http://localhost:8090");
    if (resolvedChromaScapeBaseUrl != null) {
      environment.put("CHROMASCAPE_BASE_URL", resolvedChromaScapeBaseUrl);
    }
  }

  private String buildRunArgs(
      String accountName, String accountEmail, String accountPassword, Integer world) {
    StringBuilder builder = new StringBuilder();
    builder.append("--clean-jagex-launcher");
    builder.append(" --auto-enable-accountbuilder");
    builder.append(" --profile=").append(accountName.trim());
    builder
        .append(" --profile-email=")
        .append(accountEmail.trim())
        .append(":")
        .append(accountPassword.trim());
    builder.append(" --world=").append(resolveWorld(world));
    if (extraRunArgs != null && !extraRunArgs.isBlank()) {
      builder.append(' ').append(extraRunArgs.trim());
    }
    return builder.toString();
  }

  private int resolveWorld(Integer world) {
    if (world != null && world >= 300 && world <= 700) {
      return world;
    }
    return defaultWorld;
  }

  private HealthCheckResult readHealthyExecutorTarget(
      String baseUrl,
      String expectedAccountHash,
      String expectedAccountName,
      Instant seenAfter,
      long launchedProcessPid) {
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
          false);
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
          false);
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/health"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            false);
      }

      JsonNode payload = objectMapper.readTree(response.body());
      String resolvedAccountHash = textValue(payload.path("account_hash"));
      String resolvedAccountName = textValue(payload.path("account_name"));
      String requestedAccountName = textValue(payload.path("requested_account_name"));
      String requestedAccountHash = textValue(payload.path("requested_account_hash"));
      String accountHash =
          firstNonBlank(
              resolvedAccountHash,
              requestedAccountHash);
      String accountName =
          firstNonBlank(
              resolvedAccountName,
              requestedAccountName);
      boolean accountMatched = matchesAccount(accountHash, accountName, expectedAccountHash, expectedAccountName);
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
            accountMatched);
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
            accountMatched);
      }

      boolean dispatchHold = payload.path("dispatch_hold").asBoolean(false);
      String dispatchHoldReason = textValue(payload.path("dispatch_hold_reason"));
      String startupState = textValue(payload.path("startup_state"));
      String loginState = textValue(payload.path("login_state"));
      Integer loginAttempt = payload.path("login_attempt").isNumber() ? payload.path("login_attempt").asInt() : null;
      Integer loginAttemptMax =
          payload.path("login_attempt_max").isNumber() ? payload.path("login_attempt_max").asInt() : null;
      String loginTerminalReason = textValue(payload.path("login_terminal_reason"));
      boolean publishReady =
          isExecutorPublishReady(dispatchHold, startupState, loginState, loginTerminalReason);
      if (!publishReady) {
        return new HealthCheckResult(
            Optional.empty(),
            PHASE_WAITING_FOR_DISPATCH,
            buildDispatchHoldReason(
                dispatchHoldReason, startupState, loginState, loginAttempt, loginAttemptMax, loginTerminalReason),
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
            accountMatched);
      }

      String advertisedBaseUrl = firstNonBlank(textValue(payload.path("advertised_base_url")), baseUrl);
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
                  observedAt)),
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
          accountMatched);
    } catch (Exception e) {
      return new HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_HEALTH,
          "Micro6 local executor /health on "
              + baseUrl
              + " is not reachable yet"
              + (portOwnerPid != null
                  ? " (port owner pid=" + portOwnerPid + ", launched pid=" + launchedProcessPid + ")"
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
          false);
    }
  }

  private String buildDispatchHoldReason(
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Integer loginAttempt,
      Integer loginAttemptMax,
      String loginTerminalReason) {
    String normalizedLoginState = normalizeOptionalString(loginState);
    String normalizedStartupState = normalizeOptionalString(startupState);
    if ("initializing_bot_logic".equalsIgnoreCase(normalizedStartupState)) {
      return "Micro6 local executor is logged in but AccountBuilder bot logic is still initializing";
    }
    if ("bot_initialization_failed".equalsIgnoreCase(normalizedStartupState)) {
      return "Micro6 local executor failed to initialize AccountBuilder bot logic";
    }
    if ("logged_in".equalsIgnoreCase(normalizedLoginState)
        && normalizedStartupState != null
        && !"accountbuilder_ready".equalsIgnoreCase(normalizedStartupState)) {
      return "Micro6 local executor is logged in but AccountBuilder is not ready yet: "
          + normalizedStartupState;
    }
    if ("auto_login_failed".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting for manual login after auto-login exhausted "
          + firstNonBlank(formatAttemptCounts(loginAttempt, loginAttemptMax), "its attempts");
    }
    if ("authenticator_required".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting for manual authenticator input";
    }
    if ("auto_login_in_progress".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is attempting auto-login"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("profile_bootstrap_pending".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is preparing the requested profile for auto-login";
    }
    if ("waiting_for_profile_lock".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting to acquire the profile lock before auto-login"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("waiting_for_profile_record".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor could not find the requested profile yet"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("profile_bootstrap_failed".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor failed before auto-login could be armed";
    }
    if ("waiting_for_login_screen".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting for the login screen"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("runtime_not_ready".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is still initializing the auto-login runtime";
    }
    if ("runtime_not_ready_timeout".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor auto-login runtime never became ready";
    }
    if ("login_screen_not_ready_timeout".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor login screen never became ready";
    }
    if ("waiting_for_login".equalsIgnoreCase(dispatchHoldReason)
        || "waiting_for_login".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting for login";
    }
    if ("starting_accountbuilder".equalsIgnoreCase(dispatchHoldReason)
        && startupState != null
        && !"starting_accountbuilder".equalsIgnoreCase(startupState)) {
      return "Micro6 local executor is online but not dispatch-ready: " + startupState;
    }
    if (loginTerminalReason != null && !loginTerminalReason.isBlank()) {
      return "Micro6 local executor is online but not dispatch-ready: " + loginTerminalReason;
    }
    return "Micro6 local executor is online but not dispatch-ready: "
        + firstNonBlank(dispatchHoldReason, startupState, normalizedLoginState, "dispatch_hold");
  }

  private boolean isExecutorPublishReady(
      boolean dispatchHold, String startupState, String loginState, String loginTerminalReason) {
    if (dispatchHold) {
      return false;
    }
    String normalizedStartupState = normalizeOptionalString(startupState);
    if (!"accountbuilder_ready".equalsIgnoreCase(normalizedStartupState)) {
      return false;
    }
    String normalizedLoginState = normalizeOptionalString(loginState);
    if (normalizedLoginState == null || normalizedLoginState.isBlank()) {
      return true;
    }
    if ("logged_in".equalsIgnoreCase(normalizedLoginState)) {
      return true;
    }
    if ("auto_login_in_progress".equalsIgnoreCase(normalizedLoginState)
        && loginTerminalReason == null) {
      return false;
    }
    return false;
  }

  private String formatAttemptCounts(Integer loginAttempt, Integer loginAttemptMax) {
    if (loginAttempt == null || loginAttemptMax == null || loginAttemptMax <= 0) {
      return null;
    }
    return loginAttempt + "/" + loginAttemptMax;
  }

  private String formatAttemptSuffix(Integer loginAttempt, Integer loginAttemptMax) {
    String counts = formatAttemptCounts(loginAttempt, loginAttemptMax);
    return counts != null ? " (" + counts + ")" : "";
  }

  private String resolveLocalExecutorBaseUrl(Integer port) {
    int resolvedPort = port != null && port > 0 ? port : 8098;
    return "http://127.0.0.1:" + resolvedPort;
  }

  private Long resolveListeningPid(Integer port) {
    if (port == null || port <= 0) {
      return null;
    }
    try {
      List<String> command;
      if (isWindows()) {
        command =
            List.of(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                "(Get-NetTCPConnection -LocalPort "
                    + port
                    + " -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess | Select-Object -First 1)");
      } else {
        command =
            List.of(
                "bash",
                "-lc",
                "lsof -ti tcp:" + port + " -sTCP:LISTEN 2>/dev/null | head -n 1");
      }
      Process process = new ProcessBuilder(command).start();
      byte[] output = process.getInputStream().readAllBytes();
      process.waitFor();
      String value = new String(output).trim();
      if (value.isBlank()) {
        return null;
      }
      return Long.parseLong(value);
    } catch (Exception ignored) {
      return null;
    }
  }

  private boolean matchesAccount(
      String actualAccountHash,
      String actualAccountName,
      String expectedAccountHash,
      String expectedAccountName) {
    String normalizedExpectedHash = normalizeAccountHash(expectedAccountHash);
    if (normalizedExpectedHash != null) {
      String normalizedActualHash = normalizeAccountHash(actualAccountHash);
      if (normalizedExpectedHash.equals(normalizedActualHash)) {
        return true;
      }
    }
    String normalizedExpectedName = normalizeAccountName(expectedAccountName);
    String normalizedActualName = normalizeAccountName(actualAccountName);
    return normalizedExpectedName != null && normalizedExpectedName.equals(normalizedActualName);
  }

  private String normalizeAccountHash(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ENGLISH);
  }

  private String normalizeAccountName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ENGLISH);
  }

  private String normalizeDispatchReason(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ENGLISH);
  }

  private String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
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

  private String maskLaunchArgs(String launchArgs) {
    if (launchArgs == null || launchArgs.isBlank()) {
      return null;
    }
    String[] parts = launchArgs.trim().split("\\s+");
    List<String> masked = new ArrayList<>();
    for (String part : parts) {
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
    return String.join(" ", masked);
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

  private boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("win");
  }

  private String normalizeOptionalString(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private LaunchFailure analyzeLaunchFailure(Path logFile) {
    List<String> lines = readLogTail(logFile, 120);
    String combined = String.join("\n", lines);
    if (combined.contains("Execution failed for task ':runelite-client:compileJava'.")
        && combined.contains("Unable to delete directory")
        && combined.contains("build\\classes\\java\\main")) {
      return new LaunchFailure(
          FAILURE_KIND_BUILD_OUTPUT_LOCK,
          "Micro6 relaunch compile step failed because runelite-client/build/classes/java/main is locked by an existing micro6 or Gradle process. Check "
              + logFile.toAbsolutePath(),
          true);
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

  private List<String> readLogTail(Path logFile, int maxLines) {
    if (logFile == null || !Files.isRegularFile(logFile)) {
      return List.of();
    }
    try {
      List<String> lines = Files.readAllLines(logFile, Charset.defaultCharset());
      if (lines.size() <= maxLines) {
        return lines;
      }
      return lines.subList(lines.size() - maxLines, lines.size());
    } catch (IOException e) {
      logger.debug("Failed to read launch log tail from {}: {}", logFile, e.getMessage());
      return List.of();
    }
  }

  private RelaunchException buildProcessExitedException(
      Path logFile,
      String maskedLaunchArgs,
      LaunchFailure failure,
      String lastReadinessPhase,
      String lastReadinessReason) {
    LaunchFailure resolvedFailure =
        failure != null
            ? failure
            : new LaunchFailure(
                FAILURE_KIND_PROCESS_EXITED,
                "Micro6 relaunch process exited before the local executor became ready. Check "
                    + logFile.toAbsolutePath(),
                false);
    return new RelaunchException(
        PHASE_PROCESS_EXITED,
        firstNonBlank(resolvedFailure.detail(), lastReadinessReason),
        true,
        logFile,
        gradleRunTask,
        maskedLaunchArgs,
        resolvedFailure.kind(),
        resolvedFailure.detail(),
        lastReadinessPhase);
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
      boolean reusedExecutor) {}

  private record HealthCheckResult(
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
      boolean accountMatched) {}

  private record LaunchFailure(String kind, String detail, boolean retryable) {}

  private record RelaunchAttemptResult(
      RelaunchResult success,
      LaunchFailure failure,
      String lastReadinessPhase,
      String lastReadinessReason) {}

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
      String launchFailureDetail) {}

  public static class RelaunchException extends IllegalStateException {
    private final String phase;
    private final boolean terminal;
    private final Path launchLogFile;
    private final String effectiveGradleTask;
    private final String maskedLaunchArgs;
    private final String failureKind;
    private final String failureDetail;
    private final String lastObservedReadinessPhase;

    public RelaunchException(String phase, String message) {
      this(phase, message, true, null, null, null, null, null, null);
    }

    public RelaunchException(String phase, String message, boolean terminal) {
      this(phase, message, terminal, null, null, null, null, null, null);
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
      super(message);
      this.phase = phase;
      this.terminal = terminal;
      this.launchLogFile = launchLogFile;
      this.effectiveGradleTask = effectiveGradleTask;
      this.maskedLaunchArgs = maskedLaunchArgs;
      this.failureKind = failureKind;
      this.failureDetail = failureDetail;
      this.lastObservedReadinessPhase = lastObservedReadinessPhase;
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
  }
}
