package com.chromascape.web.debug.buildergoal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.buildergoal.recovery.service.BuilderGoalCompileDiagnosticParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuilderGoalCompileDiagnosticParserTest {

  @Test
  void parsesJavacErrorsWithWindowsPathsRelativeToRepositoryRoot() {
    List<String> lines =
        List.of(
            "C:\\Dev\\IdeaProjects\\micro6\\runelite-client\\src\\main\\java\\net\\runelite\\client"
                + "\\plugins\\microbot\\accountbuilder\\FishingExecutionTask.java:93: error:"
                + " cannot find symbol",
            "C:\\Dev\\IdeaProjects\\micro6\\runelite-client\\src\\Test.java:12: warning: unused");
    BuilderGoalCompileDiagnosticParser.ParseResult result =
        BuilderGoalCompileDiagnosticParser.parse(lines, 500, "C:\\Dev\\IdeaProjects\\micro6");
    assertThat(result.diagnostics()).hasSize(2);
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.warningCount()).isEqualTo(1);
    BuilderGoalCompileDiagnosticParser.Diagnostic first = result.diagnostics().get(0);
    assertThat(first.tool()).isEqualTo("javac");
    assertThat(first.severity()).isEqualTo("error");
    assertThat(first.relativeFile())
        .isEqualTo(
            "runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/FishingExecutionTask.java");
    assertThat(first.line()).isEqualTo(93);
    assertThat(first.message()).contains("cannot find symbol");
  }

  @Test
  void parsesKotlinDiagnosticsWithColumn() {
    List<String> lines =
        List.of("src/main/java/Fishing.kt: (12, 3): error: unresolved reference: Fish");
    BuilderGoalCompileDiagnosticParser.ParseResult result =
        BuilderGoalCompileDiagnosticParser.parse(lines, 500, null);
    assertThat(result.errorCount()).isEqualTo(1);
    BuilderGoalCompileDiagnosticParser.Diagnostic diagnostic = result.diagnostics().get(0);
    assertThat(diagnostic.tool()).isEqualTo("kotlin");
    assertThat(diagnostic.line()).isEqualTo(12);
    assertThat(diagnostic.column()).isEqualTo(3);
    assertThat(diagnostic.relativeFile()).isEqualTo("src/main/java/Fishing.kt");
  }

  @Test
  void parsesGradleTaskFailureLines() {
    BuilderGoalCompileDiagnosticParser.ParseResult result =
        BuilderGoalCompileDiagnosticParser.parse(
            List.of("> Task :client:compileJava FAILED"), 500, null);
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.diagnostics().get(0).tool()).isEqualTo("gradle");
    assertThat(result.diagnostics().get(0).code()).isEqualTo("GRADLE_TASK_FAILED");
  }

  @Test
  void respectsDiagnosticLimitAndTruncatesLongLines() {
    List<String> lines = new java.util.ArrayList<>();
    for (int i = 0; i < 600; i++) {
      lines.add("src/F" + i + ".java:1: error: boom" + i);
    }
    lines.add("src/Long.java:1: error: " + "x".repeat(20000));
    BuilderGoalCompileDiagnosticParser.ParseResult result =
        BuilderGoalCompileDiagnosticParser.parse(lines, 500, null);
    assertThat(result.diagnostics()).hasSize(500);
    assertThat(
            result.diagnostics().stream()
                .allMatch(
                    diagnostic ->
                        diagnostic.message() == null || diagnostic.message().length() <= 16 * 1024))
        .isTrue();
  }

  @Test
  void ignoresNonDiagnosticLines() {
    BuilderGoalCompileDiagnosticParser.ParseResult result =
        BuilderGoalCompileDiagnosticParser.parse(
            List.of("BUILD FAILED", "", "Some random output"), 500, null);
    assertThat(result.diagnostics()).isEmpty();
    assertThat(result.errorCount()).isZero();
  }
}
