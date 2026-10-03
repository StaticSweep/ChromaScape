package com.chromascape.web.debug.buildergoal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architectural proof for Phase 8: Builder Goal compile recovery must never depend on the legacy
 * direct source-mutation paths. Approval cannot apply patches to a live checkout, patch a Micro6
 * base URL, or reuse the loose oldText/newText replacement exporter.
 */
class BuilderGoalRecoveryArchitectureTest {

  private static final List<String> PROHIBITED_TYPES =
      List.of(
          "Micro6TaskSourcePatchService",
          "PatchApplyService",
          "BuilderGoalRepairPatchExportService");

  @Test
  void recoveryPackageNeverImportsLegacyDirectMutationServices() throws IOException {
    Path recoveryRoot =
        Path.of(
            "src", "main", "java", "com", "chromascape", "web", "debug", "buildergoal", "recovery");
    assertThat(recoveryRoot).isDirectory();
    try (Stream<Path> sources = Files.walk(recoveryRoot)) {
      List<String> violations =
          sources
              .filter(path -> path.toString().endsWith(".java"))
              .map(BuilderGoalRecoveryArchitectureTest::readOrNull)
              .map(String::valueOf)
              .flatMap(content -> PROHIBITED_TYPES.stream().filter(content::contains))
              .toList();
      assertThat(violations)
          .as("compile recovery must not reference legacy direct mutation services")
          .isEmpty();
    }
  }

  @Test
  void failureCodeTaxonomyStaysStable() {
    assertThat(RecoveryFailureCodes.NOT_ELIGIBLE).isEqualTo("NOT_ELIGIBLE");
    assertThat(RecoveryFailureCodes.PATCH_PREIMAGE_MISMATCH).isEqualTo("PATCH_PREIMAGE_MISMATCH");
    assertThat(RecoveryStates.BUILD_VALIDATED).isEqualTo("BUILD_VALIDATED");
    assertThat(RecoveryFailureCodes.NEEDS_RECONCILIATION).isEqualTo("NEEDS_RECONCILIATION");
  }

  private static String readOrNull(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      return "";
    }
  }
}
