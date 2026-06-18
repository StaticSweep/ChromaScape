package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.entity.TaskPlanItem.ItemStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link TaskPlanItem} entities. */
@Repository
public interface TaskPlanItemRepository extends JpaRepository<TaskPlanItem, Long> {

  Optional<TaskPlanItem> findByItemId(String itemId);

  List<TaskPlanItem> findByPlanIdOrderBySortOrderAsc(String planId);

  List<TaskPlanItem> findByPlanIdAndStatus(String planId, ItemStatus status);

  List<TaskPlanItem> findByStatus(ItemStatus status);

  List<TaskPlanItem> findByCompletedAtBetween(LocalDateTime start, LocalDateTime end);

  List<TaskPlanItem> findByStatusAndPatchLoopIterationIdIsNotNull(ItemStatus status);
}
