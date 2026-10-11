package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Database row locks serialize operators and fence checkpoints across service instances. */
@Repository
public class XrspsSessionRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public XrspsSessionRepository(
      JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setTimeout(120);
  }

  public record Creation(XrspsSession session, boolean created) {}

  public Creation create(
      XrspsSessionMode mode,
      String key,
      String scenario,
      Function<XrspsSession, XrspsSessionDocument> initialize) {
    return create(mode, key, scenario, "diagnostic", initialize);
  }

  public Creation create(
      XrspsSessionMode mode,
      String key,
      String scenario,
      String ownerId,
      Function<XrspsSession, XrspsSessionDocument> initialize) {
    if (ownerId == null || ownerId.isBlank())
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "Session owner is required");
    // Reuse the exact key/mode/scenario validation without allocating a second world.
    XrspsSession candidate = new XrspsSessionStore().create(mode, key, scenario, ownerId);
    return transactions.execute(
        tx -> {
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          int inserted =
              jdbc.update(
                  "INSERT INTO xrsps_sessions(id,idempotency_key,owner_id,document) VALUES (?, ?, ?, '{}'::jsonb) ON CONFLICT (owner_id,idempotency_key) DO NOTHING",
                  candidate.getId(),
                  key,
                  ownerId);
          if (inserted == 1) {
            XrspsSessionDocument doc = initialize.apply(candidate);
            save(candidate.getId(), doc);
            return new Creation(XrspsSession.restore(doc.lifecycle), true);
          }
          XrspsSessionDocument existing =
              read(
                  "SELECT document::text FROM xrsps_sessions WHERE idempotency_key=? AND owner_id=? FOR UPDATE",
                  key,
                  ownerId);
          if (existing.lifecycle.mode() != mode
              || !existing.lifecycle.scenarioRef().equals(scenario)) {
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_IDEMPOTENCY_CONFLICT,
                "Idempotency key reused with different content");
          }
          return new Creation(XrspsSession.restore(existing.lifecycle), false);
        });
  }

  public <T> T update(UUID id, Function<XrspsSessionDocument, T> operation) {
    return transactions.execute(
        tx -> {
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          XrspsSessionDocument doc =
              read("SELECT document::text FROM xrsps_sessions WHERE id=? FOR UPDATE", id);
          T result = operation.apply(doc);
          save(id, doc);
          return result;
        });
  }

  public XrspsSessionDocument get(UUID id) {
    return read("SELECT document::text FROM xrsps_sessions WHERE id=?", id);
  }

  public XrspsSessionDocument getOwned(UUID id, String ownerId) {
    return read("SELECT document::text FROM xrsps_sessions WHERE id=? AND owner_id=?", id, ownerId);
  }

  private XrspsSessionDocument read(String sql, Object... keys) {
    var rows = jdbc.query(sql, (rs, n) -> rs.getString(1), keys);
    if (rows.isEmpty())
      throw ReplayException.notFound(ReplayErrorCode.RUN_NOT_FOUND, "xRSPS session not found");
    try {
      return mapper.readValue(rows.get(0), XrspsSessionDocument.class);
    } catch (Exception e) {
      throw new IllegalStateException("Persisted xRSPS session is invalid", e);
    }
  }

  private void save(UUID id, XrspsSessionDocument doc) {
    try {
      jdbc.update(
          "UPDATE xrsps_sessions SET document=?::jsonb, updated_at=now() WHERE id=?",
          mapper.writeValueAsString(doc),
          id);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize xRSPS session", e);
    }
  }
}
