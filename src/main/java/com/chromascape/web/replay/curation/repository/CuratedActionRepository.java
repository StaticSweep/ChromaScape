package com.chromascape.web.replay.curation.repository;

import com.chromascape.web.replay.curation.entity.CuratedAction;
import com.chromascape.web.replay.curation.entity.CuratedAction.CuratedActionId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CuratedActionRepository extends JpaRepository<CuratedAction, CuratedActionId> {

  List<CuratedAction> findByCurationIdOrderByTickAscSequenceAsc(UUID curationId);

  /** Full replacement support for DRAFT curation label edits. */
  @Modifying
  @Query("delete from CuratedAction a where a.curationId = :curationId")
  int deleteByCurationId(@Param("curationId") UUID curationId);
}
