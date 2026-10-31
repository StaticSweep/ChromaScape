package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The readiness ladder never claims a rung above a failed one, and never infers qualification. */
class MouseVideoReadinessTest {

  @TempDir Path root;

  private MouseVideoProperties properties(boolean launcherRequired) throws Exception {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setEnabled(true);
    properties.setRestrictedLauncher(launcherRequired);
    Path template = Files.writeString(root.resolve("arrow.png"), "png");
    properties.setVisualCursorTemplate(template.toString());
    Path policy = Files.writeString(root.resolve("policy.json"), "{}");
    properties.setCorpusManifest(policy.toString());
    return properties;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> rung(Map<String, Object> report, String id) {
    return (Map<String, Object>) report.get(id);
  }

  @Test
  void anIsolatedHostReachesIsolatedButNotQualifiedWithoutARecord() throws Exception {
    Map<String, Object> report =
        new MouseVideoReadiness(
                properties(true), () -> true, () -> true, () -> true, () -> true, () -> true)
            .report();

    Map<String, Object> cursor = rung(report, MouseVideoReadiness.CURSOR_TRACKING);
    assertThat(cursor)
        .containsEntry("isolated", true)
        .containsEntry("qualified", false)
        .containsEntry("limitedBy", "no qualification recorded");
  }

  @Test
  void qualificationIsOnlyWhatTheOperatorRecorded() throws Exception {
    MouseVideoProperties properties = properties(true);
    properties.setQualifiedCapabilities(" media-import , review-protocol ");

    Map<String, Object> report =
        new MouseVideoReadiness(
                properties, () -> true, () -> true, () -> true, () -> true, () -> true)
            .report();

    assertThat(rung(report, MouseVideoReadiness.MEDIA_IMPORT)).containsEntry("qualified", true);
    assertThat(rung(report, MouseVideoReadiness.REVIEW_PROTOCOL)).containsEntry("qualified", true);
    assertThat(rung(report, MouseVideoReadiness.CURSOR_TRACKING)).containsEntry("qualified", false);
  }

  @Test
  void aRecordedQualificationCannotLiftAFailedLowerRung() throws Exception {
    MouseVideoProperties properties = properties(true);
    properties.setQualifiedCapabilities("media-import");

    // Required launcher missing: executions cannot run, so nothing above "available" holds.
    Map<String, Object> report =
        new MouseVideoReadiness(
                properties, () -> true, () -> true, () -> false, () -> true, () -> true)
            .report();

    assertThat(rung(report, MouseVideoReadiness.MEDIA_IMPORT))
        .containsEntry("available", true)
        .containsEntry("runnable", false)
        .containsEntry("isolated", false)
        .containsEntry("qualified", false)
        .containsEntry("limitedBy", "runnable");
  }

  @Test
  void runnableWithoutTheLauncherIsNotIsolated() throws Exception {
    Map<String, Object> report =
        new MouseVideoReadiness(
                properties(false), () -> true, () -> true, () -> false, () -> true, () -> true)
            .report();

    assertThat(rung(report, MouseVideoReadiness.MEDIA_IMPORT))
        .containsEntry("runnable", true)
        .containsEntry("isolated", false)
        .containsEntry("limitedBy", "isolated");
  }

  @Test
  void youtubeIsNotConfiguredUntilEnabledWithADownloader() throws Exception {
    Map<String, Object> report =
        new MouseVideoReadiness(
                properties(true), () -> true, () -> true, () -> true, () -> true, () -> true)
            .report();

    assertThat(rung(report, MouseVideoReadiness.YOUTUBE_ACQUISITION))
        .containsEntry("configured", false)
        .containsEntry("limitedBy", "configured");
  }
}
