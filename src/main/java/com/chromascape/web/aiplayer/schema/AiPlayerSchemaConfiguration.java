package com.chromascape.web.aiplayer.schema;

import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Flyway's initialization dependency runs this check before the entity manager or web services. */
@Configuration(proxyBeanMethods = false)
@Profile(AiPlayerSchemaTarget.PROFILE)
public class AiPlayerSchemaConfiguration {
  @Bean
  static BeanFactoryPostProcessor aiPlayerSchemaConfigurationGuard(Environment environment) {
    return ignored -> validateConfiguration(environment);
  }

  static void validateConfiguration(Environment environment) {
    AiPlayerSchemaTarget.validate(
        environment.getProperty("spring.datasource.url"), Set.of(environment.getActiveProfiles()));
    if (!"validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
        || !"ai_player_dev"
            .equals(environment.getProperty("spring.jpa.properties.hibernate.default_schema"))
        || !AiPlayerCurrentSchema.LOCATION.equals(
            environment.getProperty("spring.flyway.locations"))
        || !"true".equals(environment.getProperty("spring.flyway.enabled"))
        || Boolean.parseBoolean(environment.getProperty("spring.jpa.generate-ddl"))
        || "always".equals(environment.getProperty("spring.sql.init.mode"))
        || environment.containsProperty("spring.jpa.properties.hibernate.hbm2ddl.auto")
        || environment.containsProperty(
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action")) {
      throw new IllegalArgumentException(
          "AI_PLAYER_UNSAFE_CONFIGURATION: use the unchanged development profile.");
    }
  }

  @Bean
  FlywayMigrationStrategy aiPlayerCurrentSchemaStrategy(
      DataSource source, Environment environment) {
    validateConfiguration(environment);
    return ignored -> {
      try {
        new AiPlayerCurrentSchema(source).bootstrap();
      } catch (Exception exception) {
        throw new IllegalStateException(
            "AI_PLAYER_SCHEMA_STARTUP_REJECTED: " + exception.getMessage(), exception);
      }
    };
  }
}
