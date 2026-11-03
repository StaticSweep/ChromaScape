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

  @Test
  void omittedPackResolvesToTheConfiguredPackAndSharesItsIdentity() {
    properties.setVisualCursorTemplatePackId("clip-arrow/v1");
    assertThat(identity.resolveTemplatePackId(null)).isEqualTo("clip-arrow/v1");
    assertThat(identity.resolveTemplatePackId("  ")).isEqualTo("clip-arrow/v1");
    assertThat(identity.resolveTemplatePackId("clip-arrow/v1")).isEqualTo("clip-arrow/v1");
    assertThat(
            identity.configDigest("visual/v1", identity.resolveTemplatePackId(null), "video/mp4"))
        .isEqualTo(
            identity.configDigest(
                "visual/v1", identity.resolveTemplatePackId("clip-arrow/v1"), "video/mp4"));
  }

  @Test
  void unsupportedPackIsRefusedInsteadOfImplyingOtherBytes() {
    assertThatThrownBy(() -> identity.resolveTemplatePackId("another-pack"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);
    properties.setVisualCursorTemplatePackId("clip-arrow/v1");
    assertThatThrownBy(() -> identity.resolveTemplatePackId("another-pack"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);
  }

  @Test
  void pinnedTemplateDigestMustMatchTheProvisionedBytes() throws Exception {
    String actual = identity.templateStatus().checksum();
    properties.setVisualCursorTemplateSha256(actual);
    assertThat(identity.requireCursorTemplateChecksum()).isEqualTo("sha256:" + actual);

    properties.setVisualCursorTemplateSha256("b".repeat(64));
    assertThatThrownBy(() -> identity.requireCursorTemplateChecksum())
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CHECKSUM_MISMATCH);
    assertThat(identity.cursorTemplateChecksum()).isNull();
  }

  @Test
  void pinnedTemplateThatIsMissingFailsProvisioning() throws Exception {
    properties.setVisualCursorTemplateSha256("c".repeat(64));
    Files.delete(template);
    assertThatThrownBy(() -> identity.requireCursorTemplateChecksum())
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_SOURCE_UNAVAILABLE);
  }

  private void assertIdentityChanged(MouseVideoAnalysis analysis, String mediaType) {
    assertThatThrownBy(() -> identity.requireUnchanged(analysis, mediaType))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_IDENTITY_CHANGED);
  }
}
