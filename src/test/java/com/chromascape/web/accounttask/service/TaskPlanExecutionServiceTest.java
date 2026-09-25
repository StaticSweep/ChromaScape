package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.TaskPlan;
import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.entity.TaskPlanStatus;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.accounttask.repository.TaskPlanRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskPlanExecutionServiceTest {

  private static final String PLAN_ID = "plan_sync";
  private static final String ITEM_ID = "item_sync";
  private static final String ITERATION_ID = "iter_sync";

  @Mock private TaskPlanRepository planRepository;
  @Mock private TaskPlanItemRepository itemRepository;

  private TaskPlanExecutionService service;

  @BeforeEach
  void setUp() {
    service = new TaskPlanExecutionService(planRepository, itemRepository);
  }

  @Test
  @DisplayName("markItemExecuting links iteration and moves approved plan to EXECUTING")
  void markItemExecuting_linksIterationAndMovesPlanToExecuting() {
    TaskPlanItem item = item(TaskPlanItem.ItemStatus.APPROVED);
    TaskPlan plan = plan(TaskPlanStatus.APPROVED);

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(itemRepository.save(any(TaskPlanItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(planRepository.findByPlanId(PLAN_ID)).thenReturn(Optional.of(plan));
    when(planRepository.save(any(TaskPlan.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TaskPlanItem result = service.markItemExecuting(ITEM_ID, ITERATION_ID);

    assertThat(result.getStatus()).isEqualTo(TaskPlanItem.ItemStatus.EXECUTING);
    assertThat(result.getPatchLoopIterationId()).isEqualTo(ITERATION_ID);
    assertThat(result.getExecutedAt()).isNotNull();
    assertThat(plan.getStatus()).isEqualTo(TaskPlanStatus.EXECUTING);
  }

  @Test
  @DisplayName(
      "markItemCompleted marks item terminal and completes plan when all items are completed")
  void markItemCompleted_marksItemAndCompletesPlan() {
    TaskPlanItem item = item(TaskPlanItem.ItemStatus.EXECUTING);
    TaskPlanItem sibling = sibling(TaskPlanItem.ItemStatus.COMPLETED);
    TaskPlan plan = plan(TaskPlanStatus.EXECUTING);

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(itemRepository.save(any(TaskPlanItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(planRepository.findByPlanId(PLAN_ID)).thenReturn(Optional.of(plan));
    when(itemRepository.findByPlanIdOrderBySortOrderAsc(PLAN_ID))
        .thenReturn(List.of(item, sibling));
    when(planRepository.save(any(TaskPlan.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TaskPlanItem result = service.markItemCompleted(ITEM_ID, "Patch loop completed");

    assertThat(result.getStatus()).isEqualTo(TaskPlanItem.ItemStatus.COMPLETED);
    assertThat(result.getExecutionResult()).isEqualTo("Patch loop completed");
    assertThat(result.getCompletedAt()).isNotNull();
    assertThat(plan.getStatus()).isEqualTo(TaskPlanStatus.COMPLETED);
    assertThat(plan.getCompletedAt()).isNotNull();
  }

  @Test
  @DisplayName("markItemFailed marks item terminal and fails the plan")
  void markItemFailed_marksItemAndFailsPlan() {
    TaskPlanItem item = item(TaskPlanItem.ItemStatus.EXECUTING);
    TaskPlanItem sibling = sibling(TaskPlanItem.ItemStatus.APPROVED);
    TaskPlan plan = plan(TaskPlanStatus.EXECUTING);

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(itemRepository.save(any(TaskPlanItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(planRepository.findByPlanId(PLAN_ID)).thenReturn(Optional.of(plan));
    when(itemRepository.findByPlanIdOrderBySortOrderAsc(PLAN_ID))
        .thenReturn(List.of(item, sibling));
    when(planRepository.save(any(TaskPlan.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TaskPlanItem result = service.markItemFailed(ITEM_ID, "Patch loop ended with FAILED");

    assertThat(result.getStatus()).isEqualTo(TaskPlanItem.ItemStatus.FAILED);
    assertThat(result.getExecutionResult()).isEqualTo("Patch loop ended with FAILED");
    assertThat(result.getCompletedAt()).isNotNull();
    assertThat(plan.getStatus()).isEqualTo(TaskPlanStatus.FAILED);

    ArgumentCaptor<TaskPlan> planCaptor = ArgumentCaptor.forClass(TaskPlan.class);
    verify(planRepository).save(planCaptor.capture());
    assertThat(planCaptor.getValue().getStatus()).isEqualTo(TaskPlanStatus.FAILED);
  }

  private TaskPlanItem item(TaskPlanItem.ItemStatus status) {
    TaskPlanItem item = new TaskPlanItem();
    item.setItemId(ITEM_ID);
    item.setPlanId(PLAN_ID);
    item.setSortOrder(1);
    item.setTitle("Execute quest");
    item.setStatus(status);
    return item;
  }

  private TaskPlanItem sibling(TaskPlanItem.ItemStatus status) {
    TaskPlanItem item = new TaskPlanItem();
    item.setItemId("item_sync_other");
    item.setPlanId(PLAN_ID);
    item.setSortOrder(2);
    item.setTitle("Other step");
    item.setStatus(status);
    return item;
  }

  private TaskPlan plan(TaskPlanStatus status) {
    TaskPlan plan = new TaskPlan();
    plan.setPlanId(PLAN_ID);
    plan.setIntent("Complete quest line");
    plan.setStatus(status);
    return plan;
  }
}
