package com.chromascape.web.debug;

import com.chromascape.web.decisions.entity.DecisionEntity;
import com.chromascape.web.decisions.entity.DecisionRepository;
import com.chromascape.web.instance.ScriptInstance;
import com.chromascape.web.instance.ScriptInstanceManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Lightweight rule-based inspector for identifying common failure patterns. */
@Service
public class AIInspectorService {

  private final DecisionRepository decisionRepository;

  @Autowired
  public AIInspectorService(DecisionRepository decisionRepository) {
    this.decisionRepository = decisionRepository;
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
