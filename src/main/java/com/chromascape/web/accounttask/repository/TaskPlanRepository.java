package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.TaskPlan;
import com.chromascape.web.accounttask.entity.TaskPlanStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link TaskPlan} entities. */
@Repository
public interface TaskPlanRepository extends JpaRepository<TaskPlan, Long> {

  Optional<TaskPlan> findByPlanId(String planId);

  List<TaskPlan> findByPlanIdIn(List<String> planIds);

  List<TaskPlan> findByStatusOrderByCreatedAtDesc(TaskPlanStatus status);

  List<TaskPlan> findByAccountNameOrderByCreatedAtDesc(String accountName);

  List<TaskPlan> findAllByOrderByCreatedAtDesc();

  List<TaskPlan> findBySourceSessionId(String sessionId);

  List<TaskPlan> findBySourceRecordingId(String recordingId);

  @Query(
      "SELECT p FROM TaskPlan p WHERE p.accountName IS NULL OR TRIM(p.accountName) = '' ORDER BY p.createdAt DESC")
  List<TaskPlan> findUnassignedOrderByCreatedAtDesc();

  @Query("SELECT p FROM TaskPlan p WHERE p.accountName IS NULL OR TRIM(p.accountName) = ''")
  List<TaskPlan> findUnassigned();
}
