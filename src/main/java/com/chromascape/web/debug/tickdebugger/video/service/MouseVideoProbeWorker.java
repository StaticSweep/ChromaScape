package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.VideoProbeResult;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Fixed server-owned probe worker. The packaged {@code probe.py} is frozen and hash-verified before
 * every run; the interpreter, ffprobe binary and wall-clock budget are server configuration, never
 * caller input. Owned process descendants are always cleaned up, and cleanup is confirmed rather
 * than assumed.
 */
@Component
public class MouseVideoProbeWorker {

  private static final String SCRIPT_RESOURCE = "/mouse-video/probe.py";
  private static final String SCRIPT_NAME = "probe.py";
  private static final String MANIFEST_NAME = "probe.code.json";

  private static final int MAX_PROBE_LOG_BYTES = 2 * 1024 * 1024;

  private final MouseVideoProperties properties;
  private final MouseVideoByteStore byteStore;
  private final MouseVideoProcessRunner runner;
  private final ObjectMapper mapper;
  private final AtomicReference<Boolean> ffprobeStatus = new AtomicReference<>();

  public MouseVideoProbeWorker(
      MouseVideoProperties properties,
      MouseVideoByteStore byteStore,
      MouseVideoProcessRunner runner) {
    this.properties = properties;
    this.byteStore = byteStore;
    this.runner = runner;
    this.mapper = new ObjectMapper();
  }

  /** True when a Python interpreter is configured; ffprobe presence is reported separately. */
  public boolean pythonConfigured() {
    return properties.getPython() != null && !properties.getPython().isBlank();
  }

  /** Cached availability check for the configured ffprobe binary. */
  public boolean ffprobeAvailable() {
    Boolean cached = ffprobeStatus.get();
    if (cached != null) {
      return cached;
    }
    boolean available = probeBinary();
    ffprobeStatus.set(available);
    return available;
  }

  private boolean probeBinary() {
    try {
      MouseVideoProcessRunner.Result result =
          runner.run(
              java.util.List.of(properties.getFfprobe(), "-version"),
              5_000L,
              new MouseVideoCancellation(),
              null,
              64 * 1024);
      return result.exitCode() == 0 && result.outputComplete();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return false;
    } catch (IOException | RuntimeException failure) {
      return false;
    }
  }

  /** Freezes the packaged probe script into the job workspace and returns its digest. */
  public String freeze(Path workspace) throws IOException {
    Files.createDirectories(workspace);
    String checksum;
    try (InputStream source = getClass().getResourceAsStream(SCRIPT_RESOURCE)) {
      if (source == null) {
        throw new IOException("Packaged probe worker is missing");
      }
      Path target = workspace.resolve(SCRIPT_NAME);
      if (Files.exists(target)) {
        Files.delete(target);
      }
      Files.copy(source, target);
      checksum = byteStore.digestFile(target, 4L * 1024 * 1024);
    }
    String manifest =
        mapper.writeValueAsString(java.util.Map.of("version", 1, SCRIPT_NAME, checksum));
    Files.writeString(
        workspace.resolve(MANIFEST_NAME),
        manifest,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
    return checksum;
  }

  /**
   * Runs the probe against a local media file and parses the canonical result. Fails with a typed
   * {@link MouseVideoException} when the worker cannot produce trustworthy facts.
   */
  public VideoProbeResult probe(Path workspace, Path media)
      throws IOException, InterruptedException {
    return probe(workspace, media, new MouseVideoCancellation());
  }

  /**
   * Runs the probe against a local media file with a cooperative cancellation token. A cancellation
   * terminates only this worker's own process descendants and confirms cleanup before returning.
   */
  public VideoProbeResult probe(Path workspace, Path media, MouseVideoCancellation cancellation)
      throws IOException, InterruptedException {
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Probe cancelled before process start");
    }
    Path script = workspace.resolve(SCRIPT_NAME);
    if (!Files.isRegularFile(script)) {
      freeze(workspace);
    }
    verifyFrozenScript(workspace);

    Path output = workspace.resolve("probe.json");
    Path log = workspace.resolve("probe.log");
    MouseVideoProcessRunner.Result execution =
        runner.run(
            List.of(
                properties.getPython(),
                script.toString(),
                "--ffprobe",
                properties.getFfprobe(),
                "--input",
                media.toAbsolutePath().toString(),
                "--output",
                output.toAbsolutePath().toString()),
            TimeUnit.SECONDS.toMillis(properties.getProbeBudgetSeconds()),
            cancellation,
            null,
            MAX_PROBE_LOG_BYTES);
    String executionLog = execution.stdout() + execution.stderr();
    if (!executionLog.isEmpty()) {
      Files.writeString(log, executionLog, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    if (!execution.outputComplete()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED,
          "Probe output did not drain completely; refusing incomplete evidence");
    }

    if (!Files.isRegularFile(output)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Probe produced no result for this media");
    }
    JsonNode document = mapper.readTree(output.toFile());
    boolean ok = document.path("ok").asBoolean(false);
    if (!ok) {
      String code = document.path("code").asText(MouseVideoException.CODE_DECODER_FAILED);
      String message = document.path("message").asText("The media could not be probed");
      return VideoProbeResult.unavailable(code, message, document);
    }
    return new VideoProbeResult(
        true,
        document.path("hasVideo").asBoolean(false),
        document.path("container").asText(""),
        document.path("durationSeconds").asDouble(0.0d),
        document.path("width").asInt(0),
        document.path("height").asInt(0),
        document.path("fps").asDouble(0.0d),
        document.path("frameCount").asLong(0L),
        document.path("videoCodec").asText(""),
        document.path("rotationDegrees").asInt(0),
        document.path("audioStreams").asInt(0),
        null,
        null,
        document);
  }

  private void verifyFrozenScript(Path workspace) throws IOException {
    Path manifest = workspace.resolve(MANIFEST_NAME);
    if (!Files.isRegularFile(manifest)) {
      return;
    }
    JsonNode document = mapper.readTree(manifest.toFile());
    String expected = document.path(SCRIPT_NAME).asText("");
    String actual = byteStore.digestFile(workspace.resolve(SCRIPT_NAME), 4L * 1024 * 1024);
    if (!expected.equals(actual)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CHECKSUM_MISMATCH, "Frozen probe code hash mismatch");
    }
  }
}
