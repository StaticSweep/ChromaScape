package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Micro6TargetRelaunchService {

  private static final Logger logger = LoggerFactory.getLogger(Micro6TargetRelaunchService.class);

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
      @Value(
              "${builder-goal.recovery.gradle-run-task::runelite-client:net.runelite.client.RuneLite.main()}")
          String gradleRunTask,
      @Value("${builder-goal.recovery.extra-run-args:}") String extraRunArgs,
      @Value("${builder-goal.recovery.world:360}") int defaultWorld,
      @Value("${builder-goal.recovery.launch-timeout-ms:180000}") long launchTimeoutMs,
      @Value("${builder-goal.recovery.heartbeat-poll-interval-ms:2000}")
          long heartbeatPollIntervalMs,
      @Value("${builder-goal.recovery.launch-log-file:micro6-recovery-launch.log}") String launchLogFile,
      @Value("${chromascape.base-url:http://localhost:8090}") String chromaScapeBaseUrl,
      @Value("${chromascape.patchloop.micro6-executor.base-url:http://127.0.0.1:8098}")
          String defaultExecutorBaseUrl) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.micro6ProjectDir = micro6ProjectDir;
    this.gradleRunTask = gradleRunTask;
    this.extraRunArgs = extraRunArgs;
    this.defaultWorld = defaultWorld;
    this.launchTimeoutMs = launchTimeoutMs;
    this.heartbeatPollIntervalMs = heartbeatPollIntervalMs;
    this.launchLogFile = launchLogFile;
    this.chromaScapeBaseUrl = normalizeBaseUrl(chromaScapeBaseUrl);
    this.defaultExecutorBaseUrl = normalizeBaseUrl(defaultExecutorBaseUrl);
  }

  public RelaunchResult restartTarget(
      String accountName,
      String accountHash,
      String accountEmail,
      String accountPassword,
      String targetBaseUrl)
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
    Instant launchStartedAt = Instant.now();
    stopExistingTargetProcess(targetPort);

    String runArgs = buildRunArgs(accountName, accountEmail, accountPassword);
    List<String> command = buildLaunchCommand(runArgs);
    File logFile = projectDir.resolve(launchLogFile).toFile();

    ProcessBuilder processBuilder =
        new ProcessBuilder(command)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
    applyBuilderGoalEnvironment(processBuilder.environment(), targetPort);

    Process process = processBuilder.start();

    logger.info(
        "Started Micro6 relaunch for account {} using command {} (pid={})",
        accountName,
        command,
        process.pid());

    Instant deadline = Instant.now().plusMillis(Math.max(10_000L, launchTimeoutMs));
    while (Instant.now().isBefore(deadline)) {
      Optional<Micro6OnlineClientRegistryService.TargetClient> targetClient =
          readHealthyExecutorTarget(
              resolvedBaseUrl, accountHash, accountName, launchStartedAt.minusSeconds(2));
      if (targetClient.isPresent()) {
        return new RelaunchResult(
            targetClient.get(), command, process.pid(), logFile.toPath(), maskLaunchArgs(runArgs));
      }
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "Micro6 relaunch process exited before the local executor became ready. Check "
                + logFile.getAbsolutePath());
      }
      Thread.sleep(Math.max(500L, heartbeatPollIntervalMs));
    }

    throw new IllegalStateException(
        "Micro6 local executor did not become healthy for the selected account before timeout. Check "
            + logFile.getAbsolutePath());
  }

  private void stopExistingTargetProcess(Integer targetPort) {
    if (targetPort == null || targetPort <= 0) {
      return;
    }
    try {
      List<String> command;
      if (isWindows()) {
        String script =
            "$pids = Get-NetTCPConnection -LocalPort "
                + targetPort
                + " -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess; "
                + "if ($pids) { $pids | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }";
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
          "Failed to stop existing Micro6 target process on port {}: {}",
          targetPort,
          e.getMessage());
    }
  }

  private List<String> buildLaunchCommand(String runArgs) {
    if (isWindows()) {
      List<String> command = new ArrayList<>();
      command.add("cmd.exe");
      command.add("/c");
      command.add("gradlew.bat");
      command.add(gradleRunTask);
      command.add("--args=" + runArgs);
      return command;
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

  private String buildRunArgs(String accountName, String accountEmail, String accountPassword) {
    StringBuilder builder = new StringBuilder();
    builder.append("--clean-jagex-launcher");
    builder.append(" --profile=").append(accountName.trim());
    builder
        .append(" --profile-email=")
        .append(accountEmail.trim())
        .append(":")
        .append(accountPassword.trim());
    builder.append(" --world=").append(defaultWorld);
    if (extraRunArgs != null && !extraRunArgs.isBlank()) {
      builder.append(' ').append(extraRunArgs.trim());
    }
    return builder.toString();
  }

  private Optional<Micro6OnlineClientRegistryService.TargetClient> readHealthyExecutorTarget(
      String baseUrl, String expectedAccountHash, String expectedAccountName, Instant seenAfter) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return Optional.empty();
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
        return Optional.empty();
      }

      JsonNode payload = objectMapper.readTree(response.body());
      String accountHash =
          firstNonBlank(textValue(payload.path("account_hash")), textValue(payload.path("requested_account_hash")));
      String accountName =
          firstNonBlank(textValue(payload.path("account_name")), textValue(payload.path("requested_account_name")));
      if (!matchesAccount(accountHash, accountName, expectedAccountHash, expectedAccountName)) {
        return Optional.empty();
      }

      Instant observedAt = Instant.now();
      if (seenAfter != null && observedAt.isBefore(seenAfter)) {
        return Optional.empty();
      }

      String advertisedBaseUrl = firstNonBlank(textValue(payload.path("advertised_base_url")), baseUrl);
      return Optional.of(
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
              firstNonBlank(textValue(payload.path("executor_profile")), "accountbuilder_builderpro"),
              firstNonBlank(textValue(payload.path("status")), "HEALTHY"),
              textValue(payload.path("current_task")),
              observedAt));
    } catch (Exception e) {
      return Optional.empty();
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

  private String escapeSingleQuotes(String value) {
    return value.replace("'", "'\"'\"'");
  }

  public record RelaunchResult(
      Micro6OnlineClientRegistryService.TargetClient targetClient,
      List<String> launchCommand,
      long launcherPid,
      Path launchLogFile,
      String maskedLaunchArgs) {}
}
