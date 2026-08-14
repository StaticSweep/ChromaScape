package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.dto.SessionContextDto;
import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.exception.PatchLoopRequestException;
import com.chromascape.web.debug.patchloop.service.PatchLoopSessionResolverService;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatchLoopSessionResolverServiceTest {

  @Mock private DebugSessionRepository debugSessionRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private PatchLoopSessionResolverService service;

  @BeforeEach
  void setUp() {
    service = new PatchLoopSessionResolverService(debugSessionRepository, objectMapper);
  }

  @Test
  @DisplayName("Explicit debugSessionId resolves strictly and suppresses fallback")
  void explicitDebugSessionIdResolvesStrictly() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setScriptName("TestScript");
    iteration.setSessionContext(objectMapper.valueToTree(sessionContext("session-123", null)));

    DebugSession session = debugSession("session-123", "recording-123");
    when(debugSessionRepository.findBySessionId("session-123")).thenReturn(Optional.of(session));

    PatchLoopSessionResolverService.ResolvedSessionContext resolved =
        service.resolveForIteration(iteration).orElseThrow();

    assertEquals("session-123", resolved.getSession().getSessionId());
    assertEquals(
        PatchLoopSessionResolverService.SOURCE_EXPLICIT_DEBUG_SESSION,
        resolved.getResolutionSource());
    verify(debugSessionRepository, never()).findFirstByScriptNameOrderByStartTimeDesc("TestScript");
  }

  @Test
  @DisplayName("Invalid explicit debugSessionId fails without fallback")
  void invalidExplicitDebugSessionIdFailsWithoutFallback() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setScriptName("TestScript");
    iteration.setSessionContext(objectMapper.valueToTree(sessionContext("missing-session", null)));

    when(debugSessionRepository.findBySessionId("missing-session")).thenReturn(Optional.empty());

    PatchLoopRequestException error =
        assertThrows(PatchLoopRequestException.class, () -> service.resolveForIteration(iteration));

    assertEquals(PatchLoopFailureCode.SESSION_NOT_FOUND, error.getErrorCode());
    verify(debugSessionRepository, never()).findFirstByScriptNameOrderByStartTimeDesc("TestScript");
  }

  @Test
  @DisplayName("Persisted runSessionId is reused before latest-by-script fallback")
  void persistedRunSessionIdIsReused() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setScriptName("TestScript");
    iteration.setRunSessionId("run-session");

    DebugSession session = debugSession("run-session", "recording-456");
    when(debugSessionRepository.findBySessionId("run-session")).thenReturn(Optional.of(session));

    PatchLoopSessionResolverService.ResolvedSessionContext resolved =
        service.resolveForIteration(iteration).orElseThrow();

    assertEquals(
        PatchLoopSessionResolverService.SOURCE_RUN_SESSION, resolved.getResolutionSource());
    verify(debugSessionRepository, never()).findFirstByScriptNameOrderByStartTimeDesc("TestScript");
  }

  @Test
  @DisplayName("Retest path does not reuse runSessionId and falls back to latest-by-script")
  void retestFallsBackToLatestByScript() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setScriptName("TestScript");
    iteration.setRunSessionId("old-session");

    DebugSession latest = debugSession("latest-session", "recording-789");
    when(debugSessionRepository.findFirstByScriptNameOrderByStartTimeDesc("TestScript"))
        .thenReturn(Optional.of(latest));

    PatchLoopSessionResolverService.ResolvedSessionContext resolved =
        service.resolveForRetest(iteration).orElseThrow();

    assertEquals(
        PatchLoopSessionResolverService.SOURCE_LATEST_BY_SCRIPT, resolved.getResolutionSource());
    assertEquals("latest-session", resolved.getSession().getSessionId());
    verify(debugSessionRepository, never()).findBySessionId("old-session");
  }

  @Test
  @DisplayName("applyResolvedSession persists debug session, recording, and resolution source")
  void applyResolvedSessionPersistsResolutionMetadata() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    DebugSession session = debugSession("resolved-session", "resolved-recording");
    PatchLoopSessionResolverService.ResolvedSessionContext resolved =
        new PatchLoopSessionResolverService.ResolvedSessionContext(
            session, PatchLoopSessionResolverService.SOURCE_EXPLICIT_RECORDING);

    service.applyResolvedSession(iteration, resolved);

    assertEquals("resolved-session", iteration.getRunSessionId());
    assertEquals("resolved-recording", iteration.getRunRecordingId());
    SessionContextDto sessionContext = service.readSessionContext(iteration).orElseThrow();
    assertEquals("resolved-session", sessionContext.getDebugSessionId());
    assertEquals("resolved-recording", sessionContext.getRecordingId());
    assertEquals(
        PatchLoopSessionResolverService.SOURCE_EXPLICIT_RECORDING,
        sessionContext.getResolutionSource());
  }

  @Test
  @DisplayName("No matching session returns empty when no explicit context and no fallback session")
  void returnsEmptyWhenNoSessionCanBeResolved() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setScriptName("MissingScript");

    when(debugSessionRepository.findFirstByScriptNameOrderByStartTimeDesc("MissingScript"))
        .thenReturn(Optional.empty());

    assertFalse(service.resolveForIteration(iteration).isPresent());
  }

  private SessionContextDto sessionContext(String debugSessionId, String recordingId) {
    SessionContextDto sessionContext = new SessionContextDto();
    sessionContext.setDebugSessionId(debugSessionId);
    sessionContext.setRecordingId(recordingId);
    return sessionContext;
  }

  private DebugSession debugSession(String sessionId, String recordingId) {
    DebugSession session = new DebugSession();
    session.setSessionId(sessionId);
    session.setRecordingId(recordingId);
    return session;
  }
}
