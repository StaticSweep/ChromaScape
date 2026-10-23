package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Real-tool derivative coverage: generates a small clip with ffmpeg, then proves the bounded frame
 * index carries real named PTS/timebase/key fields, and that cancellation terminates the owned root
 * and descendant processes instead of abandoning them.
 */
class MouseVideoDerivativeWorkerTest {

  @TempDir Path root;

  private MouseVideoProperties properties;
  private MouseVideoDerivativeWorker worker;
  private MouseVideoByteStore byteStore;

  @BeforeEach
  void setUp() throws IOException {
    properties = new MouseVideoProperties();
    properties.setDirectory(root.resolve("store").toString());
    properties.setFfmpeg("ffmpeg");
    properties.setFfprobe("ffprobe");
    properties.setProxyMaxHeight(240);
    properties.setMaxIndexedFrames(1000L);
    properties.setProbeBudgetSeconds(30L);
    properties.setAnalysisBudgetSeconds(60L);
    byteStore = new MouseVideoByteStore(properties);
    worker =
        new MouseVideoDerivativeWorker(
            properties, byteStore, new MouseVideoProcessRunner(properties));
  }

  @Test
  void derivesFrameIndexWithCorrectNamedTimestamps() throws Exception {
    assumeTrue(worker.ffmpegAvailable(), "ffmpeg is required for derivative qualification");
    Path media = generateClip("clip.mp4", 1);

    MouseVideoDerivativeWorker.Derivatives derivatives =
        worker.derive(root.resolve("workspace"), media, new MouseVideoCancellation());

    Set<String> roles =
        derivatives.artifacts().stream()
            .map(MouseVideoDerivativeWorker.DerivedArtifact::role)
            .collect(Collectors.toSet());
    assertThat(roles)
        .contains(
            MouseVideoArtifactRef.ROLE_FRAME_INDEX,
            MouseVideoArtifactRef.ROLE_THUMBNAIL,
            MouseVideoArtifactRef.ROLE_PROXY);
    assertThat(derivatives.indexedFrames()).isEqualTo(10L);
    assertThat(derivatives.hasProxy()).isTrue();
    assertThat(derivatives.hasThumbnail()).isTrue();
    for (MouseVideoDerivativeWorker.DerivedArtifact artifact : derivatives.artifacts()) {
      assertThat(byteStore.verify(artifact.checksum())).isTrue();
    }

    MouseVideoDerivativeWorker.DerivedArtifact index =
        derivatives.artifacts().stream()
            .filter(artifact -> MouseVideoArtifactRef.ROLE_FRAME_INDEX.equals(artifact.role()))
            .findFirst()
            .orElseThrow();
    Path indexFile = byteStore.locate(index.checksum()).orElseThrow();
    JsonNode document = new ObjectMapper().readTree(indexFile.toFile());
    assertThat(document.path("schema").asText()).isEqualTo("frame-index/v2");
    assertThat(document.path("complete").asBoolean()).isTrue();
    assertThat(document.path("timeBase").asText()).isNotBlank();

    JsonNode frames = document.path("frames");
    assertThat(frames.isArray()).isTrue();
    assertThat(frames.size()).isEqualTo(10);
    assertThat(frames.get(0).path("pts").isIntegralNumber()).isTrue();
    assertThat(frames.get(0).path("pts").asLong()).isZero();
    assertThat(frames.get(0).path("ptsTime").asDouble()).isZero();
    assertThat(frames.get(0).path("key").asBoolean()).isTrue();
    assertThat(frames.get(1).path("ptsTime").asDouble()).isEqualTo(0.1d);
    assertThat(frames.get(1).path("key").asBoolean()).isFalse();
    // The old positional parser reported the key-frame flag (1, 0, 0, ...) as the timestamp.
    assertThat(frames.get(1).path("pts").asLong()).isNotEqualTo(1L);
    assertThat(frames.get(2).path("ptsTime").asDouble()).isEqualTo(0.2d);
  }

  @Test
  void frameIndexRunsUnderTheAnalysisBudgetNotTheProbeBudget() throws Exception {
    assumeTrue(worker.ffmpegAvailable(), "ffmpeg is required for derivative qualification");
    Path media = generateClip("clip.mp4", 1);
    // A 1 ms probe budget would time out any full-stream frame listing.
    properties.setProbeBudgetSeconds(0L);

    MouseVideoDerivativeWorker.Derivatives derivatives =
        worker.derive(root.resolve("workspace"), media, new MouseVideoCancellation());

    assertThat(derivatives.indexedFrames()).isEqualTo(10L);
  }

  @Test
  void cancellationStopsOwnedRootAndDescendantProcesses() throws Exception {
    assumeTrue(worker.ffmpegAvailable(), "ffmpeg is required for derivative qualification");
    Path workspace = root.resolve("cancel-workspace");
    Files.createDirectories(workspace);
    Path fakeFfmpeg = workspace.resolve("fake-ffmpeg.sh");
    Files.writeString(
        fakeFfmpeg,
        "#!/bin/sh\n"
            + "if [ \"$1\" = \"-version\" ]; then echo fake; exit 0; fi\n"
            + "echo $$ > \""
            + workspace.resolve("root.pid")
            + "\"\n"
            + "sleep 300 &\n"
            + "child=$!\n"
            + "echo $child > \""
            + workspace.resolve("child.pid")
            + "\"\n"
            + "wait $child\n");
    Files.setPosixFilePermissions(fakeFfmpeg, PosixFilePermissions.fromString("rwx------"));
    properties.setFfmpeg(fakeFfmpeg.toString());
    properties.setAnalysisBudgetSeconds(300L);
    worker =
        new MouseVideoDerivativeWorker(
            properties, byteStore, new MouseVideoProcessRunner(properties));

    Path media = generateClip("cancel.mp4", 1);
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread workerThread =
        new Thread(
            () -> {
              try {
                worker.derive(workspace, media, cancellation);
              } catch (Throwable throwable) {
                failure.set(throwable);
              }
            },
            "derivative-cancel-test");
    workerThread.start();

    Path rootPid = workspace.resolve("root.pid");
    Path childPid = workspace.resolve("child.pid");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while ((!Files.isRegularFile(rootPid) || !Files.isRegularFile(childPid))
        && System.nanoTime() < deadline) {
      Thread.sleep(20L);
    }
    assumeTrue(Files.isRegularFile(rootPid), "fake ffmpeg did not start in time");
    long rootHandle = Long.parseLong(Files.readString(rootPid).trim());
    long childHandle = Long.parseLong(Files.readString(childPid).trim());

    cancellation.requestCancel();
    workerThread.join(TimeUnit.SECONDS.toMillis(15));
    assertThat(workerThread.isAlive()).isFalse();
    assertThat(failure.get()).isInstanceOf(InterruptedException.class);
    assertThat(isAlive(rootHandle)).isFalse();
    assertThat(isAlive(childHandle)).isFalse();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "0/0",
        "1/0",
        "0/1000",
        "-1/1000",
        "1/-1000",
        "garbage",
        "",
        "1",
        "1/2/3",
        "1.0/2",
        "1/9223372036854775808"
      })
  void invalidTimeBaseNeverProducesACompleteFrameIndex(String timeBase) throws Exception {
    JsonNode index =
        indexFromOutput(
            timeBase, "key_frame=1|best_effort_timestamp=0|best_effort_timestamp_time=0.0");
    assertThat(index.path("complete").asBoolean()).isFalse();
    assertThat(index.path("timeBase").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "1e9999", "0x1.0p0", "bad"})
  void nonFiniteOrMalformedTimingIsIncompleteAndSerializedAsJsonNull(String time) throws Exception {
    JsonNode index =
        indexFromOutput(
            "1/1000", "key_frame=1|best_effort_timestamp=0|best_effort_timestamp_time=" + time);
    assertThat(index.path("complete").asBoolean()).isFalse();
    assertThat(index.path("frames").get(0).path("ptsTime").isNull()).isTrue();
  }

  @Test
  void nonIntegralPtsIsIncompleteAndSerializedAsJsonNull() throws Exception {
    JsonNode index =
        indexFromOutput(
            "1/1000", "key_frame=1|best_effort_timestamp=1.5|best_effort_timestamp_time=0.0015");
    assertThat(index.path("complete").asBoolean()).isFalse();
    assertThat(index.path("frames").get(0).path("pts").isNull()).isTrue();
  }

  @Test
  void finiteNamedTimingWithPositiveRationalTimeBaseRemainsComplete() throws Exception {
    JsonNode index =
        indexFromOutput(
            "1/90000",
            "key_frame=1|best_effort_timestamp=9000|best_effort_timestamp_time=0.1|duration_time=0.04");
    assertThat(index.path("complete").asBoolean()).isTrue();
    assertThat(index.path("frames").get(0).path("pts").asLong()).isEqualTo(9000L);
  }

  private JsonNode indexFromOutput(String timeBase, String frame) throws Exception {
    MouseVideoProcessRunner fixture = mock(MouseVideoProcessRunner.class);
    when(fixture.run(anyList(), anyLong(), any(), any(), anyInt()))
        .thenAnswer(
            invocation -> {
              MouseVideoProcessRunner.LineConsumer consumer = invocation.getArgument(3);
              consumer.accept(frame);
              if (timeBase != null) {
                consumer.accept("time_base=" + timeBase);
              }
              return new MouseVideoProcessRunner.Result(
                  0, "", "", false, false, true, false, false, false);
            });
    MouseVideoDerivativeWorker indexed =
        new MouseVideoDerivativeWorker(properties, byteStore, fixture);
    Path output =
        ReflectionTestUtils.invokeMethod(
            indexed,
            "deriveFrameIndex",
            root,
            root.resolve("fixture.mp4"),
            new MouseVideoCancellation());
    return new ObjectMapper().readTree(output.toFile());
  }

  private Path generateClip(String name, int seconds) throws Exception {
    Path media = root.resolve(name);
    Process generation =
        new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=160x120:rate=10",
                "-t",
                Integer.toString(seconds),
                "-pix_fmt",
                "yuv420p",
                media.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(generation.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(generation.exitValue()).isZero();
    assertThat(Files.size(media)).isGreaterThan(0L);
    return media;
  }

  private static boolean isAlive(long pid) {
    Optional<ProcessHandle> handle = ProcessHandle.of(pid);
    return handle.map(ProcessHandle::isAlive).orElse(false);
  }
}
