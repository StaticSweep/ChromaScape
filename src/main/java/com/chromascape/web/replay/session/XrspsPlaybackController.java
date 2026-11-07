package com.chromascape.web.replay.session;

import com.chromascape.web.replay.security.ReplayPrincipalService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

/** Operator-owned clock outside the worker. It never resumes after a service restart by itself. */
@RestController
@RequestMapping("/api/xrsps/sessions")
public class XrspsPlaybackController {
  private final XrspsDurableSessionService service;
  private final ReplayPrincipalService principals;
  private final String coordinator = UUID.randomUUID().toString();
  private final Map<UUID, String> drivers = new ConcurrentHashMap<>();

  public XrspsPlaybackController(
      XrspsDurableSessionService service, ReplayPrincipalService principals) {
    this.service = service;
    this.principals = principals;
  }

  @PostMapping("/{id}/playback")
  public Map<String, Object> begin(
      @PathVariable UUID id,
      @Valid @RequestBody XrspsSessionsController.RunJournalRequest input,
      Principal principal) {
    var session = service.getOwned(id, principals.requirePrincipal(principal));
    service.bindPlayback(
        id,
        input.actions().stream().map(XrspsSessionsController.RecordedActionBody::toAction).toList(),
        coordinator);
    drivers.put(id, session.getLeaseId());
    return Map.of(
        "sessionId", id, "paced", true, "tickDurationMs", 600, "leaseId", session.getLeaseId());
  }

  @PostMapping("/{id}/seek")
  public XrspsSessionsController.SessionView seek(
      @PathVariable UUID id, @RequestBody Seek input, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    drivers.remove(id);
    return XrspsSessionsController.SessionView.of(service.seek(id, input.tick()));
  }

  @PostMapping("/{id}/recover")
  public XrspsSessionsController.SessionView recover(@PathVariable UUID id, Principal principal) {
    var current = service.getOwned(id, principals.requirePrincipal(principal));
    drivers.remove(id);
    if (current.getMode() != XrspsSessionMode.RECORDED_ACTIONS)
      throw com.chromascape.web.replay.error.ReplayException.conflict(
          com.chromascape.web.replay.error.ReplayErrorCode.RUN_STATE_INVALID,
          "Use bounded AI task recovery for this session");
    return XrspsSessionsController.SessionView.of(service.recover(id, current.getLeaseId()));
  }

  @GetMapping("/{id}/playback")
  public Map<String, Object> status(@PathVariable UUID id, Principal principal) {
    var session = service.getOwned(id, principals.requirePrincipal(principal));
    return Map.of(
        "paced", drivers.containsKey(id), "status", session.getStatus(), "tick", session.getTick());
  }

  @Scheduled(fixedDelay = 600)
  public void tick() {
    for (var entry : drivers.entrySet()) {
      try {
        var status = service.playbackTick(entry.getKey(), entry.getValue(), coordinator);
        if (status.isTerminal() || status == XrspsSessionStatus.RECONCILING)
          drivers.remove(entry.getKey(), entry.getValue());
      } catch (RuntimeException failure) {
        drivers.remove(entry.getKey(), entry.getValue());
        try {
          service.transition(
              entry.getKey(),
              session -> {
                if (session.getLeaseId().equals(entry.getValue())
                    && session.getStatus() == XrspsSessionStatus.RUNNING)
                  session.reportWorkerLoss();
              });
        } catch (RuntimeException ignored) {
          /* Persisted world remains authoritative; no automatic retry. */
        }
      }
    }
  }

  public record Seek(long tick) {}
}
