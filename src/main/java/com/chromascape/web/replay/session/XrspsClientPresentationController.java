package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Reads only packets committed atomically with the authoritative checkpoint. Never opens a world.
 */
@RestController
@RequestMapping("/api/xrsps/sessions")
public class XrspsClientPresentationController {
  private final XrspsSessionRepository repository;
  private final ReplayPrincipalService principals;

  public XrspsClientPresentationController(
      XrspsSessionRepository repository, ReplayPrincipalService principals) {
    this.repository = repository;
    this.principals = principals;
  }

  @GetMapping("/{id}/presentation")
  public Presentation presentation(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") long afterSequence,
      @RequestParam(defaultValue = "false") boolean bootstrap,
      @RequestParam(defaultValue = "-1") long epoch,
      Principal principal) {
    if (afterSequence < 0)
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "Invalid presentation cursor");
    var doc = repository.getOwned(id, principals.requirePrincipal(principal));
    if (doc.clientBootstrap == null || !doc.clientBootstrap.isObject())
      throw ReplayException.unavailable(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          "Real-client presentation is unavailable for this worker",
          "Configure a packaged production-sync worker");
    long first =
        doc.clientFrames.isEmpty()
            ? doc.clientSequence
            : doc.clientFrames.get(0).path("sequence").asLong();
    boolean resync =
        bootstrap
            || epoch != doc.lifecycle.epoch()
            || afterSequence < first - 1
            || afterSequence > doc.clientSequence;
    List<JsonNode> frames =
        resync
            ? List.of(doc.clientBootstrap.deepCopy())
            : doc.clientFrames.stream()
                .filter(frame -> frame.path("sequence").asLong() > afterSequence)
                .limit(64)
                .map(frame -> (JsonNode) frame.deepCopy())
                .toList();
    return new Presentation(
        1,
        id,
        doc.lifecycle.epoch(),
        doc.lifecycle.status(),
        doc.lifecycle.tick(),
        resync,
        doc.clientSequence,
        frames);
  }

  public record Presentation(
      int version,
      UUID sessionId,
      long epoch,
      XrspsSessionStatus status,
      long tick,
      boolean bootstrap,
      long latestSequence,
      List<JsonNode> frames) {}
}
