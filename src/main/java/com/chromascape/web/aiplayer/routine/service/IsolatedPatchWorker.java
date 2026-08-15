package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

@Component
public class IsolatedPatchWorker {
  private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);
  private static final int MAX_OUTPUT_BYTES = 256 * 1024;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** commands is retained for source compatibility but must always be empty; the worker owns gates. */
  public record BuildRequest(Path micro6Root, String baseCommit, String unifiedDiff, Set<String> allowlistedPaths, List<String> commands,
      String sourceSnapshotId, String sourceFile, int startLine, int endLine, String preimageHash,
      String failureManifestHash, String proposalHash, String approvalHash, String patchHash,
      String sourceManifestHash, String sourceManifestJson, String modelHash, String expectedJarName) {}

  public record BuildResult(String status, String baseCommit, List<String> changedFiles, String failureCode, Path outputDirectory,
      String jarName, byte[] jarBytes, String sbomName, byte[] sbomBytes, byte[] testReportBytes, byte[] certificationBytes) {
    public BuildResult(String status, String baseCommit, List<String> changedFiles, String failureCode, Path outputDirectory) {
      this(status, baseCommit, changedFiles, failureCode, outputDirectory, null, null, null, null, null, null);
    }
  }

  /** Executes only in a private clone and never executes caller-supplied shell commands. */
  public BuildResult run(BuildRequest request) {
    Path temp = null;
    List<String> changed = new ArrayList<>();
    BuildResult result = null;
    try {
      validateRequest(request);
      temp = Files.createTempDirectory("sc967-micro6-");
      Path repo = temp.resolve("repo");
      Path patch = temp.resolve("candidate.patch");
      runCommand(List.of("git", "clone", "--no-hardlinks", request.micro6Root().toString(), repo.toString()), temp, "ISOLATED_CLONE_FAILED");
      runCommand(List.of("git", "-C", repo.toString(), "checkout", "--detach", request.baseCommit()), temp, "ISOLATED_CLONE_FAILED");
      if (!capture(List.of("git", "-C", repo.toString(), "status", "--porcelain"), temp).isBlank()) {
        return failed(request, changed, "ISOLATED_TREE_DIRTY");
      }
      verifyCompleteSourceManifest(repo, request);
      if (!verifySourcePreimage(repo, request)) throw new IOException("SOURCE_PREIMAGE_MISMATCH");
      Files.writeString(patch, request.unifiedDiff(), StandardCharsets.UTF_8);
      runCommand(List.of("git", "-C", repo.toString(), "apply", "--check", patch.toString()), temp, "PATCH_CHECK_FAILED");
      runCommand(List.of("git", "-C", repo.toString(), "apply", patch.toString()), temp, "PATCH_APPLY_FAILED");

      changed = changedFiles(repo);
      Set<String> allowlist = request.allowlistedPaths() == null ? Set.of() : request.allowlistedPaths();
      if (!allowlist.equals(new HashSet<>(changed))) return failed(request, changed, "PATCH_ALLOWLIST_VIOLATION");

      Path gradleHome = configuredGradleHome(temp);
      Files.createDirectories(gradleHome);
      runCommand(List.of("bash", "./gradlew", "--offline", "--no-daemon", "--no-watch-fs", "--max-workers=1",
          ":runelite-client:compileJava", ":runelite-client:checkstyleMain", ":runelite-client:checkstyleTest",
          ":runelite-client:test", ":runelite-client:shadowJar", ":runelite-client:generateRuntimeSbom"), repo,
          "BUILD_FAILED", Map.of("GRADLE_USER_HOME", gradleHome.toString()));

      Path jar = findExpectedShadowJar(repo, request.expectedJarName());
      byte[] jarBytes = Files.readAllBytes(jar);
      byte[] sbomBytes = authoritativeSbom(repo);
      byte[] testReportBytes = deterministicTestReport(repo);
      byte[] certificationBytes = certification(request, jarBytes, sbomBytes, testReportBytes);
      result = new BuildResult("BUILT", request.baseCommit(), List.copyOf(changed), null, null,
          jar.getFileName().toString(), jarBytes, "runtime-sbom.cdx.json", sbomBytes, testReportBytes, certificationBytes);
    } catch (Exception e) {
      result = failed(request, changed, e.getMessage() == null ? "BUILD_FAILED" : e.getMessage());
    } finally {
      if (temp != null) {
        try {
          deleteRecursively(temp);
        } catch (IOException cleanupFailure) {
          String original = result == null ? "BUILD_FAILED" : result.failureCode();
          result = failed(request, changed, original + ";CLEANUP_FAILED");
        }
      }
    }
    return result;
  }

  private static BuildResult failed(BuildRequest request, List<String> changed, String code) {
    return new BuildResult("FAILED", request == null ? null : request.baseCommit(), List.copyOf(changed), code, null);
  }

  private static void validateRequest(BuildRequest request) throws IOException {
    if (request == null || request.micro6Root() == null || request.baseCommit() == null || request.unifiedDiff() == null) throw new IOException("ISOLATED_CLONE_FAILED");
    if (request.commands() != null && !request.commands().isEmpty()) throw new IOException("CALLER_COMMANDS_FORBIDDEN");
    if (request.sourceManifestJson() == null || request.sourceManifestJson().isBlank()) throw new IOException("SOURCE_MANIFEST_REQUIRED");
    if (request.expectedJarName() == null || !request.expectedJarName().matches("[^/\\\\]+\\.jar")) throw new IOException("EXPECTED_JAR_NAME_REQUIRED");
    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome != null && !javaHome.isBlank() && !Files.isDirectory(Path.of(javaHome).toAbsolutePath().normalize())) throw new IOException("JAVA_HOME_INVALID");
  }

  private static List<String> changedFiles(Path repo) throws IOException, InterruptedException {
    Set<String> files = new HashSet<>();
    addLines(files, capture(List.of("git", "-C", repo.toString(), "diff", "--name-only"), repo));
    addLines(files, capture(List.of("git", "-C", repo.toString(), "ls-files", "--others", "--exclude-standard"), repo));
    return files.stream().sorted().toList();
  }

  private static void addLines(Set<String> target, String output) {
    for (String line : output.split("\\R")) if (!line.isBlank()) target.add(line.trim());
  }

  private static void runCommand(List<String> command, Path cwd, String code) throws IOException, InterruptedException { runCommand(command, cwd, code, Map.of()); }

  private static void runCommand(List<String> command, Path cwd, String code, Map<String, String> extraEnvironment) throws IOException, InterruptedException {
    Path output = Files.createTempFile(cwd, ".sc967-output-", ".log");
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
      configureEnvironment(builder, extraEnvironment);
      builder.redirectErrorStream(true).redirectOutput(output.toFile());
      process = builder.start();
      if (!process.waitFor(COMMAND_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        throw new IOException(code + ";TIMEOUT");
      }
      if (process.exitValue() != 0) throw new IOException(code + ";OUTPUT=" + boundedOutput(output));
    } catch (InterruptedException interrupted) {
      if (process != null) { process.destroy(); if (process.isAlive()) process.destroyForcibly(); }
      Thread.currentThread().interrupt();
      throw interrupted;
    } finally {
      Files.deleteIfExists(output);
    }
  }

  private static String capture(List<String> command, Path cwd) throws IOException, InterruptedException {
    Path output = Files.createTempFile(cwd, ".sc967-capture-", ".log");
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
      configureEnvironment(builder, Map.of());
      builder.redirectErrorStream(true).redirectOutput(output.toFile());
      process = builder.start();
      if (!process.waitFor(COMMAND_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        throw new IOException("COMMAND_TIMEOUT");
      }
      if (process.exitValue() != 0) throw new IOException("COMMAND_FAILED;OUTPUT=" + boundedOutput(output));
      return Files.readString(output, StandardCharsets.UTF_8);
    } catch (InterruptedException interrupted) {
      process.destroy(); if (process.isAlive()) process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw interrupted;
    } finally {
      Files.deleteIfExists(output);
    }
  }

  private static void configureEnvironment(ProcessBuilder builder, Map<String, String> extra) {
    Map<String, String> environment = builder.environment();
    environment.clear();
    copyEnvironment(environment, "PATH");
    copyEnvironment(environment, "JAVA_HOME");
    copyEnvironment(environment, "TMPDIR");
    copyEnvironment(environment, "TMP");
    copyEnvironment(environment, "TEMP");
    environment.put("LC_ALL", "C.UTF-8");
    environment.put("LANG", "C.UTF-8");
    environment.putAll(extra);
  }

  private static void copyEnvironment(Map<String, String> destination, String name) {
    String value = System.getenv(name);
    if (value != null && !value.isBlank()) destination.put(name, value);
  }

  private static String boundedOutput(Path file) {
    try {
      byte[] bytes = Files.readAllBytes(file);
      int start = Math.max(0, bytes.length - MAX_OUTPUT_BYTES);
      return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    } catch (IOException ignored) { return "unavailable"; }
  }

  private static Path findExpectedShadowJar(Path repo, String expectedName) throws IOException {
    Path libs = repo.resolve("runelite-client/build/libs");
    if (!Files.isDirectory(libs)) throw new IOException("JAR_NOT_FOUND");
    try (var stream = Files.list(libs)) {
      List<Path> matches = stream.filter(path -> expectedName.equals(path.getFileName().toString())).toList();
      if (matches.size() != 1) throw new IOException(matches.isEmpty() ? "JAR_NOT_FOUND" : "JAR_AMBIGUOUS");
      return matches.get(0);
    }
  }

  private static byte[] authoritativeSbom(Path repo) throws IOException {
    Path report = repo.resolve("runelite-client/build/reports/sc967/runtime-sbom.cdx.json");
    if (!Files.isRegularFile(report) || Files.size(report) == 0) throw new IOException("SBOM_NOT_FOUND");
    JsonNode bom = CanonicalJsonHasher.parse(Files.readAllBytes(report));
    if (!"CycloneDX".equals(bom.path("bomFormat").asText()) || !"1.5".equals(bom.path("specVersion").asText()) || !bom.path("components").isArray()) throw new IOException("SBOM_INVALID");
    Set<String> purls = new HashSet<>();
    for (JsonNode component : bom.path("components")) {
      String group = component.path("group").asText(); String name = component.path("name").asText(); String version = component.path("version").asText();
      String purl = component.path("purl").asText(); String hash = component.path("sha256").asText();
      if (group.isBlank() || name.isBlank() || version.isBlank() || !purl.matches("pkg:maven/[^/]+/[^@]+@[^?]+") || !purls.add(purl) || !hash.matches("sha256:[0-9a-f]{64}")) throw new IOException("SBOM_COMPONENT_INVALID");
    }
    return CanonicalJsonHasher.canonical(bom).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] deterministicTestReport(Path repo) throws Exception {
    Path reports = repo.resolve("runelite-client/build/test-results");
    Map<String, List<Map<String, Object>>> grouped = new TreeMap<>();
    if (Files.isDirectory(reports)) {
      try (var stream = Files.walk(reports)) {
        for (Path file : stream.filter(path -> path.toString().endsWith(".xml")).sorted().toList()) {
          var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
          var cases = document.getElementsByTagName("testcase");
          for (int i = 0; i < cases.getLength(); i++) {
            Element test = (Element) cases.item(i);
            String suite = test.getParentNode() instanceof Element parent ? parent.getAttribute("name") : "";
            String name = test.getAttribute("name");
            String className = test.getAttribute("classname");
            String outcome = test.getElementsByTagName("failure").getLength() > 0 || test.getElementsByTagName("error").getLength() > 0 ? "FAIL" : test.getElementsByTagName("skipped").getLength() > 0 ? "SKIP" : "PASS";
            grouped.computeIfAbsent(suite, ignored -> new ArrayList<>()).add(Map.of("class", className, "name", name, "outcome", outcome));
          }
        }
      }
    }
    List<Map<String, Object>> suites = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
      entry.getValue().sort(Comparator.comparing(value -> String.valueOf(value.get("class")) + "\u0000" + value.get("name")));
      for (Map<String, Object> test : entry.getValue()) { names.add(String.valueOf(test.get("name"))); if (!"PASS".equals(test.get("outcome")) && List.of("nullFishIsRejected", "blankFishIsRejected", "unknownFishIsRejected", "canonicalFishRemainsSupported").contains(test.get("name"))) throw new IOException("REGRESSION_TEST_FAILED_" + test.get("name")); }
      suites.add(Map.of("name", entry.getKey(), "tests", entry.getValue()));
    }
    for (String expected : List.of("nullFishIsRejected", "blankFishIsRejected", "unknownFishIsRejected", "canonicalFishRemainsSupported")) if (!names.contains(expected)) throw new IOException("REGRESSION_TEST_MISSING_" + expected);
    return MAPPER.writeValueAsBytes(Map.of("schema", "sc967-test-result/v1", "suites", suites));
  }

  private static byte[] certification(BuildRequest request, byte[] jar, byte[] sbom, byte[] report) throws IOException {
    ObjectNode certification = MAPPER.createObjectNode();
    certification.put("schema", "candidate-certification/v1");
    certification.put("baseline_fail_manifest_hash", request.failureManifestHash());
    certification.put("source_manifest_hash", request.sourceManifestHash());
    certification.put("source_snapshot_id", request.sourceSnapshotId());
    certification.put("patch_hash", request.patchHash());
    certification.put("proposal_hash", request.proposalHash());
    certification.put("approval_hash", request.approvalHash());
    certification.put("post_patch_test_result_hash", hash(report));
    certification.put("jar_sha256", hash(jar));
    certification.put("sbom_sha256", hash(sbom));
    certification.put("model_hash", request.modelHash());
    certification.put("client_build_id", request.baseCommit());
    return CanonicalJsonHasher.canonical(certification).getBytes(StandardCharsets.UTF_8);
  }

  private static void verifyCompleteSourceManifest(Path repo, BuildRequest request) throws Exception {
    JsonNode manifest = CanonicalJsonHasher.parse(request.sourceManifestJson().getBytes(StandardCharsets.UTF_8));
    ObjectNode unsigned = (ObjectNode) manifest.deepCopy();
    unsigned.remove("source_manifest_hash");
    unsigned.remove("source_snapshot_id");
    if (!request.sourceManifestHash().equals(CanonicalJsonHasher.hash(unsigned, null)) || !request.sourceSnapshotId().equals(manifest.path("source_snapshot_id").asText()) || !request.baseCommit().equals(manifest.path("client_build_id").asText())) throw new IOException("SOURCE_MANIFEST_MISMATCH");
    if (!manifest.path("files").isArray() || manifest.path("files").isEmpty()) throw new IOException("SOURCE_MANIFEST_INVALID");
    boolean selected = false;
    for (JsonNode entry : manifest.path("files")) {
      String relative = entry.path("relative_path").asText();
      String expectedHash = entry.path("content_hash").asText();
      long expectedSize = entry.path("size_bytes").asLong(-1);
      Path file = repo.resolve("runelite-client").resolve(relative).normalize();
      if (!file.startsWith(repo.resolve("runelite-client").normalize()) || !Files.isRegularFile(file)) throw new IOException("SOURCE_FILE_MISSING_" + relative);
      byte[] normalized = normalize(Files.readAllBytes(file));
      if (!expectedHash.equals(hash(normalized)) || expectedSize != normalized.length) throw new IOException("SOURCE_MANIFEST_FILE_MISMATCH_" + relative);
      if (("runelite-client/" + relative).equals(request.sourceFile())) selected = true;
    }
    if (!selected) throw new IOException("SOURCE_SELECTED_FILE_MISMATCH");
  }

  private static boolean verifySourcePreimage(Path repo, BuildRequest request) throws Exception {
    if (request.sourceFile() == null || request.preimageHash() == null || request.startLine() <= 0 || request.endLine() < request.startLine()) return false;
    Path file = repo.resolve(request.sourceFile()).normalize();
    if (!file.startsWith(repo.normalize()) || !Files.isRegularFile(file)) return false;
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    if (request.endLine() > lines.size()) return false;
    String span = String.join("\n", lines.subList(request.startLine() - 1, request.endLine()));
    return request.preimageHash().equals(hash(span.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] normalize(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n").getBytes(StandardCharsets.UTF_8); }
  private static String hash(byte[] data) { try { return "sha256:" + toHex(MessageDigest.getInstance("SHA-256").digest(data)); } catch (Exception e) { throw new IllegalStateException(e); } }
  private static String toHex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format("%02x", b)); return value.toString(); }

  private static Path configuredGradleHome(Path temp) throws IOException {
    String configured = System.getenv("SC967_GRADLE_USER_HOME");
    if (configured == null || configured.isBlank()) throw new IOException("GRADLE_CACHE_NOT_CONFIGURED");
    Path source = Path.of(configured).toAbsolutePath().normalize();
    if (!Files.isDirectory(source)) throw new IOException("GRADLE_CACHE_NOT_CONFIGURED");
    Path target = temp.resolve("gradle-user-home");
    try (var stream = Files.walk(source)) {
      stream.forEach(path -> {
        try { Path relative = source.relativize(path); Path destination = target.resolve(relative); if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
      });
    } catch (java.io.UncheckedIOException e) { throw e.getCause(); }
    return target;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    IOException[] failure = new IOException[1];
    try (var stream = Files.walk(root)) { stream.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException e) { failure[0] = e; } }); }
    if (failure[0] != null) throw failure[0];
  }
}
