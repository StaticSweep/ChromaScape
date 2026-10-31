package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Micro6MouseTraceServiceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String HEADER =
      "{\"schema\":\"micro6-mouse-trace/v1\",\"kind\":\"header\",\"pid\":7,"
          + "\"startEpochMs\":1000,\"startNanos\":2000}";
  private static final String FRAME =
      "{\"seq\":%d,\"epochMs\":1001,\"nanos\":%d,\"visible\":true,\"x\":10,\"y\":20,\"trail\":3,"
          + "\"canvas\":[765,503],\"stretched\":false,\"stretchedSize\":[0,0],\"human\":[-1,-1]}";

  @TempDir Path directory;
  private final RecordingService recordings = mock(RecordingService.class);
  private final AtomicLong now = new AtomicLong(1_000_000_000L);
  private Micro6MouseTraceService service;

  @BeforeEach
  void setUp() {
    RecordingConfig config = new RecordingConfig();
    config.setOutputDirectory(directory.toString());
    service = new Micro6MouseTraceService(recordings, config, now::get);
  }

  private static JsonNode batch(String... frames) throws Exception {
    return MAPPER.readTree(
        "{\"header\":" + HEADER + ",\"frames\":[" + String.join(",", frames) + "]}");
  }

  @Test
  void storesSanitizedFramesNextToTheActiveRecordingWithOneHeader() throws Exception {
    when(recordings.isRecording()).thenReturn(true);
    when(recordings.getCurrentRecordingId()).thenReturn("recording_ab12cd34");
    String injected = FRAME.formatted(1, 6).replace("\"trail\":3", "\"trail\":3,\"evil\":\"x\"");

    Micro6MouseTraceService.Result first = service.accept(batch(FRAME.formatted(0, 5), injected));
    Micro6MouseTraceService.Result second = service.accept(batch(FRAME.formatted(2, 7)));

    assertThat(first.recordingId()).isEqualTo("recording_ab12cd34");
    assertThat(first.stored()).isEqualTo(2);
    assertThat(second.stored()).isEqualTo(1);
    List<String> lines =
        Files.readAllLines(directory.resolve("recording_ab12cd34.mouse-trace.jsonl"));
    assertThat(lines).hasSize(4);
    assertThat(lines.get(0))
        .contains("\"kind\":\"header\"")
        .contains("\"pid\":7")
        .contains("\"recordingId\":\"recording_ab12cd34\"");
    assertThat(lines.get(2)).doesNotContain("evil").contains("\"seq\":1");
    assertThat(lines.get(3)).contains("\"seq\":2");
  }

  @Test
  void rejectsMalformedFramesAndBatches() throws Exception {
    when(recordings.isRecording()).thenReturn(true);
    when(recordings.getCurrentRecordingId()).thenReturn("recording_ab12cd34");
    String stringX = FRAME.formatted(0, 5).replace("\"x\":10", "\"x\":\"10\"");

    assertThat(service.accept(batch(stringX)).rejected()).isEqualTo(1);
    assertThatThrownBy(() -> service.accept(MAPPER.readTree("{\"frames\":[]}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.accept(
                    MAPPER.readTree(
                        "{\"header\":" + HEADER.replace("v1", "v9") + ",\"frames\":[]}")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsInFlightFramesBrieflyAfterStopThenDiscards() throws Exception {
    when(recordings.isRecording()).thenReturn(true);
    when(recordings.getCurrentRecordingId()).thenReturn("recording_ab12cd34");
    service.accept(batch(FRAME.formatted(0, 5)));

    when(recordings.isRecording()).thenReturn(false);
    now.addAndGet(Micro6MouseTraceService.STOP_GRACE_NANOS - 1);
    assertThat(service.accept(batch(FRAME.formatted(1, 6))).recordingId())
        .isEqualTo("recording_ab12cd34");
    now.addAndGet(Micro6MouseTraceService.STOP_GRACE_NANOS);
    Micro6MouseTraceService.Result late = service.accept(batch(FRAME.formatted(2, 7)));
    assertThat(late.recordingId()).isNull();
    assertThat(late.stored()).isZero();
  }

  @Test
  void neverUsesUnsafeRecordingIdsAsFileNames() throws Exception {
    when(recordings.isRecording()).thenReturn(true);
    when(recordings.getCurrentRecordingId()).thenReturn("../escape");
    assertThat(service.accept(batch(FRAME.formatted(0, 5))).recordingId()).isNull();
    assertThat(Files.list(directory)).isEmpty();
  }
}
