package com.chromascape.web.aiplayer.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;

/**
 * Owns only the explicitly disposable SC-665 schema. No application/runtime services are started.
 */
public final class AiPlayerCurrentSchema {
  public static final String IDENTITY = "sc609-current-v1";
  public static final int MIGRATION_COUNT = 6;
  public static final String LOCATION = "classpath:db/ai-player-current";
  private static final long LOCK = 66520261002L;
  private final DataSource dataSource;

  /** Creates an owner for the explicit disposable datasource. */
  public AiPlayerCurrentSchema(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** Creates an empty schema, or verifies an existing schema without changing it. */
  public void bootstrap() throws Exception {
    try (var guard = lockedConnection()) {
      var lock = guard.connection();
      if (!isEmpty(lock)) {
        verifyExisting(lock);
        return;
      }
      migrations(dataSource).migrate();
      verifyExisting(lock);
    }
  }

  /** Checks the pinned current identity, migration history and physical structure. */
  public void verify() throws Exception {
    try (var guard = lockedConnection()) {
      var lock = guard.connection();
      verifyExisting(lock);
    }
  }

  /** Drops and rebuilds the fixed development schema after explicit confirmation. */
  public void reset(String confirmation) throws Exception {
    AiPlayerSchemaTarget.requireResetConfirmation(confirmation);
    try (var guard = lockedConnection()) {
      var lock = guard.connection();
      // Refuse CASCADE if an object outside the dedicated schema depends on one of its objects.
      // Check catalog dependencies before CASCADE. The fixed database and schema are the
      // disposable ownership boundary; other schemas and dependent public objects are refused.
      try (var statement = lock.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT count(*) FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' "
                      + "AND nspname NOT IN ('information_schema','public','ai_player_dev')")) {
        rows.next();
        if (rows.getLong(1) != 0) {
          throw new IllegalArgumentException("AI_PLAYER_UNSAFE_TARGET: additional user schemas.");
        }
      }
      if (hasExternalDependencies(lock)) {
        throw new IllegalArgumentException(
            "AI_PLAYER_UNSAFE_TARGET: external schema dependencies.");
      }
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS ai_player_dev CASCADE");
        lock.commit();
      } catch (Exception exception) {
        lock.rollback();
        throw exception;
      } finally {
        lock.setAutoCommit(true);
      }
      // Grouped transactional migrations never expose a partly initialized successful identity.
      // A failed rebuild leaves the disposable schema unavailable; startup then fails closed.
      migrations(dataSource).migrate();
      verifyExisting(lock);
    }
  }

  /** Returns the isolated transactional migration configuration. */
  public static Flyway migrations(DataSource source) {
    return Flyway.configure()
        .dataSource(source)
        .locations(LOCATION)
        .schemas(AiPlayerSchemaTarget.SCHEMA)
        .defaultSchema(AiPlayerSchemaTarget.SCHEMA)
        .baselineOnMigrate(false)
        .outOfOrder(false)
        .cleanDisabled(true)
        .group(true)
        .load();
  }

  private SchemaLock lockedConnection() throws SQLException {
    Connection connection = dataSource.getConnection();
    try {
      AiPlayerSchemaTarget.validateConnection(connection);
      try (var statement = connection.createStatement()) {
        statement.execute("SET search_path TO ai_player_dev, pg_catalog");
        statement.execute("SET lock_timeout TO '10s'");
        try (var rows = statement.executeQuery("SELECT pg_try_advisory_lock(" + LOCK + ")")) {
          rows.next();
          if (!rows.getBoolean(1)) {
            throw new IllegalStateException(
                "AI_PLAYER_SCHEMA_BUSY: another schema operation is active.");
          }
        }
      }
      return new SchemaLock(connection);
    } catch (Exception exception) {
      connection.close();
      throw exception;
    }
  }

  private record SchemaLock(Connection connection) implements AutoCloseable {
    @Override
    public void close() throws SQLException {
      try (var statement = connection.createStatement()) {
        statement.execute("SELECT pg_advisory_unlock(" + LOCK + ")");
      } finally {
        connection.close();
      }
    }
  }

  private static boolean isEmpty(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n "
                    + "ON n.oid=c.relnamespace WHERE n.nspname='ai_player_dev') "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n "
                    + "ON n.oid=p.pronamespace WHERE n.nspname='ai_player_dev') "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n "
                    + "ON n.oid=t.typnamespace WHERE n.nspname='ai_player_dev')")) {
      rows.next();
      return rows.getBoolean(1);
    }
  }

  private static boolean hasExternalDependencies(Connection connection) throws Exception {
    String query =
        new ClassPathResource("db/ai-player-external-dependencies.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      rows.next();
      return rows.getBoolean(1);
    }
  }

  private void verifyExisting(Connection connection) throws Exception {
    try {
      Properties pinned = new Properties();
      try (var input =
          new ClassPathResource("db/ai-player-current-identity.properties").getInputStream()) {
        pinned.load(input);
      }
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT identity FROM ai_player_schema_identity")) {
        if (!rows.next() || !IDENTITY.equals(rows.getString(1)) || rows.next()) {
          throw mismatch();
        }
      }
      var flyway = migrations(dataSource);
      flyway.validate();
      if (java.util.Arrays.stream(flyway.info().all())
                  .filter(info -> info.getVersion() != null)
                  .count()
              != MIGRATION_COUNT
          || flyway.info().pending().length != 0
          || java.util.Arrays.stream(flyway.info().applied())
                  .filter(info -> info.getVersion() != null)
                  .count()
              != MIGRATION_COUNT) {
        throw mismatch();
      }
      if (!IDENTITY.equals(pinned.getProperty("identity"))
          || !fingerprint(connection).equals(pinned.getProperty("fingerprint"))) {
        throw mismatch();
      }
    } catch (Exception exception) {
      throw new IllegalStateException(mismatch().getMessage(), exception);
    }
  }

  /** Hashes deterministic physical catalog definitions, excluding row and sequence values. */
  public static String fingerprint(Connection connection) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.execute("SET search_path TO ai_player_dev, pg_catalog");
    }
    String query =
        new ClassPathResource("db/ai-player-schema-fingerprint.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      while (rows.next()) {
        for (int column = 1; column <= 2; column++) {
          byte[] value = rows.getString(column).getBytes(StandardCharsets.UTF_8);
          digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
          digest.update(value);
        }
      }
    }
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }

  private static IllegalStateException mismatch() {
    return new IllegalStateException(
        "AI_PLAYER_SCHEMA_MISMATCH: current development schema is missing, changed or stale. "
            + "Stop application instances, then run the explicit development reset procedure "
            + "in docs/SC-665-CURRENT-SCHEMA.md.");
  }
}
