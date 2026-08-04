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
  private static final String DEFAULT_GRADLE_RUN_TASK = ":runelite-client:run";
  private static final String CUSTOM_LAUNCH_COMMAND = "custom_launch_command";
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
  private final String launchCommandTemplate;
  private final String extraRunArgs;
  private final int defaultWorld;
  private final long launchTimeoutMs;
  private final long heartbeatPollIntervalMs;
  private final String launchLogFile;
  private final String defaultExecutorBaseUrl;
  private final String chromaScapeBaseUrl;
  private final String leeuwbotApiUrl;
  private final boolean reuseExistingExecutor;

  public Micro6TargetRelaunchService(
      ObjectMapper objectMapper,
      @Value("${builder-goal.recovery.micro6-project-dir}") String micro6ProjectDir,
      @Value("${builder-goal.recovery.gradle-run-task::runelite-client:run}")
          String gradleRunTask,
      @Value("${builder-goal.recovery.launch-command-template:}") String launchCommandTemplate,
      @Value("${builder-goal.recovery.extra-run-args:}") String extraRunArgs,
      @Value("${builder-goal.recovery.world:360}") int defaultWorld,
      @Value("${builder-goal.recovery.launch-timeout-ms:1800000}") long launchTimeoutMs,
      @Value("${builder-goal.recovery.heartbeat-poll-interval-ms:2000}")
          long heartbeatPollIntervalMs,
      @Value("${builder-goal.recovery.launch-log-file:micro6-recovery-launch.log}") String launchLogFile,
      @Value("${chromascape.base-url:http://localhost:8090}") String chromaScapeBaseUrl,
      @Value("${chromascape.patchloop.micro6-executor.base-url:http://127.0.0.1:8098}")
          String defaultExecutorBaseUrl,
      @Value("${builder-goal.recovery.leeuwbot-api-url:http://localhost:8086}")
          String leeuwbotApiUrl,
      @Value("${builder-goal.recovery.reuse-existing-executor:false}")
          boolean reuseExistingExecutor) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.micro6ProjectDir = micro6ProjectDir;
    this.launchCommandTemplate = normalizeOptionalString(launchCommandTemplate);
    this.gradleRunTask =
        useStructuredWindowsLaunch() || this.launchCommandTemplate != null
            ? CUSTOM_LAUNCH_COMMAND
            : firstNonBlank(normalizeOptionalString(gradleRunTask), DEFAULT_GRADLE_RUN_TASK);
    this.extraRunArgs = extraRunArgs;
    this.defaultWorld = defaultWorld;
    this.launchTimeoutMs = launchTimeoutMs;
    this.heartbeatPollIntervalMs = heartbeatPollIntervalMs;
    this.launchLogFile = launchLogFile;
    this.chromaScapeBaseUrl = normalizeBaseUrl(chromaScapeBaseUrl);
    this.defaultExecutorBaseUrl = normalizeBaseUrl(defaultExecutorBaseUrl);
    this.leeuwbotApiUrl = normalizeBaseUrl(leeuwbotApiUrl);
    this.reuseExistingExecutor = reuseExistingExecutor;
    logIgnoredLegacyGradleTaskOverride();
    logIgnoredLegacyLaunchTemplateOverride();
    logger.info(
        "Builder Goal local launch config loaded: projectDir={}, gradleTask={}, launchTemplateConfigured={}, defaultWorld={}, executorBaseUrl={}, chromaScapeBaseUrl={}, leeuwbotApiUrl={}, reuseExistingExecutor={}",
        this.micro6ProjectDir,
        this.gradleRunTask,
        this.launchCommandTemplate != null,
        this.defaultWorld,
        this.defaultExecutorBaseUrl,
        this.chromaScapeBaseUrl,
        this.leeuwbotApiUrl,
        this.reuseExistingExecutor);
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
    validateMicro6ProjectDir(projectDir);

    String resolvedBaseUrl = firstNonBlank(normalizeBaseUrl(targetBaseUrl), defaultExecutorBaseUrl);
    Integer targetPort = parsePort(resolvedBaseUrl);
    String localExecutorBaseUrl = resolveLocalExecutorBaseUrl(targetPort);
    File logFile = projectDir.resolve(launchLogFile).toFile();
    List<String> clientArgs = buildClientArgs(accountName, accountEmail, accountPassword, world);
    String maskedLaunchArgs = maskLaunchArgs(clientArgs);
    List<String> command =
        buildLaunchCommand(
            clientArgs,
            projectDir,
            accountName,
            accountEmail,
            accountPassword,
            resolveWorld(world),
            targetPort,
            localExecutorBaseUrl);

    boolean reusedExistingExecutor = false;

    HealthCheckResult initialHealthCheck =
        readHealthyExecutorTarget(localExecutorBaseUrl, accountHash, accountName, null, -1L);
    Instant deadline = Instant.now().plusMillis(Math.max(60_000L, launchTimeoutMs));
    if (reuseExistingExecutor && canReuseExistingExecutor(initialHealthCheck)) {
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
            maskedLaunchArgs,
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
                maskedLaunchArgs);
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
            maskedLaunchArgs,
            failure,
            attemptResult.lastReadinessPhase(),
            attemptResult.lastReadinessReason());
      }
      if (attemptResult != null) {
        throw buildProcessExitedException(
            logFile.toPath(),
            maskedLaunchArgs,
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
          maskedLaunchArgs,
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
      String maskedLaunchArgs)
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
          maskedLaunchArgs,
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
                maskedLaunchArgs,
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
            maskedLaunchArgs,
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
        maskedLaunchArgs,
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
    if (useStructuredWindowsLaunch()) {
      return buildStructuredWindowsLaunchCommand(clientArgs, projectDir);
    }
    if (launchCommandTemplate != null) {
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
    }
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

  private List<String> buildStructuredWindowsLaunchCommand(List<String> clientArgs, Path projectDir)
      throws IOException {
    Path launcherScript = projectDir.resolve("launch-buildergoal-client.ps1").toAbsolutePath().normalize();
    if (!Files.isRegularFile(launcherScript)) {
      throw new IllegalStateException(
          "Builder Goal launcher script does not exist: " + launcherScript);
    }
    Path payloadFile = Files.createTempFile("buildergoal-launch-", ".json");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("clientArgs", clientArgs);
    objectMapper.writeValue(payloadFile.toFile(), payload);
    return List.of(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        launcherScript.toString(),
        "-PayloadFile",
        payloadFile.toString());
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
      String accountName, String accountEmail, String accountPassword, Integer world) {
    List<String> clientArgs = new ArrayList<>();
    clientArgs.add("--clean-jagex-launcher");
    clientArgs.add("--auto-enable-accountbuilder");
    clientArgs.add("--profile=" + accountName.trim());
    clientArgs.add("--profile-email=" + accountEmail.trim() + ":" + accountPassword.trim());
    clientArgs.add("--world=" + resolveWorld(world));
    clientArgs.addAll(tokenizeExtraRunArgs(extraRunArgs));
    return clientArgs;
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

  /**
   * Validates the resolved Micro6 project directory before any launch work is
   * attempted. The directory must exist, be a directory, and contain the
   * Gradle wrapper used to launch the client.
   */
  private void validateMicro6ProjectDir(Path projectDir) {
    if (!projectDir.toFile().isDirectory()) {
      throw new IllegalStateException(
          "Configured Micro6 project directory does not exist or is not a directory: "
              + projectDir);
    }
    Path gradleWrapper = projectDir.resolve("gradlew.bat");
    if (!gradleWrapper.toFile().isFile()) {
      throw new IllegalStateException(
          "Configured Micro6 project directory does not contain gradlew.bat: " + projectDir);
    }
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

  private boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("win");
  }

  private boolean useStructuredWindowsLaunch() {
    return isWindows();
  }

  private String normalizeOptionalString(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private LaunchFailure analyzeLaunchFailure(Path logFile) {
    List<String> lines = readLogTail(logFile, 240);
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
    String extractedFailureDetail = extractLaunchFailureDetail(lines);
    if (extractedFailureDetail != null) {
      return new LaunchFailure(
          combined.contains("BUILD FAILED")
              ? FAILURE_KIND_GRADLE_TASK_FAILED
              : FAILURE_KIND_PROCESS_EXITED,
          extractedFailureDetail,
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
      if (!excerpt.isEmpty() && (line.startsWith("BUILD FAILED in ") || line.startsWith("> Task "))) {
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
        preferLaterFailure(
            latestFailure, lastMatchingLine(lines, "The command line is too long."));
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
        && line.matches("^\\d{4}-\\d{2}-\\d{2} .*\\[(?:.+)]\\s+(?:TRACE|DEBUG|INFO|WARN|ERROR)\\s+.*$");
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

  private record LaunchFailureExcerpt(int lineIndex, String detail) {}

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
