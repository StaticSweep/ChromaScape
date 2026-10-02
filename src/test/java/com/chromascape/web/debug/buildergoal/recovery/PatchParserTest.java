package com.chromascape.web.debug.buildergoal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.buildergoal.recovery.service.PatchParser;
import org.junit.jupiter.api.Test;

class PatchParserTest {

  private static final String VALID_DIFF =
      """
      diff --git a/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/FishingExecutionTask.java b/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/FishingExecutionTask.java
      --- a/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/FishingExecutionTask.java
      +++ b/runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/FishingExecutionTask.java
      @@ -10,5 +10,8 @@
       public class FishingExecutionTask {
         private Fish getFishType(String fishName) {
      -    switch (fishName.toLowerCase()) {
      +    if (fishName == null) {
      +      throw new IllegalArgumentException("fish");
      +    }
      +    switch (fishName.trim().toLowerCase()) {
           case "shrimp":
             return Fish.SHRIMP;
      """;

  @Test
  void parsesValidCanonicalDiff() {
    PatchParser.ParsedPatch patch = PatchParser.parse(VALID_DIFF, null);
    assertThat(patch.files()).hasSize(1);
    PatchParser.PatchFile file = patch.files().get(0);
    assertThat(file.path())
        .isEqualTo(
            "runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/FishingExecutionTask.java");
    assertThat(file.changeType()).isEqualTo("MODIFY");
    assertThat(file.hunks()).hasSize(1);
    assertThat(file.newLines()).contains("    if (fishName == null) {");
    assertThat(file.oldLines()).contains("    switch (fishName.toLowerCase()) {");
    assertThat(patch.affectedPaths()).containsExactly(file.path());
  }

  @Test
  void rejectsAbsolutePath() {
    String diff =
        VALID_DIFF
            .replace("a/runelite-client/", "a/C:/Users/dev/runelite-client/")
            .replace("b/runelite-client/", "b/C:/Users/dev/runelite-client/");
    assertThatThrownBy(() -> PatchParser.parse(diff, null))
        .isInstanceOf(RecoveryException.class)
        .extracting(e -> ((RecoveryException) e).code())
        .isEqualTo(RecoveryFailureCodes.PATCH_PATH_FORBIDDEN);
  }

  @Test
  void rejectsPathTraversal() {
    String diff =
        VALID_DIFF
            .replace("a/runelite-client/", "a/../../etc/runelite-client/")
            .replace("b/runelite-client/", "b/../../etc/runelite-client/");
    assertThatThrownBy(() -> PatchParser.parse(diff, null))
        .isInstanceOf(RecoveryException.class)
        .extracting(e -> ((RecoveryException) e).code())
        .isEqualTo(RecoveryFailureCodes.PATCH_PATH_FORBIDDEN);
  }

  @Test
  void rejectsBinaryPatch() {
    assertThatThrownBy(() -> PatchParser.parse(VALID_DIFF + "GIT binary patch\n", null))
        .isInstanceOf(RecoveryException.class)
        .extracting(e -> ((RecoveryException) e).code())
        .isEqualTo(RecoveryFailureCodes.PATCH_FORMAT_INVALID);
  }

  @Test
  void rejectsMissingHunk() {
    String headerOnly =
        "diff --git a/src/A.java b/src/A.java\n--- a/src/A.java\n+++ b/src/A.java\n";
    assertThatThrownBy(() -> PatchParser.parse(headerOnly, null))
        .isInstanceOf(RecoveryException.class)
        .extracting(e -> ((RecoveryException) e).code())
        .isEqualTo(RecoveryFailureCodes.PATCH_FORMAT_INVALID);
  }

  @Test
  void rejectsTruncatedHunk() {
    String truncated = VALID_DIFF.substring(0, VALID_DIFF.indexOf("@@") + 3);
    assertThatThrownBy(() -> PatchParser.parse(truncated, null))
        .isInstanceOf(RecoveryException.class)
        .extracting(e -> ((RecoveryException) e).code())
        .isEqualTo(RecoveryFailureCodes.PATCH_FORMAT_INVALID);
  }

  @Test
  void rejectsProtectedKeystorePaths() {
    String diff =
        VALID_DIFF
            .replace("a/runelite-client/", "a/signing/micro6.")
            .replace("b/runelite-client/", "b/signing/micro6.")
            .replace("FishingExecutionTask.java", "identity.jks");
    assertThatThrownBy(() -> PatchParser.parse(diff, null))
        .isInstanceOf(RecoveryException.class)
        .extracting(e -> ((RecoveryException) e).code())
        .isEqualTo(RecoveryFailureCodes.PATCH_PATH_FORBIDDEN);
  }

  @Test
  void rejectsUncPath() {
    String diff = VALID_DIFF.replace("a/runelite-client/", "a//server/runelite-client/");
    assertThatThrownBy(() -> PatchParser.parse(diff, null))
        .isInstanceOf(RecoveryException.class)
        .extracting(e -> ((RecoveryException) e).code())
        .isIn(RecoveryFailureCodes.PATCH_PATH_FORBIDDEN, RecoveryFailureCodes.PATCH_FORMAT_INVALID);
  }

  @Test
  void rejectsEmptyPatch() {
    assertThatThrownBy(() -> PatchParser.parse("", null))
        .isInstanceOf(RecoveryException.class)
        .extracting(e -> ((RecoveryException) e).code())
        .isEqualTo(RecoveryFailureCodes.PATCH_FORMAT_INVALID);
  }
}
