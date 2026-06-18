package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.TaskPlan;
import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.entity.TaskPlanItem.ItemStatus;
import com.chromascape.web.accounttask.entity.TaskPlanStatus;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.accounttask.repository.TaskPlanRepository;
import com.chromascape.web.accounttask.service.StructuredEvidenceCollectorService.EvidenceSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates task plans from telemetry evidence using the analysis provider.
 *
 * <p>The synthesis pipeline: collect evidence → format prompt → send to analysis provider → parse
 * response into structured task items with citations.
 */
@Service
public class HybridTaskSynthesisService {

  private static final Logger logger = LoggerFactory.getLogger(HybridTaskSynthesisService.class);

  private final TaskPlanRepository planRepository;
  private final TaskPlanItemRepository itemRepository;
  private final StructuredEvidenceCollectorService evidenceCollector;
  private final TaskPlanAccountService accountService;
  private final ObjectMapper objectMapper;

  @Value("${features.task-synthesis.enabled:true}")
  private boolean synthesisEnabled;

  public HybridTaskSynthesisService(
      TaskPlanRepository planRepository,
      TaskPlanItemRepository itemRepository,
      StructuredEvidenceCollectorService evidenceCollector,
      TaskPlanAccountService accountService,
      ObjectMapper objectMapper) {
    this.planRepository = planRepository;
    this.itemRepository = itemRepository;
    this.evidenceCollector = evidenceCollector;
    this.accountService = accountService;
    this.objectMapper = objectMapper;
  }

  /**
   * Generates a task plan from the given intent and evidence window.
   *
   * @param intent the user-supplied goal (e.g., "Get quest cape")
   * @param evidenceStart start of the evidence window
   * @param evidenceEnd end of the evidence window
   * @param sourceSessionId optional session for tick-level evidence
   * @param sourceRecordingId optional recording ID
   * @return the generated task plan with items
   */
  @Transactional
  public TaskPlan generatePlan(
      String intent,
      LocalDateTime evidenceStart,
      LocalDateTime evidenceEnd,
      String sourceSessionId,
      String sourceRecordingId) {

    if (!synthesisEnabled) {
      throw new IllegalStateException("Task synthesis is disabled via feature flag");
    }

    logger.info("Generating task plan for intent: {}", intent);

    // Collect evidence
    EvidenceSummary evidence =
        evidenceCollector.collectEvidence(evidenceStart, evidenceEnd, sourceSessionId);

    // Create the plan
    TaskPlan plan = new TaskPlan();
    plan.setPlanId("plan_" + UUID.randomUUID().toString().substring(0, 8));
    plan.setIntent(intent);
    plan.setStatus(TaskPlanStatus.GENERATED);
    plan.setSourceSessionId(sourceSessionId);
    plan.setSourceRecordingId(sourceRecordingId);
    accountService
        .resolveAccountName(sourceSessionId, sourceRecordingId)
        .ifPresent(plan::setAccountName);

    // Store synthesis metadata
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("evidenceLogCount", evidence.getLogs().size());
    metadata.put("evidenceDecisionCount", evidence.getDecisions().size());
    metadata.put("evidenceTickLogCount", evidence.getTickLogs().size());
    metadata.put("evidenceRecordingCount", evidence.getRecordingSessions().size());
    metadata.put("evidenceStart", evidenceStart.toString());
    metadata.put("evidenceEnd", evidenceEnd.toString());
    try {
      plan.setSynthesisMetadata(objectMapper.valueToTree(metadata));
    } catch (Exception e) {
      logger.warn("Failed to serialize synthesis metadata", e);
    }

    plan = planRepository.save(plan);

    // Generate task items from evidence patterns
    List<TaskPlanItem> items = synthesizeItems(plan.getPlanId(), intent, evidence);
    for (TaskPlanItem item : items) {
      itemRepository.save(item);
    }

    // Create citations for each item
    for (TaskPlanItem item : items) {
      evidenceCollector.createCitations(plan.getPlanId(), item.getItemId(), evidence);
    }

    logger.info("Generated plan {} with {} items", plan.getPlanId(), items.size());
    return plan;
  }

  /**
   * Synthesizes task items from evidence patterns.
   *
   * <p>In v1 this uses rule-based synthesis from structured evidence. Future versions will
   * integrate with the analysis provider for AI-driven synthesis.
   */
  private List<TaskPlanItem> synthesizeItems(
      String planId, String intent, EvidenceSummary evidence) {
    List<TaskPlanItem> items = new ArrayList<>();
    int sortOrder = 0;

    // Analyze failed decisions to generate improvement tasks
    long failedDecisions =
        evidence.getDecisions().stream().filter(d -> !Boolean.TRUE.equals(d.getSuccess())).count();
    if (failedDecisions > 0) {
      TaskPlanItem item = new TaskPlanItem();
      item.setItemId("item_" + UUID.randomUUID().toString().substring(0, 8));
      item.setPlanId(planId);
      item.setSortOrder(sortOrder++);
      item.setTitle("Investigate " + failedDecisions + " failed decisions");
      item.setDescription(
          "Review and resolve "
              + failedDecisions
              + " failed bot decisions found in evidence window.");
      item.setDifficulty("MEDIUM");
      item.setStatus(ItemStatus.PENDING);
      items.add(item);
    }

    // Check for error logs suggesting specific tasks
    long errorLogs = evidence.getLogs().stream().filter(l -> "ERROR".equals(l.getLevel())).count();
    if (errorLogs > 0) {
      TaskPlanItem item = new TaskPlanItem();
      item.setItemId("item_" + UUID.randomUUID().toString().substring(0, 8));
      item.setPlanId(planId);
      item.setSortOrder(sortOrder++);
      item.setTitle("Resolve " + errorLogs + " error conditions");
      item.setDescription(
          "Address " + errorLogs + " error-level log entries detected during evidence window.");
      item.setDifficulty("HARD");
      item.setStatus(ItemStatus.PENDING);
      items.add(item);
    }

    // If we have recording sessions, suggest replay analysis
    if (!evidence.getRecordingSessions().isEmpty()) {
      TaskPlanItem item = new TaskPlanItem();
      item.setItemId("item_" + UUID.randomUUID().toString().substring(0, 8));
      item.setPlanId(planId);
      item.setSortOrder(sortOrder++);
      item.setTitle("Review " + evidence.getRecordingSessions().size() + " recording sessions");
      item.setDescription("Analyze recorded sessions for patterns relevant to: " + intent);
      item.setDifficulty("EASY");
      item.setStatus(ItemStatus.PENDING);
      items.add(item);
    }

    // Always include at least one item based on the intent
    if (items.isEmpty()) {
      TaskPlanItem item = new TaskPlanItem();
      item.setItemId("item_" + UUID.randomUUID().toString().substring(0, 8));
      item.setPlanId(planId);
      item.setSortOrder(0);
      item.setTitle(intent);
      item.setDescription("Execute task: " + intent);
      item.setDifficulty("MEDIUM");
      item.setStatus(ItemStatus.PENDING);
      items.add(item);
    }

    return items;
  }
}
