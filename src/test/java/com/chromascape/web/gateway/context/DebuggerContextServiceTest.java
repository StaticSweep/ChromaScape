package com.chromascape.web.gateway.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DebuggerContextServiceTest {

  private DebugSessionRepository debugSessions;
  private RoutineCaptureRecordRepository captures;
  private ObjectMapper mapper;
  private DebuggerContextService service;

  @BeforeEach
  void setUp() {
    debugSessions = mock(DebugSessionRepository.class);
    captures = mock(RoutineCaptureRecordRepository.class);
    mapper = new ObjectMapper();
    service =
        new DebuggerContextService(
            debugSessions,
            captures,
            mapper,
            new CurrentSliceIdentityValidator(),
            new DebuggerContextValidator());
  }

  @Test
  void authenticatesAndAuthorizesBeforeResolvingCallerSelectors() throws Exception {
    DebugSession session =
        new DebugSession("session-1", "account-1", "script", LocalDateTime.now(), 1L);
    RoutineCaptureRecord capture = new RoutineCaptureRecord();
    capture.setSessionId("session-1");
    capture.setIdentityJson(mapper.writeValueAsString(identity()));
    when(debugSessions.findBySessionId("session-1")).thenReturn(Optional.of(session));
    when(captures.findBySessionId("session-1")).thenReturn(Optional.of(capture));
    Principal principal = () -> "account-1";

    DebuggerContext result = service.resolve(principal, "session-1", "replay", 8L, null, 3L);

    assertEquals(72, result.accountId().length());
    assertTrue(result.accountId().startsWith("account:"));
    assertEquals("attempt-1", result.patchIterationId());
    assertEquals(8L, result.selectedTick());
    assertEquals(3L, result.contextRevision());
  }

  @Test
  void identityValuesDoNotAuthorizeAnAnonymousOrDifferentPrincipal() {
    DebugSession session =
        new DebugSession("session-1", "account-1", "script", LocalDateTime.now(), 1L);
    when(debugSessions.findBySessionId("session-1")).thenReturn(Optional.of(session));

    assertThrows(
        ResponseStatusException.class,
        () -> service.resolve(null, "session-1", "live", null, null, 1L));
    assertThrows(
        ResponseStatusException.class,
        () -> service.resolve(() -> "account-2", "session-1", "live", null, null, 1L));
  }

  @Test
  void rejectsInvalidSelectorsBeforeRepositoryLookup() {
    ResponseStatusException invalidRevision =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolve(() -> "account-1", "session-1", "live", null, null, 0L));
    assertEquals(400, invalidRevision.getStatusCode().value());

    ResponseStatusException replayWithoutTick =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolve(() -> "account-1", "session-1", "replay", null, null, 1L));
    assertEquals(400, replayWithoutTick.getStatusCode().value());
  }

  private static CurrentSliceIdentity identity() {
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("task-1", hash('1'), "profile-1", hash('2'), hash('3')),
        new CurrentSliceIdentity.Run("run-1", "attempt-1", 1, "agent-1"),
        new CurrentSliceIdentity.Client(
            "client-1",
            "MICRO6_MANAGED",
            "client-build-1",
            hash('4'),
            "micro6-builder/v2",
            "MICRO6"),
        new CurrentSliceIdentity.Capture("session-1", "recording-1", hash('5')),
        null,
        null,
        null,
        null,
        false);
  }

  private static String hash(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
