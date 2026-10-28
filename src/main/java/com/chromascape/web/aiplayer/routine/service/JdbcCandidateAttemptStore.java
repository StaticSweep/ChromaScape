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
    // An open command of a reconciled attempt becomes UNKNOWN evidence in the same transaction
    // (P3):
    // the permit may already have reached the worker, so it is resolved, never retried blindly.
    Integer reconciled =
        transactions.execute(
            tx ->
                jdbc.queryForObject(
                    "WITH reconciled AS (UPDATE candidate_execution_attempts"
                        + " SET state = 'UNCERTAIN',"
                        + " epoch = epoch + 1, lease_id = NULL, lease_expires_at = NULL,"
                        + " paused = FALSE, updated_at = now(),"
                        + " detail = 'owner lease expired at ' || lease_expires_at::text"
                        + " || '; reconciliation required, never relaunched'"
                        + " WHERE state IN ('RESERVED', 'RUNNING') AND lease_expires_at < ?"
                        + " RETURNING attempt_id),"
                        + " open_commands AS (UPDATE candidate_attempt_commands c"
                        + " SET state = 'UNKNOWN', resolved_at = now() FROM reconciled r"
                        + " WHERE c.attempt_id = r.attempt_id AND c.state = 'PERMITTED'"
                        + " RETURNING 1)"
                        + " SELECT count(*) FROM reconciled",
                    Integer.class,
                    Timestamp.from(now)));
    return reconciled == null ? 0 : reconciled;
  }

  @Override
  public CommandRecord recordIntent(
      String attemptId, String leaseId, long epoch, CommandIntent intent) {
    requireText(leaseId, "leaseId");
    return transactions.execute(
        tx -> {
          Map<String, Object> row = lockFenced(attemptId, leaseId, epoch);
          CandidateAttemptStore.rejectForeignIntent(
              attemptId,
              String.valueOf(row.get("state")),
              (String) row.get("request_digest"),
              ((Number) row.get("binding_generation")).longValue(),
              intent);
          List<CommandRecord> existing = commandRows(attemptId, intent.commandId());
          if (!existing.isEmpty()) {
            CommandRecord recorded = existing.get(0);
            if (!recorded.payloadDigest().equals(intent.payloadDigest())
                || !recorded.method().equals(intent.method())) {
              throw new CommandConflictException(
                  "command " + intent.commandId() + " was recorded with another payload");
            }
            return recorded;
          }
          Integer open =
              jdbc.queryForObject(
                  "SELECT count(*) FROM candidate_attempt_commands"
                      + " WHERE attempt_id = ? AND state = 'PERMITTED'",
                  Integer.class,
                  attemptId);
          if (open != null && open > 0) {
            throw new CommandRejectedException(
                "attempt " + attemptId + " already has an open command");
          }
          jdbc.update(
              "INSERT INTO candidate_attempt_commands(attempt_id, command_id, intent_id, method,"
                  + " kind, payload_digest, binding_generation, expected_worker_epoch,"
                  + " expected_worker_revision, state, owner_lease_id, owner_epoch)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PERMITTED', ?, ?)",
              attemptId,
              intent.commandId(),
              intent.intentId(),
              intent.method(),
              intent.kind(),
              intent.payloadDigest(),
              intent.bindingGeneration(),
              intent.expectedWorkerEpoch(),
              intent.expectedWorkerRevision(),
              leaseId,
              epoch);
          return commandRows(attemptId, intent.commandId()).get(0);
        });
  }

  @Override
  public CommandRecord recordReceipt(
      String attemptId, String leaseId, long epoch, CommandReceipt receipt) {
    requireText(leaseId, "leaseId");
    if (receipt == null
        || receipt.outcome() == null
        || receipt.outcome() == CommandState.PERMITTED) {
      throw new CommandRejectedException("a receipt must resolve the command");
    }
    return transactions.execute(
        tx -> {
          lockFenced(attemptId, leaseId, epoch);
          List<CommandRecord> rows = commandRows(attemptId, receipt.commandId());
          if (rows.isEmpty()) {
            throw new CommandRejectedException(
                "attempt " + attemptId + " has no command " + receipt.commandId());
          }
          CommandRecord recorded = rows.get(0);
          if (!recorded.intentId().equals(receipt.intentId())) {
            throw new CommandRejectedException("receipt names another intent");
          }
          if (recorded.state() != CommandState.PERMITTED) {
            throw new CommandRejectedException(
                "command " + receipt.commandId() + " is already " + recorded.state());
          }
          jdbc.update(
              "UPDATE candidate_attempt_commands SET state = ?, worker_revision = ?,"
                  + " receipt_digest = ?, resolved_at = now()"
                  + " WHERE attempt_id = ? AND command_id = ?",
              receipt.outcome().name(),
              receipt.workerRevision(),
              receipt.receiptDigest(),
              attemptId,
              receipt.commandId());
          return commandRows(attemptId, receipt.commandId()).get(0);
        });
  }

  @Override
  public List<CommandRecord> commands(String attemptId) {
    return jdbc.query(
        "SELECT "
            + COMMAND_COLUMNS
            + " FROM candidate_attempt_commands"
            + " WHERE attempt_id = ? ORDER BY permitted_at, command_id",
        JdbcCandidateAttemptStore::commandRow,
        attemptId);
  }

  private static final String COMMAND_COLUMNS =
      "attempt_id, command_id, intent_id, method, kind, payload_digest, binding_generation,"
          + " expected_worker_epoch, expected_worker_revision, state, worker_revision,"
          + " receipt_digest";

  private List<CommandRecord> commandRows(String attemptId, String commandId) {
    return jdbc.query(
        "SELECT "
            + COMMAND_COLUMNS
            + " FROM candidate_attempt_commands"
            + " WHERE attempt_id = ? AND command_id = ? FOR UPDATE",
        JdbcCandidateAttemptStore::commandRow,
        attemptId,
        commandId);
  }

  private static CommandRecord commandRow(ResultSet rs, int n) throws SQLException {
    long workerRevision = rs.getLong("worker_revision");
    Long revision = rs.wasNull() ? null : workerRevision;
    return new CommandRecord(
        rs.getString("attempt_id"),
        rs.getString("command_id"),
        rs.getString("intent_id"),
        rs.getString("method"),
        rs.getString("kind"),
        rs.getString("payload_digest"),
        rs.getLong("binding_generation"),
        rs.getLong("expected_worker_epoch"),
        rs.getLong("expected_worker_revision"),
        CommandState.valueOf(rs.getString("state")),
        revision,
        rs.getString("receipt_digest"));
  }

  /** Locks the attempt row and verifies the presented fence; returns the locked row. */
  private Map<String, Object> lockFenced(String attemptId, String leaseId, long epoch) {
    jdbc.execute("SET LOCAL lock_timeout = '5s'");
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT state, lease_id, epoch, request_digest, binding_generation"
                + " FROM candidate_execution_attempts WHERE attempt_id = ? FOR UPDATE",
            attemptId);
    if (rows.isEmpty()) {
      throw new CommandRejectedException("attempt " + attemptId + " does not exist");
    }
    Map<String, Object> row = rows.get(0);
    if (!leaseId.equals(row.get("lease_id")) || ((Number) row.get("epoch")).longValue() != epoch) {
      throw new StaleOwnerException(
          "attempt " + attemptId + " is not held by the presented lease/epoch");
    }
    return row;
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

  @Override
  public void recordBinding(String attemptId, String leaseId, long epoch, WorkerBinding binding) {
    requireText(leaseId, "leaseId");
    if (binding == null) {
      throw new BindingRejectedException("no binding");
    }
    transactions.executeWithoutResult(
        tx -> {
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          List<Map<String, Object>> rows =
              jdbc.queryForList(
                  "SELECT state, lease_id, epoch, request_digest, binding_generation"
                      + " FROM candidate_execution_attempts WHERE attempt_id = ? FOR UPDATE",
                  attemptId);
          if (rows.isEmpty()) {
            throw new BindingRejectedException("attempt " + attemptId + " does not exist");
          }
          Map<String, Object> row = rows.get(0);
          if (!leaseId.equals(row.get("lease_id"))
              || ((Number) row.get("epoch")).longValue() != epoch) {
            throw new StaleOwnerException(
                "attempt " + attemptId + " is not held by the presented lease/epoch");
          }
          rejectForeignBinding(
              attemptId,
              String.valueOf(row.get("state")),
              (String) row.get("request_digest"),
              ((Number) row.get("binding_generation")).longValue(),
              binding);
          jdbc.update(
              "INSERT INTO candidate_attempt_worker_bindings(attempt_id, generation, binding_id,"
                  + " request_digest, worker_authority, worker_session_id, worker_epoch,"
                  + " worker_lease_digest, worker_revision, runner_steps, runner_intents, reason,"
                  + " worker_identity, owner_lease_id, owner_epoch)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
              attemptId,
              binding.generation(),
              binding.bindingId(),
              binding.requestDigest(),
              binding.workerAuthority(),
              binding.workerSessionId(),
              binding.workerEpoch(),
              binding.workerLeaseDigest(),
              binding.workerRevision(),
              binding.runnerSteps(),
              binding.runnerIntents(),
              binding.reason(),
              json(binding.workerIdentity()),
              leaseId,
              epoch);
          jdbc.update(
              "UPDATE candidate_execution_attempts SET binding_generation = ?, updated_at = now()"
                  + " WHERE attempt_id = ?",
              binding.generation(),
              attemptId);
        });
  }

  /**
   * The checks every store applies before accepting a binding (R4.2); the caller has already
   * verified the owner's fence.
   */
  static void rejectForeignBinding(
      String attemptId,
      String state,
      String requestDigest,
      long currentGeneration,
      WorkerBinding binding) {
    if (!"RUNNING".equals(state)) {
      throw new BindingRejectedException("attempt " + attemptId + " is " + state + ", not RUNNING");
    }
    if (!attemptId.equals(binding.attemptId())) {
      throw new BindingRejectedException(
          "binding names attempt " + binding.attemptId() + ", not " + attemptId);
    }
    if (requestDigest == null || !requestDigest.equals(binding.requestDigest())) {
      throw new BindingRejectedException("binding names another request digest");
    }
    if (binding.generation() != currentGeneration + 1) {
      throw new BindingRejectedException(
          "binding generation "
              + binding.generation()
              + " is not the next after "
              + currentGeneration);
    }
    if (binding.bindingId() == null
        || binding.bindingId().isBlank()
        || binding.workerSessionId() == null
        || binding.workerSessionId().isBlank()
        || binding.workerAuthority() == null
        || binding.workerLeaseDigest() == null
        || !binding.workerLeaseDigest().matches("sha256:[0-9a-f]{64}")
        || binding.workerEpoch() < 0
        || binding.workerRevision() < 0
        || binding.runnerSteps() < 0
        || binding.runnerIntents() < 0
        || !("CREATE".equals(binding.reason()) || "RESTORE".equals(binding.reason()))) {
      throw new BindingRejectedException("binding is incomplete or malformed");
    }
  }

  @Override
  public Optional<WorkerBinding> currentBinding(String attemptId) {
    List<WorkerBinding> rows =
        jdbc.query(
            "SELECT b.binding_id, b.generation, b.attempt_id, b.request_digest,"
                + " b.worker_authority, b.worker_session_id, b.worker_epoch,"
                + " b.worker_lease_digest, b.worker_revision, b.runner_steps, b.runner_intents,"
                + " b.reason, b.worker_identity::text AS worker_identity"
                + " FROM candidate_attempt_worker_bindings b"
                + " JOIN candidate_execution_attempts a ON a.attempt_id = b.attempt_id"
                + " AND a.binding_generation = b.generation WHERE b.attempt_id = ?",
            (rs, n) -> {
              String identity = rs.getString("worker_identity");
              try {
                return new WorkerBinding(
                    rs.getString("binding_id"),
                    rs.getLong("generation"),
                    rs.getString("attempt_id"),
                    rs.getString("request_digest"),
                    rs.getString("worker_authority"),
                    rs.getString("worker_session_id"),
                    rs.getLong("worker_epoch"),
                    rs.getString("worker_lease_digest"),
                    rs.getLong("worker_revision"),
                    rs.getLong("runner_steps"),
                    rs.getLong("runner_intents"),
                    rs.getString("reason"),
                    identity == null ? null : mapper.readValue(identity, MAP));
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("persisted worker identity is invalid", e);
              }
            },
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
