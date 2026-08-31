package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Detects task-bound exceptions and silent stalls without invoking or applying a repair. */
@Service
public class BuilderGoalRepairIncidentService {

  private static final String SOURCE_TYPE_BUILDER_GOAL = "builder_goal";

  private final PatchLoopIterationRepository iterationRepository;
  private final BuilderGoalRunStateMutationService mutationService;
  private final long stallMillis;
  private final long cooldownMillis;
  private final LongSupplier clock;
  private final Map<String, Observation> observations = new ConcurrentHashMap<>();

  @Autowired
  public BuilderGoalRepairIncidentService(
      PatchLoopIterationRepository iterationRepository,
      BuilderGoalRunStateMutationService mutationService,
      @Value("${builder-goal.repair.stall-seconds:180}") long stallSeconds,
      @Value("${builder-goal.repair.cooldown-seconds:900}") long cooldownSeconds) {
    this(
        iterationRepository,
        mutationService,
        stallSeconds,
        cooldownSeconds,
        System::currentTimeMillis);
  }

  BuilderGoalRepairIncidentService(
      PatchLoopIterationRepository iterationRepository,
      BuilderGoalRunStateMutationService mutationService,
      long stallSeconds,
      long cooldownSeconds,
      LongSupplier clock) {
    this.iterationRepository = iterationRepository;
    this.mutationService = mutationService;
    this.stallMillis = Math.max(30L, stallSeconds) * 1000L;
    this.cooldownMillis = Math.max(60L, cooldownSeconds) * 1000L;
    this.clock = clock;
  }

  public synchronized void observe(TickBatchMessage batch) {
    if (batch == null || batch.getSessionId() == null || batch.getSessionId().isBlank()) {
      return;
    }
    PatchLoopIteration iteration =
        iterationRepository
            .findFirstBySourceTypeAndRunSessionIdOrderByCreatedAtDesc(
                SOURCE_TYPE_BUILDER_GOAL, batch.getSessionId())
            .orElse(null);
    if (iteration == null) {
      return;
    }

    long now = clock.getAsLong();
    Observation observation =
        observations.computeIfAbsent(iteration.getIterationId(), ignored -> new Observation(now));

    TickBatchMessage.TickLogEntry error = firstError(batch.getLogs());
    if (error != null) {
      String reason = firstNonBlank(error.getMessage(), error.getEventType(), "Task error log");
      String incidentFingerprint = "EXCEPTION|" + normalizeError(reason);
      recordIncident(
          iteration, batch, observation, now, "EXCEPTION", reason, incidentFingerprint, 0L);
      return;
    }

    if (iteration.getStatus() != null && iteration.getStatus().isTerminal()) {
      observations.remove(iteration.getIterationId());
      return;
    }

    if (isIntentionalWait(batch.getMicrobotStatus())) {
      observation.lastProgressAt = now;
      observation.lastFingerprint = progressFingerprint(batch);
      return;
    }

    String fingerprint = progressFingerprint(batch);
    if (!Objects.equals(fingerprint, observation.lastFingerprint)) {
      observation.lastFingerprint = fingerprint;
      observation.lastProgressAt = now;
      return;
    }

    long stagnantMillis = now - observation.lastProgressAt;
    if (stagnantMillis >= stallMillis) {
      String reason =
          "No meaningful runtime progress for " + (stagnantMillis / 1000L) + " seconds";
      recordIncident(
          iteration,
          batch,
          observation,
          now,
          "STALL",
          reason,
          "STALL|" + fingerprint,
          stagnantMillis / 1000L);
    }
  }

  private void recordIncident(
      PatchLoopIteration iteration,
      TickBatchMessage batch,
      Observation observation,
      long now,
      String type,
      String reason,
      String fingerprint,
      long stagnantSeconds) {
    if (now - observation.lastIncidentAt < cooldownMillis
        && Objects.equals(fingerprint, observation.lastIncidentFingerprint)) {
      return;
    }

    Map<String, Object> evidenceWindow = new LinkedHashMap<>();
    evidenceWindow.put("start_tick", Math.max(0L, batch.getTick() - 300L));
    evidenceWindow.put("end_tick", batch.getTick());

    Map<String, Object> incident = new LinkedHashMap<>();
    incident.put("type", type);
    incident.put("reason", reason);
    incident.put("state", "OPEN");
    incident.put("detected_at", Instant.ofEpochMilli(now).toString());
    incident.put("updated_at", Instant.ofEpochMilli(now).toString());
    incident.put("session_id", batch.getSessionId());
    incident.put("tick", batch.getTick());
    incident.put("fingerprint", fingerprint);
    incident.put("stagnant_seconds", stagnantSeconds);
    incident.put("evidence_window", evidenceWindow);
    incident.put("microbot_status", batch.getMicrobotStatus());
    incident.put("active_task_key", batch.getActiveTaskKey());
    incident.put(
        "active_plugin_classes",
        batch.getActivePluginClasses() == null ? List.of() : batch.getActivePluginClasses());
    incident.put("cooldown_until", Instant.ofEpochMilli(now + cooldownMillis).toString());
    mutationService.recordRepairIncident(iteration.getIterationId(), incident);

    observation.lastIncidentAt = now;
    observation.lastIncidentFingerprint = fingerprint;
    observation.lastProgressAt = now;
  }

  private TickBatchMessage.TickLogEntry firstError(List<TickBatchMessage.TickLogEntry> logs) {
    if (logs == null) {
      return null;
    }
    for (TickBatchMessage.TickLogEntry entry : logs) {
      if (entry != null && "ERROR".equalsIgnoreCase(entry.getLevel())) {
        return entry;
      }
    }
    return null;
  }

  private String progressFingerprint(TickBatchMessage batch) {
    Map<String, Object> player = batch.getPlayerState();
    Object position =
        player == null
            ? null
            : firstNonNull(player.get("position"), player.get("worldX"), player.get("x"));
    Object worldY =
        player == null
            ? null
            : firstNonNull(player.get("worldY"), player.get("z"), player.get("y"));
    Object animation =
        player == null
            ? null
            : firstNonNull(player.get("animation"), player.get("animationId"));
    Object interacting = player == null ? null : player.get("interacting");
    Object skills = player == null ? null : player.get("skills");
    return Integer.toHexString(
        Objects.hash(
            position,
            worldY,
            animation,
            interacting,
            skills,
            batch.getInventory(),
            batch.getMicrobotStatus(),
            batch.getActiveTaskKey()));
  }

  private boolean isIntentionalWait(String status) {
    String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    return value.equals("idle")
        || value.contains("break")
        || value.contains("logout")
        || value.contains("waiting for login")
        || value.contains("world hop")
        || value.contains("hopping world")
        || value.contains("paused");
  }

  private String normalizeError(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("0x[0-9a-f]+|\\d+", "#").trim();
  }

  private Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static final class Observation {
    private String lastFingerprint;
    private long lastProgressAt;
    private String lastIncidentFingerprint;
    private long lastIncidentAt;

    private Observation(long now) {
      this.lastProgressAt = now;
    }
  }
}
