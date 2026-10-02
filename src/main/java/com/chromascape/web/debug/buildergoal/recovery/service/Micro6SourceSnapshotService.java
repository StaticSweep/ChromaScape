package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.aiplayer.routine.source.SourceManifestContract;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Read-only source identity boundary for compile recovery. Resolves the pinned Micro6 commit, tree,
 * cleanliness, and per-file pre-image identities without ever mutating the checkout, and renders
 * them as a manifest-compatible immutable validation bundle. Signed producer manifests remain the
 * production authority; when required and unavailable, this snapshot fails closed so unsigned
 * provenance can never enter approval.
 */
@Service
public class Micro6SourceSnapshotService {

  public record SourceFile(String relativePath, String contentHash, long sizeBytes) {}

  public record SourceSnapshot(
      String repositoryId,
      String sourceSnapshotId,
      String sourceManifestHash,
      String commitId,
      String treeId,
      String buildId,
      String artifactDigest,
      boolean manifestSigned,
      Map<String, SourceFile> files) {}

  private final BuilderGoalRecoveryProperties properties;
  private final ObjectMapper objectMapper;

  public Micro6SourceSnapshotService(
      BuilderGoalRecoveryProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public SourceSnapshot resolve(List<String> affectedPaths) {
    Path root = micro6Root();
    String commit = git(root, "rev-parse", "HEAD");
    String tree = git(root, "rev-parse", "HEAD^{tree}");
    String status = git(root, "status", "--porcelain");
    if (commit == null || tree == null || !commit.matches("[0-9a-f]{40}")) {
      throw RecoveryException.unprocessable(
          RecoveryFailureCodes.SOURCE_IDENTITY_MISMATCH,
          "Micro6 source commit identity could not be resolved");
    }
    if (status != null && !status.isBlank()) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.SOURCE_IDENTITY_MISMATCH,
          "Micro6 working tree is not clean; recovery requires a clean pinned source base");
    }
    Map<String, SourceFile> files = new LinkedHashMap<>();
    for (String path : affectedPaths.stream().sorted().toList()) {
      SourceFile entry = readSourceFile(root, path);
      if (entry == null) {
        throw RecoveryException.unprocessable(
            RecoveryFailureCodes.PATCH_PREIMAGE_MISMATCH,
            "Pinned source base does not contain a file referenced by the proposal");
      }
      files.put(path, entry);
    }
    String repositoryId = RecoveryHashes.shortId("repo", RecoveryHashes.sha256(root.toString()));
    SourceSnapshot snapshot =
        new SourceSnapshot(
            repositoryId,
            null,
            null,
            commit,
            tree,
            commit,
            RecoveryHashes.sha256(commit + ":" + tree),
            false,
            Map.copyOf(files));
    String manifestJson = buildManifestJson(snapshot);
    String manifestHash = RecoveryHashes.sha256(manifestJson);
    return new SourceSnapshot(
        snapshot.repositoryId(),
        com.chromascape.web.aiplayer.routine.source.SourceManifestContract.snapshotForHash(
            manifestHash),
        manifestHash,
        snapshot.commitId(),
        snapshot.treeId(),
        snapshot.buildId(),
        snapshot.artifactDigest(),
        snapshot.manifestSigned(),
        snapshot.files());
  }

  /**
   * Renders the snapshot as a manifest-compatible immutable validation bundle consumed by the
   * isolated worker. New files carry the explicit empty-file hash per the patch contract.
   */
  public String buildManifestJson(SourceSnapshot snapshot) {
    ObjectNode manifest = objectMapper.createObjectNode();
    manifest.put("schema", SourceManifestContract.SCHEMA);
    manifest.put("status", "AVAILABLE");
    ObjectNode repository = manifest.putObject("repository");
    repository.put("commit_id", snapshot.commitId());
    repository.put("tree_id", snapshot.treeId());
    repository.put("clean", true);
    ObjectNode build = manifest.putObject("build");
    build.put("build_id", snapshot.buildId());
    build.put("artifact_digest", snapshot.artifactDigest());
    ArrayNode filesNode = manifest.putArray("files");
    Map<String, SourceFile> sorted = new TreeMap<>(snapshot.files());
    List<Map.Entry<String, SourceFile>> entries = new ArrayList<>(sorted.entrySet());
    for (Map.Entry<String, SourceFile> entry : entries) {
      String path = entry.getKey();
      String contentHash = entry.getValue().contentHash();
      ObjectNode fileNode = filesNode.addObject();
      fileNode.put("relative_path", path);
      fileNode.put("content_hash", contentHash);
      fileNode.put("size_bytes", entry.getValue().sizeBytes());
      fileNode.put("file_id", SourceManifestContract.fileId(path, contentHash));
    }
    byte[] canonicalBytes = SourceManifestContract.canonicalHashBytes(manifest.deepCopy());
    String manifestHash = "sha256:" + java.util.HexFormat.of().formatHex(canonicalBytes);
    manifest.put("source_manifest_hash", manifestHash);
    manifest.put("source_snapshot_id", SourceManifestContract.snapshotForHash(manifestHash));
    try {
      return objectMapper.writeValueAsString(manifest);
    } catch (Exception e) {
      throw new IllegalStateException("manifest rendering failed", e);
    }
  }

  public Path micro6Root() {
    Path root = Path.of(properties.getMicro6ProjectDir()).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw RecoveryException.unprocessable(
          RecoveryFailureCodes.SOURCE_MANIFEST_MISSING,
          "The configured Micro6 source reference is unavailable");
    }
    return root;
  }

  public void requireVerifiedProvenance(SourceSnapshot snapshot) {
    if (properties.isRequireSignedManifest() && !snapshot.manifestSigned()) {
      throw RecoveryException.unprocessable(
          RecoveryFailureCodes.SOURCE_PROVENANCE_UNVERIFIED,
          "Only a signed source manifest with exact commit, tree, and artifact identity may enter"
              + " approval; enable offline development mode explicitly to proceed");
    }
  }

  private SourceFile readSourceFile(Path root, String relativePath) {
    if (!SourceManifestContract.isSafeRelativePath(relativePath)) {
      return null;
    }
    Path file = root.resolve(relativePath).normalize();
    if (!file.startsWith(root) || !Files.isRegularFile(file)) {
      return null;
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      String normalized =
          new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
      return new SourceFile(relativePath, RecoveryHashes.sha256(normalized), normalized.length());
    } catch (IOException e) {
      return null;
    }
  }

  private static String git(Path root, String... args) {
    try {
      List<String> command = new ArrayList<>();
      command.add("git");
      command.add("-C");
      command.add(root.toString());
      for (String arg : args) {
        command.add(arg);
      }
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      builder.environment().clear();
      String path = System.getenv("PATH");
      if (path != null) {
        builder.environment().put("PATH", path);
      }
      builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
      Process process = builder.start();
      boolean finished = process.waitFor(20, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return null;
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        return null;
      }
      return output.trim();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }
}
