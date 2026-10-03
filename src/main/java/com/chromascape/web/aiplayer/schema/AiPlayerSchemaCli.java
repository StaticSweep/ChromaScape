package com.chromascape.web.aiplayer.schema;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Native Gradle/Java entry point; never starts ChromaScape or any gameplay services. */
public final class AiPlayerSchemaCli {
  private AiPlayerSchemaCli() {}

  /** Executes one explicit schema action without starting application services. */
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !Set.of("bootstrap", "verify", "reset").contains(args[0])) {
      throw new IllegalArgumentException(
          "Usage: aiPlayerSchema -PschemaAction=bootstrap|verify|reset");
    }
    String profiles = System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "");
    String url = System.getenv("AI_PLAYER_DEV_JDBC_URL");
    AiPlayerSchemaTarget.validate(
        url, Arrays.stream(profiles.split(",")).map(String::trim).collect(Collectors.toSet()));
    var source =
        new DriverManagerDataSource(
            url, required("AI_PLAYER_DEV_USERNAME"), required("AI_PLAYER_DEV_PASSWORD"));
    var schema = new AiPlayerCurrentSchema(source);
    switch (args[0]) {
      case "bootstrap" -> schema.bootstrap();
      case "reset" -> schema.reset(System.getenv("AI_PLAYER_DEV_RESET_CONFIRM"));
      default -> schema.verify();
    }
    System.out.println("AI_PLAYER_SCHEMA_OK: " + AiPlayerCurrentSchema.IDENTITY + " " + args[0]);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AI_PLAYER_CONFIGURATION_MISSING: " + name);
    }
    return value;
  }
}
