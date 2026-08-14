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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p>Collects logs and task outcomes and exports them as JSONL plus a provenance manifest.
 */
@Service
public class ImitationDatasetBuilderService {

  private static final Logger log = LoggerFactory.getLogger(ImitationDatasetBuilderService.class);
  private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final LogRepository logRepository;
  private final TaskPlanItemRepository taskPlanItemRepository;
  private final TaskPlanRepository taskPlanRepository;
  private final TaskEvidenceCitationRepository citationRepository;
  private final ObjectMapper objectMapper;

  @Value("${features.ai-player.enabled:false}")
  private boolean aiPlayerEnabled;

  @Value("${features.ai-player.shadow-mode:false}")
  private boolean shadowMode;

  @Value("${features.ai-player.control-enabled:false}")
  private boolean controlEnabled;

  @Value("${ai-player.dataset.output-dir:.chromascape/datasets}")
  private String outputDir;

  public ImitationDatasetBuilderService(
      LogRepository logRepository,
      TaskPlanItemRepository taskPlanItemRepository,
      TaskPlanRepository taskPlanRepository,
      TaskEvidenceCitationRepository citationRepository,
      ObjectMapper objectMapper) {
    this.logRepository = logRepository;
    this.taskPlanItemRepository = taskPlanItemRepository;
    this.taskPlanRepository = taskPlanRepository;
    this.citationRepository = citationRepository;
    this.objectMapper = objectMapper;
  }

  public String buildDataset(String accountName, LocalDateTime startTime, LocalDateTime endTime) {
    return buildDatasetResult(accountName, startTime, endTime).getOutputPath();
  }

  public DatasetBuildResult buildDatasetResult(
      String accountName, LocalDateTime startTime, LocalDateTime endTime) {
    ensureEnabled();

    log.info(
        "Building imitation dataset for account={} from={} to={}", accountName, startTime, endTime);

    String datasetId = "dataset_" + UUID.randomUUID().toString().substring(0, 8);
    List<LogEntity> logs = loadLogs(accountName, startTime, endTime);
    List<Map<String, Object>> records = new ArrayList<>(logs.size());
    for (LogEntity logEntity : logs) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tick", logEntity.getTick());
      row.put("timestamp", formatTimestamp(logEntity.getTimestamp()));
      row.put("source", logEntity.getSource());
      row.put("message", logEntity.getMessage());
      row.put("level", logEntity.getLevel());
      records.add(row);
    }

    Map<String, Object> manifest =
        baseManifest(
            "logs", datasetId, startTime, endTime, accountName, records.size(), logs.size(), 0);
    manifest.put("source_tables", List.of("logs"));
    manifest.put("source_signals", List.of("logs", "ticks", "script_name"));
    manifest.put("evidence_triplet_filter", Map.of("applied", false, "reason", "not_applicable"));
    return writeDataset(datasetId, "logs", records, manifest, logs.size(), 0);
  }

  public String buildPatchOutcomeDataset(LocalDateTime startTime, LocalDateTime endTime) {
    return buildPatchOutcomeDatasetResult(startTime, endTime).getOutputPath();
  }

  /**
   * Builds a patch-outcome dataset for future policy training.
   *
   * <p>Includes intent + task metadata + evidence references + execution outcome, and excludes
   * items that do not have the full evidence triplet.
   */
  public DatasetBuildResult buildPatchOutcomeDatasetResult(
      LocalDateTime startTime, LocalDateTime endTime) {
    ensureEnabled();

    String datasetId = "patch_outcomes_" + UUID.randomUUID().toString().substring(0, 8);
    List<TaskPlanItem> items = taskPlanItemRepository.findByCompletedAtBetween(startTime, endTime);
    Map<String, TaskPlan> plansById = loadPlansById(items);

    List<Map<String, Object>> records = new ArrayList<>();
    int excluded = 0;
    for (TaskPlanItem item : items) {
      if (!hasEvidenceTriplet(item.getItemId())) {
        excluded++;
        continue;
      }
      TaskPlan plan = plansById.get(item.getPlanId());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("planId", item.getPlanId());
      row.put("itemId", item.getItemId());
      row.put("intent", plan != null ? plan.getIntent() : "");
      row.put("scriptName", item.getScriptName());
      row.put("title", item.getTitle());
      row.put("status", item.getStatus() != null ? item.getStatus().name() : "");
      row.put("result", item.getExecutionResult());
      row.put("patchLoopIterationId", item.getPatchLoopIterationId());
      row.put("completedAt", formatTimestamp(item.getCompletedAt()));
      records.add(row);
    }

    Map<String, Object> manifest =
        baseManifest(
            "patch_outcomes",
            datasetId,
            startTime,
            endTime,
            null,
            records.size(),
            items.size(),
            excluded);
    manifest.put("source_tables", List.of("task_plan_item", "task_plan", "task_evidence_citation"));
    manifest.put(
        "source_signals",
        List.of("task_status", "execution_result", "patch_loop_iteration", "evidence_citations"));
    manifest.put(
        "evidence_triplet_filter",
        Map.of(
            "applied",
            true,
            "required_types",
            List.of("LOG", "TICK_LOG", "RECORDING_SESSION"),
            "included_records",
            records.size(),
            "excluded_records",
            excluded));
    return writeDataset(datasetId, "patch_outcomes", records, manifest, items.size(), excluded);
  }

  private List<LogEntity> loadLogs(
      String accountName, LocalDateTime startTime, LocalDateTime endTime) {
    if (accountName != null && !accountName.isBlank()) {
      return logRepository.findBySourceAndTimestampBetweenOrderByTimestampAsc(
          accountName, startTime, endTime);
    }
    return logRepository.findByTimestampBetweenOrderByTimestampAsc(startTime, endTime);
  }

  private Map<String, TaskPlan> loadPlansById(List<TaskPlanItem> items) {
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
    return plansById;
  }

  private DatasetBuildResult writeDataset(
      String datasetId,
      String datasetKind,
      List<Map<String, Object>> records,
      Map<String, Object> manifest,
      int sourceRecordCount,
      int excludedRecordCount) {
    Path outputPath = resolveOutputPath(datasetId);
    Path manifestPath = resolveManifestPath(outputPath);

    try {
      Files.createDirectories(outputPath.getParent());
      writeJsonLines(outputPath, records);
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
    } catch (IOException e) {
      throw new RuntimeException("Failed to write dataset artifacts: " + e.getMessage(), e);
    }

    log.info(
        "Dataset {} written: {} records to {} (manifest {})",
        datasetId,
        records.size(),
        outputPath,
        manifestPath);
    return new DatasetBuildResult(
        datasetId,
        datasetKind,
        outputPath.toString(),
        manifestPath.toString(),
        records.size(),
        sourceRecordCount,
        excludedRecordCount,
        manifest,
        records);
  }

  private void writeJsonLines(Path outputPath, List<Map<String, Object>> records)
      throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
      for (Map<String, Object> record : records) {
        writer.write(objectMapper.writeValueAsString(record));
        writer.newLine();
      }
    }
  }

  private Path resolveOutputPath(String datasetId) {
    return Paths.get(outputDir, datasetId + ".jsonl");
  }

  private Path resolveManifestPath(Path outputPath) {
    String name = outputPath.getFileName().toString();
    if (name.endsWith(".jsonl")) {
      name = name.substring(0, name.length() - ".jsonl".length());
    }
    return outputPath.getParent().resolve(name + ".manifest.json");
  }

  private Map<String, Object> baseManifest(
      String datasetKind,
      String datasetId,
      LocalDateTime startTime,
      LocalDateTime endTime,
      String accountName,
      int recordCount,
      int sourceRecordCount,
      int excludedRecordCount) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("dataset_id", datasetId);
    manifest.put("dataset_kind", datasetKind);
    manifest.put("generated_at", formatTimestamp(LocalDateTime.now()));
    manifest.put("record_count", recordCount);
    manifest.put("source_record_count", sourceRecordCount);
    manifest.put("excluded_record_count", excludedRecordCount);
    manifest.put(
        "time_window",
        Map.of("start", formatTimestamp(startTime), "end", formatTimestamp(endTime)));
    if (accountName != null && !accountName.isBlank()) {
      manifest.put("account_filter", accountName);
    }
    manifest.put(
        "feature_flags",
        Map.of(
            "ai_player_enabled", aiPlayerEnabled,
            "shadow_mode", shadowMode,
            "control_enabled", controlEnabled,
            "control_allowed", false));
    return manifest;
  }

  private String formatTimestamp(LocalDateTime value) {
    return value != null ? value.format(TS_FORMAT) : "";
  }

  private void ensureEnabled() {
    if (!aiPlayerEnabled) {
      throw new IllegalStateException("AI player feature is disabled");
    }
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

  /**
   * Returns a summary of available data for dataset building.
   *
   * @param accountName optional account filter
   * @return summary string
   */
  public String getDatasetSummary(String accountName) {
    long totalLogs = logRepository.count();
    return String.format(
        "Total logs available: %d (filter: %s)",
        totalLogs, accountName != null ? accountName : "all");
  }

  public boolean isAiPlayerEnabled() {
    return aiPlayerEnabled;
  }

  public String getOutputDir() {
    return outputDir;
  }
}
