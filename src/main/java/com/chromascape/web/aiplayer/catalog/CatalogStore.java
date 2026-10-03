package com.chromascape.web.aiplayer.catalog;

import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable catalog and profile SQL storage; all identifiers/JSON are bound parameters. */
@Repository
public class CatalogStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  /** Creates this component with its explicit dependencies or bounded failure details. */
  public CatalogStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.json = json;
    this.transaction = new TransactionTemplate(manager);
  }

  /** Persists a last-known-good catalog without modifying prior snapshots. */
  public void cache(String endpoint, String mode, ObjectNode payload) {
    String id = Micro6CanonicalHash.sha256WithPrefix(payload);
    String capture = payload.path("captured_at").asText();
    Instant captured = capture.isBlank() ? Instant.now() : Instant.parse(capture);
    run(
        () -> {
          jdbc.update(
              "INSERT INTO "
                  + "ai_player_catalog_snapshots(snapshot_id,endpoint_ref,mode,"
                  + "catalog_hash,payload,captured_at) "
                  + "VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT "
                  + "(endpoint_ref,mode,snapshot_id) DO NOTHING",
              id,
              endpoint,
              mode,
              payload.path("catalog_hash").asText(),
              payload.toString(),
              java.sql.Timestamp.from(captured));
          return null;
        });
  }

  /** Returns the newest snapshot for this endpoint and catalog mode. */
  public ObjectNode cached(String endpoint, String mode) {
    return run(
        () -> {
          List<ObjectNode> rows =
              jdbc.query(
                  "SELECT payload,captured_at FROM ai_player_catalog_snapshots "
                      + "WHERE endpoint_ref=? AND mode=? "
                      + "ORDER BY captured_at DESC,snapshot_id DESC LIMIT 1",
                  (r, n) -> {
                    ObjectNode node = parse(r.getString("payload"));
                    node.put("captured_at", r.getTimestamp("captured_at").toInstant().toString());
                    return node;
                  },
                  endpoint,
                  mode);
          return rows.isEmpty() ? null : rows.get(0);
        });
  }

  /** Allocates and persists one immutable profile version atomically. */
  public ObjectNode append(
      String requestedId, String name, ObjectNode detail, ObjectNode settings) {
    String id = requestedId == null ? UUID.randomUUID().toString() : requestedId;
    if (!id.matches("[A-Za-z0-9_-]{1,160}")) {
      throw new CatalogProblem(422, "INVALID_PROFILE_ID", "Invalid profile identity.");
    }
    return run(
        () ->
            transaction.execute(
                ignored -> {
                  // Serializes version allocation across processes, not only this Java instance.
                  jdbc.queryForObject(
                      "SELECT pg_advisory_xact_lock(hashtextextended(?,641))",
                      Object.class,
                      "sc641-profile:" + id);
                  Integer previous =
                      jdbc.queryForObject(
                          "SELECT COALESCE(max(profile_version),0) FROM "
                              + "ai_player_behavior_profiles WHERE profile_id=?",
                          Integer.class,
                          id);
                  if (requestedId != null && previous == 0) {
                    throw new CatalogProblem(404, "PROFILE_NOT_FOUND", "Profile does not exist.");
                  }
                  int version = previous + 1;
                  JsonNode task = detail.path("task");
                  jdbc.update(
                      "INSERT INTO "
                          + "ai_player_behavior_profiles(profile_id,profile_version,task_key,"
                          + "catalog_hash,settings_json,"
                          + "settings_hash,requirements_json,created_at,name,catalog_version,"
                          + "catalog_snapshot,task_snapshot,schema_snapshot,source_summary,"
                          + "method_facts,schema_hash,requirements_hash) "
                          + "VALUES "
                          + "(?,?,?,?,?::jsonb,?,?::jsonb,?,?,?,?::jsonb,?::jsonb,?::jsonb,"
                          + "?::jsonb,?::jsonb,?,?)",
                      id,
                      version,
                      task.path("task_key").asText(),
                      detail.path("catalog_hash").asText(),
                      settings.toString(),
                      Micro6CanonicalHash.sha256WithPrefix(settings),
                      task.path("requirements").isObject()
                          ? task.path("requirements").toString()
                          : "{}",
                      java.sql.Timestamp.from(Instant.now()),
                      name,
                      detail.path("catalog_version").asText(),
                      detail.path("catalog_snapshot").toString(),
                      task.toString(),
                      task.path("args_schema").toString(),
                      detail.path("source_summary").toString(),
                      detail.path("method_facts").toString(),
                      task.path("settings_schema_hash").asText(),
                      Micro6CanonicalHash.sha256WithPrefix(
                          task.path("requirements").isObject()
                              ? task.path("requirements")
                              : json.createObjectNode()));
                  return profile(id, version);
                }));
  }

  /** Lists named saved profile versions in reverse creation order. */
  public List<ObjectNode> profiles() {
    return run(
        () ->
            jdbc.query(
                "SELECT profile_id,profile_version,name,task_key,created_at FROM "
                    + "ai_player_behavior_profiles "
                    + "WHERE name IS NOT NULL ORDER BY created_at "
                    + "DESC,profile_id,profile_version DESC LIMIT 200",
                (r, n) -> {
                  ObjectNode o = json.createObjectNode();
                  o.put("profile_id", r.getString(1));
                  o.put("profile_version", r.getInt(2));
                  o.put("name", r.getString(3));
                  o.put("task_key", r.getString(4));
                  o.put("created_at", r.getTimestamp(5).toInstant().toString());
                  return o;
                }));
  }

  /** Returns an exact historical profile version with its retained snapshots. */
  public ObjectNode profile(String id, int version) {
    return run(
        () -> {
          List<ObjectNode> rows =
              jdbc.query(
                  "SELECT * FROM ai_player_behavior_profiles WHERE profile_id=? AND "
                      + "profile_version=?",
                  (r, n) -> {
                    ObjectNode out = json.createObjectNode();
                    for (String field :
                        List.of(
                            "profile_id",
                            "name",
                            "task_key",
                            "catalog_version",
                            "catalog_hash",
                            "settings_hash",
                            "schema_hash",
                            "requirements_hash")) {
                      out.put(field, r.getString(field));
                    }
                    out.put("profile_version", r.getInt("profile_version"));
                    out.put("created_at", r.getTimestamp("created_at").toInstant().toString());
                    for (String field :
                        List.of(
                            "settings_json",
                            "requirements_json",
                            "catalog_snapshot",
                            "task_snapshot",
                            "schema_snapshot",
                            "source_summary",
                            "method_facts")) {
                      String value = r.getString(field);
                      out.set(field, value == null ? json.nullNode() : parse(value));
                    }
                    return out;
                  },
                  id,
                  version);
          if (rows.isEmpty()) {
            throw new CatalogProblem(404, "PROFILE_NOT_FOUND", "Profile version does not exist.");
          }
          return rows.get(0);
        });
  }

  private ObjectNode parse(String text) {
    try {
      return (ObjectNode) json.readTree(text);
    } catch (Exception e) {
      throw new CatalogProblem(503, "STORAGE_INVALID", "Stored snapshot cannot be read.");
    }
  }

  private <T> T run(java.util.function.Supplier<T> action) {
    try {
      return action.get();
    } catch (DataAccessException e) {
      throw new CatalogProblem(
          503,
          "PROFILE_STORAGE_UNAVAILABLE",
          "Catalog/profile storage is unavailable; apply the SC-641 migration "
              + "or reviewed development reset.");
    }
  }
}
