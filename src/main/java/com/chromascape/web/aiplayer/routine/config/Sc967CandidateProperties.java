package com.chromascape.web.aiplayer.routine.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding for the SC-967 candidate build gate.
 *
 * <p>Relaxed binding means the documented environment variables bind directly:
 * {@code SC967_CANDIDATE_BUILDS_ENABLED} → {@code sc967.candidate.builds-enabled} and
 * {@code SC967_CANDIDATE_STAGING_ROOT} → {@code sc967.candidate.staging-root}. The previous
 * flattened {@code @Value} keys ({@code sc967.candidate-builds-enabled}) never matched the YAML
 * tree and silently defaulted the gate to disabled.
 *
 * <p>Bean validation is conditional: the cross-field constraint only fires when candidate builds
 * are enabled. With builds disabled (the default) an empty staging root is valid. The {@link
 * com.chromascape.web.aiplayer.routine.service.PatchCandidateWorkflowService} startup gate still
 * fails closed when candidate builds are enabled but the root is unusable.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "sc967.candidate")
public class Sc967CandidateProperties {

  /**
   * Runtime gate for candidate builds. When false (default), build enqueue, worker execution and
   * recovery dispatch are all rejected. When true, startup fails unless operator token/ID, signing
   * key path/key ID, registry URL/token, staging root and the exact expected JAR name are valid.
   */
  private boolean buildsEnabled = false;

  /** Persistent root for durable candidate registration bundles (required when builds are enabled). */
  private String stagingRoot = "";

  public boolean isBuildsEnabled() {
    return buildsEnabled;
  }

  public void setBuildsEnabled(boolean buildsEnabled) {
    this.buildsEnabled = buildsEnabled;
  }

  public String getStagingRoot() {
    return stagingRoot == null ? "" : stagingRoot;
  }

  public void setStagingRoot(String stagingRoot) {
    this.stagingRoot = stagingRoot == null ? "" : stagingRoot;
  }

  /** A staging root is only required when candidate builds are enabled. */
  @AssertTrue(message = "sc967.candidate.staging-root (SC967_CANDIDATE_STAGING_ROOT) is required when candidate builds are enabled")
  public boolean isStagingRootValidWhenBuildsEnabled() {
    return !buildsEnabled || (stagingRoot != null && !stagingRoot.isBlank());
  }
}
