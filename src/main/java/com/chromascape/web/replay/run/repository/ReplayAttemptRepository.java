package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.run.entity.ReplayAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplayAttemptRepository extends JpaRepository<ReplayAttempt, UUID> {

    List<ReplayAttempt> findByRunIdOrderByAttemptNoAsc(UUID runId);

    Optional<ReplayAttempt> findByRunIdAndAttemptNo(UUID runId, int attemptNo);

    Optional<ReplayAttempt> findFirstByRunIdOrderByAttemptNoDesc(UUID runId);

    /** Latest attempt regardless of state — used for fencing checks. */
    Optional<ReplayAttempt> findFirstByRunIdAndWorkerIdentityOrderByFencingGenerationDesc(UUID runId, String workerIdentity);
}
