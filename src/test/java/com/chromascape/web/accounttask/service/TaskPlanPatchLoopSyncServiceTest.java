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
import java.time.Duration;
import java.time.LocalDateTime;
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

  private static final long TIMEOUT_MS = 30_000L;
  private static final String ITEM_ID = "item_test1";
  private static final String ITERATION_ID = "iter_test1";

  @Mock private TaskPlanItemRepository itemRepository;
  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private TaskPlanExecutionService executionService;

  private TaskPlanPatchLoopSyncService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskPlanPatchLoopSyncService(
            itemRepository, iterationRepository, executionService, TIMEOUT_MS, true);
  }

  private TaskPlanItem executingItem() {
    TaskPlanItem item = new TaskPlanItem();
    item.setItemId(ITEM_ID);
    item.setStatus(TaskPlanItem.ItemStatus.EXECUTING);
    item.setPatchLoopIterationId(ITERATION_ID);
    item.setExecutedAt(LocalDateTime.now().minus(Duration.ofMillis(TIMEOUT_MS + 5000)));
    return item;
  }

  private PatchLoopIteration iteration(PatchLoopStatus status) {
    PatchLoopIteration iter = new PatchLoopIteration();
    iter.setIterationId(ITERATION_ID);
    iter.setStatus(status);
    iter.setCreatedAt(LocalDateTime.now().minus(Duration.ofMinutes(5)));
    iter.setLastStateAt(LocalDateTime.now().minus(Duration.ofMinutes(1)));
    return iter;
  }

  @Test
  @DisplayName("Marks COMPLETED when linked iteration is COMPLETED")
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
  @DisplayName("Marks FAILED when linked iteration is FAILED")
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
  @DisplayName("Marks FAILED when linked iteration is CANCELLED")
  void syncExecutingItems_marksFailed_whenIterationCancelled() {
    TaskPlanItem item = executingItem();
    PatchLoopIteration iter = iteration(PatchLoopStatus.CANCELLED);

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.of(iter));

    service.syncExecutingItems();

    verify(executionService).markItemFailed(eq(ITEM_ID), anyString());
  }

  @Test
  @DisplayName("Marks FAILED when linked iteration is INSUFFICIENT_EVIDENCE")
  void syncExecutingItems_marksFailed_whenIterationInsufficientEvidence() {
    TaskPlanItem item = executingItem();
    PatchLoopIteration iter = iteration(PatchLoopStatus.INSUFFICIENT_EVIDENCE);

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.of(iter));

    service.syncExecutingItems();

    verify(executionService).markItemFailed(eq(ITEM_ID), anyString());
  }

  @Test
  @DisplayName("Skips when iteration is non-terminal and within timeout")
  void syncExecutingItems_skips_whenIterationNonTerminalWithinTimeout() {
    TaskPlanItem item = executingItem();
    item.setExecutedAt(LocalDateTime.now().minus(Duration.ofSeconds(10)));

    PatchLoopIteration iter = iteration(PatchLoopStatus.RUNNING_SCRIPT);
    iter.setLastStateAt(LocalDateTime.now().minus(Duration.ofSeconds(5)));

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.of(iter));

    service.syncExecutingItems();

    verify(executionService, never()).markItemCompleted(anyString(), anyString());
    verify(executionService, never()).markItemFailed(anyString(), anyString());
  }

  @Test
  @DisplayName("Skips when iteration is missing and within timeout")
  void syncExecutingItems_skips_whenIterationMissingWithinTimeout() {
    TaskPlanItem item = executingItem();
    item.setExecutedAt(LocalDateTime.now().minus(Duration.ofSeconds(10)));

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.empty());

    service.syncExecutingItems();

    verify(executionService, never()).markItemFailed(anyString(), anyString());
    verify(executionService, never()).markItemCompleted(anyString(), anyString());
  }

  @Test
  @DisplayName("Marks FAILED when iteration is missing and past timeout")
  void syncExecutingItems_marksFailed_whenIterationMissingPastTimeout() {
    TaskPlanItem item = executingItem();
    // executedAt is already set beyond timeout by executingItem()

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.empty());

    service.syncExecutingItems();

    verify(executionService).markItemFailed(eq(ITEM_ID), anyString());
  }

  @Test
  @DisplayName("Marks FAILED when iteration is non-terminal and past timeout")
  void syncExecutingItems_marksFailed_whenIterationNonTerminalPastTimeout() {
    TaskPlanItem item = executingItem();

    PatchLoopIteration iter = iteration(PatchLoopStatus.ANALYZING);
    iter.setLastStateAt(LocalDateTime.now().minus(Duration.ofMillis(TIMEOUT_MS + 5000)));

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.of(iter));

    service.syncExecutingItems();

    verify(executionService).markItemFailed(eq(ITEM_ID), anyString());
  }

  @Test
  @DisplayName("Does not mutate when fail-stale is disabled")
  void syncExecutingItems_doesNotMutate_whenFailStaleDisabled() {
    TaskPlanPatchLoopSyncService noFailService =
        new TaskPlanPatchLoopSyncService(
            itemRepository, iterationRepository, executionService, TIMEOUT_MS, false);

    TaskPlanItem item = executingItem();

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.empty());

    noFailService.syncExecutingItems();

    verify(executionService, never()).markItemFailed(anyString(), anyString());
    verify(executionService, never()).markItemCompleted(anyString(), anyString());
  }

  @Test
  @DisplayName("Is idempotent after item leaves EXECUTING (empty list)")
  void syncExecutingItems_isIdempotent_afterItemLeavesExecuting() {
    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(Collections.emptyList());

    service.syncExecutingItems();

    verify(executionService, never()).markItemCompleted(anyString(), anyString());
    verify(executionService, never()).markItemFailed(anyString(), anyString());
  }

  @Test
  @DisplayName("Uses item.createdAt as fallback when executedAt is null and iteration missing")
  void syncExecutingItems_marksFailed_whenExecutedAtNull_usesCreatedAtFallback() {
    TaskPlanItem item = new TaskPlanItem();
    item.setItemId(ITEM_ID);
    item.setStatus(TaskPlanItem.ItemStatus.EXECUTING);
    item.setPatchLoopIterationId(ITERATION_ID);
    item.setExecutedAt(null);
    // createdAt beyond timeout triggers stale detection
    item.setCreatedAt(LocalDateTime.now().minus(Duration.ofMillis(TIMEOUT_MS + 5000)));

    when(itemRepository.findByStatusAndPatchLoopIterationIdIsNotNull(
            TaskPlanItem.ItemStatus.EXECUTING))
        .thenReturn(List.of(item));
    when(iterationRepository.findByIterationId(ITERATION_ID)).thenReturn(Optional.empty());

    service.syncExecutingItems();

    verify(executionService).markItemFailed(eq(ITEM_ID), anyString());
  }
}
