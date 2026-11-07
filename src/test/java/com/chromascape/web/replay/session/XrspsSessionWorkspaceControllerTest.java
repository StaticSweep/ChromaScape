package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class XrspsSessionWorkspaceControllerTest {
  @Test
  void playbackRetainsSourceTimingAndHashesOnlyTheSemanticAction() throws Exception {
    var mapper = new ObjectMapper();
    var doc = new XrspsSessionDocument();
    doc.lifecycle =
        new XrspsSessionStore()
            .create(XrspsSessionMode.RECORDED_ACTIONS, "playback", "scenario", "owner")
            .snapshot();
    doc.manifest =
        mapper.readTree(
            """
        {"recording":{"startTick":100},"actions":[
          {"type":"WAIT","ticks":1,"sourceActionTick":103,"sourceActionSequence":7,
           "mappingVersion":"2.0.1","provenance":"OBSERVED",
           "sourceEvidence":{"recordingId":"original-recording"}}
        ]}
        """);
    var original = doc.manifest.deepCopy();
    var repository = mock(XrspsSessionRepository.class);
    var playback = mock(XrspsPlaybackController.class);
    var principals = mock(ReplayPrincipalService.class);
    Principal principal = () -> "owner";
    var id = UUID.randomUUID();
    when(principals.requirePrincipal(principal)).thenReturn("owner");
    when(repository.getOwned(id, "owner")).thenReturn(doc);
    when(playback.begin(eq(id), any(), eq(principal))).thenReturn(Map.of("paced", true));
    var canonical = new CanonicalProjectionService();
    var controller =
        new XrspsSessionWorkspaceController(repository, playback, principals, canonical);

    controller.play(id, principal);

    var request = ArgumentCaptor.forClass(XrspsSessionsController.RunJournalRequest.class);
    verify(playback).begin(eq(id), request.capture(), eq(principal));
    var journal = request.getValue().actions();
    assertEquals(1, journal.size());
    var action = journal.get(0).toAction();
    assertEquals(3, action.relativeTick());
    assertEquals(0, action.expectedRevision());
    var semantic = mapper.readTree("{\"type\":\"WAIT\",\"ticks\":1}");
    assertEquals(canonical.actionDigest(semantic), action.payloadHash());
    assertEquals(semantic, action.payload());
    assertEquals(original, doc.manifest);
  }
}
