package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.config.AiRoutineOperatorProperties;
import com.chromascape.web.aiplayer.routine.config.Sc967CandidateProperties;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
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
  void canonicalCandidatePatchRetainsExactUnifiedDiffContext() throws Exception {
    Field patchField = PatchCandidateWorkflowService.class.getDeclaredField("PATCH");
    patchField.setAccessible(true);
    String patch = (String) patchField.get(null);

    assertTrue(
        patch.contains(
            "@@ -93,8 +93,11 @@ public class FishingExecutionTask"
                + " extends AccountBuilderTask implements Configur\n"
                + "     }\n"
                + "\n"
                + "     private Fish getFishType(String fishName) {\n"));
    assertTrue(patch.contains("\n         // Convert fish name to Fish enum - simple mapping\n"));
    assertTrue(
        patch.contains(
            "@@ -120,8 +123,7 @@ public class FishingExecutionTask"
                + " extends AccountBuilderTask implements Configur\n"
                + "             case \"raw shark\":\n"
                + "                 return Fish.SHARK;\n"
                + "             default:\n"));
    assertTrue(
        patch.contains(
            "+                throw new UnsupportedFishTypeException(fishName);\n"
                + "         }\n"
                + "     }\n"
                + "\n"
                + "diff --git a/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/"));
    assertTrue(
        patch.contains(
            "@@ -0,0 +1,38 @@\n"
                + "+package net.runelite.client.plugins.microbot.accountbuilder.builderPlanStep.fishing;"));
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
