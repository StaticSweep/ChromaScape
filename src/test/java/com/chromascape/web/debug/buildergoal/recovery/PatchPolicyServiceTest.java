package com.chromascape.web.debug.buildergoal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.service.PatchParser;
import com.chromascape.web.debug.buildergoal.recovery.service.PatchPolicyService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatchPolicyServiceTest {

  private static final String ACCOUNTBUILDER_MAIN =
      "runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/";

  private PatchPolicyService service() {
    BuilderGoalRecoveryProperties properties =
        new BuilderGoalRecoveryProperties(
            true,
            false,
            false,
            false,
            true,
            "data/compile-recovery-artifacts",
            "../micro6",
            "MICRO6_PRODUCTION_COMPILE",
            2097152,
            500,
            262144,
            524288,
            12,
            262144,
            1500,
            1,
            2,
            Duration.ofMinutes(15),
            Duration.ofHours(24),
            Duration.ofHours(12),
            "2",
            "1",
            "",
            "",
            ACCOUNTBUILDER_MAIN
                + ",runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/");
    return new PatchPolicyService(properties);
  }

  private PatchParser.ParsedPatch patchWithPaths(String... paths) {
    StringBuilder diff = new StringBuilder();
    for (String path : paths) {
      diff.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
      diff.append("--- a/").append(path).append('\n');
      diff.append("+++ b/").append(path).append('\n');
      diff.append("@@ -1,1 +1,1 @@\n");
      diff.append("-old\n");
      diff.append("+new\n");
    }
    return PatchParser.parse(diff.toString(), null);
  }

  @Test
  void acceptsAccountBuilderProductionAndTestPaths() {
    PatchParser.ParsedPatch patch =
        patchWithPaths(
            ACCOUNTBUILDER_MAIN + "fishing/FishingExecutionTask.java",
            "runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/fishing/FishingExecutionTaskTest.java");
    PatchPolicyService.PolicyReport report =
        service().evaluate(patch, List.copyOf(patch.affectedPaths()));
    assertThat(report.accepted()).isTrue();
    assertThat(report.forbiddenPaths()).isEmpty();
  }

  @Test
  void rejectsPathsOutsideTheAllowlist() {
    PatchParser.ParsedPatch patch =
        patchWithPaths("runelite-client/src/main/java/net/runelite/client/plugins/Other.java");
    PatchPolicyService.PolicyReport report =
        service().evaluate(patch, List.copyOf(patch.affectedPaths()));
    assertThat(report.accepted()).isFalse();
    assertThat(report.violations())
        .anySatisfy(
            violation ->
                assertThat(violation.code()).isEqualTo(RecoveryFailureCodes.PATCH_PATH_FORBIDDEN));
  }

  @Test
  void rejectsBuildAndSecretPathsByCategory() {
    PatchParser.ParsedPatch patch =
        patchWithPaths(
            "gradle/wrapper/gradle-wrapper.properties",
            "runelite-client/build/output.bin",
            "runelite-client/vendor/lib.so");
    PatchPolicyService.PolicyReport report =
        service().evaluate(patch, List.copyOf(patch.affectedPaths()));
    assertThat(report.accepted()).isFalse();
    assertThat(report.forbiddenPaths()).hasSize(3);
  }

  @Test
  void rejectsSecretLikeNewContent() {
    String diff =
        "diff --git a/"
            + ACCOUNTBUILDER_MAIN
            + "Task.java b/"
            + ACCOUNTBUILDER_MAIN
            + "Task.java\n"
            + "--- a/"
            + ACCOUNTBUILDER_MAIN
            + "Task.java\n"
            + "+++ b/"
            + ACCOUNTBUILDER_MAIN
            + "Task.java\n"
            + "@@ -1,1 +1,2 @@\n"
            + "-old\n"
            + "+String token = \"ghp_abcdefghij0123456789\";\n";
    PatchParser.ParsedPatch patch = PatchParser.parse(diff, null);
    PatchPolicyService.PolicyReport report =
        service().evaluate(patch, List.copyOf(patch.affectedPaths()));
    assertThat(report.accepted()).isFalse();
    assertThat(report.secretScanClean()).isFalse();
  }

  @Test
  void enforcesFileBudget() {
    StringBuilder paths = new StringBuilder();
    String[] array = new String[13];
    for (int i = 0; i < 13; i++) {
      array[i] = ACCOUNTBUILDER_MAIN + "T" + i + ".java";
    }
    PatchParser.ParsedPatch patch = patchWithPaths(array);
    PatchPolicyService.PolicyReport report =
        service().evaluate(patch, List.copyOf(patch.affectedPaths()));
    assertThat(report.accepted()).isFalse();
  }
}
