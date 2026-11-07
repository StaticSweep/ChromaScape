package com.chromascape.web.debug.tickdebugger.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.chromascape.web.aiplayer.catalog.CatalogStore;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.provenance.BehaviorRunContextService;
import com.chromascape.web.recording.provenance.BehaviorSemanticContract;
import com.chromascape.web.recording.provenance.ProvenanceException;
import com.chromascape.web.recording.provenance.RecordingProvenance;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The reused-session provenance guard of SC-274: an executor that echoes the capture it was armed
 * with must be accepted even when it round-trips numbers as floating point, and any real difference
 * must still be refused without replacing the stored context.
 */
@ExtendWith(MockitoExtension.class)
class DebugSessionProvenanceReuseTest {

  @Mock private DebugSessionRepository sessionRepository;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private TickLogRepository tickLogRepository;
  @Mock private TimelineMarkerRepository timelineMarkerRepository;
  @Mock private RecordingService recordingService;
  @Mock private RecordingSessionRepository recordingSessionRepository;
  @Mock private RecordingConfig recordingConfig;
  @Mock private CatalogStore catalogStore;
  @Mock private JdbcTemplate jdbc;

  private final ObjectMapper json = new ObjectMapper();
  private final AtomicReference<DebugSession> savedSession = new AtomicReference<>();
  private final AtomicReference<RecordingSession> savedRecording = new AtomicReference<>();
  private ObjectNode taskArgs;
  private DebugSessionService service;
  private String persistedSessionId;

  @BeforeEach
  void setUp() {
    taskArgs = json.createObjectNode().put("fish", "trout");
    ObjectNode profile =
        json.createObjectNode()
            .put("profile_id", "profile_1")
            .put("profile_version", 1)
            .put("task_key", "FISHING")
            .put("settings_hash", RecordingProvenance.capture("FISHING", taskArgs).settingsHash())
            .put("catalog_hash", "sha256:" + "d".repeat(64))
            .put("catalog_version", "1");
    profile.set("settings_json", RecordingProvenance.capture("FISHING", taskArgs).taskArgs());
    profile.set("requirements_json", json.createObjectNode());
    profile.set("task_snapshot", json.createObjectNode());
    lenient().when(catalogStore.profile(anyString(), anyInt())).thenReturn(profile);

    service =
        new DebugSessionService(
            sessionRepository,
            snapshotRepository,
            tickLogRepository,
            timelineMarkerRepository,
            recordingService,
            recordingSessionRepository,
            recordingConfig,
            json);
    ReflectionTestUtils.setField(
        service,
        "contexts",
        new BehaviorRunContextService(
            catalogStore, recordingSessionRepository, sessionRepository, jdbc, json));
  }

  private ObjectNode reference(long privacyLimit) {
    return reference(json.getNodeFactory().numberNode(privacyLimit));
  }

  private ObjectNode reference(double privacyLimit) {
    return reference(json.getNodeFactory().numberNode(privacyLimit));
  }

  private ObjectNode reference(com.fasterxml.jackson.databind.JsonNode privacyLimit) {
    ObjectNode reference =
        json.createObjectNode()
            .put("runId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
            .put("allocationId", "alloc_1")
            .put("executorId", "exec_1")
            .put("clientInstanceId", "client_1")
            .put("behaviorProfileId", "profile_1")
            .put("behaviorProfileVersion", 1)
            .put("semanticContractDigest", BehaviorSemanticContract.digest())
            .put("dataOrigin", "HUMAN");
    ObjectNode privacy = reference.putObject("privacy");
    privacy.put("policyVersion", "privacy/v1");
    privacy.putArray("redactedFields").add("password");
    privacy.set("limit", privacyLimit);
    return reference;
  }

  /** Captures once through the HTTP-owned path and leaves the same session reusable. */
  private void captureOnce() {
    when(sessionRepository.findBySessionId(anyString())).thenReturn(Optional.empty());
    when(sessionRepository.save(any(DebugSession.class)))
        .thenAnswer(
            invocation -> {
              DebugSession session = invocation.getArgument(0);
              savedSession.set(session);
              return session;
            });
    when(recordingSessionRepository.save(any(RecordingSession.class)))
        .thenAnswer(
            invocation -> {
              RecordingSession recording = invocation.getArgument(0);
              savedRecording.set(recording);
              return recording;
            });
    lenient()
        .when(recordingSessionRepository.findByRecordingId(anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(savedRecording.get()));
    when(recordingService.startRecording(any(RecordingTriggerSource.class))).thenReturn("rec_1");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(LocalDateTime.now());

    service.startSessionWithOutcome(
        "acct",
        "script",
        true,
        RecordingTriggerSource.MANUAL,
        null,
        "FISHING",
        taskArgs,
        reference(5L));

    persistedSessionId = savedSession.get().getSessionId();
    when(sessionRepository.findBySessionId(persistedSessionId))
        .thenReturn(Optional.of(savedSession.get()));
  }

  @Test
  void anEchoOfTheSameCaptureWithFloatingPointNumbersIsAccepted() {
    captureOnce();
    assertTrue(
        savedRecording.get().getRunContext().path("privacy").path("limit").isIntegralNumber());
    assertTrue(reference(5.0d).path("privacy").path("limit").isFloatingPointNumber());

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct",
            "script",
            true,
            RecordingTriggerSource.MANUAL,
            persistedSessionId,
            "FISHING",
            taskArgs,
            reference(5.0d));

    assertEquals(persistedSessionId, outcome.response().getSessionId());
    assertEquals(savedRecording.get().getRunContext(), outcome.response().getRunContext());
    verify(recordingService, times(1)).startRecording(any(RecordingTriggerSource.class));
  }

  @Test
  void anEchoWithADifferentExecutorIsRefused() {
    captureOnce();
    ObjectNode changed = reference(5L);
    changed.put("executorId", "exec_2");

    ProvenanceException failure =
        assertThrows(
            ProvenanceException.class,
            () ->
                service.startSessionWithOutcome(
                    "acct",
                    "script",
                    true,
                    RecordingTriggerSource.MANUAL,
                    persistedSessionId,
                    "FISHING",
                    taskArgs,
                    changed));

    assertEquals("IMMUTABLE_PROVENANCE", failure.getCode());
    assertEquals("exec_1", savedRecording.get().getRunContext().path("executorId").asText());
    verify(recordingService, times(1)).startRecording(any(RecordingTriggerSource.class));
  }

  @Test
  void aDifferentTaskKeyOnAReusedRecordingIsRefusedAtTheSettingsGuard() {
    captureOnce();

    ProvenanceException failure =
        assertThrows(
            ProvenanceException.class,
            () ->
                service.startSessionWithOutcome(
                    "acct",
                    "script",
                    true,
                    RecordingTriggerSource.MANUAL,
                    persistedSessionId,
                    "WOODCUTTING",
                    null,
                    null));

    assertEquals("IMMUTABLE_PROVENANCE", failure.getCode());
    verify(recordingService, times(1)).startRecording(any(RecordingTriggerSource.class));
  }
}
