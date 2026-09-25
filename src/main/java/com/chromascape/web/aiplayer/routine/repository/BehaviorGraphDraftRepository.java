package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDraft;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BehaviorGraphDraftRepository extends JpaRepository<BehaviorGraphDraft, String> {
  Optional<BehaviorGraphDraft> findByDraftId(String draftId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from BehaviorGraphDraft d where d.draftId=:draftId")
  Optional<BehaviorGraphDraft> findWithLockByDraftId(@Param("draftId") String draftId);
}
