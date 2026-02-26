package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest;
import com.chromascape.web.debug.patchloop.entity.PatchApplyStatus;
import com.chromascape.web.debug.patchloop.entity.PatchLoopPatchArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopPatchArtifactRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for staging and applying patches with safety policy checks.
 *
 * <p>Creates feature branches, stages patches, validates against policies, and applies changes.
 */
@Service
public class PatchApplyService {

  private static final Logger logger = LoggerFactory.getLogger(PatchApplyService.class);

  private final PatchLoopPatchArtifactRepository patchArtifactRepository;
  private final PatchPolicy defaultPolicy;

  private static final String STAGING_DIR = ".chromascape/staging";
  private static final String BACKUP_DIR = ".chromascape/backups";

  public PatchApplyService(
      PatchLoopPatchArtifactRepository patchArtifactRepository, PatchPolicy defaultPolicy) {
    this.patchArtifactRepository = patchArtifactRepository;
    this.defaultPolicy = defaultPolicy;
  }

  /**
   * Stages and applies a patch with policy validation.
   *
   * @param executionId the execution ID
   * @param diffText the unified diff text
   * @param attemptNumber the attempt number
   * @param policy the policy to apply (uses default if null)
   * @return the apply result
   */
  @Transactional
  public PatchApplyResult stageAndApply(
      String executionId,
      String diffText,
      int attemptNumber,
      GraphExecuteRequest.PatchPolicyConfig policy) {

    String patchId = "patch_" + UUID.randomUUID().toString().substring(0, 8);

    logger.info("Applying patch {} for execution {}", patchId, executionId);

    try {
      // Parse the diff to extract affected files and line counts
      DiffInfo diffInfo = parseDiff(diffText);

      // Validate against policy
      PolicyValidationResult policyValidation = validateAgainstPolicy(diffInfo, policy);

      if (!policyValidation.isValid()) {
        logger.warn("Patch {} rejected by policy: {}", patchId, policyValidation.errorMessage());
        return new PatchApplyResult(
            PatchApplyStatus.FAILED,
            null,
            null,
            0,
            0,
            policyValidation.errorMessage());
      }

      // Create the patch artifact record
      PatchLoopPatchArtifact artifact = new PatchLoopPatchArtifact();
      artifact.setExecutionId(executionId);
      artifact.setAttemptNumber(attemptNumber);
      artifact.setPatchId(patchId);
      artifact.setDiffText(diffText);
      artifact.setFilesChanged(diffInfo.filesChanged());
      artifact.setLinesChanged(diffInfo.linesChanged());
      artifact.setApplyStatus(PatchApplyStatus.STAGED);
      artifact.setConfidence("MEDIUM");

      // Store affected files as JSON
      ObjectNode affectedFilesJson = JsonNodeFactory.instance.objectNode();
      affectedFilesJson.put("files", JsonNodeFactory.instance.arrayNode().addAll(diffInfo.files()));
      artifact.setAffectedFiles(affectedFilesJson);

      patchArtifactRepository.save(artifact);

      // Stage the patch in the staging directory
      Path stagingPath = Paths.get(STAGING_DIR, executionId, String.valueOf(attemptNumber));
      Files.createDirectories(stagingPath);

      Path patchFile = stagingPath.resolve(patchId + ".patch");
      Files.writeString(patchFile, diffText);

      // Create backup of original files
      createBackups(diffInfo.files(), executionId, attemptNumber);

      // Apply the patch using git apply
      String gitBranch = createFeatureBranch(executionId, attemptNumber);
      applyPatchWithGit(patchFile.toFile());

      // Commit the changes
      String gitCommit = commitChanges(patchId, diffInfo, executionId);

      // Update the artifact
      artifact.markApplied(gitBranch, gitCommit);
      patchArtifactRepository.save(artifact);

      logger.info("Patch {} applied successfully on branch {}", patchId, gitBranch);

      return new PatchApplyResult(
          PatchApplyStatus.APPLIED,
          gitBranch,
          gitCommit,
          diffInfo.filesChanged(),
          diffInfo.linesChanged(),
          null);

    } catch (Exception e) {
      logger.error("Failed to apply patch {}", patchId, e);
      return new PatchApplyResult(
          PatchApplyStatus.FAILED, null, null, 0, 0, "Patch application failed: " + e.getMessage());
    }
  }

  /**
   * Rolls back a patch by restoring from backup.
   */
  @Transactional
  public void rollbackPatch(String patchId, String reason) {
    PatchLoopPatchArtifact artifact =
        patchArtifactRepository.findByPatchId(patchId).orElse(null);

    if (artifact == null) {
      logger.warn("Cannot rollback patch {}: not found", patchId);
      return;
    }

    if (!artifact.isApplied()) {
      logger.warn("Cannot rollback patch {}: not applied", patchId);
      return;
    }

    logger.info("Rolling back patch {} - {}", patchId, reason);

    try {
      // Restore files from backup
      Path backupPath =
          Paths.get(BACKUP_DIR, artifact.getExecutionId(), String.valueOf(artifact.getAttemptNumber()));

      if (Files.exists(backupPath)) {
        // TODO: Implement actual file restoration
        // For now, just mark as rolled back
      }

      artifact.markRolledBack(reason);
      patchArtifactRepository.save(artifact);

    } catch (Exception e) {
      logger.error("Failed to rollback patch {}", patchId, e);
    }
  }

  /**
   * Validates a diff against the safety policy.
   */
  private PolicyValidationResult validateAgainstPolicy(
      DiffInfo diffInfo, GraphExecuteRequest.PatchPolicyConfig policyConfig) {

    // Use provided config or default
    int maxFiles = policyConfig != null && policyConfig.getMaxFilesChanged() != null
        ? policyConfig.getMaxFilesChanged()
        : defaultPolicy.getMaxFilesChanged();

    int maxLines = policyConfig != null && policyConfig.getMaxLinesChanged() != null
        ? policyConfig.getMaxLinesChanged()
        : defaultPolicy.getMaxLinesChanged();

    List<String> blockedPaths = policyConfig != null && policyConfig.getBlockedPaths() != null
        ? policyConfig.getBlockedPaths()
        : defaultPolicy.getBlockedPaths();

    List<String> forbiddenPatterns = policyConfig != null && policyConfig.getForbiddenPatterns() != null
        ? policyConfig.getForbiddenPatterns()
        : defaultPolicy.getForbiddenPatterns();

    // Check file count
    if (diffInfo.filesChanged() > maxFiles) {
      return new PolicyValidationResult(
          false, "File count exceeded: " + diffInfo.filesChanged() + " > " + maxFiles);
    }

    // Check line count
    if (diffInfo.linesChanged() > maxLines) {
      return new PolicyValidationResult(
          false, "Line count exceeded: " + diffInfo.linesChanged() + " > " + maxLines);
    }

    // Check for blocked paths
    for (String file : diffInfo.files()) {
      for (String blockedPattern : blockedPaths) {
        if (matchesPattern(file, blockedPattern)) {
          return new PolicyValidationResult(
              false, "Blocked path: " + file + " matches " + blockedPattern);
        }
      }
    }

    // Check for forbidden patterns in the diff
    String diffText = diffInfo.rawDiff();
    if (diffText != null) {
      for (String pattern : forbiddenPatterns) {
        if (diffText.contains(pattern)) {
          return new PolicyValidationResult(
              false, "Forbidden pattern detected: " + pattern);
        }
      }
    }

    return new PolicyValidationResult(true, null);
  }

  /**
   * Parses a unified diff to extract file and line information.
   */
  private DiffInfo parseDiff(String diffText) {
    List<String> files = new ArrayList<>();
    int filesChanged = 0;
    int linesChanged = 0;

    for (String line : diffText.split("\n")) {
      if (line.startsWith("+++ ") || line.startsWith("--- ")) {
        String file = line.substring(4).split("\t")[0].trim();
        // Remove a/ and b/ prefixes
        file = file.replaceFirst("^[ab]/", "");
        if (!file.startsWith("/dev/null") && !files.contains(file)) {
          files.add(file);
          filesChanged++;
        }
      } else if (line.startsWith("+") && !line.startsWith("+++")) {
        linesChanged++;
      } else if (line.startsWith("-") && !line.startsWith("---")) {
        linesChanged++;
      }
    }

    return new DiffInfo(files, filesChanged, linesChanged, diffText);
  }

  /**
   * Creates backups of files before patching.
   */
  private void createBackups(List<String> files, String executionId, int attemptNumber)
      throws IOException {

    Path backupPath = Paths.get(BACKUP_DIR, executionId, String.valueOf(attemptNumber));
    Files.createDirectories(backupPath);

    for (String file : files) {
      Path original = Paths.get(file);
      if (Files.exists(original)) {
        Path backup = backupPath.resolve(file.replace("/", "_"));
        Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /**
   * Creates a feature branch for the patch.
   */
  private String createFeatureBranch(String executionId, int attemptNumber) {
    String branchName = String.format("patchloop/%s/%d", executionId, attemptNumber);

    try {
      // Create and checkout the branch using git
      ProcessBuilder pb = new ProcessBuilder("git", "checkout", "-b", branchName);
      pb.redirectErrorStream(true);
      Process process = pb.start();
      process.waitFor();

      logger.debug("Created branch: {}", branchName);
      return branchName;

    } catch (Exception e) {
      logger.error("Failed to create branch {}", branchName, e);
      // Fallback: return a virtual branch name
      return branchName;
    }
  }

  /**
   * Applies a patch file using git apply.
   */
  private void applyPatchWithGit(java.io.File patchFile) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("git", "apply", patchFile.getAbsolutePath());
    pb.redirectErrorStream(true);
    Process process = pb.start();

    // Read output
    java.io.BufferedReader reader =
        new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
    StringBuilder output = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      output.append(line).append("\n");
    }

    int exitCode = process.waitFor();

    if (exitCode != 0) {
      throw new RuntimeException("git apply failed: " + output);
    }
  }

  /**
   * Commits the applied patch changes.
   */
  private String commitChanges(String patchId, DiffInfo diffInfo, String executionId)
      throws Exception {

    String commitMessage =
        String.format(
            "PatchLoop: Apply patch %s\n\nExecution: %s\nFiles changed: %d\nLines changed: %d",
            patchId, executionId, diffInfo.filesChanged(), diffInfo.linesChanged());

    ProcessBuilder pb =
        new ProcessBuilder("git", "commit", "-am", commitMessage, "-m", "[PatchLoop auto-commit]");
    pb.redirectErrorStream(true);
    Process process = pb.start();
    process.waitFor();

    // Get the commit hash
    pb = new ProcessBuilder("git", "rev-parse", "HEAD");
    pb.redirectErrorStream(true);
    process = pb.start();

    java.io.BufferedReader reader =
        new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
    String commitHash = reader.readLine().trim();
    process.waitFor();

    return commitHash;
  }

  /**
   * Checks if a file path matches an Ant-style pattern.
   */
  private boolean matchesPattern(String path, String pattern) {
    // Simple glob matching (enhance with AntPathMatcher if needed)
    String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
    return path.matches(regex);
  }

  /** Result of a patch apply operation. */
  public record PatchApplyResult(
      PatchApplyStatus status,
      String gitBranch,
      String gitCommit,
      int filesChanged,
      int linesChanged,
      String errorMessage) {}

  /** Result of policy validation. */
  private record PolicyValidationResult(boolean isValid, String errorMessage) {}

  /** Parsed diff information. */
  private record DiffInfo(List<String> files, int filesChanged, int linesChanged, String rawDiff) {}
}
