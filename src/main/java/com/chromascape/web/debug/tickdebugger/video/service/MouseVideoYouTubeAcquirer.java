package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Pinned, constrained YouTube acquisition. The downloader executable and its expected SHA-256 are
 * server configuration; caller executable selection, playlists, live streams, cookies, extra
 * plugins/config, and arbitrary flags are refused by construction. Provider failures are surfaced
 * truthfully and a successful import records the exact acquired media hash.
 */
@Component
public class MouseVideoYouTubeAcquirer {

  private static final long MAX_PINNED_TOOL_BYTES = 64L * 1024 * 1024;

  private final MouseVideoProperties properties;
  private final MouseVideoByteStore byteStore;
  private final MouseVideoProcessRunner runner;

  public MouseVideoYouTubeAcquirer(
      MouseVideoProperties properties,
      MouseVideoByteStore byteStore,
      MouseVideoProcessRunner runner) {
    this.properties = properties;
    this.byteStore = byteStore;
    this.runner = runner;
  }

  public record Acquired(Path mediaPath, String checksum, long sizeBytes) {}

  public boolean configured() {
    String downloader = properties.getYoutubeDownloader();
    return downloader != null && !downloader.isBlank() && Files.isRegularFile(Path.of(downloader));
  }

  private void verifyPin() {
    String expected = properties.getYoutubeDownloaderSha256();
    if (expected == null || expected.isBlank()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_TOOL_UNAVAILABLE,
          "The YouTube downloader is not pinned to a verified SHA-256");
    }
    String actual;
    try {
      actual =
          byteStore.digestFile(Path.of(properties.getYoutubeDownloader()), MAX_PINNED_TOOL_BYTES);
    } catch (IOException | RuntimeException failure) {
      throw new MouseVideoException(
          MouseVideoException.CODE_TOOL_UNAVAILABLE,
          "The configured YouTube downloader could not be verified",
          failure);
    }
    String normalized = expected.startsWith("sha256:") ? expected : "sha256:" + expected;
    if (!normalized.equalsIgnoreCase(actual)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_TOOL_UNAVAILABLE,
          "The configured YouTube downloader does not match its pinned SHA-256");
    }
  }

  public Acquired acquire(
      Path workspace, String canonicalUrl, String providerId, MouseVideoCancellation cancellation)
      throws IOException, InterruptedException {
    if (!configured()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_TOOL_UNAVAILABLE,
          "YouTube acquisition requires a pinned downloader executable");
    }
    verifyPin();
    if (cancellation.isCancelled()) {
      throw new InterruptedException("Acquisition cancelled before start");
    }
    Path target = workspace.resolve("acquired.%(ext)s");
    List<String> command =
        List.of(
            properties.getYoutubeDownloader(),
            "--no-playlist",
            "--no-progress",
            "--no-cookies",
            "--no-config",
            "--no-exec",
            "--restrict-filenames",
            "--max-filesize",
            Long.toString(properties.getMaxUploadBytes()),
            "--merge-output-format",
            "mp4",
            "-o",
            target.toString(),
            "--",
            canonicalUrl);
    MouseVideoProcessRunner.Result result =
        runner.run(
            command,
            TimeUnit.SECONDS.toMillis(properties.getAcquireBudgetSeconds()),
            cancellation,
            null,
            64 * 1024);
    if (result.exitCode() != 0) {
      throw mapProviderFailure(result.stdout() + result.stderr());
    }
    Path produced = locateProduced(workspace);
    if (produced == null) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "The provider returned no downloadable media for this video");
    }
    if (Files.size(produced) > properties.getMaxUploadBytes()) {
      Files.deleteIfExists(produced);
      throw new MouseVideoException(
          MouseVideoException.CODE_MEDIA_LIMIT, "Acquired media exceeds the configured limit");
    }
    MouseVideoByteStore.StagedArtifact staged =
        byteStore.stage(Files.newInputStream(produced), properties.getMaxUploadBytes());
    byteStore.publish(staged);
    return new Acquired(produced, staged.checksum(), staged.sizeBytes());
  }

  private Path locateProduced(Path workspace) throws IOException {
    try (var entries = Files.list(workspace)) {
      return entries
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().startsWith("acquired."))
          .filter(path -> !path.getFileName().toString().endsWith(".part"))
          .findFirst()
          .orElse(null);
    }
  }

  private MouseVideoException mapProviderFailure(String output) {
    String message = output == null ? "" : output.toLowerCase(Locale.ROOT);
    if (message.contains("private")
        || message.contains("sign in")
        || message.contains("login")
        || message.contains("members-only")) {
      return new MouseVideoException(
          MouseVideoException.CODE_SOURCE_AUTH_REQUIRED,
          "The provider requires authentication for this video");
    }
    if (message.contains("rate") && message.contains("limit")
        || message.contains("429")
        || message.contains("too many requests")) {
      return new MouseVideoException(
          MouseVideoException.CODE_SOURCE_RATE_LIMITED,
          "The provider rate-limited acquisition; retry later",
          true);
    }
    if (message.contains("not available")
        || message.contains("removed")
        || message.contains("404")) {
      return new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "The provider reports this video unavailable");
    }
    return new MouseVideoException(
        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
        "The provider failed the download without a recognised outcome",
        true);
  }
}
