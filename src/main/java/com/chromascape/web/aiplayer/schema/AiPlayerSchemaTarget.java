package com.chromascape.web.aiplayer.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Pattern;

/** The schema tool never accepts an inferred or shared database target. */
public final class AiPlayerSchemaTarget {
  public static final String DATABASE = "chromascape_aiplayer_dev";
  public static final String SCHEMA = "ai_player_dev";
  public static final String PROFILE = "ai-player-development";
  public static final String RESET_CONFIRMATION = "RESET_AI_PLAYER_DEV";
  private static final Pattern URL =
      Pattern.compile(
          "jdbc:postgresql://(?:127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})/"
              + DATABASE
              + "(?:\\?currentSchema="
              + SCHEMA
              + ")?");

  private AiPlayerSchemaTarget() {}

  /** Rejects inferred, shared, remote and mixed-profile development targets. */
  public static void validate(String url, Set<String> profiles) {
    var match = URL.matcher(url == null ? "" : url);
    if (!profiles.equals(Set.of(PROFILE)) || !match.matches()) {
      throw new IllegalArgumentException(
          "AI_PLAYER_UNSAFE_TARGET: require only profile ai-player-development and an explicit "
              + "loopback JDBC URL for chromascape_aiplayer_dev (no connection options except "
              + "currentSchema=ai_player_dev).");
    }
    int port = Integer.parseInt(match.group(1));
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("AI_PLAYER_UNSAFE_TARGET: invalid PostgreSQL port.");
    }
  }

  static void validateConnection(Connection connection) throws SQLException {
    validate(connection.getMetaData().getURL(), Set.of(PROFILE));
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT current_database()")) {
      if (!rows.next() || !DATABASE.equals(rows.getString(1))) {
        throw new IllegalArgumentException(
            "AI_PLAYER_UNSAFE_TARGET: unexpected database identity.");
      }
    }
  }

  /** Requires the exact one-use operator confirmation string before reset. */
  public static void requireResetConfirmation(String confirmation) {
    if (!RESET_CONFIRMATION.equals(confirmation)) {
      throw new IllegalArgumentException(
          "AI_PLAYER_RESET_NOT_CONFIRMED: set AI_PLAYER_DEV_RESET_CONFIRM=" + RESET_CONFIRMATION);
    }
  }
}
