package com.chromascape.web.replay.curation.repository;

import com.chromascape.web.replay.curation.entity.CuratedAction;
import com.chromascape.web.replay.curation.entity.CuratedAction.CuratedActionId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CuratedActionRepository extends JpaRepository<CuratedAction, CuratedActionId> {

    List<CuratedAction> findByCurationIdOrderByTickAscSequenceAsc(UUID curationId);
}