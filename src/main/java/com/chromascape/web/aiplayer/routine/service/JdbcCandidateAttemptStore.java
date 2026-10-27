package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL {@link CandidateAttemptStore}, following the SC-1991 session repository: one row per
 * attempt, row locks as the commit boundary, and every post-reservation write fenced in its WHERE
 * clause on the presented lease and epoch.
 */
@Repository
public class JdbcCandidateAttemptStore implements CandidateAttemptStore {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final String COLUMNS =
      "attempt_id, owner_id, idempotency_key, job_id, fingerprint, state, lease_id, epoch,"
          + " lease_expires_at, request_digest, job::text, detail";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public JdbcCandidateAttemptStore(
      JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setTimeout(60);
    // A waiter on the lineage root must read the reservations committed by the previous holder.
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override
  public Reservation reserve(
      String ownerId, String idempotencyKey, String jobId, String fingerprint, Duration lease) {
    return reserve(ownerId, idempotencyKey, jobId, fingerprint, lease, null);
  }

  @Override
  public Reservation reserveRecovery(
      String ownerId,
      String idempotencyKey,
      String jobId,
      String fingerprint,
      Duration lease,
      String recovers) {
    requireText(recovers, "recovers");
    return reserve(ownerId, idempotencyKey, jobId, fingerprint, lease, recovers);
  }

  /** Locks the original attempt, shared by every branch, until reservation commits. */
  private void lockRecoveryLineage(String recovers) {
    String root =
        jdbc.queryForObject(
            "WITH RECURSIVE ancestors AS ("
                + " SELECT attempt_id, recovers FROM candidate_execution_attempts"
                + " WHERE attempt_id = ?"
                + " UNION SELECT parent.attempt_id, parent.recovers"
                + " FROM candidate_execution_attempts parent"
                + " JOIN ancestors a ON parent.attempt_id = a.recovers)"
                + " SELECT attempt_id FROM ancestors WHERE recovers IS NULL",
            String.class,
            recovers);
    jdbc.queryForObject(
        "SELECT attempt_id FROM candidate_execution_attempts WHERE attempt_id = ? FOR UPDATE",
        String.class,
        root);
  }

  /**
   * Uncertain ancestors permit continuation through themselves. Every other live, uncertain or
   * completed branch blocks it, even when the immediate parent of that branch ended FAILED.
   */
  private void refuseCompetingRecovery(String recovers) {
    List<String> holders =
        jdbc.queryForList(
            "WITH RECURSIVE ancestors AS ("
                + " SELECT attempt_id, recovers FROM candidate_execution_attempts"
                + " WHERE attempt_id = ?"
                + " UNION SELECT parent.attempt_id, parent.recovers"
                + " FROM candidate_execution_attempts parent"
                + " JOIN ancestors a ON parent.attempt_id = a.recovers),"
                + " family AS (SELECT attempt_id, state FROM candidate_execution_attempts"
                + " WHERE attempt_id = (SELECT attempt_id FROM ancestors WHERE recovers IS NULL)"
                + " UNION SELECT child.attempt_id, child.state"
                + " FROM candidate_execution_attempts child"
                + " JOIN family f ON child.recovers = f.attempt_id)"
                + " SELECT attempt_id FROM family"
                + " WHERE state IN ('RESERVED', 'RUNNING', 'COMPLETED', 'UNCERTAIN')"
                + " AND attempt_id NOT IN (SELECT attempt_id FROM ancestors) LIMIT 1",
            String.class,
            recovers);
    if (!holders.isEmpty()) {
      throw new RecoveryClaimedException(recovers, holders.get(0));
    }
  }

  private Reservation reserve(
      String ownerId,
      String idempotencyKey,
      String jobId,
      String fingerprint,
      Duration lease,
      String recovers) {
    requireText(ownerId, "ownerId");
    requireText(idempotencyKey, "idempotencyKey");
    requireText(jobId, "jobId");
    requireText(fingerprint, "fingerprint");
    String attemptId = "attempt-" + UUID.randomUUID();
    String leaseId = "lease-" + UUID.randomUUID();
    return transactions.execute(
        tx -> {
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          if (recovers != null) {
            lockRecoveryLineage(recovers);
            // Reattachment never acquires a new continuation, even after another branch exists.
            List<Attempt> existing =
                jdbc.query(
                    "SELECT "
                        + COLUMNS
                        + " FROM candidate_execution_attempts"
                        + " WHERE owner_id = ? AND idempotency_key = ? FOR UPDATE",
                    this::row,
                    ownerId,
                    idempotencyKey);
            if (!existing.isEmpty()) {
              return new Reservation(existing.get(0), false);
            }
            refuseCompetingRecovery(recovers);
          }
          int inserted =
              jdbc.update(
                  "INSERT INTO candidate_execution_attempts(attempt_id, owner_id, idempotency_key,"
                      + " job_id, fingerprint, state, lease_id, epoch, lease_expires_at, recovers)"
                      + " VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, 1, now() + ?::interval, ?)"
                      + " ON CONFLICT (owner_id, idempotency_key) DO NOTHING",
                  attemptId,
                  ownerId,
                  idempotencyKey,
                  jobId,
                  fingerprint,
                  leaseId,
                  interval(lease),
                  recovers);
          Attempt attempt =
              one(
                  "SELECT "
                      + COLUMNS
                      + " FROM candidate_execution_attempts"
                      + " WHERE owner_id = ? AND idempotency_key = ? FOR UPDATE",
                  ownerId,
                  idempotencyKey);
          return new Reservation(attempt, inserted == 1);
        });
  }

  @Override
  public Attempt markRunning(String attemptId, String leaseId, long epoch, String requestDigest) {
    requireText(requestDigest, "requestDigest");
    return fenced(
        "UPDATE candidate_execution_attempts SET state = 'RUNNING', request_digest = ?,"
            + " updated_at = now() WHERE attempt_id = ? AND lease_id = ? AND epoch = ?"
            + " AND state = 'RESERVED'",
        attemptId,
        requestDigest,
        attemptId,
        leaseId,
        epoch);
  }

  @Override
  public boolean renew(String attemptId, String leaseId, long epoch, Duration lease) {
    return transactions.execute(
            tx ->
                jdbc.update(
                    "UPDATE candidate_execution_attempts SET lease_expires_at = now() + ?::interval,"
                        + " updated_at = now() WHERE attempt_id = ? AND lease_id = ? AND epoch = ?"
                        + " AND state IN ('RESERVED', 'RUNNING')",
                    interval(lease),
                    attemptId,
                    leaseId,
                    epoch))
        == 1;
  }

  @Override
  public Attempt finish(
      String attemptId,
      String leaseId,
      long epoch,
      State state,
      Map<String, Object> job,
      String detail) {
    if (state == null || state.open()) {
      throw new IllegalArgumentException("finish needs a terminal or UNCERTAIN state");
    }
    return fenced(
        "UPDATE candidate_execution_attempts SET state = ?, job = ?::jsonb, detail = ?,"
            + " lease_id = NULL, lease_expires_at = NULL, paused = FALSE, updated_at = now()"
            + " WHERE attempt_id = ? AND lease_id = ? AND epoch = ?"
            + " AND state IN ('RESERVED', 'RUNNING')",
        attemptId,
        state.name(),
        json(job),
        detail,
        attemptId,
        leaseId,
        epoch);
  }

  @Override
  public int reconcileExpired(Instant now) {
    return transactions.execute(
        tx ->
            jdbc.update(
                "UPDATE candidate_execution_attempts SET state = 'UNCERTAIN', epoch = epoch + 1,"
                    + " lease_id = NULL, lease_expires_at = NULL, paused = FALSE, updated_at = now(),"
                    + " detail = 'owner lease expired at ' || lease_expires_at::text"
                    + " || '; reconciliation required, never relaunched'"
                    + " WHERE state IN ('RESERVED', 'RUNNING') AND lease_expires_at < ?",
                Timestamp.from(now)));
  }

  @Override
  public Optional<Attempt> find(String attemptId) {
    List<Attempt> rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM candidate_execution_attempts WHERE attempt_id = ?",
            this::row,
            attemptId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Attempt> findByKey(String ownerId, String idempotencyKey) {
    List<Attempt> rows =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM candidate_execution_attempts WHERE owner_id = ? AND idempotency_key = ?",
            this::row,
            ownerId,
            idempotencyKey);
    return rows.stream().findFirst();
  }

  @Override
  public Control control(String attemptId) {
    List<Control> rows =
        jdbc.query(
            "SELECT cancel_requested, pause_requested, paused FROM candidate_execution_attempts"
                + " WHERE attempt_id = ?",
            (rs, n) ->
                new Control(
                    rs.getBoolean("cancel_requested"),
                    rs.getBoolean("pause_requested"),
                    rs.getBoolean("paused")),
            attemptId);
    return rows.isEmpty() ? Control.NONE : rows.get(0);
  }

  @Override
  public boolean requestCancel(String attemptId) {
    return transactions.execute(
            tx ->
                jdbc.update(
                    "UPDATE candidate_execution_attempts SET cancel_requested = TRUE,"
                        + " updated_at = now() WHERE attempt_id = ?"
                        + " AND state IN ('RESERVED', 'RUNNING')",
                    attemptId))
        == 1;
  }

  @Override
  public boolean requestPause(String attemptId, boolean pause) {
    return transactions.execute(
            tx ->
                jdbc.update(
                    "UPDATE candidate_execution_attempts SET pause_requested = ?,"
                        + " updated_at = now() WHERE attempt_id = ?"
                        + " AND state IN ('RESERVED', 'RUNNING')",
                    pause,
                    attemptId))
        == 1;
  }

  @Override
  public void recordPaused(String attemptId, String leaseId, long epoch, boolean paused) {
    fenced(
        "UPDATE candidate_execution_attempts SET paused = ?, updated_at = now()"
            + " WHERE attempt_id = ? AND lease_id = ? AND epoch = ?"
            + " AND state = 'RUNNING'",
        attemptId,
        paused,
        attemptId,
        leaseId,
        epoch);
  }

  /** Runs one fenced UPDATE; zero rows means the presented lease or epoch is stale. */
  private Attempt fenced(String sql, String attemptId, Object... args) {
    return transactions.execute(
        tx -> {
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          if (jdbc.update(sql, args) != 1) {
            Attempt current = find(attemptId).orElse(null);
            throw new StaleOwnerException(
                "attempt "
                    + attemptId
                    + " is not held by the presented lease/epoch (now "
                    + (current == null ? "missing" : current.state() + " epoch " + current.epoch())
                    + ")");
          }
          return find(attemptId).orElseThrow();
        });
  }

  private Attempt one(String sql, Object... args) {
    return jdbc.query(sql, this::row, args).get(0);
  }

  private Attempt row(ResultSet rs, int n) throws SQLException {
    Timestamp expires = rs.getTimestamp("lease_expires_at");
    String job = rs.getString("job");
    try {
      return new Attempt(
          rs.getString("attempt_id"),
          rs.getString("owner_id"),
          rs.getString("idempotency_key"),
          rs.getString("job_id"),
          rs.getString("fingerprint"),
          State.valueOf(rs.getString("state")),
          rs.getString("lease_id"),
          rs.getLong("epoch"),
          expires == null ? null : expires.toInstant(),
          rs.getString("request_digest"),
          job == null ? null : mapper.readValue(job, MAP),
          rs.getString("detail"));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("persisted candidate attempt job is invalid", e);
    }
  }

  private String json(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize candidate attempt job", e);
    }
  }

  private static String interval(Duration lease) {
    if (lease == null || lease.isNegative() || lease.isZero()) {
      throw new IllegalArgumentException("lease must be positive");
    }
    return lease.toMillis() + " milliseconds";
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
