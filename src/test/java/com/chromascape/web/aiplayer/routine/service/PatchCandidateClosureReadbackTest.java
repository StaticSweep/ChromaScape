package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The persisted bundle is what gets registered, and the registry's readback must reproduce it byte
 * for byte. These tests build a bundle from the producer-generated closure vector and drive the
 * upload description and the readback check against a stubbed registry.
 */
class PatchCandidateClosureReadbackTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CANDIDATE_ID = "cand_000000000000000000000000";

  @TempDir Path bundle;

  private byte[] closure;
  private byte[] runner;
  private byte[] core;
  private byte[] dependency;
  private String runnerName;
  private String coreName;
  private String dependencyName;

  @BeforeEach
  void stageBundle() throws Exception {
    Path vectors = vectorsPath().getParent().resolve("execution-closure-vectors.json");
    JsonNode vector = MAPPER.readTree(Files.readAllBytes(vectors));
    closure = decode(vector, "closure");
    runner = decode(vector, "runner");
    core = decode(vector, "core");
    dependency = decode(vector, "dependency");
    JsonNode document = MAPPER.readTree(closure);
    runnerName = document.path("package").path("name").asText();
    coreName = document.path("core").path("name").asText();
    dependencyName = document.path("dependencies").get(0).path("name").asText();
    Files.write(bundle.resolve("execution-closure.json"), closure);
    Files.write(bundle.resolve(runnerName), runner);
    Files.write(bundle.resolve(coreName), core);
    Files.write(bundle.resolve(dependencyName), dependency);
  }

  @Test
  void uploadNamesTheDocumentThenRunnerCoreAndDependenciesFromTheBundle() throws Exception {
    CandidateRegistryClient.ClosureUpload upload =
        PatchCandidateWorkflowService.closureUpload(bundle);

    assertEquals(bundle.resolve("execution-closure.json"), upload.document());
    assertEquals(
        List.of(
            bundle.resolve(runnerName), bundle.resolve(coreName), bundle.resolve(dependencyName)),
        upload.jars());
  }

  @Test
  void uploadRefusesAJarNameThatCouldEscapeTheBundle() throws Exception {
    String hostile =
        new String(closure, StandardCharsets.UTF_8).replace(runnerName, "../outside.jar");
    Files.writeString(bundle.resolve("execution-closure.json"), hostile);

    assertThrows(IOException.class, () -> PatchCandidateWorkflowService.closureUpload(bundle));
  }

  @Test
  void uploadRefusesAMissingJar() throws Exception {
    Files.delete(bundle.resolve(coreName));

    assertThrows(IOException.class, () -> PatchCandidateWorkflowService.closureUpload(bundle));
  }

  @Test
  void readbackAcceptsTheExactRegisteredBytes() throws Exception {
    CandidateRegistryClient registry = registryServingTheBundle();

    assertDoesNotThrow(
        () ->
            PatchCandidateWorkflowService.verifyClosureReadback(
                registry,
                bundle,
                CANDIDATE_ID,
                registration(CandidateRegistryClient.hash(closure))));
  }

  @Test
  void readbackRejectsADigestTheRegistryDidNotAdvertise() throws Exception {
    CandidateRegistryClient registry = registryServingTheBundle();

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                PatchCandidateWorkflowService.verifyClosureReadback(
                    registry, bundle, CANDIDATE_ID, registration("")));
    assertEquals("REGISTRY_CLOSURE_HASH_MISMATCH", error.getMessage());
  }

  @Test
  void readbackRejectsAChangedClosureDocument() throws Exception {
    CandidateRegistryClient registry = mock(CandidateRegistryClient.class);
    byte[] other = "{\"changed\":true}".getBytes(StandardCharsets.UTF_8);
    when(registry.downloadClosure(CANDIDATE_ID))
        .thenReturn(
            new CandidateRegistryClient.ArtifactDownload(
                other, CandidateRegistryClient.hash(other)));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                PatchCandidateWorkflowService.verifyClosureReadback(
                    registry,
                    bundle,
                    CANDIDATE_ID,
                    registration(CandidateRegistryClient.hash(closure))));
    assertEquals("REGISTRY_CLOSURE_READBACK_MISMATCH", error.getMessage());
  }

  @Test
  void readbackRejectsAChangedClosureJarAndNamesIt() throws Exception {
    CandidateRegistryClient registry = registryServingTheBundle();
    byte[] flipped = core.clone();
    flipped[flipped.length - 1] ^= 0x01;
    when(registry.downloadClosureJar(CANDIDATE_ID, coreName)).thenReturn(flipped);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                PatchCandidateWorkflowService.verifyClosureReadback(
                    registry,
                    bundle,
                    CANDIDATE_ID,
                    registration(CandidateRegistryClient.hash(closure))));
    assertTrue(error.getMessage().endsWith(coreName), error.getMessage());
  }

  private CandidateRegistryClient registryServingTheBundle() throws Exception {
    CandidateRegistryClient registry = mock(CandidateRegistryClient.class);
    when(registry.downloadClosure(CANDIDATE_ID))
        .thenReturn(
            new CandidateRegistryClient.ArtifactDownload(
                closure, CandidateRegistryClient.hash(closure)));
    when(registry.downloadClosureJar(CANDIDATE_ID, runnerName)).thenReturn(runner);
    when(registry.downloadClosureJar(CANDIDATE_ID, coreName)).thenReturn(core);
    when(registry.downloadClosureJar(CANDIDATE_ID, dependencyName)).thenReturn(dependency);
    return registry;
  }

  private static CandidateRegistryClient.RegistryManifestResponse registration(String closureHash) {
    return new CandidateRegistryClient.RegistryManifestResponse(
        new byte[] {'{', '}'}, "sha256:" + "0".repeat(64), 201, closureHash);
  }

  private static byte[] decode(JsonNode vector, String field) {
    return Base64.getDecoder().decode(vector.path(field).asText());
  }

  private static Path vectorsPath() {
    String resolved = System.getProperty("sc967.vectors.path");
    if (resolved == null || resolved.isBlank()) {
      resolved = System.getenv("SC967_VECTORS_PATH");
    }
    if (resolved == null || resolved.isBlank()) {
      throw new IllegalStateException(
          "Gradle did not resolve the canonical SC-967 fixture; configure -Psc967VectorsPath "
              + "or SC967_VECTORS_PATH");
    }
    return Paths.get(resolved).toAbsolutePath().normalize();
  }
}
