package com.chromascape.web.aiplayer.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Validates the reviewed deployment manifest against independently measured immutable artifact
 * copies. This is pin evidence only; it never asserts live runtime readiness.
 */
class CurrentContractReleaseManifestTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path property(String name) {
    String value = System.getProperty(name, "");
    if (value.isBlank()) throw new IllegalStateException(name + " must name a release artifact");
    Path path = Path.of(value).toAbsolutePath();
    if (!Files.isRegularFile(path))
      throw new IllegalStateException(name + " is not a file: " + path);
    return path;
  }

  @Test
  void installedManifestPinsMatchIndependentlyMeasuredArtifacts() throws Exception {
    JsonNode manifest = MAPPER.readTree(property("sc663.manifest").toFile());
    var artifacts =
        new CurrentClientArtifacts(
            property("sc663.packaged.micro6").toString(),
            property("sc663.packaged.runelite").toString());
    var gate = new CurrentContractGate(property("sc663.manifest").toString(), null, artifacts);
    Map<String, Object> diagnostics = gate.diagnostics();

    assertThat(diagnostics)
        .containsEntry("status", "PINNED_EXPECTATIONS")
        .containsEntry("controlAllowed", false)
        .containsEntry("runtimeReadinessVerified", false);
    assertThat(diagnostics.get("manifestDigest")).isNotNull();
    Map<String, Map<String, Object>> observed = artifacts.observed();
    for (String kind : new String[] {"MICRO6_MANAGED", "RUNELITE_CAPTURE"}) {
      JsonNode pin = manifest.path("clients").path(kind);
      assertThat(observed.get(kind))
          .as("%s measured metadata", kind)
          .containsEntry("build_id", pin.path("build_id").asText())
          .containsEntry("commit_id", pin.path("commit_id").asText())
          .containsEntry("artifact_digest", pin.path("artifact_digest").asText());
      assertThat(artifacts.refusal(kind, pin)).isNull();
      @SuppressWarnings("unchecked")
      Map<String, String> operations =
          (Map<String, String>) ((Map<String, Object>) diagnostics.get("operations")).get(kind);
      assertThat(operations).containsEntry("capture", "AVAILABLE_BY_CONTRACT");
    }
    assertThat(manifest.path("controlAllowed").asBoolean()).isFalse();
    assertThat(
            manifest
                .path("clients")
                .path("RUNELITE_CAPTURE")
                .path("operations")
                .path("managedExecution")
                .asBoolean())
        .isFalse();
  }
}
