package com.chromascape.web.config;

import com.chromascape.web.debug.patchloop.service.provider.mcp.McpConfigProperties;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Manages the lifecycle of the MCP bridge Node.js process.
 *
 * <p>This service automatically starts the MCP bridge as a child process when ChromaScape starts,
 * monitors its health, streams its output to logs, and gracefully shuts it down when ChromaScape
 * exits.
 *
 * <p>Auto-start can be disabled via {@code analysis.mcp.auto-start=false} in application.yml or the
 * {@code MCP_AUTO_START} environment variable.
 */
@Service
@ConditionalOnProperty(
    name = "analysis.mcp.auto-start",
    havingValue = "true",
    matchIfMissing = true)
public class McpBridgeLifecycleManager {

  private static final Logger logger = LoggerFactory.getLogger(McpBridgeLifecycleManager.class);

  private Process bridgeProcess;
  private final McpConfigProperties config;
  private final McpTransportClient transportClient;
  private final BridgeOutputStreamHandler outputHandler;
  private volatile String bridgeMode = "managed";
  private volatile String availabilityReason = "initializing";
  private volatile boolean shutdownHookRegistered;
  private volatile Instant lastRecoveryAttemptAt = Instant.EPOCH;

  @Value("${analysis.mcp.bridge-directory:scripts/mcp-bridge}")
  private String bridgeDirectory;

  @Value("${analysis.mcp.startup-timeout:30}")
  private int startupTimeoutSeconds;

  @Value("${analysis.mcp.monitor-interval-ms:30000}")
  private long monitorIntervalMs;

  @Value("${analysis.mcp.monitor-initial-delay-ms:45000}")
  private long monitorInitialDelayMs;

  @Value("${analysis.mcp.recovery-cooldown-ms:30000}")
  private long recoveryCooldownMs;

  /**
   * Creates a new MCP bridge lifecycle manager.
   *
   * @param config the MCP configuration properties
   * @param transportClient the MCP transport client for health checks
   * @param outputHandler the output stream handler for logging
   */
  @Autowired
  public McpBridgeLifecycleManager(
      McpConfigProperties config,
      McpTransportClient transportClient,
      BridgeOutputStreamHandler outputHandler) {
    this.config = config;
    this.transportClient = transportClient;
    this.outputHandler = outputHandler;
  }

  /**
   * Starts the MCP bridge process automatically during application initialization.
   *
   * <p>Startup sequence: 1. Check if bridge is already running 2. Ensure .env configuration exists
   * (auto-generate if missing) 3. Start bridge process via ProcessBuilder 4. Stream output to logs
   * 5. Wait for health check to pass 6. Register shutdown hook
   *
   * @throws BridgeStartupException if the bridge fails to start or become healthy
   */
  @PostConstruct
  public void startBridge() {
    startBridgeIfNeeded(false);
  }

  private synchronized void startBridgeIfNeeded(boolean recoveryAttempt) {
    logger.info("===========================================");
    logger.info("MCP Bridge Lifecycle Manager");
    logger.info("===========================================");

    try {
      // Check if bridge is already running (external instance)
      if (isBridgeRunning()) {
        logger.info("MCP bridge is already running externally on port {}", getBridgePort());
        logger.info("Skipping auto-start (using existing instance)");
        bridgeMode = "external";
        availabilityReason = "attached_existing_bridge";
        return;
      }

      cleanupManagedProcessIfNecessary();
      logger.info("Starting MCP bridge automatically...");

      // Ensure .env configured
      ensureBridgeConfigured();

      // Ensure bridge dependencies are installed
      ensureBridgeDependencies();

      // Start bridge process
      startBridgeProcess();

      // Stream output to logs
      streamBridgeOutput();

      // Wait for health
      waitForBridgeReady(Duration.ofSeconds(startupTimeoutSeconds));

      // Register shutdown hook
      registerShutdownHookIfNeeded();

      bridgeMode = "managed";
      availabilityReason = "provider_ready";
      lastRecoveryAttemptAt = Instant.now();
      logger.info("MCP bridge started successfully on port {}", getBridgePort());
      logger.info("===========================================");

    } catch (BridgeStartupException e) {
      logger.error("Failed to start MCP bridge: {}", e.getMessage());
      boolean continueStartup = handleStartupFailure(e);
      if (!continueStartup) {
        throw e;
      }
      logger.warn(
          "Continuing ChromaScape startup with MCP provider unavailable (reason: {})",
          availabilityReason);
    } catch (Exception e) {
      logger.error("Unexpected error during MCP bridge startup", e);
      throw new BridgeStartupException("Unexpected error during bridge startup", e);
    }
  }

  @Scheduled(
      fixedDelayString = "${analysis.mcp.monitor-interval-ms:30000}",
      initialDelayString = "${analysis.mcp.monitor-initial-delay-ms:45000}")
  public void monitorBridgeAvailability() {
    if (isBridgeRunning()) {
      availabilityReason = "provider_ready";
      return;
    }

    if (!shouldAttemptRecoveryNow()) {
      return;
    }

    synchronized (this) {
      if (isBridgeRunning()) {
        availabilityReason = "provider_ready";
        return;
      }

      lastRecoveryAttemptAt = Instant.now();

      if ("external".equalsIgnoreCase(bridgeMode) && isPortInUse(getBridgePort())) {
        availabilityReason = "external_bridge_unhealthy";
        logger.warn(
            "MCP bridge previously attached as external is unhealthy on port {}."
                + " Waiting for the external instance to recover before taking over.",
            getBridgePort());
        return;
      }

      logger.warn(
          "MCP bridge unavailable (mode={}, reason={}). Attempting runtime recovery.",
          bridgeMode,
          availabilityReason);

      startBridgeIfNeeded(true);
    }
  }

  /**
   * Checks if the MCP bridge is already running by attempting a health check.
   *
   * @return true if the bridge is running and healthy
   */
  private boolean isBridgeRunning() {
    try {
      var health = transportClient.healthCheck();
      return health.isHealthy();
    } catch (McpTransportException e) {
      return false;
    }
  }

  private boolean shouldAttemptRecoveryNow() {
    return Duration.between(lastRecoveryAttemptAt, Instant.now()).toMillis() >= recoveryCooldownMs;
  }

  private void registerShutdownHookIfNeeded() {
    if (!shutdownHookRegistered) {
      Runtime.getRuntime().addShutdownHook(new Thread(this::stopBridge, "MCP-Bridge-Shutdown"));
      shutdownHookRegistered = true;
    }
  }

  private void cleanupManagedProcessIfNecessary() {
    if (bridgeProcess == null) {
      return;
    }
    if (bridgeProcess.isAlive()) {
      logger.info("Stopping existing managed MCP bridge process before restart");
      bridgeProcess.destroy();
      try {
        if (!bridgeProcess.waitFor(10, TimeUnit.SECONDS)) {
          bridgeProcess.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        bridgeProcess.destroyForcibly();
      }
    }
    bridgeProcess = null;
  }

  /**
   * Ensures the MCP bridge .env configuration file exists, generating it if missing.
   *
   * @throws BridgeConfigException if configuration cannot be generated
   */
  private void ensureBridgeConfigured() {
    File bridgeDir = getBridgeDirectory();
    File envFile = new File(bridgeDir, ".env");

    if (!envFile.exists()) {
      logger.info("MCP bridge .env not found, auto-generating...");
      McpBridgeAutoConfig.generateDefaultEnv(envFile);
    } else {
      logger.info("Using existing .env configuration");
    }
  }

  /**
   * Ensures the MCP bridge Node.js dependencies are installed.
   *
   * <p>If {@code node_modules} is missing, this method runs {@code npm install} in the bridge
   * directory before attempting to start the bridge process.
   *
   * @throws BridgeStartupException if dependency installation fails
   */
  private void ensureBridgeDependencies() throws BridgeStartupException {
    File bridgeDir = getBridgeDirectory();
    File packageJson = new File(bridgeDir, "package.json");
    File nodeModules = new File(bridgeDir, "node_modules");

    if (!packageJson.exists()) {
      logger.warn("MCP bridge package.json not found; skipping dependency check");
      return;
    }

    if (nodeModules.exists()) {
      logger.info("MCP bridge dependencies already installed");
      return;
    }

    logger.info("MCP bridge dependencies not found; running npm install...");

    if (!isNpmAvailable()) {
      throw new BridgeStartupException(
          "npm is not installed or not on PATH. Install Node.js/npm to use MCP bridge.");
    }

    ProcessBuilder pb =
        isWindows()
            ? new ProcessBuilder("cmd", "/c", "npm install --no-audit --no-fund")
            : new ProcessBuilder("npm", "install", "--no-audit", "--no-fund");
    pb.directory(bridgeDir).redirectErrorStream(true);

    try {
      Process process = pb.start();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          logger.info("[Bridge setup] {}", line);
        }
      }

      boolean finished = process.waitFor(5, TimeUnit.MINUTES);
      if (!finished) {
        process.destroyForcibly();
        throw new BridgeStartupException(
            "npm install timed out after 5 minutes in " + bridgeDir.getAbsolutePath());
      }

      if (process.exitValue() != 0) {
        throw new BridgeStartupException(
            "npm install failed with exit code "
                + process.exitValue()
                + " in "
                + bridgeDir.getAbsolutePath());
      }

      logger.info("MCP bridge dependencies installed successfully");
    } catch (BridgeStartupException e) {
      throw e;
    } catch (Exception e) {
      throw new BridgeStartupException("Failed to install MCP bridge dependencies", e);
    }
  }

  /**
   * Starts the MCP bridge Node.js process.
   *
   * @throws BridgeStartupException if the process fails to start
   */
  private void startBridgeProcess() throws BridgeStartupException {
    File bridgeDir = getBridgeDirectory();

    logger.info("Bridge directory: {}", bridgeDir.getAbsolutePath());

    // Check if node is available
    if (!isNodeAvailable()) {
      throw new BridgeStartupException(
          "Node.js is not installed or not on PATH. Install Node.js to use MCP bridge.");
    }

    // Build process
    ProcessBuilder pb =
        new ProcessBuilder()
            .command("node", "server.js")
            .directory(bridgeDir)
            .redirectErrorStream(true);

    try {
      bridgeProcess = pb.start();
      logger.info("MCP bridge process started (PID: {})", bridgeProcess.pid());
    } catch (Exception e) {
      throw new BridgeStartupException("Failed to start bridge process: " + e.getMessage(), e);
    }
  }

  /**
   * Checks if Node.js is available on the system PATH.
   *
   * @return true if node command is available
   */
  private boolean isNodeAvailable() {
    try {
      Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
      boolean finished = process.waitFor(5, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isNpmAvailable() {
    try {
      Process process = new ProcessBuilder("npm", "--version").redirectErrorStream(true).start();
      boolean finished = process.waitFor(5, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isWindows() {
    String os = System.getProperty("os.name", "").toLowerCase();
    return os.contains("win");
  }

  /**
   * Streams the bridge process output to ChromaScape logs.
   *
   * <p>Output is logged with the [Bridge] prefix and appropriate log levels based on content
   * parsing.
   */
  private void streamBridgeOutput() {
    if (bridgeProcess != null) {
      outputHandler.streamOutput(bridgeProcess.getInputStream(), "stdout");
    }
  }

  /**
   * Waits for the MCP bridge to become healthy by polling the health endpoint.
   *
   * @param timeout the maximum time to wait
   * @throws BridgeStartupException if the bridge doesn't become healthy within the timeout
   */
  private void waitForBridgeReady(Duration timeout) throws BridgeStartupException {
    logger.info("Waiting for MCP bridge to be ready (timeout: {}s)...", timeout.getSeconds());

    Instant deadline = Instant.now().plus(timeout);
    int attempt = 0;

    while (Instant.now().isBefore(deadline)) {
      attempt++;

      // Check if process is still alive
      if (bridgeProcess != null && !bridgeProcess.isAlive()) {
        throw new BridgeStartupException(
            "Bridge process terminated unexpectedly (exit code: "
                + bridgeProcess.exitValue()
                + ")");
      }

      // Try health check
      try {
        var health = transportClient.healthCheck();
        if (health.isHealthy()) {
          logger.info("MCP bridge is ready (attempt {})", attempt);
          return;
        }
      } catch (McpTransportException e) {
        // Expected during startup, just continue waiting
      }

      // Wait before next attempt
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BridgeStartupException("Interrupted while waiting for bridge to start", e);
      }
    }

    throw new BridgeStartupException(
        "MCP bridge failed to become healthy within " + timeout.getSeconds() + " seconds");
  }

  /**
   * Handles startup failure by checking for fallback options.
   *
   * <p>If ANTHROPIC_API_KEY is available, logs a warning about falling back to API mode. Otherwise
   * re-throws the exception.
   *
   * @param e the startup exception
   * @throws BridgeStartupException if no fallback is available
   */
  private boolean handleStartupFailure(BridgeStartupException e) {
    if (isFatalStartupError(e)) {
      String apiKey = System.getenv("ANTHROPIC_API_KEY");
      if (apiKey != null && !apiKey.isBlank()) {
        logger.warn("MCP bridge startup failed, but ANTHROPIC_API_KEY is available");
        logger.warn(
            "You can set ANALYSIS_PROVIDER=claude-api to use direct API mode without the bridge");
      } else {
        logger.error("No fallback available - set ANTHROPIC_API_KEY to use API mode");
      }
      availabilityReason = "fatal_bridge_startup_failure";
      return false;
    }

    if (isBridgeRunning()) {
      bridgeMode = "external";
      availabilityReason = "attached_existing_bridge_after_failure";
      logger.warn(
          "Bridge startup failed, but an existing healthy bridge is available on port {}."
              + " Reusing external bridge.",
          getBridgePort());
      return true;
    }

    if (isPortInUse(getBridgePort())) {
      bridgeMode = "external";
      availabilityReason = "bridge_port_in_use_existing_instance_unhealthy";
      logger.warn(
          "Bridge startup failed and port {} is already in use by another process. "
              + "Proceeding without a healthy MCP provider.",
          getBridgePort());
      return true;
    }

    bridgeMode = "managed";
    availabilityReason = "bridge_startup_failed";
    logger.warn("Proceeding without MCP provider after startup failure: {}", e.getMessage());
    return true;
  }

  private boolean isFatalStartupError(BridgeStartupException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    if (message.contains("node.js is not installed")
        || message.contains("failed to start bridge process")
        || message.contains("bridge directory not found")
        || message.contains("invalid")) {
      return true;
    }
    return hasCause(e, BridgeConfigException.class);
  }

  private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    Throwable current = error;
    while (current != null) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private boolean isPortInUse(int port) {
    try (ServerSocket ignored = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
      return false;
    } catch (Exception e) {
      return true;
    }
  }

  /**
   * Stops the MCP bridge process gracefully.
   *
   * <p>Called automatically during application shutdown via shutdown hook.
   */
  @PreDestroy
  public void stopBridge() {
    if (bridgeProcess != null && bridgeProcess.isAlive()) {
      logger.info("Stopping MCP bridge...");
      bridgeProcess.destroy();
      try {
        boolean exited = bridgeProcess.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
          logger.warn("MCP bridge did not stop gracefully, forcing shutdown");
          bridgeProcess.destroyForcibly();
        }
        logger.info("MCP bridge stopped");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        bridgeProcess.destroyForcibly();
      }
    }
  }

  /**
   * Returns the bridge directory as a File object.
   *
   * @return the bridge directory
   * @throws BridgeConfigException if the directory doesn't exist
   */
  private File getBridgeDirectory() {
    String projectRoot = System.getProperty("user.dir");
    File bridgeDir = new File(projectRoot, bridgeDirectory);

    if (!bridgeDir.exists()) {
      throw new BridgeConfigException(
          "Bridge directory not found: "
              + bridgeDir.getAbsolutePath()
              + ". Check analysis.mcp.bridge-directory configuration.");
    }

    return bridgeDir;
  }

  /**
   * Returns the configured bridge port number.
   *
   * @return the port number (extracted from server URL)
   */
  private int getBridgePort() {
    String serverUrl = config.getServerUrl();
    // Extract port from URL (e.g., http://localhost:8765 -> 8765)
    try {
      String[] parts = serverUrl.split(":");
      return Integer.parseInt(parts[parts.length - 1]);
    } catch (Exception e) {
      return 8765; // Default
    }
  }

  /**
   * Returns the bridge process for testing/monitoring purposes.
   *
   * @return the bridge process, or null if not started
   */
  public Process getBridgeProcess() {
    return bridgeProcess;
  }

  /**
   * Checks if the bridge process is running.
   *
   * @return true if the process is alive
   */
  public boolean isBridgeProcessRunning() {
    return bridgeProcess != null && bridgeProcess.isAlive();
  }

  public String getBridgeMode() {
    return bridgeMode;
  }

  public String getAvailabilityReason() {
    return availabilityReason;
  }
}
