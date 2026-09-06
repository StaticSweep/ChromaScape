package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedPatchWorkerTest {
  @TempDir Path temporary;

  private static final String BASE = "a".repeat(40);
  private static final String SELECTED = "runelite-client/src/main/java/Fishing.java";

  private com.fasterxml.jackson.databind.node.ObjectNode sourceManifest() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var manifest = mapper.createObjectNode();
    manifest.put("schema", "task-source-manifest/v1");
    manifest.put("status", "AVAILABLE");
    manifest.putObject("repository").put("commit_id", BASE).put("clean", true);
    manifest.putObject("build").put("build_id", BASE);
    // Test-only signature metadata models an already verified stored manifest envelope.
    manifest.putObject("signature").put("key_id", "unit-test").put("value", "unit-test-only");
    var files = manifest.putArray("files");
    for (String relative : java.util.List.of(SELECTED, "runelite-api/src/main/java/Fish.java")) {
      Path file = temporary.resolve(relative);
      Files.createDirectories(file.getParent());
      Files.writeString(file, "class Fish {}\r\n");
      files.addObject().put("relative_path", relative).put("file_id", relative.equals(SELECTED) ? "selected" : "other").put("size_bytes", 14)
          .put("content_hash", digest("class Fish {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    rehash(manifest);
    return manifest;
  }

  private static String digest(byte[] value) throws Exception {
    return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static void rehash(com.fasterxml.jackson.databind.node.ObjectNode manifest) throws Exception {
    String hash = digest(com.chromascape.web.aiplayer.routine.source.SourceManifestContract.canonicalHashBytes(manifest));
    manifest.put("source_manifest_hash", hash);
    manifest.put("source_snapshot_id", com.chromascape.web.aiplayer.routine.source.SourceManifestContract.snapshotForHash(hash));
  }

  private IsolatedPatchWorker.BuildRequest manifestRequest(com.fasterxml.jackson.databind.JsonNode manifest, String selected) {
    return manifestRequest(manifest, selected, "");
  }

  private IsolatedPatchWorker.BuildRequest manifestRequest(com.fasterxml.jackson.databind.JsonNode manifest, String selected, String preimage) {
    return new IsolatedPatchWorker.BuildRequest(temporary, BASE, "", java.util.Set.of(), java.util.List.of(),
        manifest.path("source_snapshot_id").asText(), selected, 1, 1, preimage, "", "", "", "",
        manifest.path("source_manifest_hash").asText(), manifest.toString(), "", "candidate.jar");
  }

  @Test
  void validatesExactUtf8BytePreimageInsteadOfWholeLines() throws Exception {
    var manifest = sourceManifest();
    Files.writeString(temporary.resolve(SELECTED), "/*\u03c0*/ Fish\r\n");
    manifest.putArray("symbols").addObject().put("file_id", "selected")
        .put("start_line", 1).put("end_line", 1).put("start_byte", 7).put("end_byte", 11);
    String preimage = digest("Fish".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertTrue(IsolatedPatchWorker.verifySourcePreimage(temporary, manifestRequest(manifest, SELECTED, preimage)));
    assertFalse(IsolatedPatchWorker.verifySourcePreimage(temporary, manifestRequest(manifest, SELECTED, digest("wrong".getBytes()))));
  }

  @Test
  void rejectsMissingAmbiguousAndInvalidByteRanges() throws Exception {
    var manifest = sourceManifest();
    String preimage = digest("Fish".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertFalse(IsolatedPatchWorker.verifySourcePreimage(temporary, manifestRequest(manifest, SELECTED, preimage)));
    var symbols = manifest.putArray("symbols");
    var symbol = symbols.addObject().put("file_id", "selected").put("start_line", 1).put("end_line", 1)
        .put("start_byte", 6).put("end_byte", 10);
    assertTrue(IsolatedPatchWorker.verifySourcePreimage(temporary, manifestRequest(manifest, SELECTED, preimage)));
    symbols.add(symbol.deepCopy());
    assertFalse(IsolatedPatchWorker.verifySourcePreimage(temporary, manifestRequest(manifest, SELECTED, preimage)));
    symbols.remove(1);
    for (int end : new int[]{-1, 6, 999}) {
      symbol.put("end_byte", end);
      assertFalse(IsolatedPatchWorker.verifySourcePreimage(temporary, manifestRequest(manifest, SELECTED, preimage)));
    }
    Files.writeString(temporary.resolve(SELECTED), "/*\u03c0*/ Fish\n");
    symbol.put("start_byte", 3).put("end_byte", 11);
    assertFalse(IsolatedPatchWorker.verifySourcePreimage(temporary, manifestRequest(manifest, SELECTED, preimage)));
  }

  @Test
  void verifiesCurrentCanonicalManifestAcrossRepositoryRelativeModules() throws Exception {
    var manifest = sourceManifest();
    IsolatedPatchWorker.verifyCompleteSourceManifest(temporary, manifestRequest(manifest, SELECTED));
  }

  @Test
  void rejectsChangedCanonicalContentAndDeclaredIdentity() throws Exception {
    var manifest = sourceManifest();
    manifest.put("task_key", "TAMPERED");
    assertThrows(IOException.class, () -> IsolatedPatchWorker.verifyCompleteSourceManifest(temporary, manifestRequest(manifest, SELECTED)));
    var changedIdentity = sourceManifest();
    changedIdentity.put("source_snapshot_id", "srcsnap_" + "0".repeat(24));
    assertThrows(IOException.class, () -> IsolatedPatchWorker.verifyCompleteSourceManifest(temporary, manifestRequest(changedIdentity, SELECTED)));
  }

  @Test
  void rejectsWrongCommitBuildDirtyStateAndLegacySchemaEvenWhenRehashed() throws Exception {
    for (String mismatch : java.util.List.of("commit", "build", "dirty", "schema")) {
      var manifest = sourceManifest();
      switch (mismatch) {
        case "commit" -> ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.get("repository")).put("commit_id", "b".repeat(40));
        case "build" -> ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.get("build")).put("build_id", "b".repeat(40));
        case "dirty" -> ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.get("repository")).put("clean", false);
        case "schema" -> manifest.put("schema", "micro6-source-manifest/v1");
      }
      rehash(manifest);
      assertThrows(IOException.class, () -> IsolatedPatchWorker.verifyCompleteSourceManifest(temporary, manifestRequest(manifest, SELECTED)));
    }
  }

  @Test
  void rejectsTamperedCheckoutMissingSelectionAndPathTraversal() throws Exception {
    var manifest = sourceManifest();
    Files.writeString(temporary.resolve(SELECTED), "tampered");
    assertThrows(IOException.class, () -> IsolatedPatchWorker.verifyCompleteSourceManifest(temporary, manifestRequest(manifest, SELECTED)));
    var clean = sourceManifest();
    assertEquals("SOURCE_SELECTED_FILE_MISMATCH", assertThrows(IOException.class,
        () -> IsolatedPatchWorker.verifyCompleteSourceManifest(temporary, manifestRequest(clean, "missing.java"))).getMessage());
    ((com.fasterxml.jackson.databind.node.ObjectNode)clean.path("files").get(0)).put("relative_path", "../outside.java");
    rehash(clean);
    assertEquals("SOURCE_FILE_PATH_INVALID", assertThrows(IOException.class,
        () -> IsolatedPatchWorker.verifyCompleteSourceManifest(temporary, manifestRequest(clean, SELECTED))).getMessage());
  }

  private String git(Path directory, String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>(java.util.List.of("git", "-C", directory.toString()));
    command.addAll(java.util.List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), String.join(" ", arguments) + ": " + output);
    return output.trim();
  }

  @Test
  void fetchesExactDetachedWorktreeCommitAbsentFromClonedBranches() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("source"));
    git(source, "init", "--initial-branch=main");
    Files.writeString(source.resolve("file.txt"), "base");
    git(source, "add", "file.txt");
    git(source, "-c", "user.name=Native Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "-m", "base");
    Path detached = temporary.resolve("detached");
    git(source, "worktree", "add", "--detach", detached.toString());
    Files.writeString(detached.resolve("file.txt"), "approved detached content");
    git(detached, "add", "file.txt");
    git(detached, "-c", "user.name=Native Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "-m", "detached");
    String approved = git(detached, "rev-parse", "HEAD");
    Path clone = temporary.resolve("clone");
    git(temporary, "clone", "--no-local", "--no-checkout", source.toString(), clone.toString());
    Process missing = new ProcessBuilder("git", "-C", clone.toString(), "cat-file", "-e", approved + "^{commit}")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    assertTrue(missing.waitFor() != 0, "Regression setup must omit the detached commit");
    IsolatedPatchWorker.checkoutPinnedCommit(source.resolve(".git"), clone, approved, temporary);
    assertEquals(approved, git(clone, "rev-parse", "HEAD"));
    assertEquals("approved detached content", Files.readString(clone.resolve("file.txt")));
    assertEquals("", git(clone, "status", "--porcelain"));
    assertEquals("main", git(source, "branch", "--show-current"));
  }

  @Test
  void rejectsMovingRefsInsteadOfFetchingUnapprovedSource() {
    assertEquals("BASE_COMMIT_INVALID", assertThrows(IOException.class,
        () -> IsolatedPatchWorker.checkoutPinnedCommit(temporary, temporary, "main", temporary)).getMessage());
  }

  @Test
  void resolvesExplicitContractsOutsideTheMicro6SiblingLayout() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("run/rt"));
    Path contracts = Files.createDirectories(temporary.resolve("separate-contracts/java"));
    assertEquals(contracts, IsolatedPatchWorker.resolveCaptureContracts(source, contracts.toString()));
  }

  @Test
  void explicitContractsTakePrecedenceOverAnExistingSibling() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("run/rt"));
    Files.createDirectories(source.resolveSibling("contracts").resolve("java"));
    Path contracts = Files.createDirectories(temporary.resolve("selected/java"));
    assertEquals(contracts, IsolatedPatchWorker.resolveCaptureContracts(source, contracts.toString()));
  }

  @Test
  void invalidExplicitContractsNeverFallBackToSibling() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("run/rt"));
    Files.createDirectories(source.resolveSibling("contracts").resolve("java"));
    assertEquals("CAPTURE_CONTRACTS_NOT_FOUND", assertThrows(IOException.class,
        () -> IsolatedPatchWorker.resolveCaptureContracts(source, temporary.resolve("missing").toString())).getMessage());
    assertEquals("CAPTURE_CONTRACTS_PATH_NOT_ABSOLUTE", assertThrows(IOException.class,
        () -> IsolatedPatchWorker.resolveCaptureContracts(source, "relative/contracts")).getMessage());
    assertEquals("CAPTURE_CONTRACTS_PATH_INVALID", assertThrows(IOException.class,
        () -> IsolatedPatchWorker.resolveCaptureContracts(source, "invalid" + (char) 0 + "path")).getMessage());
  }

  @Test
  void unconfiguredContractsRetainTheConventionalSiblingLayout() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("run/rt"));
    Path contracts = Files.createDirectories(source.resolveSibling("contracts").resolve("java"));
    assertEquals(contracts, IsolatedPatchWorker.resolveCaptureContracts(source, null));
    assertEquals(contracts, IsolatedPatchWorker.resolveCaptureContracts(source, " "));
  }

  @Test
  void contractsMustBeADirectory() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("run/rt"));
    Path file = Files.createFile(temporary.resolve("not-a-directory"));
    assertEquals("CAPTURE_CONTRACTS_NOT_FOUND", assertThrows(IOException.class,
        () -> IsolatedPatchWorker.resolveCaptureContracts(source, file.toString())).getMessage());
    assertEquals("CAPTURE_CONTRACTS_NOT_FOUND", assertThrows(IOException.class,
        () -> IsolatedPatchWorker.resolveCaptureContracts(source, null)).getMessage());
  }

  @Test
  void excludesOnlyGradleCoordinationLocksFromThePrivateCacheSeed() {
    assertTrue(IsolatedPatchWorker.isGradleCoordinationFile(
        Path.of("caches", "8.14.2", "fileContent", "fileContent.lock")));
    assertTrue(IsolatedPatchWorker.isGradleCoordinationFile(Path.of("modules-2.lock")));
    assertTrue(IsolatedPatchWorker.isGradleCoordinationFile(Path.of("gradle-8.8-all.zip.lck")));
    assertFalse(IsolatedPatchWorker.isGradleCoordinationFile(
        Path.of("caches", "modules-2", "files-2.1", "dependency.jar")));
    assertFalse(IsolatedPatchWorker.isGradleCoordinationFile(Path.of("gc.properties")));
  }

  @Test
  void copiesOnlyTheExecutableGradleDistributionIntoThePrivateHome() {
    assertTrue(IsolatedPatchWorker.isGradleRuntimePath(Path.of("hash")));
    assertTrue(IsolatedPatchWorker.isGradleRuntimePath(Path.of("hash", "gradle.zip.ok")));
    assertTrue(IsolatedPatchWorker.isGradleRuntimePath(
        Path.of("hash", "gradle-8.8", "bin", "gradle.bat")));
    assertTrue(IsolatedPatchWorker.isGradleRuntimePath(
        Path.of("hash", "gradle-8.8", "lib", "gradle-launcher-8.8.jar")));
    assertFalse(IsolatedPatchWorker.isGradleRuntimePath(
        Path.of("hash", "gradle-8.8", "docs", "long-generated-name.html")));
    assertFalse(IsolatedPatchWorker.isGradleRuntimePath(
        Path.of("hash", "gradle-8.8", "src", "core", "Source.java")));
  }

  @Test
  void acceptsOnlyCanonicalMavenPurlsWithAnOptionalClassifier() {
    assertTrue(IsolatedPatchWorker.isValidSbomPurl("pkg:maven/org.lwjgl/lwjgl@3.3.2"));
    assertTrue(IsolatedPatchWorker.isValidSbomPurl(
        "pkg:maven/org.lwjgl/lwjgl@3.3.2?classifier=natives-windows-x86_64"));
    assertFalse(IsolatedPatchWorker.isValidSbomPurl(
        "pkg:maven/org.lwjgl/lwjgl@3.3.2?type=jar"));
    assertFalse(IsolatedPatchWorker.isValidSbomPurl(
        "pkg:maven/org.lwjgl/lwjgl@3.3.2?classifier="));
  }
}
