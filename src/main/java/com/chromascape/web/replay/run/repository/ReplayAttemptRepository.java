package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.run.entity.ReplayAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplayAttemptRepository extends JpaRepository<ReplayAttempt, UUID> {

  List<ReplayAttempt> findByRunIdOrderByAttemptNoAsc(UUID runId);

  Optional<ReplayAttempt> findByRunIdAndAttemptNo(UUID runId, int attemptNo);

  Optional<ReplayAttempt> findFirstByRunIdOrderByAttemptNoDesc(UUID runId);

  /** Latest attempt regardless of state — used for fencing checks. */
  Optional<ReplayAttempt> findFirstByRunIdAndWorkerIdentityOrderByFencingGenerationDesc(
      UUID runId, String workerIdentity);

  /**
   * Locks and validates the fence in one database-clock predicate. The row is locked (FOR UPDATE)
   * so the caller's transaction serializes against lease recovery; every condition (attempt, run,
   * generation, owner, RUNNING state, unexpired lease, live run state) is evaluated by the
   * database. An empty result means the caller holds a stale fence.
   */
  @Query(
      value =
          """
            SELECT a.* FROM replay_attempts a
            JOIN replay_runs r ON r.id = a.run_id
            WHERE a.id = :attemptId
              AND a.run_id = :runId
              AND a.fencing_generation = :generation
              AND a.lease_owner = :workerIdentity
              AND a.state = 'RUNNING'
              AND a.lease_expires_at > CURRENT_TIMESTAMP
              AND r.state IN ('CLAIMED', 'STARTING', 'RUNNING', 'CANCELLING')
            FOR UPDATE OF a
            """,
      nativeQuery = true)
  Optional<ReplayAttempt> findValidFencedAttempt(
      @Param("attemptId") UUID attemptId,
      @Param("runId") UUID runId,
      @Param("generation") int generation,
      @Param("workerIdentity") String workerIdentity);

  /**
   * Bounded recovery scan: active attempts whose lease has expired on the database clock. Never
   * scans every run and never uses the application clock: expiry is decided by {@code
   * CURRENT_TIMESTAMP} inside the database.
   */
  @Query(
      value =
          """
            SELECT a.* FROM replay_attempts a
            JOIN replay_runs r ON r.id = a.run_id
            WHERE a.state = 'RUNNING'
              AND r.state IN ('CLAIMED', 'STARTING', 'RUNNING', 'CANCELLING')
              AND a.lease_expires_at < CURRENT_TIMESTAMP
            ORDER BY a.created_at
            LIMIT :limit
            """,
      nativeQuery = true)
  List<ReplayAttempt> findExpiredActiveAttempts(@Param("limit") int limit);

  /** Locks one attempt row (FOR UPDATE) as the first step of an atomic recovery transaction. */
  @Query(
      value = "SELECT a.* FROM replay_attempts a WHERE a.id = :attemptId FOR UPDATE",
      nativeQuery = true)
  Optional<ReplayAttempt> findByIdForUpdate(@Param("attemptId") UUID attemptId);

  /**
   * Atomically expires an attempt: only succeeds when the lease is still expired and unclaimed by
   * anyone else. Increments the fencing generation so the stale worker can never renew again,
   * stamps terminal time on the database clock, and clears the lease so no scheduler can renew a
   * dead attempt.
   */
  @Modifying
  @Query(
      value =
          """
            UPDATE replay_attempts
            SET state = 'EXPIRED',
                terminal_at = CURRENT_TIMESTAMP,
                fencing_generation = fencing_generation + 1,
                lease_expires_at = NULL
            WHERE id = :attemptId
              AND state = 'RUNNING'
              AND lease_expires_at < CURRENT_TIMESTAMP
            """,
      nativeQuery = true)
  int expireAttemptAtomically(@Param("attemptId") UUID attemptId);

  /**
   * Atomic lease renewal. Requires an exact match on run, attempt, fencing generation, owner,
   * RUNNING state, and an unexpired lease on the database clock. Returns 0 when the caller holds a
   * stale fence.
   */
  @Modifying
  @Query(
      value =
          """
            UPDATE replay_attempts
            SET heartbeat_at = CURRENT_TIMESTAMP,
                lease_expires_at = CURRENT_TIMESTAMP + (:leaseSeconds * interval '1 second')
            WHERE id = :attemptId
              AND run_id = :runId
              AND fencing_generation = :generation
              AND lease_owner = :workerId
              AND state = 'RUNNING'
              AND lease_expires_at > CURRENT_TIMESTAMP
            """,
      nativeQuery = true)
  int renewLeaseFenced(
      @Param("attemptId") UUID attemptId,
      @Param("runId") UUID runId,
      @Param("generation") int generation,
      @Param("workerId") String workerId,
      @Param("leaseSeconds") int leaseSeconds);

  /**
   * Creates the claim attempt with every lease timestamp assigned by the database clock (heartbeat,
   * expiry, start, creation). The id is generated in Java so the caller can fetch the row without a
   * second round-trip ambiguity. Returns 0 when the insert fails; the caller must treat any result
   * other than one as a fatal claim error and roll back the whole claim transaction.
   */
  @Modifying
  @Query(
      value =
          """
            INSERT INTO replay_attempts
                (id, run_id, attempt_no, lease_owner, worker_identity,
                 fencing_generation, state, heartbeat_at, lease_expires_at,
                 started_at, created_at)
            VALUES (:id, :runId, :attemptNo, :workerId, :workerId, :generation,
                    'RUNNING',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + (:leaseSeconds * interval '1 second'),
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
            """,
      nativeQuery = true)
  int insertAttemptWithDatabaseClock(
      @Param("id") UUID id,
      @Param("runId") UUID runId,
      @Param("attemptNo") int attemptNo,
      @Param("generation") int generation,
      @Param("workerId") String workerId,
      @Param("leaseSeconds") int leaseSeconds);
}
