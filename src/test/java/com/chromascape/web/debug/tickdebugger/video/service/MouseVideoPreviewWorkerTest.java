package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.VideoProbeResult;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-ffmpeg proof that an exact preview is selected by decode ordinal and that the published PNG
 * carries verifiable bytes and source display dimensions.
 */
class MouseVideoPreviewWorkerTest {

  @TempDir Path root;

  private MouseVideoProperties properties;
  private MouseVideoByteStore byteStore;
  private MouseVideoProbeWorker probeWorker;
  private MouseVideoPreviewWorker worker;

  @BeforeEach
  void setUp() throws IOException {
    properties = new MouseVideoProperties();
    properties.setDirectory(root.resolve("store").toString());
    properties.setFfmpeg("ffmpeg");
    properties.setFfprobe("ffprobe");
    properties.setAnalysisBudgetSeconds(120L);
    byteStore = new MouseVideoByteStore(properties);
    probeWorker = mock(MouseVideoProbeWorker.class);
    worker =
        new MouseVideoPreviewWorker(
            properties, new MouseVideoProcessRunner(properties), probeWorker, byteStore);
  }

  @Test
  void extractsTheRequestedDecodeOrdinalAndPublishesVerifiableBytes() throws Exception {
    Path media = generateClip();
    when(probeWorker.probe(any(), any(), any()))
        .thenReturn(
            new VideoProbeResult(
                true,
                true,
                "mp4",
                2.0d,
                160,
                90,
                10.0d,
                20L,
                "h264",
                0,
                0,
                "",
                "",
                new ObjectMapper().createObjectNode()));
    MouseVideoFramePreview preview =
        new MouseVideoFramePreview(
            "owner",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            3L,
            "3",
            "0.3",
            "1",
            "10",
            MouseVideoFramePreview.METHOD_FRAME_PNG,
            320,
            180);
    Path workspace = root.resolve("workspace");

    MouseVideoPreviewFinalizationService.PreviewBytes bytes =
        worker.extract(workspace, media, preview, new MouseVideoCancellation());

    assertThat(bytes.mediaType()).isEqualTo("image/png");
    assertThat(bytes.sourceWidth()).isEqualTo(160);
    assertThat(bytes.sourceHeight()).isEqualTo(90);
    assertThat(bytes.outputWidth()).isLessThanOrEqualTo(320);
    assertThat(bytes.outputHeight()).isLessThanOrEqualTo(180);
    assertThat(byteStore.verify(bytes.checksum())).isTrue();
    Path published = byteStore.locate(bytes.checksum()).orElseThrow();
    BufferedImage image = ImageIO.read(published.toFile());
    assertThat(image.getWidth()).isEqualTo(bytes.outputWidth());
    assertThat(image.getHeight()).isEqualTo(bytes.outputHeight());
    assertThat(workspace.resolve("frame-3.png")).isRegularFile();
  }

  @Test
  void selectionFilterUsesTheDecodeOrdinalNotATimeSeek() {
    assertThat(MouseVideoPreviewWorker.selectionFilter(17L, 640, 360))
        .contains("select=eq(n\\,17)")
        .doesNotContain("-ss");
  }

  private Path generateClip() throws Exception {
    Path clip = root.resolve("clip.mp4");
    Process process =
        new ProcessBuilder(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=160x90:rate=10:duration=2",
                "-pix_fmt",
                "yuv420p",
                clip.toString())
            .redirectErrorStream(true)
            .start();
    assumeTrue(
        process.waitFor(90, TimeUnit.SECONDS) && process.exitValue() == 0,
        "ffmpeg is required for exact-preview qualification");
    return clip;
  }
}
