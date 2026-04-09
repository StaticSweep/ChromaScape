package com.chromascape.web.simulator;

import com.chromascape.web.simulator.SimulatorVerifyJobStore.JobStatus;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifyArgs;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifyJob;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifySummary;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifySummary.ViolationCounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for running OpenRune simulator verification jobs asynchronously.
 *
 * <p>Manages job execution via a thread pool, tracks job state in the job store, and parses
 * verifier output reports.
 */
@Service
public class SimulatorVerifyService {

  private static final Logger logger = LogManager.getLogger(SimulatorVerifyService.class);
  private static final int REPORT_FILE_WAIT_ATTEMPTS = 15;
  private static final long REPORT_FILE_WAIT_DELAY_MS = 200L;

  /** Minimal valid JSON returned when ObjectMapper serialization fails in buildSyntheticErrorReport. */
  static final String FALLBACK_ERROR_REPORT_JSON =
      "{\"passed\":false,\"summary\":{\"totalActions\":0,\"tickRange\":{\"start\":0,\"end\":0},\"actionTypeCounts\":{},\"playerCount\":0,\"durationTicks\":0},\"violations\":[],\"error\":\"A verifier error occurred but the report could not be generated\"}";

  private final SimulatorVerifierProperties properties;
  private final SimulatorVerifyJobStore jobStore;
  private final ObjectMapper objectMapper;
  private final ExecutorService executorService;
  private final Semaphore concurrencySemaphore;
  private final Map<String, Process> runningProcesses;

  @Autowired
  public SimulatorVerifyService(
      SimulatorVerifierProperties properties, SimulatorVerifyJobStore jobStore) {
    this.properties = properties;
    this.jobStore = jobStore;
    this.objectMapper = new ObjectMapper();
    this.executorService = Executors.newFixedThreadPool(properties.getMaxConcurrentJobs());
    this.concurrencySemaphore = new Semaphore(properties.getMaxConcurrentJobs());
    this.runningProcesses = new ConcurrentHashMap<>();

    // Create reports directory if enabled
    if (properties.isAutoCreateReportsDir()) {
      try {
        Path reportsDir = properties.getResolvedReportsDir();
        if (!Files.exists(reportsDir)) {
          Files.createDirectories(reportsDir);
          logger.info("Created simulator reports directory: {}", reportsDir.toAbsolutePath());
        }
      } catch (IOException e) {
        logger.warn("Could not create reports directory: {}", e.getMessage());
      }
    }
  }

  /**
   * Starts a new verification job with the given arguments.
   *
   * @param args Verification arguments
   * @return The created job
   * @throws SimulatorVerifierException if verification cannot start
   */
  public VerifyJob startVerification(VerifyArgs args) throws SimulatorVerifierException {
    if (!properties.isEnabled()) {
      throw new SimulatorVerifierException("Simulator verifier integration is disabled");
    }

    // Validate OpenRune root exists
    Path openruneRoot = Paths.get(properties.getOpenruneRoot());
    if (!Files.exists(openruneRoot)) {
      throw new SimulatorVerifierException(
          "OpenRune root directory not found: " + properties.getOpenruneRoot());
    }
    if (properties.isJarInvocation()) {
      Path verifierJar = properties.getResolvedVerifierJarPath();
      if (!Files.exists(verifierJar)) {
        throw new SimulatorVerifierException(
            "Verifier jar not found: "
                + verifierJar
                + ". Build it with ./gradlew :simulator-verifier:shadowJar");
      }
    }

    String jobId = UUID.randomUUID().toString();
    VerifyJob job = jobStore.createJob(jobId, args);

    // Submit async job
    CompletableFuture.runAsync(() -> executeJob(job), executorService);

    logger.info("Started verification job {} for session: {}", jobId, args.getSession());
    return job;
  }

  /**
   * Gets a job by ID.
   *
   * @param jobId Job identifier
   * @return The job, or null if not found
   */
  public VerifyJob getJob(String jobId) {
    return jobStore.getJob(jobId);
  }

  /**
   * Reads the full report JSON for a completed job.
   *
   * @param jobId Job identifier
   * @return The report JSON as a string
   * @throws SimulatorVerifierException if report cannot be read
   */
  public String getReport(String jobId) throws SimulatorVerifierException {
    VerifyJob job = jobStore.getJob(jobId);
    if (job == null) {
      throw new SimulatorVerifierException("Job not found: " + jobId);
    }

    if (job.getReportPath() == null) {
      throw new SimulatorVerifierException("Report not available for job: " + jobId);
    }

    try {
      return Files.readString(Paths.get(job.getReportPath()));
    } catch (IOException e) {
      throw new SimulatorVerifierException("Failed to read report: " + e.getMessage(), e);
    }
  }

  /**
   * Attempts to cancel a running job.
   *
   * <p>This will terminate the active verifier process if it's still running, preventing it from
   * completing and overwriting the cancelled status.
   *
   * @param jobId Job identifier
   * @return true if job was cancelled, false otherwise
   */
  public boolean cancel(String jobId) {
    VerifyJob job = jobStore.getJob(jobId);
    if (job == null) {
      return false;
    }

    if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING) {
      // Terminate the running process if it exists
      Process process = runningProcesses.remove(jobId);
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        logger.info("Terminated process for cancelled job: {}", jobId);
      }

      job.setStatus(JobStatus.FAILED);
      job.setError("Job cancelled by user");
      job.setCancelled(true);
      logger.info("Cancelled verification job: {}", jobId);
      return true;
    }

    return false;
  }

  /** Executes a verification job synchronously. */
  private void executeJob(VerifyJob job) {
    Instant startTime = Instant.now();
    boolean acquired = false;
    String jobId = job.getJobId();

    try {
      // Wait for semaphore (concurrency limit)
      if (!concurrencySemaphore.tryAcquire(properties.getJobTimeoutSeconds(), TimeUnit.SECONDS)) {
        throw new TimeoutException("Timeout waiting for available job slot");
      }
      acquired = true;

      job.setStatus(JobStatus.RUNNING);
      logger.info("Executing verification job: {}", jobId);

      // Pre-flight check: verify bridge is reachable
      if (!isBridgeReachable(job.getArgs().getBridgeUrl())) {
        throw new RuntimeException(
            String.format(
                "Bridge service unreachable at %s. Please start the ChromaScape backend.",
                job.getArgs().getBridgeUrl()));
      }

      List<String> command = buildProcessCommand(job);
      logger.debug("Command: {}", String.join(" ", command));

      // Execute process
      Process process = createVerifierProcess(command, new File(properties.getOpenruneRoot()));
      runningProcesses.put(jobId, process);

      // Capture output
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
          logger.debug("[{}] {}", jobId, line);
        }
      }

      // Wait for completion with timeout
      boolean completed = process.waitFor(properties.getJobTimeoutSeconds(), TimeUnit.SECONDS);

      if (!completed) {
        process.destroyForcibly();
        throw new TimeoutException("Verification job timed out");
      }

      int exitCode = process.exitValue();
      job.setExitCode(exitCode);

      // Don't update status if job was cancelled
      if (job.isCancelled()) {
        logger.info("Verification job {} was cancelled, skipping status update", jobId);
        return;
      }

      // Parse report
      String reportPath = getReportPath(jobId);
      Path reportFile = Paths.get(reportPath);
      waitForReportFile(reportFile, jobId, exitCode);

      if (exitCode == 0 || exitCode == 1) {
        parseReport(job, output.toString());
        if (job.getSummary() != null && !job.getSummary().isPassed()) {
          job.setStatus(JobStatus.FAILED);
          String errorMsg = extractErrorMessage(output.toString());
          if (!errorMsg.isBlank()) {
            job.setError(errorMsg);
          }
          logger.warn(
              "Verification job {} completed with exit code {} but report shows failure",
              jobId, exitCode);
        } else {
          job.setStatus(JobStatus.SUCCEEDED);
          logger.info(
              "Verification job {} completed with exit code {} (passed={})",
              jobId,
              exitCode,
              job.getSummary() != null && job.getSummary().isPassed());
        }
      } else if (exitCode == 2) {
        // OpenRune writes error reports before exitProcess(2) - consume them
        try {
          parseReport(job, output.toString());
          if (job.getSummary() != null) {
            job.getSummary().setPassed(false);
            if (!rewriteReportPassedFlag(reportPath)) {
              writeSyntheticReport(job, extractErrorMessage(output.toString()));
            }
          }
          job.setStatus(JobStatus.FAILED); // Keep FAILED for exitCode=2
          job.setError(extractErrorMessage(output.toString()));
          logger.warn(
              "Verification job {} failed with exit code 2, error report available at {}",
              jobId,
              reportPath);
        } catch (IOException e) {
          job.setStatus(JobStatus.FAILED);
          job.setError(
              "Verifier exited with code 2, but failed to parse error report: " + e.getMessage());
          writeSyntheticReport(job, "Failed to parse exitCode 2 report: " + e.getMessage());
          logger.error("Failed to parse error report for job {}: {}", jobId, e.getMessage(), e);
        }
      } else {
        // Unexpected exit code (non-0/1/2): attempt to parse any available report first
        try {
          parseReport(job, output.toString());
          if (job.getSummary() != null) {
            job.getSummary().setPassed(false);
            if (!rewriteReportPassedFlag(reportPath)) {
              writeSyntheticReport(job, extractErrorMessage(output.toString()));
            }
          }
          job.setStatus(JobStatus.FAILED);
          job.setError(
              "Verifier exited with code "
                  + exitCode
                  + ": "
                  + extractErrorMessage(output.toString()));
          logger.warn(
              "Verification job {} failed with exit code {}, report available at {}",
              jobId, exitCode, reportPath);
        } catch (IOException e) {
          // Report parsing failed - write synthetic report as fallback
          String errorMsg = "Verifier exited with code " + exitCode + ": " + extractErrorMessage(output.toString());
          job.setStatus(JobStatus.FAILED);
          job.setError(errorMsg);
          writeSyntheticReport(job, errorMsg);
          logger.warn(
              "Verification job {} failed with exit code {}, wrote synthetic report",
              jobId, exitCode);
        }
      }

      // Final safeguard: any non-cancelled FAILED job must have a report
      if (job.getStatus() == JobStatus.FAILED && !job.isCancelled() && job.getReportPath() == null) {
        String fallbackError = job.getError() != null ? job.getError() : "Unknown error";
        writeSyntheticReport(job, fallbackError);
      }

    } catch (TimeoutException e) {
      if (!job.isCancelled()) {
        job.setStatus(JobStatus.FAILED);
        job.setError("Job timed out after " + properties.getJobTimeoutSeconds() + " seconds");
        writeSyntheticReport(job, "Verification timed out: " + e.getMessage());
      }
      logger.error("Verification job {} timed out", jobId);

    } catch (Exception e) {
      if (!job.isCancelled()) {
        job.setStatus(JobStatus.FAILED);
        job.setError("Execution failed: " + e.getMessage());
        writeSyntheticReport(job, e.getMessage());
      }
      logger.error("Verification job {} failed: {}", jobId, e.getMessage(), e);

    } finally {
      if (acquired) {
        concurrencySemaphore.release();
      }
      runningProcesses.remove(jobId);
      Duration duration = Duration.between(startTime, Instant.now());
      logger.info("Verification job {} finished in {}ms", jobId, duration.toMillis());
    }
  }

  /** Builds the process command based on configured invocation mode. */
  private List<String> buildProcessCommand(VerifyJob job) throws IOException {
    if (properties.isJarInvocation()) {
      return buildJarCommand(job);
    }
    File batchFile = createGradleBatchFile(job);
    return List.of("cmd.exe", "/c", batchFile.getAbsolutePath());
  }

  /** Creates and starts the verifier process with redirected error stream.
   *
   * <p>Extracted for testability: tests can spy on this method to inject a mock Process.
   */
  Process createVerifierProcess(List<String> command, File workingDir) throws IOException {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(workingDir);
    processBuilder.redirectErrorStream(true);
    return processBuilder.start();
  }

  /** Builds the direct java -jar command for the simulator verifier. */
  private List<String> buildJarCommand(VerifyJob job) {
    String reportPath = getReportPath(job.getJobId());
    List<String> command = new ArrayList<>();
    command.add(resolveJavaCommand());
    command.add("-jar");
    command.add(properties.getResolvedVerifierJarPath().toString());
    command.addAll(
        buildArgsList(
            job.getArgs().getSession(),
            job.getArgs().getScenario(),
            job.getArgs().getBridgeUrl(),
            job.getArgs().getApiKey(),
            reportPath));
    return command;
  }

  /** Resolves the java executable for launching the verifier jar. */
  private String resolveJavaCommand() {
    String javaExecutable =
        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    Path javaPath = Paths.get(System.getProperty("java.home"), "bin", javaExecutable);
    if (Files.exists(javaPath)) {
      return javaPath.toString();
    }
    return "java";
  }

  /** Creates a temporary batch file with the gradle command to avoid quoting issues. */
  private File createGradleBatchFile(VerifyJob job) throws IOException {
    String reportPath = getReportPath(job.getJobId());
    String argsString =
        buildArgsString(
            job.getArgs().getSession(),
            job.getArgs().getScenario(),
            job.getArgs().getBridgeUrl(),
            job.getArgs().getApiKey(),
            reportPath);

    // Create batch file content with proper quoting
    // Add --no-daemon to avoid loopback connection issues
    String batchContent =
        String.format(
            "@echo off\r\ncd /d \"%s\"\r\n%s --no-daemon :simulator-verifier:run --args=\"%s\"\r\n",
            properties.getOpenruneRoot(), properties.getGradleCmd(), argsString);

    // Create temp file
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    File batchFile = new File(tempDir, "simulator-" + job.getJobId() + ".bat");

    // Write batch file
    java.nio.file.Files.write(
        batchFile.toPath(), batchContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    // Mark for deletion on exit
    batchFile.deleteOnExit();

    return batchFile;
  }

  /** Builds an argument list for the verifier process. */
  private List<String> buildArgsList(
      String session, String scenario, String bridgeUrl, String apiKey, String outputPath) {
    List<String> args = new ArrayList<>();

    // Required arguments
    args.add("--session");
    args.add(session);
    args.add("--bridge");
    args.add(bridgeUrl);
    args.add("--nonInteractive");
    args.add("--output");
    args.add(outputPath);

    // Optional arguments - only add when non-empty to allow verifier fallback logic
    if (apiKey != null && !apiKey.isBlank()) {
      args.add("--apiKey");
      args.add(apiKey);
    }

    if (scenario != null && !scenario.isBlank()) {
      args.add("--scenario");
      args.add(scenario);
    }
    return args;
  }

  /** Builds a single --args string for gradle :run invocation. */
  private String buildArgsString(
      String session, String scenario, String bridgeUrl, String apiKey, String outputPath) {
    return String.join(" ", buildArgsList(session, scenario, bridgeUrl, apiKey, outputPath));
  }

  /**
   * Waits briefly for a report file to appear after process termination.
   *
   * <p>This reduces false negatives when the verifier flushes output just after exit.
   */
  private boolean waitForReportFile(Path reportFile, String jobId, int exitCode) {
    if (Files.exists(reportFile)) {
      return true;
    }
    for (int attempt = 1; attempt <= REPORT_FILE_WAIT_ATTEMPTS; attempt++) {
      try {
        Thread.sleep(REPORT_FILE_WAIT_DELAY_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.warn("Interrupted while waiting for report file for job {}", jobId);
        return Files.exists(reportFile);
      }
      if (Files.exists(reportFile)) {
        logger.debug("Report file became available for job {} on retry {}", jobId, attempt);
        return true;
      }
    }
    logger.warn(
        "Report file still missing after {} retries for job {} (exitCode={})",
        REPORT_FILE_WAIT_ATTEMPTS,
        jobId,
        exitCode);
    return false;
  }

  /** Gets the report file path for a job. */
  private String getReportPath(String jobId) {
    return properties.getResolvedReportsDir().resolve(jobId + ".json").toString();
  }

  /** Parses the verifier report and populates the job summary. */
  private void parseReport(VerifyJob job, String output) throws IOException {
    String reportPath = getReportPath(job.getJobId());
    Path reportFile = Paths.get(reportPath);

    if (!Files.exists(reportFile)) {
      logger.warn("Report file not found at {}, attempting to parse from output", reportPath);
      // Try to extract JSON from output
      String json = extractJsonFromOutput(output);
      if (json == null) {
        // Build a synthetic error report from extracted error so reportAvailable stays true
        String extractedError = extractErrorMessage(output);
        json = buildSyntheticErrorReport(job, extractedError);
        logger.info(
            "Built synthetic error report for job {} from verifier output", job.getJobId());
      }
      parseJsonSummary(job, json);
      try {
        Path parent = reportFile.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(reportFile, json);
        job.setReportPath(reportPath);
        logger.info("Persisted report from verifier output to {}", reportPath);
      } catch (IOException e) {
        logger.warn(
            "Unable to persist parsed output report for job {}: {}",
            job.getJobId(),
            e.getMessage());
      }
    } else {
      String json = Files.readString(reportFile);
      parseJsonSummary(job, json);
      job.setReportPath(reportPath);
    }
  }

  /** Writes a synthetic error report to disk and sets reportPath on the job. */
  private void writeSyntheticReport(VerifyJob job, String errorMessage) {
    try {
      String reportPath = getReportPath(job.getJobId());
      Path reportFile = Paths.get(reportPath);
      Path parent = reportFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      String json = buildSyntheticErrorReport(job, errorMessage);
      Files.writeString(reportFile, json);
      job.setReportPath(reportPath);
      logger.info("Wrote synthetic error report to {}", reportPath);
    } catch (IOException e) {
      logger.warn("Failed to write synthetic report for job {}: {}", job.getJobId(), e.getMessage());
    }
  }

  /** Builds a synthetic JSON error report when the verifier produced no structured output. */
  private String buildSyntheticErrorReport(VerifyJob job, String errorMessage) {
    String safeMessage = errorMessage != null ? errorMessage : "Unknown error";
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("sessionId", job.getArgs().getSession() != null ? job.getArgs().getSession() : "");
      root.put("timestamp", Instant.now().toString());
      if (job.getArgs().getScenario() != null) {
        root.put("scenario", job.getArgs().getScenario());
      } else {
        root.putNull("scenario");
      }
      root.put("passed", false);
      ObjectNode summary = root.putObject("summary");
      summary.put("totalActions", 0);
      ObjectNode tickRange = summary.putObject("tickRange");
      tickRange.put("start", 0);
      tickRange.put("end", 0);
      summary.putObject("actionTypeCounts");
      summary.put("playerCount", 0);
      summary.put("durationTicks", 0);
      root.putArray("violations");
      root.put("error", safeMessage);
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (Exception e) {
      logger.warn("Failed to serialize synthetic report for job {}: {}", job.getJobId(), e.getMessage());
      return FALLBACK_ERROR_REPORT_JSON;
    }
  }

  /** Rewrites the report file on disk to force "passed": false.
   *
   * <p>This ensures GET /report returns a file consistent with the in-memory summary
   * after we override passed=false for failure exit codes.
   *
   * @return true if the file was successfully rewritten or didn't need rewriting
   */
  private boolean rewriteReportPassedFlag(String reportPath) {
    try {
      Path reportFile = Paths.get(reportPath);
      if (!Files.exists(reportFile)) {
        return true; // nothing to rewrite; synthetic reports already have passed=false
      }
      String json = Files.readString(reportFile);
      JsonNode root = objectMapper.readTree(json);
      if (root.isObject()) {
        ((ObjectNode) root).put("passed", false);
        Files.writeString(reportFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
      }
      return true;
    } catch (IOException e) {
      logger.warn("Failed to rewrite passed flag in report {}: {}", reportPath, e.getMessage());
      return false;
    }
  }

  /** Parses JSON summary into the job. */
  private void parseJsonSummary(VerifyJob job, String json) throws IOException {
    JsonNode root = objectMapper.readTree(json);

    boolean passed = root.path("passed").asBoolean();
    JsonNode summaryNode = root.path("summary");

    int totalActions = summaryNode.path("totalActions").asInt();
    JsonNode tickRange = summaryNode.path("tickRange");
    int startTick = tickRange.path("start").asInt(tickRange.path("first").asInt());
    int endTick = tickRange.path("end").asInt(tickRange.path("last").asInt());
    int playerCount = summaryNode.path("playerCount").asInt();
    long durationTicks = summaryNode.path("durationTicks").asLong();
    long durationMs = (durationTicks * 1000) / 6; // Approx 6 ticks per second

    // Count violations by severity
    int hard = 0, soft = 0, warn = 0, notEvaluated = 0;
    JsonNode violations = root.path("violations");
    if (violations.isArray()) {
      for (JsonNode violation : violations) {
        String severity = violation.path("severity").asText();
        switch (severity) {
          case "HARD" -> hard++;
          case "SOFT" -> soft++;
          case "WARN" -> warn++;
          case "NOT_EVALUATED" -> notEvaluated++;
        }
      }
    }

    ViolationCounts counts = new ViolationCounts(hard, soft, warn, notEvaluated);
    VerifySummary summary =
        new VerifySummary(
            passed, totalActions, startTick, endTick, playerCount, durationMs, counts);
    job.setSummary(summary);
  }

  /** Extracts JSON from verifier output if present. */
  private String extractJsonFromOutput(String output) {
    // Look for JSON object start/end
    int start = output.indexOf("{");
    int end = output.lastIndexOf("}");

    if (start >= 0 && end > start) {
      return output.substring(start, end + 1);
    }
    return null;
  }

  /** Extracts error message from verifier output. */
  private String extractErrorMessage(String output) {
    // Check for CLI argument errors first
    if (output.contains("Unknown option")) {
      return "Verifier CLI argument error: " + extractUnknownOptionLine(output);
    }

    // Look for common error patterns
    String[] errorPatterns = {
      "Verification failed:", "Error:", "Exception:", "Cannot connect", "Authentication failed"
    };

    for (String pattern : errorPatterns) {
      int index = output.indexOf(pattern);
      if (index >= 0) {
        int end = output.indexOf('\n', index);
        if (end > index) {
          return output.substring(index, end).trim();
        }
        return output.substring(index).trim();
      }
    }

    return "Unknown error";
  }

  /** Extracts the line containing the unknown option error. */
  private String extractUnknownOptionLine(String output) {
    String[] lines = output.split("\n");
    for (String line : lines) {
      if (line.contains("Unknown option")) {
        return line.trim();
      }
    }
    return "Unknown CLI option";
  }

  @PreDestroy
  public void shutdown() {
    logger.info("Shutting down simulator verification service");
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Checks if the bridge service is reachable before starting verification.
   *
   * <p>Performs a lightweight health check to fail fast when the bridge is unavailable.
   *
   * @param bridgeUrl The bridge endpoint URL (e.g., "http://localhost:8092")
   * @return true if the bridge is reachable, false otherwise
   */
  // Package-private for testability (Mockito spy)
  boolean isBridgeReachable(String bridgeUrl) {
    try {
      java.net.URL url = new java.net.URL(bridgeUrl.replace("/replay", "/health"));
      java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(5000);
      conn.setRequestMethod("GET"); // Changed from HEAD - some bridges don't support HEAD
      int responseCode = conn.getResponseCode();
      conn.disconnect();
      // Accept any 2xx-3xx response as reachable
      return responseCode >= 200 && responseCode < 400;
    } catch (Exception e) {
      logger.debug("Bridge reachability check failed for {}: {}", bridgeUrl, e.getMessage());
      return false;
    }
  }
}
