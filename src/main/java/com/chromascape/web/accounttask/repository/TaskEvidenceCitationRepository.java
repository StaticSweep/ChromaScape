package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link TaskEvidenceCitation} entities. */
@Repository
public interface TaskEvidenceCitationRepository extends JpaRepository<TaskEvidenceCitation, Long> {

  List<TaskEvidenceCitation> findByItemId(String itemId);

  List<TaskEvidenceCitation> findByPlanId(String planId);

  List<TaskEvidenceCitation> findByItemIdAndEvidenceType(String itemId, EvidenceType evidenceType);

  List<TaskEvidenceCitation> findByEvidenceTypeAndEvidenceId(
      EvidenceType evidenceType, Long evidenceId);
}
