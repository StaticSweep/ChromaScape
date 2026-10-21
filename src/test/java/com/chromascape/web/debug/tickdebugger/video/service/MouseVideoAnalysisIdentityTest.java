package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Queued analyses execute only the configuration, template and toolchain they were scheduled with.
 */
class MouseVideoAnalysisIdentityTest {

  @TempDir Path root;

  private MouseVideoProperties properties;
  private MouseVideoAnalysisIdentity identity;
  private Path template;

  @BeforeEach
  void setUp() throws Exception {
    properties = new MouseVideoProperties();
    template = root.resolve("cursor.png");
    Files.write(template, new byte[] {1, 2, 3});
    properties.setVisualCursorTemplate(template.toString());
    identity = new MouseVideoAnalysisIdentity(properties, new ObjectMapper());
  }

  private MouseVideoAnalysis scheduled() {
    String templateChecksum = identity.cursorTemplateChecksum();
    MouseVideoAnalysis analysis =
        new MouseVideoAnalysis(
            UUID.randomUUID(),
            "owner",
            "sha256:" + "a".repeat(64),
            "visual/v1",
            null,
            identity.configDigest("visual/v1", null, "video/mp4", templateChecksum),
            identity.toolchainDigest());
    analysis.setTemplateChecksum(templateChecksum);
    return analysis;
  }

  @Test
  void unchangedConfigurationExecutes() {
    MouseVideoAnalysis analysis = scheduled();
    assertThatCode(() -> identity.requireUnchanged(analysis, "video/mp4"))
        .doesNotThrowAnyException();
  }

  @Test
  void templateReplacedAtTheSamePathAfterSchedulingFailsClosed() throws Exception {
    MouseVideoAnalysis analysis = scheduled();
    Files.write(template, new byte[] {9, 8, 7, 6});
    assertIdentityChanged(analysis, "video/mp4");
  }

  @Test
  void outputAffectingSettingChangedAfterSchedulingFailsClosed() {
    MouseVideoAnalysis analysis = scheduled();
    properties.setVisualSampleFps(properties.getVisualSampleFps() + 1.0d);
    assertIdentityChanged(analysis, "video/mp4");
  }

  @Test
  void cursorHotspotChangedAfterSchedulingFailsClosed() {
    MouseVideoAnalysis analysis = scheduled();
    properties.setVisualCursorHotspotX(properties.getVisualCursorHotspotX() + 3);
    assertIdentityChanged(analysis, "video/mp4");
  }

  @Test
  void executableContentChangedAfterSchedulingFailsClosed() throws Exception {
    Path ffmpeg = root.resolve("ffmpeg");
    Files.write(ffmpeg, new byte[] {0x7f, 'E', 'L', 'F'});
    properties.setFfmpeg(ffmpeg.toString());
    MouseVideoAnalysis analysis = scheduled();
    Files.write(ffmpeg, new byte[] {0x7f, 'E', 'L', 'F', 2});
    assertIdentityChanged(analysis, "video/mp4");
  }

  @Test
  void templateRemovedAfterSchedulingFailsClosed() throws Exception {
    MouseVideoAnalysis analysis = scheduled();
    Files.delete(template);
    assertIdentityChanged(analysis, "video/mp4");
  }

  @Test
  void templateContentIsPartOfTheConfigDigest() throws Exception {
    String before = identity.configDigest("visual/v1", null, "video/mp4");
    Files.write(template, new byte[] {4, 5, 6});
    assertThat(identity.configDigest("visual/v1", null, "video/mp4")).isNotEqualTo(before);
  }

  private void assertIdentityChanged(MouseVideoAnalysis analysis, String mediaType) {
    assertThatThrownBy(() -> identity.requireUnchanged(analysis, mediaType))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_IDENTITY_CHANGED);
  }
}
