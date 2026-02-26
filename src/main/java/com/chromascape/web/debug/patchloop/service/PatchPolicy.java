package com.chromascape.web.debug.patchloop.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for patch safety policies.
 *
 * <p>Controls what patches are allowed to be applied automatically.
 */
@Component
@ConfigurationProperties(prefix = "patchloop.policy")
public class PatchPolicy {

  /** Maximum number of files a patch can change. */
  private int maxFilesChanged = 5;

  /** Maximum number of lines a patch can change. */
  private int maxLinesChanged = 300;

  /** Allowed file path patterns (ant-style globs). */
  private List<String> allowedPaths = List.of("src/main/java/**", "src/main/resources/**");

  /** Blocked file path patterns (ant-style globs). */
  private List<String> blockedPaths =
      List.of(
          "**/secrets/**",
          "**/credentials/**",
          "**/build/**",
          "**/.github/**",
          "**/gradle/**",
          "**/.git/**");

  /** Forbidden code patterns that will reject a patch. */
  private List<String> forbiddenPatterns =
      List.of("System.exit", "Runtime.exec", "ProcessBuilder", "eval(", "exec(");

  /** Whether to automatically rollback patches on validation failure. */
  private boolean autoRollbackOnFailure = true;

  /** Maximum time for patch loop execution in minutes. */
  private int timeoutMinutes = 30;

  /** Maximum number of retry attempts. */
  private int maxAttempts = 3;

  public int getMaxFilesChanged() {
    return maxFilesChanged;
  }

  public void setMaxFilesChanged(int maxFilesChanged) {
    this.maxFilesChanged = maxFilesChanged;
  }

  public int getMaxLinesChanged() {
    return maxLinesChanged;
  }

  public void setMaxLinesChanged(int maxLinesChanged) {
    this.maxLinesChanged = maxLinesChanged;
  }

  public List<String> getAllowedPaths() {
    return allowedPaths;
  }

  public void setAllowedPaths(List<String> allowedPaths) {
    this.allowedPaths = allowedPaths;
  }

  public List<String> getBlockedPaths() {
    return blockedPaths;
  }

  public void setBlockedPaths(List<String> blockedPaths) {
    this.blockedPaths = blockedPaths;
  }

  public List<String> getForbiddenPatterns() {
    return forbiddenPatterns;
  }

  public void setForbiddenPatterns(List<String> forbiddenPatterns) {
    this.forbiddenPatterns = forbiddenPatterns;
  }

  public boolean isAutoRollbackOnFailure() {
    return autoRollbackOnFailure;
  }

  public void setAutoRollbackOnFailure(boolean autoRollbackOnFailure) {
    this.autoRollbackOnFailure = autoRollbackOnFailure;
  }

  public int getTimeoutMinutes() {
    return timeoutMinutes;
  }

  public void setTimeoutMinutes(int timeoutMinutes) {
    this.timeoutMinutes = timeoutMinutes;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }
}
