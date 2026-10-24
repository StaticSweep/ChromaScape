package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.*;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.chromascape.web.replay.worker.ReplaySourceSchedule;
import java.security.Principal;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** Operator controls use the pinned manifest, never a hand-authored replacement action stream. */
@Controller
public class XrspsSessionWorkspaceController {
  private final XrspsSessionRepository repository;
  private final XrspsPlaybackController playback;
  private final ReplayPrincipalService principals;
  private final CanonicalProjectionService canonical;

  public XrspsSessionWorkspaceController(
      XrspsSessionRepository repository,
      XrspsPlaybackController playback,
      ReplayPrincipalService principals,
      CanonicalProjectionService canonical) {
    this.repository = repository;
    this.playback = playback;
    this.principals = principals;
    this.canonical = canonical;
  }

  @GetMapping("/xrsps-sessions")
  public String page(Principal principal) {
    principals.requirePrincipal(principal);
    return "xrsps-sessions";
  }

  @PostMapping("/api/xrsps/sessions/{id}/play-recording")
  @ResponseBody
  public Map<String, Object> play(@PathVariable UUID id, Principal principal) {
    var doc = repository.getOwned(id, principals.requirePrincipal(principal));
    if (doc.lifecycle.mode() != XrspsSessionMode.RECORDED_ACTIONS)
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID, "This session does not own recorded playback");
    long start = doc.manifest.path("recording").path("startTick").asLong();
    var journal = new ArrayList<XrspsSessionsController.RecordedActionBody>();
    int index = 0;
    for (var source : doc.manifest.path("actions")) {
      if (!source.has("sourceActionTick"))
        throw ReplayException.invalid(
            ReplayErrorCode.CURATION_REQUIRED,
            "Original action timing is required for paced recording playback");
      long tick = source.path("sourceActionTick").asLong() - start;
      var action = ReplaySourceSchedule.semanticAction(source);
      String command =
          UUID.nameUUIDFromBytes(
                  (id + ":recording:" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString();
      journal.add(
          new XrspsSessionsController.RecordedActionBody(
              tick,
              command,
              source.path("type").asText(),
              canonical.actionDigest(action),
              index,
              action));
      index++;
    }
    return playback.begin(id, new XrspsSessionsController.RunJournalRequest(journal), principal);
  }
}
