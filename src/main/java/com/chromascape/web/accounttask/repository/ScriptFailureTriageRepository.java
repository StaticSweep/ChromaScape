package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptFailureTriageRepository extends JpaRepository<ScriptFailureTriage, Long> {
  Optional<ScriptFailureTriage> findByTriageKey(String triageKey);

  List<ScriptFailureTriage> findByStatusOrderByLastSeenAtDesc(String status);

  List<ScriptFailureTriage> findByStatusAndFamilyOrderByLastSeenAtDesc(String status, String family);

  List<ScriptFailureTriage> findByStatusInOrderByBacklogPriorityDescLastSeenAtDesc(
      List<String> statuses);

  List<ScriptFailureTriage> findByStatusInAndFamilyOrderByBacklogPriorityDescLastSeenAtDesc(
      List<String> statuses, String family);
}
