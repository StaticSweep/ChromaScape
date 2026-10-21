package com.chromascape.web.aiplayer.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentBackendRuntimeTest {
  @TempDir Path root;
  private final ObjectMapper mapper = new ObjectMapper();

  private Path artifact() throws Exception {
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("SC663-Build-Id", "actual-build");
    manifest.getMainAttributes().putValue("SC663-Commit-Id", "c".repeat(40));
    Path jar = root.resolve("actual.jar");
    try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      output.putNextEntry(new JarEntry("application.class"));
      output.write(new byte[] {1, 2, 3});
      output.closeEntry();
    }
    return jar;
  }

  private ObjectNode manifest() throws Exception {
    try (var input = getClass().getResourceAsStream("/sc663/current-manifest.json")) {
      return (ObjectNode) mapper.readTree(input);
    }
  }

  @Test
  void measuresActualBytesAndEmbeddedBuildInsteadOfConfiguredPins() throws Exception {
    Path jar = artifact();
    var runtime = new CurrentBackendRuntime(jar);
    assertThat(runtime.observed())
        .containsEntry("build_id", "actual-build")
        .containsEntry("commit_id", "c".repeat(40))
        .containsEntry(
            "artifact_digest",
            "sha256:"
                + HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
    var pins = manifest();
    assertThat(runtime.refusal(pins.path("backend"))).isEqualTo("BACKEND_RUNTIME_MISMATCH");
    pins.set("backend", mapper.valueToTree(runtime.observed()));
    assertThat(runtime.refusal(pins.path("backend"))).isNull();
    for (String field : new String[] {"build_id", "commit_id", "artifact_digest"}) {
      var altered = pins.deepCopy();
      ((ObjectNode) altered.path("backend")).put(field, "wrong");
      assertThat(runtime.refusal(altered.path("backend"))).isEqualTo("BACKEND_RUNTIME_MISMATCH");
    }
  }

  @Test
  void unpackagedOrMissingMetadataNeverAttestsDeployment() throws Exception {
    for (Path path :
        new Path[] {
          root, root.resolve("absent.jar"), Files.writeString(root.resolve("bad.jar"), "bad")
        }) {
      var runtime = new CurrentBackendRuntime(path);
      assertThat(runtime.observed()).isEmpty();
      assertThat(runtime.refusal(manifest().path("backend")))
          .isEqualTo("BACKEND_RUNTIME_UNVERIFIED");
    }
    var jar = root.resolve("no-metadata.jar");
    try (var output = new JarOutputStream(Files.newOutputStream(jar), new Manifest())) {}
    assertThat(new CurrentBackendRuntime(jar).observed()).isEmpty();
  }

  @Test
  void runtimeRefusalBlocksPersistenceAndDisablesContractOperations() throws Exception {
    var path = root.resolve("pins.json");
    mapper.writeValue(path.toFile(), manifest());
    var runtime = new CurrentBackendRuntime(artifact());
    var gate = new CurrentContractGate(path.toString(), runtime);
    var rows = mock(CurrentSliceRunRepository.class);
    var stabilization = mock(StabilizationIngestionService.class);
    var service =
        new CurrentSliceRunService(
            new CurrentSliceIdentityValidator(gate), rows, mapper, stabilization);
    try (var input = getClass().getResourceAsStream("/sc663/runelite-identity.json")) {
      var identity = mapper.readValue(input, CurrentSliceIdentity.class);
      assertThatThrownBy(() -> service.createRunelite(identity))
          .hasMessage("BACKEND_RUNTIME_MISMATCH");
    }
    verifyNoInteractions(rows, stabilization);
    assertThat(gate.diagnostics())
        .containsEntry("status", "BLOCKED")
        .containsEntry("backendRuntimeVerified", false)
        .containsEntry("runtimeReadinessVerified", false)
        .containsEntry("observedBackend", runtime.observed());
    assertThat(
            mapper
                .valueToTree(gate.diagnostics())
                .path("operations")
                .path("RUNELITE_CAPTURE")
                .path("capture")
                .asText())
        .isEqualTo("DEGRADED");
  }

  @Test
  void springProductionWiringCannotUseThePinOnlyFixtureConstructor() throws Exception {
    Path path = root.resolve("spring-pins.json");
    mapper.writeValue(path.toFile(), manifest());
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "pins", java.util.Map.of("ai-player.contract.manifest", path.toString())));
      context.register(
          CurrentContractGate.class,
          CurrentBackendRuntime.class,
          CurrentClientArtifacts.class,
          CurrentSliceIdentityValidator.class);
      context.refresh();
      var gate = context.getBean(CurrentContractGate.class);
      assertThat(gate.diagnostics()).containsEntry("reason", "BACKEND_RUNTIME_UNVERIFIED");
      try (var input = getClass().getResourceAsStream("/sc663/runelite-identity.json")) {
        var identity = mapper.readValue(input, CurrentSliceIdentity.class);
        assertThatThrownBy(
                () -> context.getBean(CurrentSliceIdentityValidator.class).validate(identity))
            .hasMessage("BACKEND_RUNTIME_UNVERIFIED");
      }
    }
  }
}
