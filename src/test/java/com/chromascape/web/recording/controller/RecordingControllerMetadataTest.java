package com.chromascape.web.recording.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.dto.RecordingSessionMetadataUpdateRequest;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingSessionMetadataService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Tests for the metadata PATCH endpoint in {@link RecordingController}. */
@ExtendWith(MockitoExtension.class)
class RecordingControllerMetadataTest {

  @Mock private RecordingService recordingService;
  @Mock private RecordingSessionRepository repository;
  @Mock private WindowSelectionService windowSelectionService;
  @Mock private RecordingConfig recordingConfig;

  private RecordingController controller;
  private RecordingSessionMetadataService metadataService;

  @BeforeEach
  void setUp() {
    metadataService =
        new RecordingSessionMetadataService(new com.fasterxml.jackson.databind.ObjectMapper());
    controller =
        new RecordingController(
            recordingService, repository, windowSelectionService, recordingConfig, metadataService);
  }

  private RecordingSession createSession(String recordingId) {
    RecordingSession session =
        new RecordingSession(
            recordingId,
            "testAccount",
            "testScript",
            LocalDateTime.of(2025, 6, 15, 12, 0),
            RecordingSession.SessionTrigger.MANUAL,
            null);
    return session;
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

    // Empty request — neither field set
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
  void listSessions_includesMetadataFields() {
    RecordingSession session = createSession("rec-list");
    session.setReplayName("Custom");
    session.setReplayTagsJson("[\"alpha\"]");
    when(repository.findAllByOrderByStartTimeDesc()).thenReturn(List.of(session));

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

    ResponseEntity<List<Map<String, Object>>> response = controller.listRecordingSessions();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, Object> item = response.getBody().get(0);
    assertNull(item.get("replayName"));
    assertEquals(List.of(), item.get("replayTags"));
    // displayName should fall back to scriptName - accountName - time
    assertNotNull(item.get("displayName"));
  }
}
