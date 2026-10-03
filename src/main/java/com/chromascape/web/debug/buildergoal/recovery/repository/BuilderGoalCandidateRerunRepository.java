package com.chromascape.web.debug.buildergoal.recovery.repository;

import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalCandidateRerun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuilderGoalCandidateRerunRepository
    extends JpaRepository<BuilderGoalCandidateRerun, String> {

  Optional<BuilderGoalCandidateRerun> findByRerunRequestId(String rerunRequestId);

  Optional<BuilderGoalCandidateRerun> findByParentIterationIdAndCandidateDigestAndIdempotencyKey(
      String parentIterationId, String candidateDigest, String idempotencyKey);

  List<BuilderGoalCandidateRerun> findByStateIn(List<String> states);
}
