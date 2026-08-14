package com.chromascape.web.aiplayer.routine.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class IsolatedPatchWorker {
  /** commands is retained for source compatibility but must always be empty; the worker owns gates. */
  public record BuildRequest(Path micro6Root, String baseCommit, String unifiedDiff, Set<String> allowlistedPaths, List<String> commands,
      String sourceSnapshotId, String sourceFile, int startLine, int endLine, String preimageHash) {}
  public record BuildResult(String status, String baseCommit, List<String> changedFiles, String failureCode, Path outputDirectory) {}

  /** Executes only in a private clone and never executes caller-supplied shell commands. */
  public BuildResult run(BuildRequest request) {
    Path temp = null;
    BuildResult result = null;
    try {
      if (request == null || request.micro6Root() == null || request.baseCommit() == null || request.unifiedDiff() == null) {
        return new BuildResult("FAILED", null, List.of(), "ISOLATED_CLONE_FAILED", null);
      }
      if (request.commands() != null && !request.commands().isEmpty()) {
        return new BuildResult("FAILED", request.baseCommit(), List.of(), "CALLER_COMMANDS_FORBIDDEN", null);
      }
      temp = Files.createTempDirectory("sc967-micro6-");
      Path repo = temp.resolve("repo");
      Path patch = temp.resolve("candidate.patch");
      runCommand(List.of("git", "clone", "--no-hardlinks", request.micro6Root().toString(), repo.toString()), temp, "ISOLATED_CLONE_FAILED");
      runCommand(List.of("git", "-C", repo.toString(), "checkout", "--detach", request.baseCommit()), temp, "ISOLATED_CLONE_FAILED");
      if (!capture(List.of("git", "-C", repo.toString(), "status", "--porcelain"), temp).isBlank()) {
        return new BuildResult("FAILED", request.baseCommit(), List.of(), "ISOLATED_TREE_DIRTY", null);
      }
      if (!verifySourcePreimage(repo, request)) {
        return new BuildResult("FAILED", request.baseCommit(), List.of(), "SOURCE_PREIMAGE_MISMATCH", null);
      }
      Files.writeString(patch, request.unifiedDiff(), StandardCharsets.UTF_8);
      runCommand(List.of("git", "-C", repo.toString(), "apply", "--check", patch.toString()), temp, "PATCH_CHECK_FAILED");
      runCommand(List.of("git", "-C", repo.toString(), "apply", patch.toString()), temp, "PATCH_APPLY_FAILED");

      Set<String> changedSet = new HashSet<>();
      addLines(changedSet, capture(List.of("git", "-C", repo.toString(), "diff", "--name-only"), temp));
      addLines(changedSet, capture(List.of("git", "-C", repo.toString(), "ls-files", "--others", "--exclude-standard"), temp));
      List<String> changed = changedSet.stream().sorted().toList();
      Set<String> allowlist = request.allowlistedPaths() == null ? Set.of() : request.allowlistedPaths();
      if (!allowlist.containsAll(changed) || changed.stream().anyMatch(path -> !allowlist.contains(path))) {
        return new BuildResult("FAILED", request.baseCommit(), changed, "PATCH_ALLOWLIST_VIOLATION", null);
      }

      // The worker owns the gate list and always runs Gradle offline in a private user home.
      Path gradleHome = configuredGradleHome(temp);
      Files.createDirectories(gradleHome);
      runCommand(List.of("bash", "./gradlew", "--offline", "--no-daemon", "--no-watch-fs", "--max-workers=1",
          ":runelite-client:compileJava", ":runelite-client:test", ":runelite-client:shadowJar"), repo,
          "BUILD_FAILED", java.util.Map.of("GRADLE_USER_HOME", gradleHome.toString()));
      result = new BuildResult("BUILT", request.baseCommit(), changed, null, null);
    } catch (Exception e) {
      result = new BuildResult("FAILED", request == null ? null : request.baseCommit(), List.of(),
          e.getMessage() == null ? "BUILD_FAILED" : e.getMessage(), null);
    } finally {
      if (temp != null) {
        try {
          deleteRecursively(temp);
        } catch (IOException cleanupFailure) {
          result = new BuildResult("FAILED", result == null ? null : result.baseCommit(),
              result == null ? List.of() : result.changedFiles(), "CLEANUP_FAILED", null);
        }
      }
    }
    return result;
  }

  private static void addLines(Set<String> target, String output) {
    for (String line : output.split("\\R")) if (!line.isBlank()) target.add(line.trim());
  }

  private static void runCommand(List<String> command, Path cwd, String code) throws IOException, InterruptedException {
    runCommand(command, cwd, code, java.util.Map.of());
  }

  private static void runCommand(List<String> command, Path cwd, String code, java.util.Map<String, String> environment)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    builder.environment().putAll(environment);
    Process process = builder.start();
    if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0) throw new IOException(code);
  }

  private static String capture(List<String> command, Path cwd) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0) throw new IOException("COMMAND_FAILED");
    return new String(output, StandardCharsets.UTF_8);
  }

  private static boolean verifySourcePreimage(Path repo, BuildRequest request) throws Exception {
    if (request.sourceFile() == null || request.preimageHash() == null || request.startLine() <= 0 || request.endLine() < request.startLine()) return false;
    Path file = repo.resolve(request.sourceFile()).normalize();
    if (!file.startsWith(repo.normalize()) || !Files.isRegularFile(file)) return false;
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    if (request.endLine() > lines.size()) return false;
    String span = String.join("\n", lines.subList(request.startLine() - 1, request.endLine()));
    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(span.getBytes(StandardCharsets.UTF_8));
    StringBuilder hash = new StringBuilder("sha256:");
    for (byte value : digest) hash.append(String.format("%02x", value));
    return hash.toString().equals(request.preimageHash());
  }

  private static Path configuredGradleHome(Path temp) throws IOException {
    String configured = System.getenv("SC967_GRADLE_USER_HOME");
    if (configured == null || configured.isBlank()) throw new IOException("GRADLE_CACHE_NOT_CONFIGURED");
    Path source = Path.of(configured).toAbsolutePath().normalize();
    if (!Files.isDirectory(source)) throw new IOException("GRADLE_CACHE_NOT_CONFIGURED");
    Path target = temp.resolve("gradle-user-home");
    try (var stream = Files.walk(source)) {
      stream.forEach(path -> {
        try {
          Path relative = source.relativize(path);
          Path destination = target.resolve(relative);
          if (Files.isDirectory(path)) Files.createDirectories(destination);
          else Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
      });
    } catch (java.io.UncheckedIOException e) { throw e.getCause(); }
    return target;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    IOException[] failure = new IOException[1];
    try (var stream = Files.walk(root)) {
      stream.sorted(Comparator.reverseOrder()).forEach(path -> {
        try { Files.deleteIfExists(path); } catch (IOException e) { failure[0] = e; }
      });
    }
    if (failure[0] != null) throw failure[0];
  }
}
