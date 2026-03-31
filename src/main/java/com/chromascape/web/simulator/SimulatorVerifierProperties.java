package com.chromascape.web.simulator;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the OpenRune Simulator Verifier integration.
 *
 * <p>Configures the integration with the OpenRune simulator-verifier Gradle project for running
 * deterministic session certification from the ChromaScape UI.
 */
@Configuration
@ConfigurationProperties(prefix = "simulator.verifier")
public class SimulatorVerifierProperties {

  /** Whether the simulator verifier integration is enabled. */
  private boolean enabled = true;

  /** Root directory of the OpenRune-Server project containing the simulator-verifier module. */
  private String openruneRoot = "/mnt/f/Dev/IdeaProjects/OpenRune-Server";

  /** Gradle command to execute (relative to openrune-root). */
  private String gradleCmd = "./gradlew";

  /** Default bridge URL for fetching the latest deterministic session. */
  private String defaultBridgeUrl = "http://localhost:8092";

  /** API key for authenticating with the bridge service. */
  private String apiKey = "dev-token";

  /** Directory where simulator verification reports will be stored. */
  private String reportsDir = "output/simulator-reports";

  /** Maximum number of concurrent verification jobs allowed. */
  private int maxConcurrentJobs = 2;

  /** Maximum time (in seconds) a verification job is allowed to run. */
  private int jobTimeoutSeconds = 300;

  /** Whether to automatically create the reports directory if it doesn't exist. */
  private boolean autoCreateReportsDir = true;

  // Getters and Setters

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getOpenruneRoot() {
    return openruneRoot;
  }

  public void setOpenruneRoot(String openruneRoot) {
    this.openruneRoot = openruneRoot;
  }

  public String getGradleCmd() {
    return gradleCmd;
  }

  public void setGradleCmd(String gradleCmd) {
    this.gradleCmd = gradleCmd;
  }

  public String getDefaultBridgeUrl() {
    return defaultBridgeUrl;
  }

  public void setDefaultBridgeUrl(String defaultBridgeUrl) {
    this.defaultBridgeUrl = defaultBridgeUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getReportsDir() {
    return reportsDir;
  }

  /**
   * Resolves the reports directory as an absolute path.
   *
   * <p>If {@code reportsDir} is relative, it is resolved against {@code openruneRoot}.
   * If it is already absolute, it is returned unchanged.
   *
   * @return The resolved absolute path for the reports directory
   */
  public Path getResolvedReportsDir() {
    Path reportsPath = Paths.get(reportsDir);
    if (reportsPath.isAbsolute()) {
      return reportsPath;
    }
    return Paths.get(openruneRoot).resolve(reportsPath);
  }

  public void setReportsDir(String reportsDir) {
    this.reportsDir = reportsDir;
  }

  public int getMaxConcurrentJobs() {
    return maxConcurrentJobs;
  }

  public void setMaxConcurrentJobs(int maxConcurrentJobs) {
    this.maxConcurrentJobs = maxConcurrentJobs;
  }

  public int getJobTimeoutSeconds() {
    return jobTimeoutSeconds;
  }

  public void setJobTimeoutSeconds(int jobTimeoutSeconds) {
    this.jobTimeoutSeconds = jobTimeoutSeconds;
  }

  public boolean isAutoCreateReportsDir() {
    return autoCreateReportsDir;
  }

  public void setAutoCreateReportsDir(boolean autoCreateReportsDir) {
    this.autoCreateReportsDir = autoCreateReportsDir;
  }

  @Override
  public String toString() {
    return "SimulatorVerifierProperties{"
        + "enabled="
        + enabled
        + ", openruneRoot='"
        + openruneRoot
        + '\''
        + ", defaultBridgeUrl='"
        + defaultBridgeUrl
        + '\''
        + ", reportsDir='"
        + reportsDir
        + '\''
        + ", maxConcurrentJobs="
        + maxConcurrentJobs
        + ", jobTimeoutSeconds="
        + jobTimeoutSeconds
        + '}';
  }
}
