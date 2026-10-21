package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.VideoProbeResult;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Extracts exactly one source frame by its frozen decode ordinal. Selection is by decoded frame
 * ordinal, never by an approximate seek: ffmpeg's {@code select=eq(n,ordinal)} filter decodes from
 * the start of the stream, applies the source display transform, then emits one PNG. The pinned
 * index checksum/PTS identify the frame independently of any browser proxy seeking.
 */
@Component
public class MouseVideoPreviewWorker {

  private final MouseVideoProperties properties;
  private final MouseVideoProcessRunner runner;
  private final MouseVideoProbeWorker probeWorker;
  private final MouseVideoByteStore byteStore;

  public MouseVideoPreviewWorker(
      MouseVideoProperties properties,
      MouseVideoProcessRunner runner,
      MouseVideoProbeWorker probeWorker,
      MouseVideoByteStore byteStore) {
    this.properties = properties;
    this.runner = runner;
    this.probeWorker = probeWorker;
    this.byteStore = byteStore;
  }

  /** Extracts, verifies and publishes one preview PNG and returns its published identity. */
  public MouseVideoPreviewFinalizationService.PreviewBytes extract(
      Path workspace,
      Path media,
      MouseVideoFramePreview preview,
      MouseVideoCancellation cancellation)
      throws IOException, InterruptedException {
    Files.createDirectories(workspace);
    if (cancellation.isCancelled()) {
      throw new InterruptedException("Preview extraction cancelled");
    }
    VideoProbeResult probe = probeWorker.probe(workspace, media, cancellation);
    if (!probe.ok() || !probe.hasVideo()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Source could not be probed for preview");
    }
    int sourceWidth = displayWidth(probe);
    int sourceHeight = displayHeight(probe);
    Path output = workspace.resolve("frame-" + preview.getFrameOrdinal() + ".png");
    Files.deleteIfExists(output);
    String filter =
        "select=eq(n\\,"
            + preview.getFrameOrdinal()
            + "),scale='min(iw,"
            + preview.getMaxWidth()
            + ")':'min(ih,"
            + preview.getMaxHeight()
            + ")':flags=lanczos:force_original_aspect_ratio=decrease";
    MouseVideoProcessRunner.Result result =
        runner.run(
            List.of(
                properties.getFfmpeg(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-nostdin",
                "-y",
                "-threads",
                "2",
                "-filter_threads",
                "1",
                "-i",
                media.toAbsolutePath().toString(),
                "-map",
                "0:v:0",
                "-vf",
                filter,
                "-frames:v",
                "1",
                "-f",
                "image2",
                "-c:v",
                "png",
                output.toAbsolutePath().toString()),
            TimeUnit.SECONDS.toMillis(properties.getAnalysisBudgetSeconds()),
            cancellation,
            null,
            0);
    if (result.exitCode() != 0 || !result.outputComplete()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED,
          "Exact frame extraction failed (exit " + result.exitCode() + ")");
    }
    if (!Files.isRegularFile(output) || Files.size(output) == 0L) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Exact frame extraction produced no bytes");
    }
    BufferedImage image = ImageIO.read(output.toFile());
    if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Exact frame is not a readable image");
    }
    MouseVideoByteStore.StagedArtifact staged =
        byteStore.stage(Files.newInputStream(output), Math.max(1L, Files.size(output)));
    byteStore.publish(staged);
    String crop = "{\"x\":0,\"y\":0,\"width\":" + sourceWidth + ",\"height\":" + sourceHeight + "}";
    String transform =
        "{\"scale\":\"bounded\",\"autorotate\":true,\"rotationDegrees\":"
            + probe.rotationDegrees()
            + ",\"sourceDisplayWidth\":"
            + sourceWidth
            + ",\"sourceDisplayHeight\":"
            + sourceHeight
            + ",\"outputWidth\":"
            + image.getWidth()
            + ",\"outputHeight\":"
            + image.getHeight()
            + "}";
    return new MouseVideoPreviewFinalizationService.PreviewBytes(
        staged.checksum(),
        staged.sizeBytes(),
        "image/png",
        sourceWidth,
        sourceHeight,
        image.getWidth(),
        image.getHeight(),
        probe.rotationDegrees(),
        crop,
        transform);
  }

  /** Probe dimensions describe coded pixels; a quarter-turn display rotation swaps them. */
  static int displayWidth(VideoProbeResult probe) {
    return quarterTurn(probe.rotationDegrees()) ? probe.height() : probe.width();
  }

  static int displayHeight(VideoProbeResult probe) {
    return quarterTurn(probe.rotationDegrees()) ? probe.width() : probe.height();
  }

  private static boolean quarterTurn(int rotation) {
    int normalized = ((rotation % 360) + 360) % 360;
    return normalized == 90 || normalized == 270;
  }

  /** Package-private helper used by tests to assert the exact-selection filter. */
  static String selectionFilter(long ordinal, int maxWidth, int maxHeight) {
    return "select=eq(n\\,"
        + ordinal
        + "),scale='min(iw,"
        + maxWidth
        + ")':'min(ih,"
        + maxHeight
        + ")':flags=lanczos:force_original_aspect_ratio=decrease";
  }
}
