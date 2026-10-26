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
  }

  @Override
  public Reservation reserve(
      String ownerId, String idempotencyKey, String jobId, String fingerprint, Duration lease) {
    requireText(ownerId, "ownerId");
    requireText(idempotencyKey, "idempotencyKey");
    requireText(jobId, "jobId");
    requireText(fingerprint, "fingerprint");
    String attemptId = "attempt-" + UUID.randomUUID();
    String leaseId = "lease-" + UUID.randomUUID();
    return transactions.execute(
        tx -> {
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          int inserted =
              jdbc.update(
                  "INSERT INTO candidate_execution_attempts(attempt_id, owner_id, idempotency_key,"
                      + " job_id, fingerprint, state, lease_id, epoch, lease_expires_at)"
                      + " VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, 1, now() + ?::interval)"
                      + " ON CONFLICT (owner_id, idempotency_key) DO NOTHING",
                  attemptId,
                  ownerId,
                  idempotencyKey,
                  jobId,
                  fingerprint,
                  leaseId,
                  interval(lease));
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
            + " lease_id = NULL, lease_expires_at = NULL, updated_at = now()"
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
                    + " lease_id = NULL, lease_expires_at = NULL, updated_at = now(),"
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
