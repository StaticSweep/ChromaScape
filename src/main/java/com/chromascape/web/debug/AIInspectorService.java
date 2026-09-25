package com.chromascape.web.debug;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.decisions.entity.DecisionEntity;
import com.chromascape.web.decisions.entity.DecisionRepository;
import com.chromascape.web.instance.ScriptInstance;
import com.chromascape.web.instance.ScriptInstanceManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Lightweight rule-based inspector for identifying common failure patterns. */
@Service
public class AIInspectorService {

  private final DecisionRepository decisionRepository;
  private final DebugSessionRepository debugSessionRepository;
  private final TickLogRepository tickLogRepository;
  private final TickSnapshotRepository tickSnapshotRepository;

  @Autowired
  public AIInspectorService(
      DecisionRepository decisionRepository,
      DebugSessionRepository debugSessionRepository,
      TickLogRepository tickLogRepository,
      TickSnapshotRepository tickSnapshotRepository) {
    this.decisionRepository = decisionRepository;
    this.debugSessionRepository = debugSessionRepository;
    this.tickLogRepository = tickLogRepository;
    this.tickSnapshotRepository = tickSnapshotRepository;
  }

  /** Analyzes recent decisions and returns a diagnosis. */
  public InspectionResult analyze() {
    List<DecisionEntity> recent = decisionRepository.findAllByOrderByTimestampDesc();
    if (recent.isEmpty()) {
      return new InspectionResult(
          "NO_DATA",
          "LOW",
          List.of("No decisions recorded yet"),
          "Trigger a decision to start analysis.");
    }

    DecisionEntity latest = recent.get(0);
    LocalDateTime latestTime = latest.getTimestamp();
    ScriptInstance instance = ScriptInstanceManager.getInstance().getInstanceRef();
    boolean running = instance != null && instance.isRunning();

    if (running && latestTime != null) {
      Duration sinceLast = Duration.between(latestTime, LocalDateTime.now());
      if (sinceLast.toSeconds() > 60) {
        return new InspectionResult(
            "IDLE",
            "MEDIUM",
            List.of("Last decision over 60s ago"),
            "Verify the script is still progressing or add recovery logic.");
      }
    }

    if (Boolean.FALSE.equals(latest.getSuccess())) {
      String failureCode = normalize(latest.getFailureCode());
      if (failureCode.contains("NO_TARGET")) {
        return new InspectionResult(
            "NO_TARGET",
            "HIGH",
            List.of("Decision " + latest.getId(), latest.getFailureCode()),
            "Re-check target detection or update selectors.");
      }
      return new InspectionResult(
          "FAILED_INTERACTION",
          "HIGH",
          List.of("Decision " + latest.getId(), latest.getFailureCode()),
          "Validate interaction range and target visibility.");
    }

    int loopCount = countRepeatedDecisions(recent, 6);
    if (loopCount >= 5) {
      return new InspectionResult(
          "STUCK_IN_LOOP",
          "MEDIUM",
          List.of("Repeated decision pattern detected"),
          "Add a failsafe to break the loop or add alternate targets.");
    }

    int failureStreak = countRecentFailures(recent, 5);
    if (failureStreak >= 3) {
      List<String> evidence = new ArrayList<>();
      for (int i = 0; i < Math.min(3, recent.size()); i++) {
        DecisionEntity decision = recent.get(i);
        if (Boolean.FALSE.equals(decision.getSuccess())) {
          evidence.add("Decision " + decision.getId());
        }
      }
      return new InspectionResult(
          "REPEATED_FAILURES",
          "MEDIUM",
          evidence,
          "Inspect the failing step and add retry/backoff logic.");
    }

    return new InspectionResult(
        "OK", "LOW", List.of("No critical patterns detected"), "Monitoring for new anomalies.");
  }

  /**
   * Analyze a specific tick for potential issues using tick-based data.
   *
   * @param sessionId the debug session ID
   * @param tick the tick number to analyze
   * @param contextWindow the number of previous ticks to include in context
   * @return inspection result with tick-based analysis
   */
  public TickInspectionResult inspectTick(String sessionId, long tick, int contextWindow) {
    DebugSession session = debugSessionRepository.findBySessionId(sessionId).orElse(null);
    if (session == null) {
      return new TickInspectionResult(
          tick,
          "NO_DATA",
          "LOW",
          List.of("Session not found"),
          "Verify the session ID and try again.",
          "Start a new debug session to collect tick data.");
    }

    // Get logs for context window
    long startTick =
        Math.max(session.getStartTick() != null ? session.getStartTick() : 0, tick - contextWindow);
    List<TickLog> logs =
        tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAsc(
            sessionId, startTick, tick + contextWindow);

    // Get snapshot for the tick
    TickSnapshot snapshot =
        tickSnapshotRepository.findBySessionIdAndTick(sessionId, tick).orElse(null);

    // Analyze for issues
    String faultLabel = "OK";
    String confidence = "LOW";
    List<String> evidence = new ArrayList<>();
    String rootCause = "No issues detected at this tick.";
    String surgicalInstruction = "Continue monitoring.";

    // Check for errors in context
    List<TickLog> errors =
        logs.stream()
            .filter(log -> "Error".equalsIgnoreCase(log.getSeverity()))
            .collect(Collectors.toList());

    if (!errors.isEmpty()) {
      faultLabel = "Error Detected";
      confidence = "HIGH";
      evidence.add(errors.size() + " error(s) in context window");
      rootCause =
          "Errors found: "
              + errors.stream().map(TickLog::getMessage).collect(Collectors.joining("; "));
      surgicalInstruction = "Review error logs and add appropriate error handling.";
    }

    // Check for damage taken
    List<TickLog> damageLogs =
        logs.stream()
            .filter(
                log ->
                    log.getMessage() != null && log.getMessage().toLowerCase().contains("damage"))
            .collect(Collectors.toList());

    if (!damageLogs.isEmpty()) {
      if (!"Error Detected".equals(faultLabel)) {
        faultLabel = "Damage Taken";
        confidence = "MEDIUM";
      }
      evidence.add(damageLogs.size() + " damage event(s) in context");
      rootCause = "Damage events detected in context window.";
      surgicalInstruction = "Check combat logic and prayer usage.";
    }

    // Check player state if available
    if (snapshot != null && snapshot.getPlayerState() != null) {
      JsonNode playerState = snapshot.getPlayerState();

      // Check HP
      if (playerState.has("hp")) {
        JsonNode hp = playerState.get("hp");
        if (hp.has("current") && hp.has("max")) {
          int currentHp = hp.get("current").asInt();
          int maxHp = hp.get("max").asInt();
          double hpPercent = (double) currentHp / maxHp;

          if (hpPercent < 0.3) {
            if (!"Error Detected".equals(faultLabel)) {
              faultLabel = "Low HP";
              confidence = "HIGH";
            }
            evidence.add(String.format("HP at %d/%d (%.0f%%)", currentHp, maxHp, hpPercent * 100));
            rootCause = "HP is critically low.";
            surgicalInstruction = "Add emergency eating logic or improve threat detection.";
          }
        }
      }

      // Check prayer
      if (playerState.has("prayer")) {
        JsonNode prayer = playerState.get("prayer");
        if (prayer.has("current") && prayer.has("max")) {
          int currentPrayer = prayer.get("current").asInt();
          int maxPrayer = prayer.get("max").asInt();

          if (currentPrayer == 0 && maxPrayer > 0) {
            if (!"Error Detected".equals(faultLabel) && !"Low HP".equals(faultLabel)) {
              faultLabel = "No Prayer";
              confidence = "MEDIUM";
            }
            evidence.add("Prayer points depleted");
            rootCause = "Prayer has run out.";
            surgicalInstruction = "Add prayer potion drinking logic.";
          }
        }
      }

      // Check for threats
      if (snapshot.getThreats() != null && snapshot.getThreats().isArray()) {
        int threatCount = snapshot.getThreats().size();
        if (threatCount > 0) {
          evidence.add(threatCount + " threat(s) nearby");
        }
      }

      // Check for projectiles
      if (snapshot.getProjectiles() != null && snapshot.getProjectiles().isArray()) {
        int projectileCount = snapshot.getProjectiles().size();
        if (projectileCount > 0) {
          evidence.add(projectileCount + " projectile(s) incoming");
        }
      }
    }

    return new TickInspectionResult(
        tick, faultLabel, confidence, evidence, rootCause, surgicalInstruction);
  }

  /** Structured tick inspection result for the frontend. */
  public record TickInspectionResult(
      long tick,
      String faultLabel,
      String confidence,
      List<String> evidence,
      String rootCause,
      String surgicalInstruction) {}

  private int countRepeatedDecisions(List<DecisionEntity> recent, int limit) {
    if (recent.isEmpty()) {
      return 0;
    }
    DecisionEntity anchor = recent.get(0);
    String type = anchor.getDecisionType() != null ? anchor.getDecisionType().name() : "";
    String reason = anchor.getDecisionReason() != null ? anchor.getDecisionReason() : "";

    int count = 0;
    for (int i = 0; i < Math.min(limit, recent.size()); i++) {
      DecisionEntity decision = recent.get(i);
      String decisionType =
          decision.getDecisionType() != null ? decision.getDecisionType().name() : "";
      String decisionReason =
          decision.getDecisionReason() != null ? decision.getDecisionReason() : "";
      if (decisionType.equals(type) && decisionReason.equals(reason)) {
        count++;
      }
    }
    return count;
  }

  private int countRecentFailures(List<DecisionEntity> recent, int limit) {
    int count = 0;
    for (int i = 0; i < Math.min(limit, recent.size()); i++) {
      if (Boolean.FALSE.equals(recent.get(i).getSuccess())) {
        count++;
      }
    }
    return count;
  }

  private String normalize(String value) {
    return value == null ? "" : value.toUpperCase(Locale.ROOT);
  }

  /** Structured AI inspector result for the frontend. */
  public record InspectionResult(
      String faultType, String confidence, List<String> evidence, String suggestion) {}
}
