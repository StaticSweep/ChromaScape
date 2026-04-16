package com.chromascape.web.aiplayer;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
import com.chromascape.web.accounttask.entity.TaskPlan;
import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.repository.TaskEvidenceCitationRepository;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.accounttask.repository.TaskPlanRepository;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds imitation learning datasets from shadow sessions.
 *
 * <p>Collects tick-level logs, decisions, and actions from past recording sessions and exports them
 * as structured JSONL files suitable for training imitation-learning models.
 */
@Service
public class ImitationDatasetBuilderService {

  private static final Logger log = LoggerFactory.getLogger(ImitationDatasetBuilderService.class);

  private final LogRepository logRepository;
  private final TaskPlanItemRepository taskPlanItemRepository;
  private final TaskPlanRepository taskPlanRepository;
  private final TaskEvidenceCitationRepository citationRepository;

  @Value("${features.ai-player.enabled:false}")
  private boolean aiPlayerEnabled;

  @Value("${ai-player.dataset.output-dir:.chromascape/datasets}")
  private String outputDir;

  public ImitationDatasetBuilderService(
      LogRepository logRepository,
      TaskPlanItemRepository taskPlanItemRepository,
      TaskPlanRepository taskPlanRepository,
      TaskEvidenceCitationRepository citationRepository) {
    this.logRepository = logRepository;
    this.taskPlanItemRepository = taskPlanItemRepository;
    this.taskPlanRepository = taskPlanRepository;
    this.citationRepository = citationRepository;
  }

  /**
   * Builds an imitation dataset for a given time range.
   *
   * @param accountName optional account filter
   * @param startTime start of the evidence window
   * @param endTime end of the evidence window
   * @return path to the generated dataset file
   */
  public String buildDataset(String accountName, LocalDateTime startTime, LocalDateTime endTime) {
    if (!aiPlayerEnabled) {
      throw new IllegalStateException("AI player feature is disabled");
    }

    log.info("Building imitation dataset for account={} from={} to={}", accountName, startTime, endTime);

    String datasetId = "dataset_" + UUID.randomUUID().toString().substring(0, 8);
    Path outputPath = Paths.get(outputDir, datasetId + ".jsonl");

    try {
      Files.createDirectories(outputPath.getParent());
    } catch (IOException e) {
      throw new RuntimeException("Failed to create dataset directory: " + e.getMessage(), e);
    }

    // Collect tick-level logs from the source
    List<LogEntity> logs;
    if (accountName != null && !accountName.isBlank()) {
      logs =
          logRepository.findBySourceAndTimestampBetweenOrderByTimestampAsc(
              accountName, startTime, endTime);
    } else {
      logs = logRepository.findByTimestampBetweenOrderByTimestampAsc(startTime, endTime);
    }

    log.info("Collected {} log entries for dataset {}", logs.size(), datasetId);

    int recordsWritten = 0;
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
      for (LogEntity logEntity : logs) {
        String jsonl =
            String.format(
                "{\"tick\":%s,\"timestamp\":\"%s\",\"source\":\"%s\",\"message\":\"%s\",\"level\":\"%s\"}",
                escapeJson(String.valueOf(logEntity.getTick())),
                logEntity.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                escapeJson(logEntity.getSource()),
                escapeJson(logEntity.getMessage()),
                escapeJson(logEntity.getLevel()));
        writer.write(jsonl);
        writer.newLine();
        recordsWritten++;
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to write dataset: " + e.getMessage(), e);
    }

    log.info("Dataset {} written: {} records to {}", datasetId, recordsWritten, outputPath);
    return outputPath.toString();
  }

  /**
   * Builds a patch-outcome dataset for future policy training.
   *
   * <p>Includes intent + task metadata + evidence references + execution outcome, and excludes
   * items that do not have the full evidence triplet.
   */
  public String buildPatchOutcomeDataset(LocalDateTime startTime, LocalDateTime endTime) {
    if (!aiPlayerEnabled) {
      throw new IllegalStateException("AI player feature is disabled");
    }

    String datasetId = "patch_outcomes_" + UUID.randomUUID().toString().substring(0, 8);
    Path outputPath = Paths.get(outputDir, datasetId + ".jsonl");
    try {
      Files.createDirectories(outputPath.getParent());
    } catch (IOException e) {
      throw new RuntimeException("Failed to create dataset directory: " + e.getMessage(), e);
    }

    List<TaskPlanItem> items = taskPlanItemRepository.findByCompletedAtBetween(startTime, endTime);
    Set<String> planIds = new HashSet<>();
    for (TaskPlanItem item : items) {
      planIds.add(item.getPlanId());
    }

    Map<String, TaskPlan> plansById = new HashMap<>();
    if (!planIds.isEmpty()) {
      for (TaskPlan plan : taskPlanRepository.findByPlanIdIn(planIds.stream().toList())) {
        plansById.put(plan.getPlanId(), plan);
      }
    }

    int written = 0;
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
      for (TaskPlanItem item : items) {
        if (!hasEvidenceTriplet(item.getItemId())) {
          continue;
        }
        TaskPlan plan = plansById.get(item.getPlanId());
        String jsonl =
            String.format(
                "{\"planId\":\"%s\",\"itemId\":\"%s\",\"intent\":\"%s\",\"scriptName\":\"%s\",\"title\":\"%s\",\"status\":\"%s\",\"result\":\"%s\",\"patchLoopIterationId\":\"%s\",\"completedAt\":\"%s\"}",
                escapeJson(item.getPlanId()),
                escapeJson(item.getItemId()),
                escapeJson(plan != null ? plan.getIntent() : ""),
                escapeJson(item.getScriptName()),
                escapeJson(item.getTitle()),
                escapeJson(item.getStatus() != null ? item.getStatus().name() : ""),
                escapeJson(item.getExecutionResult()),
                escapeJson(item.getPatchLoopIterationId()),
                item.getCompletedAt() != null
                    ? item.getCompletedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    : "");
        writer.write(jsonl);
        writer.newLine();
        written++;
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to write patch-outcome dataset: " + e.getMessage(), e);
    }

    log.info("Patch-outcome dataset {} written with {} records to {}", datasetId, written, outputPath);
    return outputPath.toString();
  }

  private String escapeJson(String value) {
    if (value == null) return "";
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }

  /**
   * Returns a summary of available data for dataset building.
   *
   * @param accountName optional account filter
   * @return summary string
   */
  public String getDatasetSummary(String accountName) {
    long totalLogs = logRepository.count();
    return String.format("Total logs available: %d (filter: %s)", totalLogs,
        accountName != null ? accountName : "all");
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

  public boolean isAiPlayerEnabled() {
    return aiPlayerEnabled;
  }
}
