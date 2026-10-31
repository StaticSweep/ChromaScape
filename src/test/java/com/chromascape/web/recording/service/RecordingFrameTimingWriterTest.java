package com.chromascape.web.recording.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingFrameTimingWriterTest {

  @TempDir Path directory;

  @Test
  void writesHeaderAndOneLinePerEncodedFrameNextToTheRecording() throws Exception {
    Path recording = directory.resolve("recording_ab12cd34.mp4");
    RecordingFrameTimingWriter writer =
        RecordingFrameTimingWriter.open(
            recording, "recording_ab12cd34", 1_700_000_000_000L, 5L, 30);
    assertThat(writer).isNotNull();
    writer.frame(0, 5L, false, 0L);
    writer.frame(1, 33_000_005L, true, 66_667L);
    writer.close();
    writer.frame(2, 66_000_005L, false, 100_000L);

    assertThat(writer.file()).isEqualTo(directory.resolve("recording_ab12cd34.frames.jsonl"));
    List<String> lines = Files.readAllLines(writer.file(), StandardCharsets.UTF_8);
    assertThat(lines).hasSize(3);
    assertThat(lines.get(0))
        .contains("\"schema\":\"" + RecordingFrameTimingWriter.SCHEMA + "\"")
        .contains("\"recordingId\":\"recording_ab12cd34\"")
        .contains("\"startEpochMs\":1700000000000,\"startNanos\":5,\"fps\":30");
    assertThat(lines.get(1)).isEqualTo("{\"frame\":0,\"captureNanos\":5,\"duplicate\":false,\"ptsMicros\":0}");
    assertThat(lines.get(2))
        .isEqualTo(
            "{\"frame\":1,\"captureNanos\":33000005,\"duplicate\":true,\"ptsMicros\":66667}");
  }

  @Test
  void unsafeRecordingIdCharactersAreNotWrittenRaw() throws Exception {
    RecordingFrameTimingWriter writer =
        RecordingFrameTimingWriter.open(directory.resolve("r.mp4"), "a\"b\\c", 1L, 2L, 15);
    writer.close();
    assertThat(Files.readString(writer.file())).contains("\"recordingId\":\"a_b_c\"");
  }

  @Test
  void returnsNullWhenTheSidecarCannotBeCreated() {
    Path missing = directory.resolve("no-such-dir").resolve("r.mp4");
    assertThat(RecordingFrameTimingWriter.open(missing, "r", 1L, 2L, 15)).isNull();
  }
}
