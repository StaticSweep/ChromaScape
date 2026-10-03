package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.entity.*;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.recording.config.*;
import com.chromascape.web.recording.entity.*;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionCaptureProvenanceTest {
  @Test
  void realStartCapturesExactArgsAndHashBeforeAnyFinalization() throws Exception {
    var sessions = mock(DebugSessionRepository.class);
    var recordings = mock(RecordingSessionRepository.class);
    var recorder = mock(RecordingService.class);
    when(sessions.save(any())).thenAnswer(call -> call.getArgument(0));
    when(recorder.startRecording(RecordingTriggerSource.MANUAL)).thenReturn("recording-test");
    when(recorder.getRecordingStartTimeAsLocalDateTime()).thenReturn(LocalDateTime.now());
    var service =
        new DebugSessionService(
            sessions,
            mock(TickSnapshotRepository.class),
            mock(TickLogRepository.class),
            mock(TimelineMarkerRepository.class),
            recorder,
            recordings,
            new RecordingConfig(),
            new ObjectMapper());
    var args = new ObjectMapper().createObjectNode().put("fish", "SHRIMP");
    service.startSessionWithOutcome(
        "actual-account",
        "script",
        true,
        RecordingTriggerSource.MANUAL,
        null,
        "FISHING_TRAINING",
        args,
        null);
    args.put("fish", "LATER");
    var captured = ArgumentCaptor.forClass(RecordingSession.class);
    verify(recordings).save(captured.capture());
    assertEquals("actual-account", captured.getValue().getAccountName());
    assertEquals("SHRIMP", captured.getValue().getTaskArgs().path("fish").asText());
    assertEquals(
        RecordingProvenance.capture(
                "FISHING_TRAINING", new ObjectMapper().createObjectNode().put("fish", "SHRIMP"))
            .settingsHash(),
        captured.getValue().getSettingsHash());
  }

  @Test
  void invalidArgsFailBeforeRecorderSideEffects() {
    var recorder = mock(RecordingService.class);
    var service =
        new DebugSessionService(
            mock(DebugSessionRepository.class),
            mock(TickSnapshotRepository.class),
            mock(TickLogRepository.class),
            mock(TimelineMarkerRepository.class),
            recorder,
            mock(RecordingSessionRepository.class),
            new RecordingConfig(),
            new ObjectMapper());
    assertThrows(
        ProvenanceException.class,
        () ->
            service.startSessionWithOutcome(
                "account",
                "script",
                true,
                RecordingTriggerSource.MANUAL,
                null,
                "FISHING_TRAINING",
                new ObjectMapper().createObjectNode().put("api_token", "private"),
                null));
    verifyNoInteractions(recorder);
  }
}
