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
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentClientArtifactsTest {
  @TempDir Path root;
  private final ObjectMapper mapper = new ObjectMapper();

  private Path jar(String name, String build, String commit) throws Exception {
    var metadata = new Manifest();
    metadata.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (build != null) metadata.getMainAttributes().putValue("SC663-Build-Id", build);
    if (commit != null) metadata.getMainAttributes().putValue("SC663-Commit-Id", commit);
    var path = root.resolve(name + ".jar");
    try (var output = new JarOutputStream(Files.newOutputStream(path), metadata)) {
      output.putNextEntry(new JarEntry("client.class"));
      output.write(new byte[] {1, 2, 3});
      output.closeEntry();
    }
    return path;
  }

  private ObjectNode manifest(Path artifact) throws Exception {
    ObjectNode value;
    try (var input = getClass().getResourceAsStream("/sc663/current-manifest.json")) {
      value = (ObjectNode) mapper.readTree(input);
    }
    ((ObjectNode) value.path("clients").path("RUNELITE_CAPTURE"))
        .put("artifact_digest", digest(artifact));
    return value;
  }

  private String digest(Path artifact) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
  }

  private CurrentSliceIdentity identity(Path artifact) throws Exception {
    ObjectNode value;
    try (var input = getClass().getResourceAsStream("/sc663/runelite-identity.json")) {
      value = (ObjectNode) mapper.readTree(input);
    }
    ((ObjectNode) value.path("client")).put("artifact_digest", digest(artifact));
    return mapper.treeToValue(value, CurrentSliceIdentity.class);
  }

  private CurrentContractGate gate(ObjectNode pins, CurrentClientArtifacts artifacts)
      throws Exception {
    var path = root.resolve("pins.json");
    mapper.writeValue(path.toFile(), pins);
    return new CurrentContractGate(path.toString(), null, artifacts);
  }

  @Test
  void sourceLessClientCommitIsBoundToIndependentlyMeasuredJarBytes() throws Exception {
    var artifact = jar("current", "fixture-runelite", "a".repeat(40));
    var observed = new CurrentClientArtifacts("", artifact.toString());
    var identity = identity(artifact);
    assertThat(identity.source()).isNull();
    assertThat(observed.observed().get("RUNELITE_CAPTURE"))
        .containsEntry("commit_id", "a".repeat(40))
        .containsEntry("artifact_digest", digest(artifact));
    var pins = manifest(artifact);
    var gate = gate(pins, observed);
    gate.requireOperation(identity, "capture");
    assertThat(
            mapper
                .valueToTree(gate.diagnostics())
                .path("operations")
                .path("RUNELITE_CAPTURE")
                .path("capture")
                .asText())
        .isEqualTo("AVAILABLE_BY_CONTRACT");
    assertThat(
            mapper
                .valueToTree(gate.diagnostics())
                .path("operations")
                .path("MICRO6_MANAGED")
                .path("capture")
                .asText())
        .isEqualTo("DEGRADED");
    // Same advertised build and digest cannot conceal a different independently observed commit.
    ((ObjectNode) pins.path("clients").path("RUNELITE_CAPTURE")).put("commit_id", "c".repeat(40));
    var mismatched = gate(pins, observed);
    var rows = mock(CurrentSliceRunRepository.class);
    var stabilization = mock(StabilizationIngestionService.class);
    var service =
        new CurrentSliceRunService(
            new CurrentSliceIdentityValidator(mismatched), rows, mapper, stabilization);
    assertThatThrownBy(() -> service.createRunelite(identity))
        .hasMessage("CLIENT_ARTIFACT_MISMATCH");
    verifyNoInteractions(rows, stabilization);
  }

  @Test
  void missingMalformedOrChangedArtifactsFailClosed() throws Exception {
    var artifact = jar("current", "fixture-runelite", "a".repeat(40));
    var pins = manifest(artifact);
    for (String commit : new String[] {null, "", "aaaaaaa", "g".repeat(40)}) {
      var invalid = jar("invalid", "fixture-runelite", commit);
      var gate = gate(pins, new CurrentClientArtifacts("", invalid.toString()));
      assertThatThrownBy(() -> gate.requireIdentity(identity(artifact)))
          .hasMessage("CLIENT_ARTIFACT_UNVERIFIED");
    }
    for (String path : new String[] {"", root.toString(), root.resolve("missing.jar").toString()}) {
      var gate = gate(pins, new CurrentClientArtifacts("", path));
      assertThatThrownBy(() -> gate.requireIdentity(identity(artifact)))
          .hasMessage("CLIENT_ARTIFACT_UNVERIFIED");
    }
    for (Path wrong :
        new Path[] {
          jar("wrong-build", "other", "a".repeat(40)),
          jar("wrong-commit", "fixture-runelite", "b".repeat(40))
        }) {
      var gate = gate(pins, new CurrentClientArtifacts("", wrong.toString()));
      assertThatThrownBy(() -> gate.requireIdentity(identity(artifact)))
          .hasMessage("CLIENT_ARTIFACT_MISMATCH");
    }
    // Metadata alone is insufficient: changing only other artifact bytes also refuses.
    var altered = root.resolve("altered.jar");
    Files.write(altered, Files.readAllBytes(artifact));
    Files.write(altered, new byte[] {99}, java.nio.file.StandardOpenOption.APPEND);
    var changed = gate(pins, new CurrentClientArtifacts("", altered.toString()));
    assertThatThrownBy(() -> changed.requireIdentity(identity(artifact)))
        .hasMessage("CLIENT_ARTIFACT_MISMATCH");
  }

  @Test
  void springCannotBypassClientArtifactVerification() throws Exception {
    var artifact = jar("current", "fixture-runelite", "a".repeat(40));
    var pinFile = root.resolve("spring-pins.json");
    mapper.writeValue(pinFile.toFile(), manifest(artifact));
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "pins", java.util.Map.of("ai-player.contract.manifest", pinFile.toString())));
      context.registerBean(CurrentBackendRuntime.class, () -> mock(CurrentBackendRuntime.class));
      context.register(
          CurrentContractGate.class,
          CurrentClientArtifacts.class,
          CurrentSliceIdentityValidator.class);
      context.refresh();
      assertThatThrownBy(
              () ->
                  context.getBean(CurrentSliceIdentityValidator.class).validate(identity(artifact)))
          .hasMessage("CLIENT_ARTIFACT_UNVERIFIED");
    }
  }
}
