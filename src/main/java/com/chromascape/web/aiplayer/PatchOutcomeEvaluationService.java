package com.chromascape.web.aiplayer;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.repository.TaskEvidenceCitationRepository;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Computes read-only offline evaluation metrics for patch-outcome datasets. */
@Service
public class PatchOutcomeEvaluationService {

  private final TaskPlanItemRepository taskPlanItemRepository;
  private final TaskEvidenceCitationRepository citationRepository;

  public PatchOutcomeEvaluationService(
      TaskPlanItemRepository taskPlanItemRepository,
      TaskEvidenceCitationRepository citationRepository) {
    this.taskPlanItemRepository = taskPlanItemRepository;
    this.citationRepository = citationRepository;
  }

  public Map<String, Object> evaluate(
      LocalDateTime startTime, LocalDateTime endTime, String scriptName) {
    List<TaskPlanItem> allItems = taskPlanItemRepository.findByCompletedAtBetween(startTime, endTime);
    List<TaskPlanItem> scriptFiltered =
        allItems.stream()
            .filter(
                item ->
                    scriptName == null
                        || scriptName.isBlank()
                        || scriptName.equalsIgnoreCase(item.getScriptName()))
            .toList();

    List<TaskPlanItem> eligible = new ArrayList<>();
    for (TaskPlanItem item : scriptFiltered) {
      if (hasEvidenceTriplet(item.getItemId())) {
        eligible.add(item);
      }
    }

    int total = scriptFiltered.size();
    int eligibleCount = eligible.size();
    int excluded = total - eligibleCount;
    long successCount = eligible.stream().filter(this::isSuccess).count();
    long failureCount = eligibleCount - successCount;

    Map<String, Long> byScript =
        eligible.stream()
            .collect(
                Collectors.groupingBy(
                    item -> normalize(item.getScriptName()), LinkedHashMap::new, Collectors.counting()));
    Map<String, Long> byStatus =
        eligible.stream()
            .collect(
                Collectors.groupingBy(
                    item -> item.getStatus() != null ? item.getStatus().name() : "UNKNOWN",
                    LinkedHashMap::new,
                    Collectors.counting()));
    Map<String, Long> byResult =
        eligible.stream()
            .collect(
                Collectors.groupingBy(
                    item -> normalize(item.getExecutionResult()),
                    LinkedHashMap::new,
                    Collectors.counting()));

    List<Map<String, Object>> topScripts =
        byScript.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
            .map(entry -> Map.<String, Object>of("scriptName", entry.getKey(), "count", entry.getValue()))
            .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("startTime", startTime);
    result.put("endTime", endTime);
    result.put("scriptName", scriptName == null ? "" : scriptName);
    result.put("totalItems", total);
    result.put("eligibleItems", eligibleCount);
    result.put("excludedMissingEvidenceTriplet", excluded);
    result.put("successCount", successCount);
    result.put("failureCount", failureCount);
    result.put("successRate", eligibleCount == 0 ? 0.0d : (double) successCount / eligibleCount);
    result.put("failureRate", eligibleCount == 0 ? 0.0d : (double) failureCount / eligibleCount);
    result.put("countsByScript", byScript);
    result.put("countsByStatus", byStatus);
    result.put("countsByResult", byResult);
    result.put("topScripts", topScripts);
    return result;
  }

  private boolean isSuccess(TaskPlanItem item) {
    if (item.getStatus() == TaskPlanItem.ItemStatus.COMPLETED) {
      return true;
    }
    String result = normalize(item.getExecutionResult());
    return "SUCCESS".equals(result) || "COMPLETED".equals(result) || "PASSED".equals(result);
  }

  private boolean hasEvidenceTriplet(String itemId) {
    List<TaskEvidenceCitation> citations = citationRepository.findByItemId(itemId);
    boolean hasLog = false;
    boolean hasTick = false;
    boolean hasRecording = false;
    for (TaskEvidenceCitation citation : citations) {
      if (citation.getEvidenceType() == EvidenceType.LOG) {
        hasLog = true;
      } else if (citation.getEvidenceType() == EvidenceType.TICK_LOG) {
        hasTick = true;
      } else if (citation.getEvidenceType() == EvidenceType.RECORDING_SESSION) {
        hasRecording = true;
      }
    }
    return hasLog && hasTick && hasRecording;
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase();
  }
}
