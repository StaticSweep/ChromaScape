package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.run.entity.ReplayRun;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplayRunRepository extends JpaRepository<ReplayRun, UUID> {

  Optional<ReplayRun> findByIdempotencyKeyAndOwnerId(String idempotencyKey, String ownerId);

  /**
   * Locks the run row (PESSIMISTIC_WRITE) so cancellation, worker persistence, and recovery
   * serialize on the same row. Every worker persistence transaction locks the attempt/fence row
   * first and this run row second; {@code cancel()} locks only this row before checking
   * state/phase. Together this removes the read-then-save cancellation race.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from ReplayRun r where r.id = :runId")
  Optional<ReplayRun> findByIdForUpdate(@Param("runId") UUID runId);

  /**
   * One authoritative database timestamp for terminal stamps inside a transaction. Rendered
   * explicitly in UTC wall-clock form so Hibernate 6 binds it to {@code LocalDateTime} without
   * session-timezone dependence.
   */
  @Query(value = "SELECT now() AT TIME ZONE 'UTC'", nativeQuery = true)
  LocalDateTime currentTimestamp();

  Page<ReplayRun> findByOwnerIdOrderByCreatedAtDesc(String ownerId, Pageable pageable);

  Page<ReplayRun> findByOwnerIdAndModeOrderByCreatedAtDesc(
      String ownerId, ReplayMode mode, Pageable pageable);

  /**
   * Atomic claim for multi-instance safety. The conditional UPDATE is the claim: exactly one
   * worker's UPDATE matches a given QUEUED row; all other concurrent claims see zero updated rows.
   * Combined with FOR UPDATE SKIP LOCKED semantics via a two-step select this avoids double
   * dispatch.
   */
  @Modifying
  @Query(
      value =
          """
            UPDATE replay_runs
            SET state = 'CLAIMED'
            WHERE id = :runId AND state = 'QUEUED'
            """,
      nativeQuery = true)
  int claimQueuedRun(@Param("runId") UUID runId);

  /**
   * Phase-only update while RUNNING. Never performs a state transition: the run must already be
   * RUNNING and stay RUNNING. Fenced by the caller's attempt identity, generation, and an unexpired
   * lease on the database clock. Returns 0 when any predicate fails (stale fence).
   */
  @Modifying
  @Query(
      value =
          """
            UPDATE replay_runs r
            SET phase = :targetPhase
            FROM replay_attempts a
            WHERE r.id = :runId
              AND a.id = :attemptId
              AND a.run_id = r.id
              AND a.fencing_generation = :generation
              AND a.lease_owner = :workerId
              AND a.state = 'RUNNING'
              AND a.lease_expires_at > CURRENT_TIMESTAMP
              AND r.state = 'RUNNING'
            """,
      nativeQuery = true)
  int updatePhaseFenced(
      @Param("runId") UUID runId,
      @Param("attemptId") UUID attemptId,
      @Param("generation") int generation,
      @Param("workerId") String workerId,
      @Param("targetPhase") String targetPhase);

  /** Selects candidate ids without locking out other instances. */
  @Query(
      value =
          """
            SELECT id FROM replay_runs
            WHERE state = 'QUEUED' AND mode = :mode
            ORDER BY created_at
            LIMIT :limit
            """,
      nativeQuery = true)
  List<UUID> findClaimableRunIds(@Param("mode") String mode, @Param("limit") int limit);

  /**
   * Terminal runs without a report row, oldest terminal first. Deliberately UNLOCKED: the
   * orchestrator must never hold run row locks while invoking REQUIRES_NEW report writers (that
   * topology self-deadlocks). Per-run mutual exclusion is provided by {@link
   * #tryAdvisoryLockForRun(UUID)} inside each writer transaction.
   */
  @Query(
      value =
          """
            SELECT r.id FROM replay_runs r
            WHERE r.state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
              AND NOT EXISTS (SELECT 1 FROM replay_reports rp WHERE rp.run_id = r.id)
              AND r.terminal_at IS NOT NULL
            ORDER BY r.terminal_at ASC, r.id ASC
            LIMIT :limit
            """,
      nativeQuery = true)
  List<UUID> findTerminalRunIdsMissingReports(@Param("limit") int limit);

  /**
   * Transaction-scoped PostgreSQL advisory lock keyed by run id. Acquire FIRST inside a per-run
   * write transaction, before locking the run row, so two instances contend on the advisory lock
   * instead of blocking each other on row locks. False means another transaction already owns this
   * run; the caller must skip without writing.
   */
  @Query(
      value =
          """
            SELECT pg_try_advisory_xact_lock(hashtextextended(CAST(:runId AS text), 0))
            """,
      nativeQuery = true)
  Boolean tryAdvisoryLockForRun(@Param("runId") UUID runId);

  /** Authoritative report existence check straight against committed rows. */
  @Query(
      value = "SELECT EXISTS(SELECT 1 FROM replay_reports rp WHERE rp.run_id = :runId)",
      nativeQuery = true)
  Boolean hasReport(@Param("runId") UUID runId);

  /**
   * Database-clock deadline predicate. Never compares the JVM clock: expiry is decided by
   * PostgreSQL {@code CURRENT_TIMESTAMP} against the run start (or creation fallback).
   */
  @Query(
      value =
          """
            SELECT now() >
                   COALESCE(started_at, created_at) AT TIME ZONE 'UTC'
                   + (:timeoutSeconds * interval '1 second')
            FROM replay_runs
            WHERE id = :runId
            """,
      nativeQuery = true)
  Boolean isDeadlineExceeded(
      @Param("runId") UUID runId, @Param("timeoutSeconds") long timeoutSeconds);

  /**
   * Milliseconds of run-deadline budget REMAINING according to the PostgreSQL clock, or a negative
   * value once expired. Null means the run row vanished: callers must treat it as no time
   * remaining. Used to bound every kernel request by the remaining run deadline so a slow kernel
   * call can never outlive the run.
   *
   * <p>The value is cast EXPLICITLY to {@code bigint}: {@code EXTRACT(EPOCH ...)} yields PostgreSQL
   * {@code numeric}, and the implicit numeric→Long conversion must never be left to Hibernate
   * driver behaviour.
   */
  @Query(
      value =
          """
            SELECT FLOOR(
                       EXTRACT(EPOCH FROM (
                           COALESCE(started_at, created_at) AT TIME ZONE 'UTC'
                           + (:timeoutSeconds * interval '1 second')
                           - now()
                       )) * 1000
                   )::bigint
            FROM replay_runs
            WHERE id = :runId
            """,
      nativeQuery = true)
  Long remainingDeadlineMs(
      @Param("runId") UUID runId, @Param("timeoutSeconds") long timeoutSeconds);

  long countByState(ReplayRunState state);
}
