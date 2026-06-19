package com.chromascape.web.accounttask.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskPlanPatchLoopSyncServiceTest {

  private static final String ITEM_ID = "item_test1";
  private static final String ITERATION_ID = "iter_test1";

  @Mock private TaskPlanItemRepository itemRepository;
  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private TaskPlanExecutionService executionService;

  private TaskPlanPatchLoopSyncService service;

  @BeforeEach
  void setUp() {
    service = new TaskPlanPatchLoopSyncService(itemRepository, iterationRepository, executionService);
  }

  @Test
  @DisplayName("marks COMPLETED when linked iteration is COMPLETED")
  void syncExecutingItems_marksCompleted_whenIterationCompleted() {
    TaskPlanItem item = executingItem();
    PatchLoopIteration iter = iteration(PatchLoopStatus.COMPLETED);

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.of(iter));

    service.syncExecutingItems();

    verify(executionService).markItemCompleted(eq(ITEM_ID), anyString());
    verify(executionService, never()).markItemFailed(anyString(), anyString());
  }

  @Test
  @DisplayName("marks FAILED when linked iteration is terminal and failed")
  void syncExecutingItems_marksFailed_whenIterationFailed() {
    TaskPlanItem item = executingItem();
    PatchLoopIteration iter = iteration(PatchLoopStatus.FAILED);
    iter.setFailureCode("SCRIPT_ERROR");
    iter.setFailureReason("Script crashed");

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.of(iter));

    service.syncExecutingItems();

    verify(executionService).markItemFailed(eq(ITEM_ID), anyString());
    verify(executionService, never()).markItemCompleted(anyString(), anyString());
  }

  @Test
  @DisplayName("skips when iteration is non-terminal")
  void syncExecutingItems_skips_whenIterationNonTerminal() {
    TaskPlanItem item = executingItem();
    PatchLoopIteration iter = iteration(PatchLoopStatus.RUNNING_SCRIPT);

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.of(iter));

    service.syncExecutingItems();

    verify(executionService, never()).markItemCompleted(anyString(), anyString());
    verify(executionService, never()).markItemFailed(anyString(), anyString());
  }

  @Test
  @DisplayName("skips when linked iteration is missing")
  void syncExecutingItems_skips_whenIterationMissing() {
    TaskPlanItem item = executingItem();

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.empty());

    service.syncExecutingItems();

    verify(executionService, never()).markItemFailed(anyString(), anyString());
    verify(executionService, never()).markItemCompleted(anyString(), anyString());
  }

  @Test
  @DisplayName("is idempotent after item leaves EXECUTING")
  void syncExecutingItems_isIdempotent_afterItemLeavesExecuting() {
    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(Collections.emptyList());

    service.syncExecutingItems();

    verify(executionService, never()).markItemCompleted(anyString(), anyString());
    verify(executionService, never()).markItemFailed(anyString(), anyString());
  }

  private TaskPlanItem executingItem() {
    TaskPlanItem item = new TaskPlanItem();
    item.setItemId(ITEM_ID);
    item.setStatus(TaskPlanItem.ItemStatus.EXECUTING);
    item.setPatchLoopIterationId(ITERATION_ID);
    return item;
  }

  private PatchLoopIteration iteration(PatchLoopStatus status) {
    PatchLoopIteration iter = new PatchLoopIteration();
    iter.setIterationId(ITERATION_ID);
    iter.setStatus(status);
    return iter;
  }
}
