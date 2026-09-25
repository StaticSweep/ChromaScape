package com.chromascape.web.recording.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.dto.RecordingSessionMetadataUpdateRequest;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingSessionMetadataService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Tests for {@link RecordingController} metadata and playability behaviour. */
@ExtendWith(MockitoExtension.class)
class RecordingControllerMetadataTest {

  @TempDir Path tempDir;

  @Mock private RecordingService recordingService;
  @Mock private RecordingSessionRepository repository;
  @Mock private WindowSelectionService windowSelectionService;
  @Mock private RecordingConfig recordingConfig;
  @Mock private DebugSessionService debugSessionService;

  private RecordingController controller;
  private RecordingSessionMetadataService metadataService;

  @BeforeEach
  void setUp() {
    metadataService =
        new RecordingSessionMetadataService(new com.fasterxml.jackson.databind.ObjectMapper());
    controller =
        new RecordingController(
            recordingService,
            repository,
            windowSelectionService,
            recordingConfig,
            metadataService,
            debugSessionService);
  }

  private RecordingSession createSession(String recordingId) {
    return new RecordingSession(
        recordingId,
        "testAccount",
        "testScript",
        LocalDateTime.of(2025, 6, 15, 12, 0),
        RecordingSession.SessionTrigger.MANUAL,
        null);
  }

  @Test
  void updateMetadata_nameOnly_returns200() {
    RecordingSession session = createSession("rec-1");
    when(repository.findByRecordingId("rec-1")).thenReturn(Optional.of(session));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RecordingSessionMetadataUpdateRequest req = new RecordingSessionMetadataUpdateRequest();
    req.setReplayName("My Custom Name");

    ResponseEntity<?> response = controller.updateMetadata("rec-1", req);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(repository).save(argThat(s -> "My Custom Name".equals(s.getReplayName())));
  }

  @Test
  void updateMetadata_tagsOnly_returns200() {
    RecordingSession session = createSession("rec-2");
    when(repository.findByRecordingId("rec-2")).thenReturn(Optional.of(session));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RecordingSessionMetadataUpdateRequest req = new RecordingSessionMetadataUpdateRequest();
    req.setReplayTags(List.of("bug", "regression"));

    ResponseEntity<?> response = controller.updateMetadata("rec-2", req);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(repository).save(argThat(s -> s.getReplayTagsJson() != null));
  }

  @Test
  void updateMetadata_bothNameAndTags_returns200() {
    RecordingSession session = createSession("rec-3");
    when(repository.findByRecordingId("rec-3")).thenReturn(Optional.of(session));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RecordingSessionMetadataUpdateRequest req = new RecordingSessionMetadataUpdateRequest();
    req.setReplayName("Replay A");
    req.setReplayTags(List.of("tag1"));

    ResponseEntity<?> response = controller.updateMetadata("rec-3", req);

    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void updateMetadata_clearName_returns200() {
    RecordingSession session = createSession("rec-4");
    session.setReplayName("Old Name");
    when(repository.findByRecordingId("rec-4")).thenReturn(Optional.of(session));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RecordingSessionMetadataUpdateRequest req = new RecordingSessionMetadataUpdateRequest();
    req.setReplayName("");

    ResponseEntity<?> response = controller.updateMetadata("rec-4", req);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(repository).save(argThat(s -> s.getReplayName() == null));
  }

  @Test
  void updateMetadata_clearTags_returns200() {
    RecordingSession session = createSession("rec-5");
    session.setReplayTagsJson("[\"old\"]");
    when(repository.findByRecordingId("rec-5")).thenReturn(Optional.of(session));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RecordingSessionMetadataUpdateRequest req = new RecordingSessionMetadataUpdateRequest();
    req.setReplayTags(List.of());

    ResponseEntity<?> response = controller.updateMetadata("rec-5", req);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(repository).save(argThat(s -> s.getReplayTagsJson() == null));
  }

  @Test
  void updateMetadata_notFound_returns404() {
    when(repository.findByRecordingId("missing")).thenReturn(Optional.empty());

    RecordingSessionMetadataUpdateRequest req = new RecordingSessionMetadataUpdateRequest();
    req.setReplayName("test");

    ResponseEntity<?> response = controller.updateMetadata("missing", req);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void updateMetadata_nameTooLong_returns400() {
    RecordingSession session = createSession("rec-6");
    when(repository.findByRecordingId("rec-6")).thenReturn(Optional.of(session));

    RecordingSessionMetadataUpdateRequest req = new RecordingSessionMetadataUpdateRequest();
    req.setReplayName("x".repeat(161));

    ResponseEntity<?> response = controller.updateMetadata("rec-6", req);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, String> body = (Map<String, String>) response.getBody();
    assertTrue(body.containsKey("error"));
  }

  @Test
  void updateMetadata_omittedFields_keepsExisting() {
    RecordingSession session = createSession("rec-7");
    session.setReplayName("Existing Name");
    session.setReplayTagsJson("[\"existing\"]");
    when(repository.findByRecordingId("rec-7")).thenReturn(Optional.of(session));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RecordingSessionMetadataUpdateRequest req = new RecordingSessionMetadataUpdateRequest();

    ResponseEntity<?> response = controller.updateMetadata("rec-7", req);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(repository)
        .save(
            argThat(
                s ->
                    "Existing Name".equals(s.getReplayName())
                        && "[\"existing\"]".equals(s.getReplayTagsJson())));
  }

  @Test
  void listRecordingSessions_marksUnfinishedRecordingsAsNotPlayable() {
    RecordingSession finalized = createSession("rec-finalized");
    finalized.setEndTime(LocalDateTime.of(2025, 6, 15, 13, 0));
    Path finalizedVideo = tempDir.resolve("rec-finalized.mp4");
    assertDoesNotThrow(() -> Files.writeString(finalizedVideo, "video"));
    finalized.setVideoPath(finalizedVideo.toString());

    RecordingSession recording = createSession("rec-recording");

    when(repository.findAllByOrderByStartTimeDesc()).thenReturn(List.of(finalized, recording));
    when(recordingConfig.getOutputDirectory()).thenReturn(tempDir.toString());

    ResponseEntity<List<Map<String, Object>>> response = controller.listRecordingSessions();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<Map<String, Object>> body = response.getBody();
    assertEquals(2, body.size());

    Map<String, Object> finalizedMap = body.get(0);
    assertEquals(true, finalizedMap.get("playable"));
    assertEquals("FINALIZED", finalizedMap.get("status"));

    Map<String, Object> recordingMap = body.get(1);
    assertEquals(false, recordingMap.get("playable"));
    assertEquals("RECORDING", recordingMap.get("status"));
    assertNotNull(recordingMap.get("videoError"));
  }

  @Test
  void listRecordingSessions_marksMissingVideoAsNotPlayable() {
    RecordingSession finalized = createSession("rec-missing-video");
    finalized.setEndTime(LocalDateTime.of(2025, 6, 15, 13, 0));
    finalized.setVideoPath(tempDir.resolve("missing.mp4").toString());

    when(repository.findAllByOrderByStartTimeDesc()).thenReturn(List.of(finalized));
    when(recordingConfig.getOutputDirectory()).thenReturn(tempDir.toString());

    ResponseEntity<List<Map<String, Object>>> response = controller.listRecordingSessions();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, Object> item = response.getBody().get(0);
    assertEquals(false, item.get("playable"));
    assertEquals("MISSING_VIDEO", item.get("status"));
    assertEquals(false, item.get("videoAvailable"));
  }

  @Test
  void listSessions_includesMetadataFields() {
    RecordingSession session = createSession("rec-list");
    session.setReplayName("Custom");
    session.setReplayTagsJson("[\"alpha\"]");
    when(repository.findAllByOrderByStartTimeDesc()).thenReturn(List.of(session));
    when(recordingConfig.getOutputDirectory()).thenReturn(tempDir.toString());

    ResponseEntity<List<Map<String, Object>>> response = controller.listRecordingSessions();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, Object> item = response.getBody().get(0);
    assertEquals("Custom", item.get("replayName"));
    assertEquals(List.of("alpha"), item.get("replayTags"));
    assertEquals("Custom", item.get("displayName"));
  }

  @Test
  void listSessions_nullMetadata_fallsBack() {
    RecordingSession session = createSession("rec-nometa");
    when(repository.findAllByOrderByStartTimeDesc()).thenReturn(List.of(session));
    when(recordingConfig.getOutputDirectory()).thenReturn(tempDir.toString());

    ResponseEntity<List<Map<String, Object>>> response = controller.listRecordingSessions();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, Object> item = response.getBody().get(0);
    assertNull(item.get("replayName"));
    assertEquals(List.of(), item.get("replayTags"));
    assertNotNull(item.get("displayName"));
  }

  @Test
  void getRecording_attemptsOrphanRepairWhenVideoMissing() {
    RecordingSession session = createSession("rec-orphan");
    session.setEndTime(LocalDateTime.of(2025, 6, 15, 13, 0));

    when(repository.findByRecordingId("rec-orphan")).thenReturn(Optional.of(session));
    when(debugSessionService.repairOrphanedRecordingSession("rec-orphan")).thenReturn(true);
    when(recordingConfig.getOutputDirectory()).thenReturn(tempDir.toString());
    assertDoesNotThrow(() -> Files.writeString(tempDir.resolve("rec-orphan.mp4"), "video"));

    ResponseEntity<Map<String, Object>> response = controller.getRecording("rec-orphan");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(debugSessionService).repairOrphanedRecordingSession("rec-orphan");
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(true, body.get("repaired"));
    assertEquals("UNFINALIZED", body.get("status"));
  }

  @Test
  void getRecording_doesNotAttemptOrphanRepairWhenVideoFileMissing() {
    RecordingSession session = createSession("rec-missing");
    session.setEndTime(LocalDateTime.of(2025, 6, 15, 13, 0));

    when(repository.findByRecordingId("rec-missing")).thenReturn(Optional.of(session));
    when(recordingConfig.getOutputDirectory()).thenReturn(tempDir.toString());

    ResponseEntity<Map<String, Object>> response = controller.getRecording("rec-missing");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(debugSessionService, never()).repairOrphanedRecordingSession("rec-missing");
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(false, body.get("playable"));
    assertEquals("MISSING_VIDEO", body.get("status"));
  }

  @Test
  void listRecordingSessions_flagsLowCoverageFinalizedRecordingAsDegraded() {
    RecordingSession session = createSession("rec-degraded");
    session.setEndTime(LocalDateTime.of(2025, 6, 15, 13, 0));
    Path video = tempDir.resolve("rec-degraded.mp4");
    assertDoesNotThrow(() -> Files.writeString(video, "video"));
    session.setVideoPath(video.toString());
    session.setDurationMs(117_336L);
    session.setFrameCount(3L);
    session.setFps(30);

    when(repository.findAllByOrderByStartTimeDesc()).thenReturn(List.of(session));
    when(recordingConfig.getOutputDirectory()).thenReturn(tempDir.toString());

    ResponseEntity<List<Map<String, Object>>> response = controller.listRecordingSessions();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, Object> item = response.getBody().get(0);
    assertEquals(true, item.get("degraded"));
    assertNotNull(item.get("videoWarning"));
    assertEquals(3L, item.get("frameCount"));
    assertEquals(30, item.get("fps"));
    assertEquals(117_336L, item.get("durationMs"));
    double coverage = ((Number) item.get("contentCoverage")).doubleValue();
    assertTrue(coverage < 0.01, "expected near-zero coverage, got " + coverage);
  }

  @Test
  void getRecording_healthyCoverageIsNotDegraded() {
    RecordingSession session = createSession("rec-healthy");
    session.setEndTime(LocalDateTime.of(2025, 6, 15, 13, 0));
    Path video = tempDir.resolve("rec-healthy.mp4");
    assertDoesNotThrow(() -> Files.writeString(video, "video"));
    session.setVideoPath(video.toString());
    session.setDurationMs(117_336L);
    session.setFrameCount(3_500L);
    session.setFps(30);

    when(repository.findByRecordingId("rec-healthy")).thenReturn(Optional.of(session));
    when(recordingConfig.getOutputDirectory()).thenReturn(tempDir.toString());

    ResponseEntity<Map<String, Object>> response = controller.getRecording("rec-healthy");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(false, body.get("degraded"));
    assertNull(body.get("videoWarning"));
    assertEquals(3_500L, body.get("frameCount"));
  }
}
