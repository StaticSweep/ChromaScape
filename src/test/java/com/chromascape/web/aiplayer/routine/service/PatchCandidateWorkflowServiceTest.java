package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.config.AiRoutineOperatorProperties;
import com.chromascape.web.aiplayer.routine.config.Sc967CandidateProperties;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SC-967 build-gate contract: while {@code sc967.candidate.builds-enabled} is false no build can be
 * queued; when enabled, startup fails unless operator token/ID, signing key, registry, staging root
 * and the exact expected JAR name are configured and usable. The gate binds through the typed
 * {@link Sc967CandidateProperties}, so {@code SC967_CANDIDATE_BUILDS_ENABLED=true} is honored.
 */
class PatchCandidateWorkflowServiceTest {

  @Test
  void candidateSourceBindingReplacesOnlyTheAuthoritativeSourceGroup() {
    String hash = "sha256:" + "a".repeat(64);
    CurrentSliceIdentity.Task task =
        new CurrentSliceIdentity.Task("FISHING_TRAINING", hash, "profile", hash, hash);
    CurrentSliceIdentity.Run run =
        new CurrentSliceIdentity.Run("run_1", "attempt_1", 1, "executor_1");
    CurrentSliceIdentity.Capture capture =
        new CurrentSliceIdentity.Capture("session_1", "recording_1", hash);
    CurrentSliceIdentity.Graph graph =
        new CurrentSliceIdentity.Graph("graph_aaaaaaaaaaaaaaaaaaaaaaaa", hash);
    CurrentSliceIdentity.Model model =
        new CurrentSliceIdentity.Model("model_aaaaaaaaaaaaaaaaaaaaaaaa", hash);
    CurrentSliceIdentity identity =
        new CurrentSliceIdentity(
            "current-slice-identity/v1",
            task,
            run,
            new CurrentSliceIdentity.Client(
                "client", "MICRO6_MANAGED", "build", hash, "micro6-builder/v2", "MICRO6"),
            capture,
            new CurrentSliceIdentity.Source(
                "srcsnap_aaaaaaaaaaaaaaaaaaaaaaaa",
                hash,
                "micro6",
                "0123456789a",
                "fedcba987",
                "build",
                hash,
                true),
            graph,
            model,
            null,
            false);

    Micro6SourceManifestClient.SourceBinding resolved =
        new Micro6SourceManifestClient.SourceBinding(
            "build",
            "srcsnap_bbbbbbbbbbbbbbbbbbbbbbbb",
            "sha256:" + "b".repeat(64),
            "file_aaaaaaaaaaaaaaaa",
            "Entry.java",
            "runelite-client/Entry.java",
            hash,
            1,
            2,
            hash,
            "class Entry {}",
            "{}",
            java.util.Map.of(),
            "micro6",
            "0123456789a",
            "fedcba987",
            "sha256:" + "b".repeat(64),
            true);
    CurrentSliceIdentity rebound =
        PatchCandidateWorkflowService.withCandidateSource(identity, resolved);

    rebound.validate();
    assertEquals(
        new CurrentSliceIdentity.Source(
            "srcsnap_bbbbbbbbbbbbbbbbbbbbbbbb",
            "sha256:" + "b".repeat(64),
            "micro6",
            "0123456789a",
            "fedcba987",
            "build",
            "sha256:" + "b".repeat(64),
            true),
        rebound.source());
    assertEquals(task, rebound.task());
    assertEquals(run, rebound.run());
    assertEquals(identity.client(), rebound.client());
    assertEquals(capture, rebound.capture());
    assertEquals(graph, rebound.graph());
    assertEquals(model, rebound.model());
    assertEquals(null, rebound.candidate());
    assertEquals(false, rebound.controlAllowed());
  }

  @Test
  void seededFailureBindingUsesTheResolvedNormalizerSymbol() throws Exception {
    String hash = "sha256:" + "b".repeat(64);
    Micro6SourceManifestClient.SourceBinding resolved =
        new Micro6SourceManifestClient.SourceBinding(
            "build",
            "srcsnap_bbbbbbbbbbbbbbbbbbbbbbbb",
            hash,
            "file_aaaaaaaaaaaaaaaa",
            "FishingTaskDefinitionNormalizer.java",
            "runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizer.java",
            hash,
            180,
            195,
            hash,
            "class FishingTaskDefinitionNormalizer {}",
            "{}",
            java.util.Map.of(),
            "micro6",
            "0123456789a",
            "fedcba987",
            hash,
            true);

    java.util.Map<String, Object> binding =
        PatchCandidateWorkflowService.seededFailureBinding(
            resolved, new ObjectMapper().readTree(resolved.manifestJson()));

    assertEquals("FishingTaskDefinitionNormalizer#normalize(String, Map)", binding.get("symbol"));
    assertEquals("BLANK_FISH_ALIAS_ACCEPTED", binding.get("failure_code"));
    assertEquals(
        "sha256:10f1e4d0f27c18472ff4193317eeaf8a1aa42751e4cefe0060b156e92cba5a3e",
        binding.get("failure_manifest_hash"));
  }

  @Test
  void seededFailureFixturesFollowTheRenamedCodeAndManifest() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode manifest;
    JsonNode sourceBinding;
    try (java.io.InputStream manifestStream =
            getClass().getResourceAsStream("/sc967/sc572-seeded-failure-v1.json");
        java.io.InputStream bindingStream =
            getClass().getResourceAsStream("/sc967/fishing-seeded-fail-source-v1.json")) {
      assertTrue(manifestStream != null, "seeded failure manifest fixture is missing");
      assertTrue(bindingStream != null, "seeded failure source binding fixture is missing");
      manifest = mapper.readTree(manifestStream);
      sourceBinding = mapper.readTree(bindingStream);
    }
    assertEquals(
        CanonicalJsonHasher.hash(manifest, null),
        sourceBinding.path("failure_manifest_hash").asText());
    assertEquals("BLANK_FISH_ALIAS_ACCEPTED", sourceBinding.path("failure_code").asText());
    assertEquals(
        "FishingTaskDefinitionNormalizer#normalize(String, Map)",
        sourceBinding.path("symbol").asText());
  }

  @Test
  void closureInputsMapTheCollectedClosureArtifacts() throws Exception {
    String inputs =
        "{\"schema\":\"sc2106-execution-closure-inputs/v1\","
            + "\"taskCoreEntry\":\"example.FishingCore\","
            + "\"runnerVersion\":\"1.0.0\","
            + "\"adapterVersion\":\"xrsps-candidate-adapter/v1\","
            + "\"protocolVersion\":\"xrsps-candidate-session/v2\","
            + "\"runnerJarName\":\"runner.jar\","
            + "\"entrypoint\":\"example.CandidateMain\","
            + "\"coreId\":\"micro6-shared-fishing-core\","
            + "\"coreVersion\":\"1.0.0\","
            + "\"coreSourceDigest\":\"sha256:"
            + "c".repeat(64)
            + "\","
            + "\"coreJarName\":\"core.jar\","
            + "\"coreEntryClass\":\"example.FishingSharedCore\","
            + "\"dependencyJarNames\":[\"dep.jar\"]}";
    IsolatedPatchWorker.BuildResult build =
        new IsolatedPatchWorker.BuildResult(
            "BUILT",
            "base",
            java.util.List.of("file"),
            null,
            null,
            "candidate.jar",
            new byte[] {1},
            "runtime-sbom.cdx.json",
            new byte[] {2},
            new byte[] {3},
            new byte[] {4},
            "execution-closure-inputs.json",
            inputs.getBytes(StandardCharsets.UTF_8),
            java.util.List.of(
                new IsolatedPatchWorker.ClosureArtifact("core.jar", new byte[] {5}),
                new IsolatedPatchWorker.ClosureArtifact("runner.jar", new byte[] {6}),
                new IsolatedPatchWorker.ClosureArtifact("dep.jar", new byte[] {7})));

    CandidateExecutionClosureV1.Inputs mapped = PatchCandidateWorkflowService.closureInputs(build);

    assertEquals("runner.jar", mapped.runner().name());
    assertEquals("core.jar", mapped.core().name());
    assertEquals(1, mapped.dependencies().size());
    assertEquals("dep.jar", mapped.dependencies().get(0).name());
    assertEquals("example.CandidateMain", mapped.entrypoint());
    assertEquals("example.FishingCore", mapped.taskCoreEntry());
  }

  @Test
  void canonicalCandidatePatchRetainsExactUnifiedDiffContext() throws Exception {
    Field patchField = PatchCandidateWorkflowService.class.getDeclaredField("PATCH");
    patchField.setAccessible(true);
    String patch = (String) patchField.get(null);

    assertTrue(
        patch.contains(
            "@@ -180,6 +180,14 @@ public final class FishingTaskDefinitionNormalizer\n"));
    assertTrue(
        patch.contains(
            " \t\tString targetFish = text(s, \"targetFish\");\n"
                + " \t\tString fishType = text(s, \"fishType\");\n"
                + "+\t\tif (s.get(\"targetFish\") != null && targetFish == null)\n"));
    assertTrue(
        patch.contains(
            "+\t\t\treturn Result.error(FishingCoreCode.INVALID_SETTING, "
                + "\"fishType must not be blank\", \"fishType\");\n"));
    assertTrue(
        patch.contains("+\t@Test\n" + "+\tvoid blankFishAliasesAreInvalidRatherThanMissing()\n"));
    assertTrue(
        patch.contains(
            "diff --git a/runelite-client/src/test/java/net/runelite/client/plugins/"
                + "microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizerTest.java"));
    assertTrue(
        patch.contains(
            "diff --git a/runelite-client/src/main/java/net/runelite/client/plugins/"
                + "microbot/accountbuilder/sharedcore/fishing/FishingTaskDefinitionNormalizer.java"));
  }

  @Test
  void canonicalCandidatePatchAppliesToConfiguredMicro6Checkout() throws Exception {
    String checkout = System.getenv("SC968_MICRO6_VERIFY_ROOT");
    assumeTrue(
        checkout != null && !checkout.isBlank(),
        "SC968_MICRO6_VERIFY_ROOT enables the native patch applicability check");
    Field patchField = PatchCandidateWorkflowService.class.getDeclaredField("PATCH");
    patchField.setAccessible(true);
    Path patchFile = Files.createTempFile("sc967-canonical-candidate-", ".patch");
    Files.writeString(patchFile, (String) patchField.get(null), StandardCharsets.UTF_8);
    Process process =
        new ProcessBuilder(
                "git",
                "-C",
                Path.of(checkout).toAbsolutePath().normalize().toString(),
                "apply",
                "--check",
                patchFile.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }

  private AiRoutineOperatorProperties operator(String token, String id) {
    AiRoutineOperatorProperties props = new AiRoutineOperatorProperties();
    props.setToken(token);
    props.setId(id);
    return props;
  }

  private PatchCandidateWorkflowService service(
      AiRoutineOperatorProperties operator, Sc967CandidateProperties candidate) {
    return new PatchCandidateWorkflowService(
        new ObjectMapper(),
        mock(IsolatedPatchWorker.class),
        mock(PatchCandidateRunRepository.class),
        mock(PatchCandidateBuildDispatcher.class),
        mock(CurrentSliceRunService.class),
        mock(Micro6SourceManifestClient.class),
        mock(com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator.class),
        registry("http://localhost:8081", "token"),
        operator,
        candidate);
  }

  private CandidateRegistryClient registry(String baseUrl, String token) {
    CandidateRegistryClient registry = mock(CandidateRegistryClient.class);
    when(registry.baseUrl()).thenReturn(baseUrl);
    when(registry.token()).thenReturn(token);
    return registry;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = PatchCandidateWorkflowService.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @Test
  void disabledBuildsRejectEnqueue() throws Exception {
    Sc967CandidateProperties candidate = new Sc967CandidateProperties();
    candidate.setBuildsEnabled(false);
    candidate.setStagingRoot(Path.of(System.getProperty("java.io.tmpdir")).toString());
    PatchCandidateWorkflowService service = service(operator("op-token", "operator-42"), candidate);
    setField(service, "expectedArtifactName", "microbot-1.0.0.jar");

    // Disabled validation is a no-op; the flag gates nothing at startup.
    assertDoesNotThrow(() -> callValidate(service));

    PatchCandidateWorkflowService.PatchWorkflowException error =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service.enqueueBuild("proposal_1"));
    assertEquals("CANDIDATE_BUILDS_DISABLED", error.code());
  }

  @Test
  void enabledWithoutOperatorTokenFailsStartup() {
    Sc967CandidateProperties candidate = new Sc967CandidateProperties();
    candidate.setBuildsEnabled(true);
    PatchCandidateWorkflowService service = service(operator("", "operator-42"), candidate);
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> callValidate(service));
    assertTrue(error.getMessage().contains("AI_ROUTINE_OPERATOR_TOKEN"));
  }

  @Test
  void enabledWithoutSigningKeyFailsStartup() throws Exception {
    Sc967CandidateProperties candidate = new Sc967CandidateProperties();
    candidate.setBuildsEnabled(true);
    candidate.setStagingRoot(Path.of(System.getProperty("java.io.tmpdir")).toString());
    PatchCandidateWorkflowService service = service(operator("op-token", "operator-42"), candidate);
    setField(service, "signingKeyPath", "");
    setField(service, "signingKeyId", "release-ed25519-2026-01");
    setField(service, "expectedArtifactName", "microbot-1.0.0.jar");
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> callValidate(service));
    assertTrue(error.getMessage().contains("CANDIDATE_SIGNING_PRIVATE_KEY_PATH"));
  }

  @Test
  void enabledWithoutStagingRootFailsStartup() throws Exception {
    Sc967CandidateProperties candidate = new Sc967CandidateProperties();
    candidate.setBuildsEnabled(true);
    candidate.setStagingRoot("");
    PatchCandidateWorkflowService service = service(operator("op-token", "operator-42"), candidate);
    setField(service, "signingKeyPath", writingTempKey().toString());
    setField(service, "signingKeyId", "release-ed25519-2026-01");
    setField(service, "expectedArtifactName", "microbot-1.0.0.jar");
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> callValidate(service));
    assertTrue(error.getMessage().contains("SC967_CANDIDATE_STAGING_ROOT"));
  }

  @Test
  void enabledWithoutExpectedJarNameFailsStartup() throws Exception {
    Sc967CandidateProperties candidate = new Sc967CandidateProperties();
    candidate.setBuildsEnabled(true);
    candidate.setStagingRoot(Path.of(System.getProperty("java.io.tmpdir")).toString());
    PatchCandidateWorkflowService service = service(operator("op-token", "operator-42"), candidate);
    setField(service, "signingKeyPath", writingTempKey().toString());
    setField(service, "signingKeyId", "release-ed25519-2026-01");
    setField(service, "expectedArtifactName", "");
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> callValidate(service));
    assertTrue(error.getMessage().contains("MICRO6_EXPECTED_ARTIFACT_NAME"));
  }

  @Test
  void enabledWithCompleteConfigurationPassesStartup(@TempDir Path staging) throws Exception {
    Sc967CandidateProperties candidate = new Sc967CandidateProperties();
    candidate.setBuildsEnabled(true);
    candidate.setStagingRoot(staging.toString());
    PatchCandidateWorkflowService service = service(operator("op-token", "operator-42"), candidate);
    setField(service, "signingKeyPath", writingTempKey().toString());
    setField(service, "signingKeyId", "release-ed25519-2026-01");
    setField(service, "expectedArtifactName", "microbot-1.0.0.jar");
    assertDoesNotThrow(() -> callValidate(service));
    assertTrue(Files.isDirectory(staging), "staging root must be created and usable");
  }

  @Test
  void enabledWithUnreadableSigningKeyFailsStartup(@TempDir Path staging) throws Exception {
    Sc967CandidateProperties candidate = new Sc967CandidateProperties();
    candidate.setBuildsEnabled(true);
    candidate.setStagingRoot(staging.toString());
    PatchCandidateWorkflowService service = service(operator("op-token", "operator-42"), candidate);
    setField(
        service,
        "signingKeyPath",
        Path.of(System.getProperty("java.io.tmpdir")).resolve("missing-key.pem").toString());
    setField(service, "signingKeyId", "release-ed25519-2026-01");
    setField(service, "expectedArtifactName", "microbot-1.0.0.jar");
    assertThrows(IllegalStateException.class, () -> callValidate(service));
  }

  private static void callValidate(PatchCandidateWorkflowService service) throws Exception {
    java.lang.reflect.Method validate =
        PatchCandidateWorkflowService.class.getDeclaredMethod("validateConfiguration");
    validate.setAccessible(true);
    try {
      validate.invoke(service);
    } catch (java.lang.reflect.InvocationTargetException exception) {
      if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
      if (exception.getCause() instanceof Error error) throw error;
      throw exception;
    }
  }

  private static Path writingTempKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
      KeyPair pair = generator.generateKeyPair();
      PrivateKey key = pair.getPrivate();
      byte[] der =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new PKCS8EncodedKeySpec(key.getEncoded()))
              .getEncoded();
      String pem =
          "-----BEGIN PRIVATE KEY-----\n"
              + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
              + "\n-----END PRIVATE KEY-----\n";
      Path temp = Files.createTempFile("sc967-test-key", ".pem");
      Files.writeString(temp, pem);
      return temp;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
