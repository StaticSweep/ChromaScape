package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.routine.source.SourceManifestContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
  // A cold native Windows candidate build compiles the full Micro6 client before running the
  // hardened checks. On the acceptance host this legitimately exceeds ten minutes; retain a
  // bounded timeout while allowing the complete fixed gate set to finish.
  private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(20);
  private static final int MAX_OUTPUT_BYTES = 256 * 1024;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private record GradleCache(Path userHome, Path readOnlyDependencies) {}

  /**
   * commands is retained for source compatibility but must always be empty; the worker owns gates.
   */
  public record BuildRequest(
      Path micro6Root,
      String baseCommit,
      String unifiedDiff,
      Set<String> allowlistedPaths,
      List<String> commands,
      String sourceSnapshotId,
      String sourceFile,
      int startLine,
      int endLine,
      String preimageHash,
      String failureManifestHash,
      String proposalHash,
      String approvalHash,
      String patchHash,
      String sourceManifestHash,
      String sourceManifestJson,
      String modelHash,
      String expectedJarName) {}

  public record BuildResult(
      String status,
      String baseCommit,
      List<String> changedFiles,
      String failureCode,
      Path outputDirectory,
      String jarName,
      byte[] jarBytes,
      String sbomName,
      byte[] sbomBytes,
      byte[] testReportBytes,
      byte[] certificationBytes) {
    public BuildResult(
        String status,
        String baseCommit,
        List<String> changedFiles,
        String failureCode,
        Path outputDirectory) {
      this(
          status,
          baseCommit,
          changedFiles,
          failureCode,
          outputDirectory,
          null,
          null,
          null,
          null,
          null,
          null);
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
      Path source = request.micro6Root().toAbsolutePath().normalize();
      if (!Files.isDirectory(source)) throw new IOException("ISOLATED_SOURCE_NOT_FOUND");
      Path captureContracts =
          resolveCaptureContracts(source, System.getenv("SC968_CAPTURE_CONTRACTS_DIR"));
      String commonGitDirectory =
          capture(
                  List.of(
                      "git",
                      "-C",
                      source.toString(),
                      "rev-parse",
                      "--path-format=absolute",
                      "--git-common-dir"),
                  temp)
              .trim();
      Path repository =
          commonGitDirectory.isBlank()
              ? null
              : Path.of(commonGitDirectory).toAbsolutePath().normalize();
      if (repository == null || !Files.isDirectory(repository))
        throw new IOException("ISOLATED_SOURCE_REPOSITORY_NOT_FOUND");
      // Micro6 contains tracked paths that exceed Win32's legacy 260-character checkout limit once
      // nested under the isolated temp directory. Enable Git's supported long-path handling for
      // this private clone only; the source repository and global Git configuration stay intact.
      // Resolve the source worktree's common repository before changing cwd to the temp sandbox.
      // This avoids treating a live linked worktree as the clone transport while the acceptance
      // client and Gradle gates still have files open there.
      runCommand(
          List.of(
              "git",
              "clone",
              "--no-checkout",
              "--config",
              "core.longpaths=true",
              "--no-hardlinks",
              repository.toString(),
              repo.toString()),
          temp,
          "ISOLATED_CLONE_FAILED");
      checkoutPinnedCommit(repository, repo, request.baseCommit(), temp);
      if (!capture(List.of("git", "-C", repo.toString(), "status", "--porcelain"), temp)
          .isBlank()) {
        return failed(request, changed, "ISOLATED_TREE_DIRTY");
      }
      verifyCompleteSourceManifest(repo, request);
      if (!verifySourcePreimage(repo, request)) throw new IOException("SOURCE_PREIMAGE_MISMATCH");
      Files.writeString(patch, request.unifiedDiff(), StandardCharsets.UTF_8);
      runCommand(
          List.of("git", "-C", repo.toString(), "apply", "--check", patch.toString()),
          temp,
          "PATCH_CHECK_FAILED");
      runCommand(
          List.of("git", "-C", repo.toString(), "apply", patch.toString()),
          temp,
          "PATCH_APPLY_FAILED");
      runCommand(
          List.of("git", "-C", repo.toString(), "diff", "--check"),
          temp,
          "PATCH_WHITESPACE_FAILED");

      changed = changedFiles(repo);
      Set<String> allowlist =
          request.allowlistedPaths() == null ? Set.of() : request.allowlistedPaths();
      if (!allowlist.equals(new HashSet<>(changed)))
        return failed(request, changed, "PATCH_ALLOWLIST_VIOLATION");

      GradleCache gradleCache = configuredGradleCache(temp, repo);
      // Invoke the native Gradle wrapper directly: gradlew.bat on Windows, ./gradlew on Unix.
      // Never route Micro6 work through bash.
      List<String> gradle = new ArrayList<>();
      gradle.add(isWindows() ? "gradlew.bat" : "./gradlew");
      gradle.addAll(
          List.of(
              "--offline",
              "--no-daemon",
              "--no-watch-fs",
              "--max-workers=1",
              "-PaiRoutineCaptureContractsDir=" + captureContracts,
              ":client:compileJava",
              ":client:sc967CandidateCheckstyleMain",
              ":client:sc967CandidateCheckstyleTest",
              ":client:sc967CandidateTest",
              ":client:shadowJar",
              ":client:generateRuntimeSbom"));
      runCommand(
          gradle,
          repo,
          "BUILD_FAILED",
          Map.of(
              "GRADLE_USER_HOME", gradleCache.userHome().toString(),
              "GRADLE_RO_DEP_CACHE", gradleCache.readOnlyDependencies().toString()));

      Path jar = findExpectedShadowJar(repo, request.expectedJarName());
      byte[] jarBytes = Files.readAllBytes(jar);
      byte[] sbomBytes = authoritativeSbom(repo);
      byte[] testReportBytes = deterministicTestReport(repo);
      byte[] certificationBytes = certification(request, jarBytes, sbomBytes, testReportBytes);
      result =
          new BuildResult(
              "BUILT",
              request.baseCommit(),
              List.copyOf(changed),
              null,
              null,
              jar.getFileName().toString(),
              jarBytes,
              "runtime-sbom.cdx.json",
              sbomBytes,
              testReportBytes,
              certificationBytes);
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

  /** Retrieve and verify the immutable approved commit even when no branch references it. */
  static void checkoutPinnedCommit(Path repository, Path repo, String baseCommit, Path temp)
      throws IOException, InterruptedException {
    if (baseCommit == null || !baseCommit.matches("[0-9a-f]{40}"))
      throw new IOException("BASE_COMMIT_INVALID");
    // A linked detached worktree's commit need not be reachable from any cloned branch.
    // Fetch exactly the approved object from its common local repository, not a moving ref.
    runCommand(
        List.of(
            "git", "-C", repo.toString(), "fetch", "--no-tags", repository.toString(), baseCommit),
        temp,
        "ISOLATED_BASE_FETCH_FAILED");
    runCommand(
        List.of("git", "-C", repo.toString(), "checkout", "--detach", baseCommit),
        temp,
        "ISOLATED_CHECKOUT_FAILED");
    if (!baseCommit.equals(
        capture(List.of("git", "-C", repo.toString(), "rev-parse", "HEAD"), temp).trim())) {
      throw new IOException("ISOLATED_BASE_COMMIT_MISMATCH");
    }
  }

  /** Explicit acceptance configuration must not silently fall back to a different contract tree. */
  static Path resolveCaptureContracts(Path source, String configured) throws IOException {
    Path contracts;
    if (configured == null || configured.isBlank()) {
      contracts = source.resolveSibling("contracts").resolve("java");
    } else {
      try {
        contracts = Path.of(configured);
      } catch (java.nio.file.InvalidPathException invalid) {
        throw new IOException("CAPTURE_CONTRACTS_PATH_INVALID", invalid);
      }
      if (!contracts.isAbsolute()) throw new IOException("CAPTURE_CONTRACTS_PATH_NOT_ABSOLUTE");
    }
    contracts = contracts.toAbsolutePath().normalize();
    if (!Files.isDirectory(contracts)) throw new IOException("CAPTURE_CONTRACTS_NOT_FOUND");
    return contracts;
  }

  private static BuildResult failed(BuildRequest request, List<String> changed, String code) {
    return new BuildResult(
        "FAILED", request == null ? null : request.baseCommit(), List.copyOf(changed), code, null);
  }

  private static void validateRequest(BuildRequest request) throws IOException {
    if (request == null
        || request.micro6Root() == null
        || request.baseCommit() == null
        || request.unifiedDiff() == null) throw new IOException("ISOLATED_CLONE_FAILED");
    if (request.commands() != null && !request.commands().isEmpty())
      throw new IOException("CALLER_COMMANDS_FORBIDDEN");
    if (request.sourceManifestJson() == null || request.sourceManifestJson().isBlank())
      throw new IOException("SOURCE_MANIFEST_REQUIRED");
    if (request.expectedJarName() == null || !request.expectedJarName().matches("[^/\\\\]+\\.jar"))
      throw new IOException("EXPECTED_JAR_NAME_REQUIRED");
    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome != null
        && !javaHome.isBlank()
        && !Files.isDirectory(Path.of(javaHome).toAbsolutePath().normalize()))
      throw new IOException("JAVA_HOME_INVALID");
  }

  private static List<String> changedFiles(Path repo) throws IOException, InterruptedException {
    Set<String> files = new HashSet<>();
    addLines(files, capture(List.of("git", "-C", repo.toString(), "diff", "--name-only"), repo));
    addLines(
        files,
        capture(
            List.of("git", "-C", repo.toString(), "ls-files", "--others", "--exclude-standard"),
            repo));
    return files.stream().sorted().toList();
  }

  private static void addLines(Set<String> target, String output) {
    for (String line : output.split("\\R")) if (!line.isBlank()) target.add(line.trim());
  }

  private static void runCommand(List<String> command, Path cwd, String code)
      throws IOException, InterruptedException {
    runCommand(command, cwd, code, Map.of());
  }

  private static void runCommand(
      List<String> command, Path cwd, String code, Map<String, String> extraEnvironment)
      throws IOException, InterruptedException {
    CappedOutput output = execute(command, cwd, extraEnvironment, code);
    if (output.limitExceeded()) {
      throw new IOException(code + ";OUTPUT_LIMIT_EXCEEDED");
    }
    if (output.exitValue() != 0) {
      throw new IOException(code + ";EXIT=" + output.exitValue() + ";OUTPUT=" + output.tail());
    }
  }

  private static String capture(List<String> command, Path cwd)
      throws IOException, InterruptedException {
    CappedOutput output = execute(command, cwd, Map.of(), "COMMAND_FAILED");
    if (output.limitExceeded()) {
      throw new IOException("OUTPUT_LIMIT_EXCEEDED");
    }
    if (output.exitValue() != 0) {
      throw new IOException("COMMAND_FAILED;OUTPUT=" + output.tail());
    }
    return output.text();
  }

  /**
   * Runs a command with a bounded streaming collector. The collector reads the merged output
   * through a capped buffer; once output exceeds 256 KiB the full process tree is terminated and
   * the call fails with {@code OUTPUT_LIMIT_EXCEEDED}. An unlimited log is never read into memory,
   * and timeout/interruption use the same descendant-tree termination as the output cap.
   */
  private static CappedOutput execute(
      List<String> command, Path cwd, Map<String, String> extraEnvironment, String code)
      throws IOException, InterruptedException {
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
      configureEnvironment(builder, extraEnvironment);
      builder.redirectErrorStream(true);
      process = builder.start();
      CappedOutput output = CappedOutput.start(process);
      long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
      boolean finished = false;
      while (System.nanoTime() < deadline) {
        if (output.limitExceeded()) {
          // Stop the full process tree as soon as output exceeds the cap; never read an unlimited
          // log into memory.
          destroyTree(process);
          throw new IOException(code + ";OUTPUT_LIMIT_EXCEEDED");
        }
        if (!process.isAlive()) {
          finished = true;
          break;
        }
        Thread.sleep(50);
      }
      if (!finished) {
        destroyTree(process);
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        throw new IOException(code + ";TIMEOUT");
      }
      output.await();
      return output.finish(process.exitValue());
    } catch (InterruptedException interrupted) {
      if (process != null) destroyTree(process);
      Thread.currentThread().interrupt();
      throw interrupted;
    } finally {
      if (process != null) process.destroy();
    }
  }

  /** Bounded collector over a process's merged output; keeps only the last 256 KiB. */
  private static final class CappedOutput {
    private static final int CAPACITY = MAX_OUTPUT_BYTES;
    private final byte[] buffer = new byte[CAPACITY];
    private int write;
    private boolean full;
    private boolean limitExceeded;
    private int exitValue;
    private volatile IOException failure;
    private final Thread collector;

    private CappedOutput(Process process) {
      collector =
          new Thread(
              () -> {
                try (var in = process.getInputStream()) {
                  byte[] chunk = new byte[8192];
                  int read;
                  while ((read = in.read(chunk)) >= 0) {
                    if (read > 0) {
                      synchronized (this) {
                        // Ring buffer: overwrite the oldest bytes once full, so the newest CAPACITY
                        // bytes
                        // are always retained.
                        for (int i = 0; i < read; i++) {
                          buffer[write] = chunk[i];
                          write = (write + 1) % CAPACITY;
                          if (write == 0) full = true;
                        }
                        if (full) limitExceeded = true;
                      }
                    }
                  }
                } catch (IOException e) {
                  failure = e;
                }
              },
              "sc967-output-collector");
      collector.setDaemon(true);
      collector.start();
    }

    static CappedOutput start(Process process) {
      return new CappedOutput(process);
    }

    void await() throws IOException {
      try {
        collector.join(COMMAND_TIMEOUT.toMillis() + 30_000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (failure != null) throw failure;
    }

    CappedOutput finish(int exitValue) {
      this.exitValue = exitValue;
      return this;
    }

    int exitValue() {
      return exitValue;
    }

    boolean limitExceeded() {
      return limitExceeded;
    }

    /** The final {@code MAX_OUTPUT_BYTES} bytes of the merged output, as UTF-8 text. */
    String tail() {
      return new String(snapshot(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    /** Raw bounded output for commands whose line structure is part of the result. */
    String text() {
      return new String(snapshot(), StandardCharsets.UTF_8);
    }

    private byte[] snapshot() {
      synchronized (this) {
        byte[] tail = new byte[full ? CAPACITY : write];
        for (int i = 0; i < tail.length; i++) {
          tail[i] = buffer[(write - tail.length + i + CAPACITY) % CAPACITY];
        }
        return tail;
      }
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
    if (isWindows()) {
      for (String name :
          List.of(
              "HOME",
              "USERPROFILE",
              "HOMEDRIVE",
              "HOMEPATH",
              "APPDATA",
              "LOCALAPPDATA",
              "SystemRoot",
              "SystemDrive",
              "WINDIR",
              "COMSPEC",
              "PATHEXT")) {
        copyEnvironmentIgnoringCase(environment, name);
      }
    }
    environment.put("LC_ALL", "C.UTF-8");
    environment.put("LANG", "C.UTF-8");
    environment.putAll(extra);
  }

  private static void copyEnvironment(Map<String, String> destination, String name) {
    String value = System.getenv(name);
    if (value != null && !value.isBlank()) destination.put(name, value);
  }

  private static void copyEnvironmentIgnoringCase(Map<String, String> destination, String name) {
    System.getenv().entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .findFirst()
        .filter(entry -> !entry.getValue().isBlank())
        .ifPresent(entry -> destination.put(entry.getKey(), entry.getValue()));
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name");
    return os != null && os.toLowerCase(java.util.Locale.ROOT).contains("win");
  }

  /** Terminates the process and its entire descendant tree. */
  private static void destroyTree(Process process) {
    try {
      process
          .toHandle()
          .descendants()
          .forEach(
              descendant -> {
                try {
                  descendant.destroy();
                } catch (Exception ignored) {
                }
              });
    } catch (Exception ignored) {
      // best-effort; the parent is destroyed below regardless
    }
    process.destroy();
    try {
      if (process.isAlive()) {
        Thread.sleep(200);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      process
          .toHandle()
          .descendants()
          .forEach(
              descendant -> {
                try {
                  descendant.destroyForcibly();
                } catch (Exception ignored) {
                }
              });
    } catch (Exception ignored) {
    }
  }

  private static Path findExpectedShadowJar(Path repo, String expectedName) throws IOException {
    Path libs = repo.resolve("runelite-client/build/libs");
    if (!Files.isDirectory(libs)) throw new IOException("JAR_NOT_FOUND");
    try (var stream = Files.list(libs)) {
      List<Path> matches =
          stream.filter(path -> expectedName.equals(path.getFileName().toString())).toList();
      if (matches.size() != 1)
        throw new IOException(matches.isEmpty() ? "JAR_NOT_FOUND" : "JAR_AMBIGUOUS");
      return matches.get(0);
    }
  }

  private static byte[] authoritativeSbom(Path repo) throws IOException {
    Path report = repo.resolve("runelite-client/build/reports/sc967/runtime-sbom.cdx.json");
    if (!Files.isRegularFile(report) || Files.size(report) == 0)
      throw new IOException("SBOM_NOT_FOUND");
    JsonNode bom = CanonicalJsonHasher.parse(Files.readAllBytes(report));
    if (!"CycloneDX".equals(bom.path("bomFormat").asText())
        || !"1.5".equals(bom.path("specVersion").asText())
        || !bom.path("components").isArray()) throw new IOException("SBOM_INVALID");
    Set<String> purls = new HashSet<>();
    for (JsonNode component : bom.path("components")) {
      String group = component.path("group").asText();
      String name = component.path("name").asText();
      String version = component.path("version").asText();
      String purl = component.path("purl").asText();
      String hash = component.path("sha256").asText();
      if (group.isBlank()
          || name.isBlank()
          || version.isBlank()
          || !isValidSbomPurl(purl)
          || !purls.add(purl)
          || !hash.matches("sha256:[0-9a-f]{64}")) throw new IOException("SBOM_COMPONENT_INVALID");
    }
    return CanonicalJsonHasher.canonical(bom).getBytes(StandardCharsets.UTF_8);
  }

  static boolean isValidSbomPurl(String purl) {
    return purl.matches("pkg:maven/[^/]+/[^@]+@[^?]+(?:\\?classifier=[A-Za-z0-9._~-]+)?");
  }

  private static byte[] deterministicTestReport(Path repo) throws Exception {
    Path reports = repo.resolve("runelite-client/build/test-results");
    Map<String, List<Map<String, Object>>> grouped = new TreeMap<>();
    if (Files.isDirectory(reports)) {
      try (var stream = Files.walk(reports)) {
        for (Path file :
            stream.filter(path -> path.toString().endsWith(".xml")).sorted().toList()) {
          // Harden XML parsing: DOCTYPE, external entities, DTD loading, and external schema access
          // are all disabled so a tampered test report cannot read local files or reach the
          // network.
          var factory = DocumentBuilderFactory.newInstance();
          factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
          factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
          factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
          factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
          factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
          factory.setXIncludeAware(false);
          factory.setExpandEntityReferences(false);
          var document = factory.newDocumentBuilder().parse(file.toFile());
          var cases = document.getElementsByTagName("testcase");
          for (int i = 0; i < cases.getLength(); i++) {
            Element test = (Element) cases.item(i);
            String suite =
                test.getParentNode() instanceof Element parent ? parent.getAttribute("name") : "";
            String name = test.getAttribute("name");
            String className = test.getAttribute("classname");
            String outcome =
                test.getElementsByTagName("failure").getLength() > 0
                        || test.getElementsByTagName("error").getLength() > 0
                    ? "FAIL"
                    : test.getElementsByTagName("skipped").getLength() > 0 ? "SKIP" : "PASS";
            grouped
                .computeIfAbsent(suite, ignored -> new ArrayList<>())
                .add(Map.of("class", className, "name", name, "outcome", outcome));
          }
        }
      }
    }
    List<Map<String, Object>> suites = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
      entry
          .getValue()
          .sort(
              Comparator.comparing(
                  value -> String.valueOf(value.get("class")) + "\u0000" + value.get("name")));
      for (Map<String, Object> test : entry.getValue()) {
        names.add(String.valueOf(test.get("name")));
        if (!"PASS".equals(test.get("outcome"))
            && List.of(
                    "nullFishIsRejected",
                    "blankFishIsRejected",
                    "unknownFishIsRejected",
                    "canonicalFishRemainsSupported")
                .contains(test.get("name")))
          throw new IOException("REGRESSION_TEST_FAILED_" + test.get("name"));
      }
      suites.add(Map.of("name", entry.getKey(), "tests", entry.getValue()));
    }
    for (String expected :
        List.of(
            "nullFishIsRejected",
            "blankFishIsRejected",
            "unknownFishIsRejected",
            "canonicalFishRemainsSupported"))
      if (!names.contains(expected)) throw new IOException("REGRESSION_TEST_MISSING_" + expected);
    return MAPPER.writeValueAsBytes(Map.of("schema", "sc967-test-result/v1", "suites", suites));
  }

  private static byte[] certification(BuildRequest request, byte[] jar, byte[] sbom, byte[] report)
      throws IOException {
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

  static void verifyCompleteSourceManifest(Path repo, BuildRequest request) throws Exception {
    JsonNode manifest =
        CanonicalJsonHasher.parse(request.sourceManifestJson().getBytes(StandardCharsets.UTF_8));
    // Signature verification occurs when the proposal binds this persisted manifest. Recheck
    // the same canonical content identity here; the signature is not part of that checksum.
    if (!SourceManifestContract.SCHEMA.equals(manifest.path("schema").asText())
        || !"AVAILABLE".equals(manifest.path("status").asText())
        || !request
            .sourceManifestHash()
            .equals(hash(SourceManifestContract.canonicalHashBytes(manifest)))
        || !request
            .sourceManifestHash()
            .equals(SourceManifestContract.declaredManifestHash(manifest))
        || !request
            .sourceSnapshotId()
            .equals(SourceManifestContract.snapshotForHash(request.sourceManifestHash()))
        || !request.sourceSnapshotId().equals(manifest.path("source_snapshot_id").asText())
        || !request.baseCommit().equals(manifest.path("repository").path("commit_id").asText())
        || !request.baseCommit().equals(manifest.path("build").path("build_id").asText())
        || !manifest.path("repository").path("clean").isBoolean()
        || !manifest.path("repository").path("clean").asBoolean())
      throw new IOException("SOURCE_MANIFEST_MISMATCH");
    if (!manifest.path("files").isArray()
        || manifest.path("files").isEmpty()
        || manifest.path("files").size() > SourceManifestContract.MAX_FILES)
      throw new IOException("SOURCE_MANIFEST_INVALID");
    Path root = repo.toRealPath();
    boolean selected = false;
    for (JsonNode entry : manifest.path("files")) {
      String relative = entry.path("relative_path").asText();
      String expectedHash = entry.path("content_hash").asText();
      long expectedSize = entry.path("size_bytes").asLong(-1);
      if (!SourceManifestContract.isSafeRelativePath(relative))
        throw new IOException("SOURCE_FILE_PATH_INVALID");
      Path file = root.resolve(relative).normalize();
      if (!file.startsWith(root)
          || !Files.isRegularFile(file)
          || !file.toRealPath().startsWith(root))
        throw new IOException("SOURCE_FILE_MISSING_" + relative);
      byte[] normalized = normalize(Files.readAllBytes(file));
      if (!expectedHash.equals(hash(normalized)) || expectedSize != normalized.length)
        throw new IOException("SOURCE_MANIFEST_FILE_MISMATCH_" + relative);
      if (relative.equals(request.sourceFile())) selected = true;
    }
    if (!selected) throw new IOException("SOURCE_SELECTED_FILE_MISMATCH");
  }

  static boolean verifySourcePreimage(Path repo, BuildRequest request) throws Exception {
    if (request.sourceFile() == null
        || request.preimageHash() == null
        || request.startLine() <= 0
        || request.endLine() < request.startLine()) return false;
    Path root = repo.toRealPath();
    if (!SourceManifestContract.isSafeRelativePath(request.sourceFile())) return false;
    Path file = root.resolve(request.sourceFile()).normalize();
    if (!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root))
      return false;
    JsonNode manifest =
        CanonicalJsonHasher.parse(request.sourceManifestJson().getBytes(StandardCharsets.UTF_8));
    List<String> fileIds = new ArrayList<>();
    for (JsonNode entry : manifest.path("files")) {
      if (request.sourceFile().equals(entry.path("relative_path").asText()))
        fileIds.add(entry.path("file_id").asText());
    }
    if (fileIds.size() != 1 || fileIds.get(0).isBlank()) return false;
    List<JsonNode> symbols = new ArrayList<>();
    for (JsonNode symbol : manifest.path("symbols")) {
      if (fileIds.get(0).equals(symbol.path("file_id").asText())
          && request.startLine() == symbol.path("start_line").asInt(-1)
          && request.endLine() == symbol.path("end_line").asInt(-1)) symbols.add(symbol);
    }
    if (symbols.size() != 1) return false;
    JsonNode symbol = symbols.get(0);
    if (!symbol.path("start_byte").isIntegralNumber()
        || !symbol.path("end_byte").isIntegralNumber()
        || !symbol.path("start_byte").canConvertToInt()
        || !symbol.path("end_byte").canConvertToInt()) return false;
    byte[] bytes = normalize(Files.readAllBytes(file));
    int start = symbol.path("start_byte").asInt();
    int end = symbol.path("end_byte").asInt();
    if (start < 0
        || end <= start
        || end > bytes.length
        || (bytes[start] & 0xc0) == 0x80
        || (end < bytes.length && (bytes[end] & 0xc0) == 0x80)) return false;
    // The producer indexes UTF-8 bytes, not reconstructed whole lines (which include indentation).
    return request.preimageHash().equals(hash(java.util.Arrays.copyOfRange(bytes, start, end)));
  }

  private static byte[] normalize(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8)
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String hash(byte[] data) {
    try {
      return "sha256:" + toHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder value = new StringBuilder();
    for (byte b : bytes) value.append(String.format("%02x", b));
    return value.toString();
  }

  private static GradleCache configuredGradleCache(Path temp, Path repo) throws IOException {
    String configured = System.getenv("SC967_GRADLE_USER_HOME");
    if (configured == null || configured.isBlank())
      throw new IOException("GRADLE_CACHE_NOT_CONFIGURED");
    Path source = Path.of(configured).toAbsolutePath().normalize();
    if (!Files.isDirectory(source)) throw new IOException("GRADLE_CACHE_NOT_CONFIGURED");
    Path readOnlyDependencies = source.resolve("caches").normalize();
    if (!Files.isDirectory(readOnlyDependencies.resolve("modules-2"))) {
      throw new IOException("GRADLE_DEPENDENCY_CACHE_NOT_FOUND");
    }
    Path target = temp.resolve("gradle-user-home");
    Files.createDirectories(target);

    Path wrapperProperties = repo.resolve("gradle/wrapper/gradle-wrapper.properties");
    String distributionLine =
        Files.readAllLines(wrapperProperties, StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("distributionUrl="))
            .findFirst()
            .orElseThrow(() -> new IOException("GRADLE_DISTRIBUTION_NOT_CONFIGURED"));
    String distribution = distributionLine.substring(distributionLine.lastIndexOf('/') + 1);
    if (!distribution.endsWith(".zip")) throw new IOException("GRADLE_DISTRIBUTION_NOT_CONFIGURED");
    distribution = distribution.substring(0, distribution.length() - 4);
    if (!distribution.matches("[A-Za-z0-9._-]+")) {
      throw new IOException("GRADLE_DISTRIBUTION_NOT_CONFIGURED");
    }
    Path wrapperSource = source.resolve("wrapper/dists").resolve(distribution).normalize();
    if (!wrapperSource.startsWith(source) || !Files.isDirectory(wrapperSource)) {
      throw new IOException("GRADLE_DISTRIBUTION_NOT_FOUND");
    }
    Path wrapperTarget = target.resolve("wrapper/dists").resolve(distribution);
    copyGradleTree(wrapperSource, wrapperTarget);
    return new GradleCache(target, readOnlyDependencies);
  }

  private static void copyGradleTree(Path source, Path target) throws IOException {
    try (var stream = Files.walk(source)) {
      stream.forEach(
          path -> {
            try {
              Path relative = source.relativize(path);
              if (!isGradleRuntimePath(relative)) return;
              Path destination = target.resolve(relative);
              if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
              } else if (!isGradleCoordinationFile(relative)) {
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
              }
            } catch (IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          });
    } catch (java.io.UncheckedIOException e) {
      throw e.getCause();
    }
  }

  static boolean isGradleCoordinationFile(Path relative) {
    Path name = relative == null ? null : relative.getFileName();
    if (name == null) return false;
    String value = name.toString().toLowerCase(java.util.Locale.ROOT);
    return value.endsWith(".lock") || value.endsWith(".lck");
  }

  static boolean isGradleRuntimePath(Path relative) {
    if (relative == null || relative.getNameCount() <= 2) return true;
    String distributionChild = relative.getName(2).toString();
    return distributionChild.equals("bin") || distributionChild.equals("lib");
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    IOException[] failure = new IOException[1];
    try (var stream = Files.walk(root)) {
      stream
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  DosFileAttributeView dos =
                      Files.getFileAttributeView(
                          path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                  if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  failure[0] = e;
                }
              });
    }
    if (failure[0] != null) throw failure[0];
  }
}
