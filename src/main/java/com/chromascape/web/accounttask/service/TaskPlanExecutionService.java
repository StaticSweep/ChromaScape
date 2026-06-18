package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.TaskPlan;
import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.entity.TaskPlanItem.ItemStatus;
import com.chromascape.web.accounttask.entity.TaskPlanStatus;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.accounttask.repository.TaskPlanRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manages the lifecycle of task plans: approval, rejection, execution tracking. */
@Service
public class TaskPlanExecutionService {

  private static final Logger logger = LoggerFactory.getLogger(TaskPlanExecutionService.class);

  private final TaskPlanRepository planRepository;
  private final TaskPlanItemRepository itemRepository;

  public TaskPlanExecutionService(
      TaskPlanRepository planRepository, TaskPlanItemRepository itemRepository) {
    this.planRepository = planRepository;
    this.itemRepository = itemRepository;
  }

  /** Approves a task plan. */
  @Transactional
  public TaskPlan approvePlan(String planId) {
    TaskPlan plan =
        planRepository
            .findByPlanId(planId)
            .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

    plan.setStatus(TaskPlanStatus.APPROVED);
    plan.setApprovedAt(LocalDateTime.now());
    plan = planRepository.save(plan);

    // Approve all pending items
    List<TaskPlanItem> items = itemRepository.findByPlanIdOrderBySortOrderAsc(planId);
    for (TaskPlanItem item : items) {
      if (item.getStatus() == ItemStatus.PENDING) {
        item.setStatus(ItemStatus.APPROVED);
        item.setApprovedAt(LocalDateTime.now());
        itemRepository.save(item);
      }
    }

    logger.info("Approved plan {} with {} items", planId, items.size());
    return plan;
  }

  /** Rejects a task plan. */
  @Transactional
  public TaskPlan rejectPlan(String planId) {
    TaskPlan plan =
        planRepository
            .findByPlanId(planId)
            .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

    plan.setStatus(TaskPlanStatus.REJECTED);
    plan = planRepository.save(plan);

    // Reject all pending items
    List<TaskPlanItem> items = itemRepository.findByPlanIdOrderBySortOrderAsc(planId);
    for (TaskPlanItem item : items) {
      if (item.getStatus() == ItemStatus.PENDING) {
        item.setStatus(ItemStatus.REJECTED);
        itemRepository.save(item);
      }
    }

    logger.info("Rejected plan {}", planId);
    return plan;
  }

  /** Approves a single item within a plan. */
  @Transactional
  public TaskPlanItem approveItem(String itemId) {
    TaskPlanItem item =
        itemRepository
            .findByItemId(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

    item.setStatus(ItemStatus.APPROVED);
    item.setApprovedAt(LocalDateTime.now());
    item = itemRepository.save(item);

    // Update plan status if all items are now approved
    updatePlanStatus(item.getPlanId());

    return item;
  }

  /** Rejects a single item within a plan. */
  @Transactional
  public TaskPlanItem rejectItem(String itemId) {
    TaskPlanItem item =
        itemRepository
            .findByItemId(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

    item.setStatus(ItemStatus.REJECTED);
    item = itemRepository.save(item);

    updatePlanStatus(item.getPlanId());
    return item;
  }

  /** Updates an item's details before execution. */
  @Transactional
  public TaskPlanItem editItem(String itemId, String title, String description, String scriptName) {
    TaskPlanItem item =
        itemRepository
            .findByItemId(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

    if (title != null) {
      item.setTitle(title);
    }
    if (description != null) {
      item.setDescription(description);
    }
    if (scriptName != null) {
      item.setScriptName(scriptName);
    }
    return itemRepository.save(item);
  }

  /** Marks an item as executing and links it to a patch loop iteration. */
  @Transactional
  public TaskPlanItem markItemExecuting(String itemId, String patchLoopIterationId) {
    TaskPlanItem item =
        itemRepository
            .findByItemId(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

    item.setStatus(ItemStatus.EXECUTING);
    item.setPatchLoopIterationId(patchLoopIterationId);
    item.setExecutedAt(LocalDateTime.now());
    item = itemRepository.save(item);

    // Update plan to executing
    TaskPlan plan = planRepository.findByPlanId(item.getPlanId()).orElseThrow();
    if (plan.getStatus() == TaskPlanStatus.APPROVED
        || plan.getStatus() == TaskPlanStatus.PARTIALLY_APPROVED) {
      plan.setStatus(TaskPlanStatus.EXECUTING);
      planRepository.save(plan);
    }

    return item;
  }

  /** Marks an item as completed with a result summary. */
  @Transactional
  public TaskPlanItem markItemCompleted(String itemId, String result) {
    TaskPlanItem item =
        itemRepository
            .findByItemId(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

    item.setStatus(ItemStatus.COMPLETED);
    item.setExecutionResult(result);
    item.setCompletedAt(LocalDateTime.now());
    item = itemRepository.save(item);

    updatePlanStatus(item.getPlanId());
    return item;
  }

  /** Marks an item as failed with a result summary. */
  @Transactional
  public TaskPlanItem markItemFailed(String itemId, String reason) {
    TaskPlanItem item =
        itemRepository
            .findByItemId(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

    item.setStatus(ItemStatus.FAILED);
    item.setExecutionResult(reason);
    item.setCompletedAt(LocalDateTime.now());
    item = itemRepository.save(item);

    updatePlanStatus(item.getPlanId());
    return item;
  }

  /** Recalculates plan status based on item states. */
  private void updatePlanStatus(String planId) {
    TaskPlan plan = planRepository.findByPlanId(planId).orElseThrow();
    List<TaskPlanItem> items = itemRepository.findByPlanIdOrderBySortOrderAsc(planId);

    boolean allCompleted = items.stream().allMatch(i -> i.getStatus() == ItemStatus.COMPLETED);
    boolean anyFailed = items.stream().anyMatch(i -> i.getStatus() == ItemStatus.FAILED);

    if (allCompleted) {
      plan.setStatus(TaskPlanStatus.COMPLETED);
      plan.setCompletedAt(LocalDateTime.now());
    } else if (anyFailed) {
      plan.setStatus(TaskPlanStatus.FAILED);
    }

    planRepository.save(plan);
  }
}
