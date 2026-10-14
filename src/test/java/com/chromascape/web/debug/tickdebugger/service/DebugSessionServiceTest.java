package com.chromascape.web.debug.tickdebugger.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionCountProjection;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link DebugSessionService}. */
@ExtendWith(MockitoExtension.class)
class DebugSessionServiceTest {

  @Mock private DebugSessionRepository sessionRepository;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private TickLogRepository tickLogRepository;
  @Mock private TimelineMarkerRepository timelineMarkerRepository;
  @Mock private RecordingService recordingService;
  @Mock private RecordingSessionRepository recordingSessionRepository;
  @Mock private RecordingConfig recordingConfig;

  private DebugSessionService service;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient()
        .when(sessionRepository.findBySessionIdForUpdate(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> sessionRepository.findBySessionId(invocation.getArgument(0)));

    service =
        new DebugSessionService(
            sessionRepository,
            snapshotRepository,
            tickLogRepository,
            timelineMarkerRepository,
            recordingService,
            recordingSessionRepository,
            recordingConfig,
            new ObjectMapper());
  }

  private DebugSession createSession(String sessionId) {
    return new DebugSession(
        sessionId, "testAccount", "testScript", LocalDateTime.of(2025, 6, 15, 12, 0), 0L);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode recorderIdentity() {
    var identity =
        new ObjectMapper()
            .createObjectNode()
            .put("schema", "debugger-client-identity/v1")
            .put("clientBuildId", "commit")
            .put("clientVersion", "1.0")
            .put("artifactSha256", "a".repeat(64))
            .put("gameRevision", 241)
            .put("cacheRevision", 241)
            .put("cacheRevisionSource", "CLIENT_GET_REVISION");
    identity.putArray("qualityCodes");
    return identity;
  }

  @Test
  void manualRecorderIdentityBindsOnceUnderSessionLockAndRejectsChangedReconnect() {
    var session = createSession("preferred");
    session.setRecordingId("existing-video");
    when(sessionRepository.findBySessionId("preferred")).thenReturn(Optional.of(session));
    when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var client = recorderIdentity();
    service.startSessionWithOutcome(
        null,
        "combat",
        false,
        RecordingTriggerSource.MANUAL,
        "preferred",
        null,
        null,
        null,
        client);
    var persisted = session.getCaptureIdentity();
    assertEquals(241, persisted.path("client").path("gameRevision").asInt());
    verify(sessionRepository).findBySessionIdForUpdate("preferred");
    service.startSessionWithOutcome(
        null,
        "combat",
        false,
        RecordingTriggerSource.MANUAL,
        "preferred",
        null,
        null,
        null,
        client);
    assertEquals(persisted, session.getCaptureIdentity());
    clearInvocations(sessionRepository, recordingService);
    client.put("artifactSha256", "b".repeat(64));
    var failure =
        assertThrows(
            com.chromascape.web.recording.provenance.ProvenanceException.class,
            () ->
                service.startSessionWithOutcome(
                    null,
                    "combat",
                    true,
                    RecordingTriggerSource.MANUAL,
                    "preferred",
                    null,
                    null,
                    null,
                    client));
    assertEquals("IMMUTABLE_CAPTURE_IDENTITY", failure.getCode());
    verify(sessionRepository, never()).save(any());
    verifyNoInteractions(recordingService);
    assertEquals(persisted, session.getCaptureIdentity());
  }

  @Test
  void cannotBackfillIdentityAfterTicksOrClosure() {
    var session = createSession("legacy");
    when(sessionRepository.findBySessionId("legacy")).thenReturn(Optional.of(session));
    when(snapshotRepository.countBySessionId("legacy")).thenReturn(1L);
    assertThrows(
        com.chromascape.web.recording.provenance.ProvenanceException.class,
        () ->
            service.startSessionWithOutcome(
                null,
                "combat",
                false,
                RecordingTriggerSource.MANUAL,
                "legacy",
                null,
                null,
                null,
                recorderIdentity()));
    assertNull(session.getCaptureIdentity());
    verify(sessionRepository, never()).save(any());
    verifyNoInteractions(recordingService);
  }

  @Test
  void heuristicLinkRepairCannotMoveKnownRecordingProvenance() {
    var current = createSession("known-identity");
    current.setRecordingId("video-identity");
    current.bindCaptureIdentity(
        DebuggerCaptureIdentity.snapshot(
            DebuggerCaptureIdentity.validateClient(recorderIdentity()), 2));
    when(sessionRepository.findByRecordingIdForUpdate("video-identity"))
        .thenReturn(Optional.of(current));
    assertFalse(service.repairRecordingLink("video-identity"));
    verifyNoInteractions(snapshotRepository);
    verify(sessionRepository, never()).save(any());
    assertEquals("video-identity", current.getRecordingId());
  }

  @Test
  void finalizedBoundsCannotGrowEvenWhenCalledDirectly() {
    DebugSession session = createSession("closed");
    session.setLifecycleState(DebugSessionLifecycleState.FINALIZED);
    session.setEndTick(322L);
    when(sessionRepository.findBySessionIdForUpdate("closed")).thenReturn(Optional.of(session));
    service.updateSessionTickBounds("closed", 824L);
    assertEquals(322L, session.getEndTick());
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void ingestionUsesTheTransactionLockedSessionLookup() {
    DebugSession session = createSession("active");
    doReturn(Optional.of(session)).when(sessionRepository).findBySessionIdForUpdate("active");
    assertEquals(session, service.getSessionForIngestion("active").orElseThrow());
    verify(sessionRepository, never()).findBySessionId(any());
  }

  private SessionCountProjection projection(String sessionId, long count) {
    return new SessionCountProjection() {
      @Override
      public String getSessionId() {
        return sessionId;
      }

      @Override
      public Long getCount() {
        return count;
      }
    };
  }

  @Test
  void toSummaries_usesGroupedCountQueriesAndMergesCounts() {
    DebugSession s1 = createSession("debug-1");
    DebugSession s2 = createSession("debug-2");
    List<DebugSession> sessions = List.of(s1, s2);

    when(snapshotRepository.countBySessionIdIn(List.of("debug-1", "debug-2")))
        .thenReturn(List.of(projection("debug-1", 10L), projection("debug-2", 5L)));
    when(tickLogRepository.countBySessionIdIn(List.of("debug-1", "debug-2")))
        .thenReturn(List.of(projection("debug-1", 3L)));
    when(timelineMarkerRepository.countFailuresBySessionIdIn(List.of("debug-1", "debug-2")))
        .thenReturn(List.of(projection("debug-2", 2L)));

    List<DebugSessionSummary> summaries = service.toSummaries(sessions);

    assertEquals(2, summaries.size());
    DebugSessionSummary one = summaries.get(0);
    assertEquals("debug-1", one.getSessionId());
    assertEquals(10L, one.getTickCount());
    assertEquals(3L, one.getEventCount());
    assertEquals(0L, one.getFailureMarkerCount());
    assertTrue(one.getHasEvents());

    DebugSessionSummary two = summaries.get(1);
    assertEquals("debug-2", two.getSessionId());
    assertEquals(5L, two.getTickCount());
    assertEquals(0L, two.getEventCount());
    assertEquals(2L, two.getFailureMarkerCount());
    assertFalse(two.getHasEvents());
  }

  @Test
  void toSummaries_emptyList_returnsEmpty() {
    assertTrue(service.toSummaries(List.of()).isEmpty());
  }

  private void stubSaveReturnsSame() {
    when(sessionRepository.save(any(DebugSession.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void startWithOutcome_reusesActivePreferredSession() {
    DebugSession preferred = createSession("debug_pref");
    when(sessionRepository.findBySessionId("debug_pref")).thenReturn(Optional.of(preferred));
    stubSaveReturnsSame();
    when(recordingService.startRecording(any(RecordingTriggerSource.class))).thenReturn("rec_new");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(LocalDateTime.now());

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", true, RecordingTriggerSource.MANUAL, "debug_pref");

    assertTrue(outcome.reusedPreferredSession());
    assertFalse(outcome.createdNewSession());
    assertEquals("debug_pref", outcome.response().getSessionId());
  }

  @Test
  void startWithOutcome_missingPreferredCreatesNewSession() {
    when(sessionRepository.findBySessionId("missing")).thenReturn(Optional.empty());
    stubSaveReturnsSame();

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", false, RecordingTriggerSource.MANUAL, "missing");

    assertTrue(outcome.createdNewSession());
    assertFalse(outcome.reusedPreferredSession());
    assertTrue(outcome.response().getSessionId().startsWith("debug_"));
  }

  @Test
  void startWithOutcome_inactivePreferredCreatesNewSession() {
    DebugSession inactive = createSession("debug_pref");
    inactive.setEndTime(LocalDateTime.now());
    when(sessionRepository.findBySessionId("debug_pref")).thenReturn(Optional.of(inactive));
    stubSaveReturnsSame();

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", false, RecordingTriggerSource.MANUAL, "debug_pref");

    assertTrue(outcome.createdNewSession());
    assertFalse(outcome.reusedPreferredSession());
    assertTrue(outcome.response().getSessionId().startsWith("debug_"));
  }

  @Test
  void startWithOutcome_noPreferredCreatesNewSession() {
    stubSaveReturnsSame();

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", false, RecordingTriggerSource.MANUAL, null);

    assertTrue(outcome.createdNewSession());
    assertFalse(outcome.reusedPreferredSession());
  }

  @Test
  void startWithOutcome_reusesExistingRecordingWithoutRestart() {
    DebugSession preferred = createSession("debug_pref");
    preferred.setRecordingId("rec_existing");
    when(sessionRepository.findBySessionId("debug_pref")).thenReturn(Optional.of(preferred));
    stubSaveReturnsSame();

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", true, RecordingTriggerSource.MANUAL, "debug_pref");

    assertTrue(outcome.reusedPreferredSession());
    assertFalse(outcome.recordingStartedByOperation());
    assertEquals("rec_existing", outcome.response().getRecordingId());
    verify(recordingService, never()).startRecording(any(RecordingTriggerSource.class));
  }

  @Test
  void startWithOutcome_reusedSessionWithoutRecordingStartsOwnedRecording() {
    DebugSession preferred = createSession("debug_pref");
    when(sessionRepository.findBySessionId("debug_pref")).thenReturn(Optional.of(preferred));
    stubSaveReturnsSame();
    when(recordingService.startRecording(any(RecordingTriggerSource.class))).thenReturn("rec_new");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(LocalDateTime.now());

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", true, RecordingTriggerSource.MANUAL, "debug_pref");

    assertTrue(outcome.reusedPreferredSession());
    assertTrue(outcome.recordingStartedByOperation());
    assertEquals("rec_new", outcome.rollbackToken().recordingIdStartedByOperation());
  }

  @Test
  void rollback_newSessionStopsRecordingAndFinalizes() {
    AtomicReference<DebugSession> created = new AtomicReference<>();
    when(sessionRepository.save(any(DebugSession.class)))
        .thenAnswer(
            inv -> {
              DebugSession s = inv.getArgument(0);
              created.set(s);
              return s;
            });
    when(sessionRepository.findBySessionId(anyString()))
        .thenAnswer(
            inv ->
                created.get() != null && created.get().getSessionId().equals(inv.getArgument(0))
                    ? Optional.of(created.get())
                    : Optional.empty());
    when(recordingService.startRecording(any(RecordingTriggerSource.class))).thenReturn("rec_new");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(LocalDateTime.now());
    when(recordingService.getCurrentRecordingId()).thenReturn("rec_new").thenReturn((String) null);
    when(recordingService.stopRecordingIfCurrent("rec_new")).thenReturn(true);

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", true, RecordingTriggerSource.MANUAL, null);
    assertTrue(outcome.createdNewSession());

    service.rollbackSessionStart(outcome.rollbackToken(), "test rollback");

    verify(recordingService).stopRecordingIfCurrent("rec_new");
    assertEquals(DebugSessionLifecycleState.FINALIZED, created.get().getLifecycleState());
  }

  @Test
  void rollback_reusedSessionRestoresPriorFields() {
    DebugSession preferred = createSession("debug_pref");
    preferred.transitionTo(DebugSessionLifecycleState.RECORDING);
    LocalDateTime priorStart = LocalDateTime.of(2025, 6, 15, 12, 0);
    preferred.setStartTime(priorStart);
    when(sessionRepository.findBySessionId("debug_pref")).thenReturn(Optional.of(preferred));
    stubSaveReturnsSame();
    when(recordingService.startRecording(any(RecordingTriggerSource.class))).thenReturn("rec_new");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(LocalDateTime.now());
    when(recordingService.getCurrentRecordingId()).thenReturn("rec_new");
    when(recordingService.stopRecordingIfCurrent("rec_new")).thenReturn(true);

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", true, RecordingTriggerSource.MANUAL, "debug_pref");
    assertTrue(outcome.reusedPreferredSession());
    assertEquals("rec_new", preferred.getRecordingId());

    service.rollbackSessionStart(outcome.rollbackToken(), "test rollback");

    verify(recordingService).stopRecordingIfCurrent("rec_new");
    assertNull(preferred.getRecordingId());
    assertEquals(priorStart, preferred.getStartTime());
    assertEquals(DebugSessionLifecycleState.RECORDING, preferred.getLifecycleState());
  }

  @Test
  void rollback_repeatedIsHarmless() {
    DebugSession preferred = createSession("debug_pref");
    preferred.transitionTo(DebugSessionLifecycleState.RECORDING);
    when(sessionRepository.findBySessionId("debug_pref")).thenReturn(Optional.of(preferred));
    stubSaveReturnsSame();
    when(recordingService.startRecording(any(RecordingTriggerSource.class))).thenReturn("rec_new");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(LocalDateTime.now());
    when(recordingService.getCurrentRecordingId()).thenReturn("rec_new").thenReturn((String) null);
    when(recordingService.stopRecordingIfCurrent("rec_new")).thenReturn(true);

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", true, RecordingTriggerSource.MANUAL, "debug_pref");

    service.rollbackSessionStart(outcome.rollbackToken(), "first");
    service.rollbackSessionStart(outcome.rollbackToken(), "second");

    verify(recordingService, times(1)).stopRecordingIfCurrent("rec_new");
    assertNull(preferred.getRecordingId());
  }

  @Test
  void rollback_reusedSessionRefusesToOverwriteNewerRecording() {
    DebugSession preferred = createSession("debug_pref");
    when(sessionRepository.findBySessionId("debug_pref")).thenReturn(Optional.of(preferred));
    stubSaveReturnsSame();
    when(recordingService.startRecording(any(RecordingTriggerSource.class))).thenReturn("rec_new");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(LocalDateTime.now());

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", true, RecordingTriggerSource.MANUAL, "debug_pref");
    assertTrue(outcome.recordingStartedByOperation());

    // A newer operation replaced the recording association.
    preferred.setRecordingId("rec_other");

    assertThrows(
        SessionStartRollbackException.class,
        () -> service.rollbackSessionStart(outcome.rollbackToken(), "test rollback"));
    verify(recordingService, never()).stopRecording();
    assertEquals("rec_other", preferred.getRecordingId());
  }

  @Test
  void startWithOutcome_recordingFailureDegradesWithoutClaimingOwnership() {
    when(sessionRepository.save(any(DebugSession.class))).thenAnswer(inv -> inv.getArgument(0));
    when(recordingService.startRecording(any(RecordingTriggerSource.class)))
        .thenThrow(new RuntimeException("no frame"));

    DebugSessionService.SessionStartOutcome outcome =
        service.startSessionWithOutcome(
            "acct", "script", true, RecordingTriggerSource.MANUAL, null);

    assertFalse(outcome.response().getRecordingEnabled());
    assertFalse(outcome.recordingStartedByOperation());
    assertNull(outcome.rollbackToken().recordingIdStartedByOperation());
    assertNotNull(outcome.response().getRecordingFailureReason());
  }

  @Test
  void finalizedBoundsNeverGrowFromLateBatches() {
    DebugSession session = createSession("closed");
    session.setEndTime(LocalDateTime.of(2025, 6, 15, 12, 1));
    session.setStartTick(10L);
    session.setEndTick(100L);
    when(sessionRepository.findBySessionIdForUpdate("closed")).thenReturn(Optional.of(session));
    service.updateSessionTickBounds("closed", 2815L);
    assertEquals(10L, session.getStartTick());
    assertEquals(100L, session.getEndTick());
    org.mockito.Mockito.verify(sessionRepository, org.mockito.Mockito.never()).save(session);
  }

  @Test
  void activeBoundsContinueAndNewRecordingHasIndependentBounds() {
    DebugSession old = createSession("old");
    old.setStartTick(10L);
    old.setEndTick(100L);
    old.setEndTime(LocalDateTime.of(2025, 6, 15, 12, 1));
    DebugSession fresh = createSession("new");
    when(sessionRepository.findBySessionIdForUpdate("old")).thenReturn(Optional.of(old));
    when(sessionRepository.findBySessionIdForUpdate("new")).thenReturn(Optional.of(fresh));
    service.updateSessionTickBounds("old", 150L);
    service.updateSessionTickBounds("new", 151L);
    assertEquals(100L, old.getEndTick());
    assertEquals(151L, fresh.getStartTick());
    assertEquals(151L, fresh.getEndTick());
  }
}
