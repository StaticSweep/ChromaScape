package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ReplayPlaybackIndexConvergenceTest {

  @Test
  void normalizesSchemaQualificationQuotingWhitespaceAndRedundantPredicateParentheses() {
    String canonical =
        "CREATE INDEX idx_playback ON debug_sessions USING btree "
            + "(start_time DESC, id DESC) WHERE (recording_id IS NOT NULL)";
    String postgresVariant =
        " CREATE  INDEX \"public\".\"idx_playback\" ON \"public\".\"debug_sessions\" "
            + "USING BTREE ( start_time   DESC , id DESC ) "
            + "WHERE ( ( ( \"recording_id\" IS NOT NULL ) ) ) ";

    assertEquals(
        ReplayPlaybackIndexConvergence.normalizeIndexDefinition(canonical, "public"),
        ReplayPlaybackIndexConvergence.normalizeIndexDefinition(postgresVariant, "\"public\""));
  }

  @Test
  void removesOnlyRedundantOuterPredicateParentheses() {
    String grouped =
        ReplayPlaybackIndexConvergence.normalizeIndexDefinition(
            "CREATE INDEX idx ON debug_sessions (id) WHERE ((a IS NOT NULL OR (b IS NOT NULL AND c IS NOT NULL)))",
            "public");
    String ungrouped =
        ReplayPlaybackIndexConvergence.normalizeIndexDefinition(
            "CREATE INDEX idx ON debug_sessions (id) WHERE a IS NOT NULL OR (b IS NOT NULL AND c IS NOT NULL)",
            "public");

    assertEquals(ungrouped, grouped);
  }

  @Test
  void nonPostgresDataSourceIsAStartupNoOp() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("H2");

    new ReplayPlaybackIndexConvergence(dataSource).run(null);

    verify(connection, never()).createStatement();
    verify(metadata, never()).getTables(any(), any(), anyString(), any(String[].class));
  }

  @Test
  void ddlPermissionDenialFailsStartupClosed() throws Exception {
    DataSource dataSource = mockPostgresDataSource("CREATE INDEX idx_wrong ON debug_sessions (id)");
    Connection connection = dataSource.getConnection();
    Statement statement = connection.createStatement();
    when(statement.execute(anyString())).thenThrow(new SQLException("permission denied"));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> new ReplayPlaybackIndexConvergence(dataSource).run(null));

    assertTrue(failure.getMessage().contains("refusing application startup"));
    assertTrue(failure.getCause().getMessage().contains("permission denied"));
  }

  @Test
  void existingIndexDefinitionDriftFailsStartupClosed() throws Exception {
    DataSource dataSource =
        mockPostgresDataSource(
            "CREATE INDEX idx_debug_sessions_playback_keyset ON debug_sessions (id)");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> new ReplayPlaybackIndexConvergence(dataSource).run(null));

    assertTrue(failure.getCause().getMessage().contains("Playback index definition drift"));
    assertTrue(failure.getCause().getMessage().contains("idx_debug_sessions_playback_keyset"));
  }

  private static DataSource mockPostgresDataSource(String actualDefinition) throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    Statement statement = mock(Statement.class);
    PreparedStatement lookup = mock(PreparedStatement.class);
    ResultSet tableResult = mock(ResultSet.class);
    ResultSet indexResult = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(connection.getSchema()).thenReturn("public");
    when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
    when(metadata.getTables(isNull(), eq("public"), anyString(), any(String[].class)))
        .thenReturn(tableResult);
    when(tableResult.next()).thenReturn(true);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenReturn(false);
    when(connection.prepareStatement(anyString())).thenReturn(lookup);
    when(lookup.executeQuery()).thenReturn(indexResult);
    when(indexResult.next()).thenReturn(true);
    when(indexResult.getString(1)).thenReturn(actualDefinition);
    return dataSource;
  }
}
