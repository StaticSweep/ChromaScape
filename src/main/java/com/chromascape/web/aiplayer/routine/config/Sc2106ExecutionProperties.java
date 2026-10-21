package com.chromascape.web.aiplayer.routine.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for executing registered candidates through the Micro6 host (SC-2106 R9.1).
 *
 * <p>Disabled by default. Execution is offline: the candidate runs against an XRSPS worker, never a
 * live client, so it does not depend on {@code FEATURES_AI_PLAYER_CONTROL}, which stays false. The
 * trust policy is never configured here; the host reads it from {@code SC2106_TRUST_POLICY}, which
 * belongs in {@link #getHostEnvironment()}.
 */
@Component
@ConfigurationProperties(prefix = "sc2106.execution")
public class Sc2106ExecutionProperties {
  /** Runtime gate; when false nothing is staged and no host is started. */
  private boolean enabled = false;

  /** Content-addressed artifact store the host resolves from ({@code <root>/sha256/<hex>}). */
  private String storeRoot = "";

  /** Where the host stages verified workspaces; each is removed after its run. */
  private String workspaceRoot = "";

  /** Parent of the per-attempt run directories. */
  private String runRoot = "";

  /** Host command, for example {@code java -cp <closure jars> ...CandidateHostMain --run}. */
  private List<String> hostCommand = new ArrayList<>();

  /** Added to the host's environment; {@code SC2106_TRUST_POLICY} goes here. */
  private Map<String, String> hostEnvironment = new LinkedHashMap<>();

  /** Passed in the job for the candidate JVM; the host filters it again. */
  private Map<String, String> childEnvironment = new LinkedHashMap<>();

  /** Must equal the {@code policy_version} of the host's trust policy. */
  private String verifierPolicyVersion = "";

  /** The XRSPS worker the runner starts, for example {@code node dist/worker/main.js --stdio}. */
  private List<String> workerCommand = new ArrayList<>();

  /** Working directory of the worker command. */
  private String workerDirectory = "";

  /** True for a development worker build; its results are never certifiable. */
  private boolean developmentWorker = false;

  /** Upper bound on one host run, after which it is cancelled. */
  private Duration hostTimeout = Duration.ofMinutes(20);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getStoreRoot() {
    return storeRoot;
  }

  public void setStoreRoot(String storeRoot) {
    this.storeRoot = storeRoot == null ? "" : storeRoot;
  }

  public String getWorkspaceRoot() {
    return workspaceRoot;
  }

  public void setWorkspaceRoot(String workspaceRoot) {
    this.workspaceRoot = workspaceRoot == null ? "" : workspaceRoot;
  }

  public String getRunRoot() {
    return runRoot;
  }

  public void setRunRoot(String runRoot) {
    this.runRoot = runRoot == null ? "" : runRoot;
  }

  public List<String> getHostCommand() {
    return hostCommand;
  }

  public void setHostCommand(List<String> hostCommand) {
    this.hostCommand = hostCommand == null ? new ArrayList<>() : new ArrayList<>(hostCommand);
  }

  public Map<String, String> getHostEnvironment() {
    return hostEnvironment;
  }

  public void setHostEnvironment(Map<String, String> hostEnvironment) {
    this.hostEnvironment =
        hostEnvironment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(hostEnvironment);
  }

  public Map<String, String> getChildEnvironment() {
    return childEnvironment;
  }

  public void setChildEnvironment(Map<String, String> childEnvironment) {
    this.childEnvironment =
        childEnvironment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(childEnvironment);
  }

  public String getVerifierPolicyVersion() {
    return verifierPolicyVersion;
  }

  public void setVerifierPolicyVersion(String verifierPolicyVersion) {
    this.verifierPolicyVersion = verifierPolicyVersion == null ? "" : verifierPolicyVersion;
  }

  public List<String> getWorkerCommand() {
    return workerCommand;
  }

  public void setWorkerCommand(List<String> workerCommand) {
    this.workerCommand = workerCommand == null ? new ArrayList<>() : new ArrayList<>(workerCommand);
  }

  public String getWorkerDirectory() {
    return workerDirectory;
  }

  public void setWorkerDirectory(String workerDirectory) {
    this.workerDirectory = workerDirectory == null ? "" : workerDirectory;
  }

  public boolean isDevelopmentWorker() {
    return developmentWorker;
  }

  public void setDevelopmentWorker(boolean developmentWorker) {
    this.developmentWorker = developmentWorker;
  }

  public Duration getHostTimeout() {
    return hostTimeout;
  }

  public void setHostTimeout(Duration hostTimeout) {
    this.hostTimeout = hostTimeout == null ? Duration.ofMinutes(20) : hostTimeout;
  }
}
