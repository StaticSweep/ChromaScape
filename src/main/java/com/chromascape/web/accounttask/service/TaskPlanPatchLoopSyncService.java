package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  public TaskPlanPatchLoopSyncService(
      TaskPlanItemRepository itemRepository,
      PatchLoopIterationRepository iterationRepository,
      TaskPlanExecutionService executionService) {
    this.itemRepository = itemRepository;
    this.iterationRepository = iterationRepository;
    this.executionService = executionService;
  }

  @Scheduled(fixedDelayString = "${chromascape.patchloop.task-sync-interval-ms:5000}")
  @Transactional
  public void syncExecutingItems() {
    List<TaskPlanItem> executingItems =
        itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(TaskPlanItem.ItemStatus.EXECUTING);

    for (TaskPlanItem item : executingItems) {
      String iterationId = item.getPatchLoopIterationId();
      if (iterationId == null || iterationId.isBlank()) {
        continue;
      }

      Optional<PatchLoopIteration> optionalIteration = iterationRepository.findByIterationId(iterationId);
      if (optionalIteration.isEmpty()) {
        continue;
      }

      PatchLoopIteration iteration = optionalIteration.get();
      PatchLoopStatus status = iteration.getStatus();
      if (status == null || !status.isTerminal()) {
        continue;
      }

      if (status == PatchLoopStatus.COMPLETED) {
        executionService.markItemCompleted(
            item.getItemId(), "Patch loop completed: " + iteration.getIterationId());
        log.info("Marked plan item {} COMPLETED from iteration {}", item.getItemId(), iterationId);
      } else {
        String reason = buildFailureSummary(iteration);
        executionService.markItemFailed(item.getItemId(), reason);
        log.info("Marked plan item {} FAILED from iteration {} ({})", item.getItemId(), iterationId, status);
      }
    }
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
}
