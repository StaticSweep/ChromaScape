package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Syncs plan item execution state from linked patch-loop iterations. */
@Service
public class TaskPlanPatchLoopSyncService {
  private static final Logger log = LoggerFactory.getLogger(TaskPlanPatchLoopSyncService.class);

  private final TaskPlanItemRepository itemRepository;
  private final PatchLoopIterationRepository iterationRepository;
  private final TaskPlanExecutionService executionService;
  private final long executingTimeoutMs;
  private final boolean failStale;

  public TaskPlanPatchLoopSyncService(
      TaskPlanItemRepository itemRepository,
      PatchLoopIterationRepository iterationRepository,
      TaskPlanExecutionService executionService,
      @Value("${chromascape.patchloop.task-sync-executing-timeout-ms:1800000}")
          long executingTimeoutMs,
      @Value("${chromascape.patchloop.task-sync-fail-stale:true}") boolean failStale) {
    this.itemRepository = itemRepository;
    this.iterationRepository = iterationRepository;
    this.executionService = executionService;
    this.executingTimeoutMs = executingTimeoutMs;
    this.failStale = failStale;
  }

  @Scheduled(fixedDelayString = "${chromascape.patchloop.task-sync-interval-ms:5000}")
  @Transactional
  public void syncExecutingItems() {
    List<TaskPlanItem> executingItems =
        itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING);

    int scanned = executingItems.size();
    int completed = 0;
    int failedTerminal = 0;
    int failedStale = 0;
    int skippedNonTerminal = 0;
    int skippedMissingIteration = 0;

    for (TaskPlanItem item : executingItems) {
      String iterationId = item.getPatchLoopIterationId();
      if (iterationId == null || iterationId.isBlank()) {
        continue;
      }

      Optional<PatchLoopIteration> optionalIteration =
          iterationRepository.findByIterationId(iterationId);

      if (optionalIteration.isEmpty()) {
        if (failStale && isStale(item, null)) {
          String reason = buildStaleFailureSummary(item, null, "missing iteration");
          executionService.markItemFailed(item.getItemId(), reason);
          failedStale++;
          log.info(
              "Marked plan item {} FAILED (stale, missing iteration {})",
              item.getItemId(),
              iterationId);
        } else {
          skippedMissingIteration++;
        }
        continue;
      }

      PatchLoopIteration iteration = optionalIteration.get();
      PatchLoopStatus status = iteration.getStatus();

      if (status != null && status.isTerminal()) {
        if (status == PatchLoopStatus.COMPLETED) {
          executionService.markItemCompleted(
              item.getItemId(), "Patch loop completed: " + iteration.getIterationId());
          completed++;
          log.info(
              "Marked plan item {} COMPLETED from iteration {}", item.getItemId(), iterationId);
        } else {
          String reason = buildFailureSummary(iteration);
          executionService.markItemFailed(item.getItemId(), reason);
          failedTerminal++;
          log.info(
              "Marked plan item {} FAILED from iteration {} ({})",
              item.getItemId(),
              iterationId,
              status);
        }
      } else if (failStale && isStale(item, iteration)) {
        String nonTerminalStatus = status != null ? status.name() : "null";
        String reason =
            buildStaleFailureSummary(item, iteration, "non-terminal " + nonTerminalStatus);
        executionService.markItemFailed(item.getItemId(), reason);
        failedStale++;
        log.info(
            "Marked plan item {} FAILED (stale, iteration {} status {})",
            item.getItemId(),
            iterationId,
            nonTerminalStatus);
      } else {
        skippedNonTerminal++;
      }
    }

    if (scanned > 0) {
      log.info(
          "Task-plan sync cycle: scanned={}, completed={}, failedTerminal={}, failedStale={}, skippedNonTerminal={}, skippedMissing={}",
          scanned,
          completed,
          failedTerminal,
          failedStale,
          skippedNonTerminal,
          skippedMissingIteration);
    }
  }

  /** Checks whether an EXECUTING item has exceeded the stale timeout. */
  boolean isStale(TaskPlanItem item, PatchLoopIteration iterationOrNull) {
    LocalDateTime activityTs = resolveActivityTimestamp(item, iterationOrNull);
    if (activityTs == null) {
      return false;
    }
    Duration age = Duration.between(activityTs, LocalDateTime.now());
    return age.toMillis() > executingTimeoutMs;
  }

  /** Resolves the most recent activity timestamp using the spec precedence order. */
  LocalDateTime resolveActivityTimestamp(TaskPlanItem item, PatchLoopIteration iterationOrNull) {
    if (iterationOrNull != null) {
      if (iterationOrNull.getLatestEventAt() != null) {
        return iterationOrNull.getLatestEventAt();
      }
      if (iterationOrNull.getLastStateAt() != null) {
        return iterationOrNull.getLastStateAt();
      }
      if (iterationOrNull.getCreatedAt() != null) {
        return iterationOrNull.getCreatedAt();
      }
    }
    if (item.getExecutedAt() != null) {
      return item.getExecutedAt();
    }
    return item.getCreatedAt();
  }

  private String buildFailureSummary(PatchLoopIteration iteration) {
    StringBuilder summary = new StringBuilder();
    summary.append("Patch loop ended with ").append(iteration.getStatus());
    if (iteration.getFailureCode() != null && !iteration.getFailureCode().isBlank()) {
      summary.append(" [").append(iteration.getFailureCode()).append("]");
    }
    if (iteration.getFailureReason() != null && !iteration.getFailureReason().isBlank()) {
      summary.append(": ").append(iteration.getFailureReason());
    }
    return summary.toString();
  }

  private String buildStaleFailureSummary(
      TaskPlanItem item, PatchLoopIteration iterationOrNull, String detail) {
    StringBuilder sb = new StringBuilder();
    sb.append("Stale execution auto-fail (").append(detail).append(")");
    sb.append(" for iteration ").append(item.getPatchLoopIterationId());
    if (iterationOrNull != null) {
      sb.append(", iteration status=").append(iterationOrNull.getStatus());
    }
    sb.append(", timeoutMs=").append(executingTimeoutMs);
    return sb.toString();
  }
}
