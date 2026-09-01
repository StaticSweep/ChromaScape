package com.chromascape.web.replay.playback;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Converges the playback indexes after Hibernate has created or updated its managed tables.
 *
 * <p>V22 remains responsible for upgrading databases where the debugger tables already exist.
 * This runner covers a fresh Hibernate-managed bootstrap, where Flyway necessarily reaches V22
 * before those tables are present.
 */
@Component
@DependsOn("entityManagerFactory")
public class ReplayPlaybackIndexConvergence implements ApplicationRunner {

  private static final String DEBUG_SESSIONS = "debug_sessions";
  private static final String TICK_SNAPSHOTS = "tick_snapshots";
  private static final String TICK_LOGS = "tick_logs";

  private static final String DEBUG_SESSIONS_KEYSET_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_debug_sessions_playback_keyset "
          + "ON debug_sessions (start_time DESC, id DESC) "
          + "WHERE recording_id IS NOT NULL";
  private static final String DEBUG_SESSIONS_ACCOUNT_KEYSET_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_debug_sessions_playback_account_keyset "
          + "ON debug_sessions (account_name, start_time DESC, id DESC) "
          + "WHERE recording_id IS NOT NULL";
  private static final String TICK_SNAPSHOTS_KEYSET_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_tick_snapshots_playback_session_tick_id "
          + "ON tick_snapshots (session_id, tick, id)";
  private static final String TICK_LOGS_KEYSET_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_tick_logs_playback_session_tick_id "
          + "ON tick_logs (session_id, tick, id)";

  private static final String DEBUG_SESSIONS_KEYSET_DEFINITION =
      "CREATE INDEX idx_debug_sessions_playback_keyset "
          + "ON debug_sessions USING btree (start_time DESC, id DESC) "
          + "WHERE (recording_id IS NOT NULL)";
  private static final String DEBUG_SESSIONS_ACCOUNT_KEYSET_DEFINITION =
      "CREATE INDEX idx_debug_sessions_playback_account_keyset "
          + "ON debug_sessions USING btree (account_name, start_time DESC, id DESC) "
          + "WHERE (recording_id IS NOT NULL)";
  private static final String TICK_SNAPSHOTS_KEYSET_DEFINITION =
      "CREATE INDEX idx_tick_snapshots_playback_session_tick_id "
          + "ON tick_snapshots USING btree (session_id, tick, id)";
  private static final String TICK_LOGS_KEYSET_DEFINITION =
      "CREATE INDEX idx_tick_logs_playback_session_tick_id "
          + "ON tick_logs USING btree (session_id, tick, id)";

  private final DataSource dataSource;

  public ReplayPlaybackIndexConvergence(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void run(ApplicationArguments args) {
    try (Connection connection = dataSource.getConnection()) {
      if (!isPostgres(connection)) {
        return;
      }

      Set<String> tables = debuggerTables(connection);
      if (tables.isEmpty()) {
        return;
      }

      try (var statement = connection.createStatement()) {
        if (tables.contains(DEBUG_SESSIONS)) {
          convergeIndex(
              connection,
              statement,
              "idx_debug_sessions_playback_keyset",
              DEBUG_SESSIONS_KEYSET_INDEX,
              DEBUG_SESSIONS_KEYSET_DEFINITION);
          convergeIndex(
              connection,
              statement,
              "idx_debug_sessions_playback_account_keyset",
              DEBUG_SESSIONS_ACCOUNT_KEYSET_INDEX,
              DEBUG_SESSIONS_ACCOUNT_KEYSET_DEFINITION);
        }
        if (tables.contains(TICK_SNAPSHOTS)) {
          convergeIndex(
              connection,
              statement,
              "idx_tick_snapshots_playback_session_tick_id",
              TICK_SNAPSHOTS_KEYSET_INDEX,
              TICK_SNAPSHOTS_KEYSET_DEFINITION);
        }
        if (tables.contains(TICK_LOGS)) {
          convergeIndex(
              connection,
              statement,
              "idx_tick_logs_playback_session_tick_id",
              TICK_LOGS_KEYSET_INDEX,
              TICK_LOGS_KEYSET_DEFINITION);
        }
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(
          "Unable to converge PostgreSQL playback indexes; refusing application startup", ex);
    }
  }

  private static boolean isPostgres(Connection connection) throws SQLException {
    return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
  }

  private static Set<String> debuggerTables(Connection connection) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    String schema = connection.getSchema();
    Set<String> tables = new LinkedHashSet<>();
    for (String table : Set.of(DEBUG_SESSIONS, TICK_SNAPSHOTS, TICK_LOGS)) {
      try (ResultSet result = metadata.getTables(null, schema, table, new String[] {"TABLE"})) {
        if (result.next()) {
          tables.add(table);
        }
      }
    }
    return tables;
  }

  /** Creates each managed index idempotently, then fails closed if its definition drifted. */
  private static void convergeIndex(
      Connection connection,
      java.sql.Statement statement,
      String indexName,
      String createSql,
      String expectedDefinition)
      throws SQLException {
    statement.execute(createSql);
    String actualDefinition;
    String sql =
        "SELECT indexdef FROM pg_indexes "
            + "WHERE schemaname = current_schema() AND indexname = ?";
    try (var lookup = connection.prepareStatement(sql)) {
      lookup.setString(1, indexName);
      try (ResultSet result = lookup.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("PostgreSQL did not create playback index " + indexName);
        }
        actualDefinition = result.getString(1);
      }
    }

    String schema = connection.getSchema();
    String actual = normalizeIndexDefinition(actualDefinition, schema);
    String expected = normalizeIndexDefinition(expectedDefinition, schema);
    if (!expected.equals(actual)) {
      throw new SQLException(
          "Playback index definition drift for "
              + indexName
              + "; expected "
              + expectedDefinition
              + " but found "
              + actualDefinition);
    }
  }

  static String normalizeIndexDefinition(String definition, String schema) {
    String normalized = normalizeSql(definition);
    String normalizedSchema = normalizeSql(schema == null ? "" : schema);
    if (!normalizedSchema.isBlank()) {
      normalized =
          normalized.replaceAll(
              "(?<![a-z0-9_$])" + Pattern.quote(normalizedSchema) + "\\.", "");
    }

    int where = normalized.indexOf(" where ");
    if (where >= 0) {
      String prefix = normalized.substring(0, where + " where ".length());
      String predicate = normalized.substring(where + " where ".length());
      normalized = prefix + stripRedundantOuterParentheses(predicate);
    }
    return normalized;
  }

  /**
   * Normalizes the presentation emitted by {@code pg_indexes}, while retaining SQL punctuation
   * and string literal contents. PostgreSQL may quote identifiers and qualify them with the active
   * schema; neither difference changes the index contract being checked here.
   */
  private static String normalizeSql(String sql) {
    if (sql == null) {
      return "";
    }
    StringBuilder normalized = new StringBuilder(sql.length());
    boolean quotedIdentifier = false;
    boolean quotedLiteral = false;
    for (int index = 0; index < sql.length(); index++) {
      char current = sql.charAt(index);
      if (quotedIdentifier) {
        if (current == '"') {
          if (index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
            normalized.append('"');
            index++;
          } else {
            quotedIdentifier = false;
          }
        } else {
          normalized.append(Character.toLowerCase(current));
        }
      } else if (quotedLiteral) {
        normalized.append(current);
        if (current == '\'') {
          if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
            normalized.append('\'');
            index++;
          } else {
            quotedLiteral = false;
          }
        }
      } else if (current == '"') {
        quotedIdentifier = true;
      } else if (current == '\'') {
        quotedLiteral = true;
        normalized.append(current);
      } else if (Character.isWhitespace(current)) {
        normalized.append(' ');
      } else {
        normalized.append(Character.toLowerCase(current));
      }
    }
    return normalizePunctuationWhitespace(normalized.toString());
  }

  /** Collapses formatting around SQL punctuation without rewriting quoted string literals. */
  private static String normalizePunctuationWhitespace(String sql) {
    StringBuilder normalized = new StringBuilder(sql.length());
    boolean quotedLiteral = false;
    for (int index = 0; index < sql.length(); index++) {
      char current = sql.charAt(index);
      if (quotedLiteral) {
        normalized.append(current);
        if (current == '\'') {
          if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
            normalized.append('\'');
            index++;
          } else {
            quotedLiteral = false;
          }
        }
        continue;
      }
      if (current == '\'') {
        quotedLiteral = true;
        normalized.append(current);
      } else if (current == '.') {
        trimTrailingSpace(normalized);
        normalized.append(current);
        while (index + 1 < sql.length() && Character.isWhitespace(sql.charAt(index + 1))) {
          index++;
        }
      } else if (current == '(') {
        normalized.append(current);
        while (index + 1 < sql.length() && Character.isWhitespace(sql.charAt(index + 1))) {
          index++;
        }
      } else if (current == ')' || current == ',') {
        trimTrailingSpace(normalized);
        normalized.append(current);
        if (current == ',') {
          while (index + 1 < sql.length() && Character.isWhitespace(sql.charAt(index + 1))) {
            index++;
          }
        }
      } else {
        normalized.append(current);
      }
    }
    return normalized
        .toString()
        .replace("where(", "where (")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private static void trimTrailingSpace(StringBuilder value) {
    while (value.length() > 0 && Character.isWhitespace(value.charAt(value.length() - 1))) {
      value.setLength(value.length() - 1);
    }
  }

  /** Removes only parentheses that wrap the complete predicate expression. */
  private static String stripRedundantOuterParentheses(String predicate) {
    String result = predicate.trim();
    while (isWrappedExpression(result)) {
      result = result.substring(1, result.length() - 1).trim();
    }
    return result;
  }

  private static boolean isWrappedExpression(String expression) {
    if (expression.length() < 2
        || expression.charAt(0) != '('
        || expression.charAt(expression.length() - 1) != ')') {
      return false;
    }
    int depth = 0;
    boolean quotedLiteral = false;
    for (int index = 0; index < expression.length(); index++) {
      char current = expression.charAt(index);
      if (quotedLiteral) {
        if (current == '\'') {
          if (index + 1 < expression.length() && expression.charAt(index + 1) == '\'') {
            index++;
          } else {
            quotedLiteral = false;
          }
        }
        continue;
      }
      if (current == '\'') {
        quotedLiteral = true;
      } else if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
        if (depth == 0 && index != expression.length() - 1) {
          return false;
        }
        if (depth < 0) {
          return false;
        }
      }
    }
    return depth == 0 && !quotedLiteral;
  }
}
