package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.run.entity.ReplayRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplayRunRepository extends JpaRepository<ReplayRun, UUID> {

    Optional<ReplayRun> findByIdempotencyKeyAndOwnerId(String idempotencyKey, String ownerId);

    Page<ReplayRun> findByOwnerIdOrderByCreatedAtDesc(String ownerId, Pageable pageable);

    Page<ReplayRun> findByOwnerIdAndModeOrderByCreatedAtDesc(String ownerId, ReplayMode mode, Pageable pageable);

    /**
     * Atomic claim for multi-instance safety. The conditional UPDATE is the
     * claim: exactly one worker's UPDATE matches a given QUEUED row; all
     * other concurrent claims see zero updated rows. Combined with
     * FOR UPDATE SKIP LOCKED semantics via a two-step select this avoids
     * double dispatch.
     */
    @Modifying
    @Query(value = """
            UPDATE replay_runs
            SET state = 'CLAIMED'
            WHERE id = :runId AND state = 'QUEUED'
            """, nativeQuery = true)
    int claimQueuedRun(@Param("runId") UUID runId);

    /** Selects candidate ids without locking out other instances. */
    @Query(value = """
            SELECT id FROM replay_runs
            WHERE state = 'QUEUED' AND mode = :mode
            ORDER BY created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findClaimableRunIds(@Param("mode") String mode, @Param("limit") int limit);

    long countByState(ReplayRunState state);
}
