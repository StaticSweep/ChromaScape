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
      String sourceSnapshotId, String sourceFile, int startLine, int endLine, String preimageHash,
      String failureManifestHash, String proposalHash, String approvalHash, String patchHash, String sourceManifestHash, String modelHash) {}
  public record BuildResult(String status, String baseCommit, List<String> changedFiles, String failureCode, Path outputDirectory,
      String jarName, byte[] jarBytes, String sbomName, byte[] sbomBytes, byte[] testReportBytes, byte[] certificationBytes) {
    public BuildResult(String status, String baseCommit, List<String> changedFiles, String failureCode, Path outputDirectory) {
      this(status, baseCommit, changedFiles, failureCode, outputDirectory, null, null, null, null, null, null);
    }
  }

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
          ":runelite-client:compileJava", ":runelite-client:checkstyleMain", ":runelite-client:checkstyleTest", ":runelite-client:test", ":runelite-client:shadowJar", ":runelite-client:generateRuntimeSbom"), repo,
          "BUILD_FAILED", java.util.Map.of("GRADLE_USER_HOME", gradleHome.toString()));
      Path jar = findShadowJar(repo);
      byte[] jarBytes = Files.readAllBytes(jar);
      byte[] sbomBytes = deterministicSbom(repo);
      byte[] testReportBytes = deterministicTestReport(repo);
      byte[] certificationBytes = certification(request, jarBytes, sbomBytes, testReportBytes);
      result = new BuildResult("BUILT", request.baseCommit(), changed, null, null,
          jar.getFileName().toString(), jarBytes, "sbom.cdx.json", sbomBytes, testReportBytes, certificationBytes);
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

  private static Path findShadowJar(Path repo) throws IOException {
    Path libs = repo.resolve("runelite-client/build/libs");
    if (!Files.isDirectory(libs)) throw new IOException("JAR_NOT_FOUND");
    try (var stream = Files.list(libs)) {
      return stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
          .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString().contains("shadow") ? 0 : 1))
          .findFirst().orElseThrow(() -> new IOException("JAR_NOT_FOUND"));
    }
  }

  private static byte[] deterministicSbom(Path repo) throws IOException {
    // The SBOM deliberately omits timestamps and serial numbers. Runtime dependency resolution is
    // represented by sorted Maven coordinates when Gradle has emitted its resolved-runtime report.
    List<String> components = new ArrayList<>();
    Path report = repo.resolve("runelite-client/build/reports/runtimeClasspath.txt");
    if (Files.isRegularFile(report)) components.addAll(Files.readAllLines(report, StandardCharsets.UTF_8));
    components.sort(String::compareTo);
    StringBuilder json = new StringBuilder("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[");
    for (int i = 0; i < components.size(); i++) { if (i > 0) json.append(','); json.append("{\"purl\":").append(jsonQuote(components.get(i))).append("}"); }
    return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] deterministicTestReport(Path repo) throws IOException {
    Path reports = repo.resolve("runelite-client/build/test-results");
    if (!Files.isDirectory(reports)) return "[]".getBytes(StandardCharsets.UTF_8);
    List<String> files = new ArrayList<>();
    try (var stream = Files.walk(reports)) { stream.filter(Files::isRegularFile).sorted().forEach(path -> { try { files.add(repo.relativize(path).toString() + "\n" + Files.readString(path)); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }); }
    return String.join("\n", files).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] certification(BuildRequest request, byte[] jar, byte[] sbom, byte[] report) {
    StringBuilder json = new StringBuilder();
    json.append("{\"schema\":\"candidate-certification/v1\",\"baseline_failure_manifest_hash\":\"").append(request.failureManifestHash())
        .append("\",\"source_manifest_hash\":\"").append(request.sourceManifestHash()).append("\",\"source_snapshot_id\":\"").append(request.sourceSnapshotId())
        .append("\",\"patch_hash\":\"").append(request.patchHash()).append("\",\"proposal_hash\":\"").append(request.proposalHash())
        .append("\",\"approval_hash\":\"").append(request.approvalHash()).append("\",\"post_patch_test_result_hash\":\"").append(hash(testReport(report)))
        .append("\",\"jar_sha256\":\"").append(hash(jar)).append("\",\"sbom_sha256\":\"").append(hash(sbom)).append("\",\"model_hash\":\"").append(request.modelHash())
        .append("\",\"client_build_id\":\"").append(request.baseCommit()).append("\"}");
    return json.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String jsonQuote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
  private static byte[] testReport(byte[] report) { return report; }
  private static String hash(byte[] data) { try { byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data); StringBuilder value = new StringBuilder("sha256:"); for (byte b : digest) value.append(String.format("%02x", b)); return value.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }

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
