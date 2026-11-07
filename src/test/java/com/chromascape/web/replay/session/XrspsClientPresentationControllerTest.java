package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class XrspsClientPresentationControllerTest {
  final ObjectMapper mapper = new ObjectMapper();
  final UUID id = UUID.randomUUID();
  final XrspsSessionRepository repository = mock(XrspsSessionRepository.class);
  final ReplayPrincipalService principals = mock(ReplayPrincipalService.class);
  final Principal principal = () -> "owner";

  XrspsSessionDocument document() throws Exception {
    var doc = new XrspsSessionDocument();
    var session =
        new XrspsSessionStore()
            .create(XrspsSessionMode.RECORDED_ACTIONS, "fixture-key", "scenario", "owner");
    session.markReady();
    doc.lifecycle = session.snapshot();
    doc.clientBootstrap = mapper.readTree("{\"sequence\":2,\"tick\":1,\"packets\":[\"AQ==\"]}");
    doc.clientSequence = 2;
    doc.clientFrames.add(mapper.readTree("{\"sequence\":1,\"tick\":0,\"packets\":[\"Ag==\"]}"));
    doc.clientFrames.add(doc.clientBootstrap.deepCopy());
    return doc;
  }

  @Test
  void viewerReadsOnlyOwnedCommittedPacketsAndDoesNotMutateTheSession() throws Exception {
    var doc = document();
    when(principals.requirePrincipal(principal)).thenReturn("owner");
    when(repository.getOwned(id, "owner")).thenReturn(doc);
    var controller = new XrspsClientPresentationController(repository, principals);
    var page = controller.presentation(id, 1, false, doc.lifecycle.epoch(), principal);
    assertFalse(page.bootstrap());
    assertEquals(1, page.frames().size());
    ((com.fasterxml.jackson.databind.node.ObjectNode) page.frames().get(0)).put("tick", 999);
    assertEquals(1, doc.clientFrames.get(1).path("tick").asLong());
    verify(repository).getOwned(id, "owner");
    verifyNoMoreInteractions(repository);
  }

  @Test
  void staleViewerEpochAndLostCursorGetCurrentBootstrapWithoutExecutingTicks() throws Exception {
    var doc = document();
    when(principals.requirePrincipal(principal)).thenReturn("owner");
    when(repository.getOwned(id, "owner")).thenReturn(doc);
    var controller = new XrspsClientPresentationController(repository, principals);
    assertTrue(controller.presentation(id, 1, false, -1, principal).bootstrap());
    assertTrue(
        controller.presentation(id, 99, false, doc.lifecycle.epoch(), principal).bootstrap());
    assertEquals(0, doc.lifecycle.tick());
    verify(repository, times(2)).getOwned(id, "owner");
    verifyNoMoreInteractions(repository);
  }
}
