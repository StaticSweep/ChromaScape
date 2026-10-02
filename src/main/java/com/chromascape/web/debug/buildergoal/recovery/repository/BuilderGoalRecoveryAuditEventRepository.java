package com.chromascape.web.debug.buildergoal.recovery.repository;

import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryAuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuilderGoalRecoveryAuditEventRepository
    extends JpaRepository<BuilderGoalRecoveryAuditEvent, String> {

  List<BuilderGoalRecoveryAuditEvent> findByIncidentIdOrderByOccurredAtAsc(String incidentId);

  List<BuilderGoalRecoveryAuditEvent> findTop200ByOrderByOccurredAtDesc();
}
