package com.chromascape;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = com.chromascape.web.ChromaScapeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChromaScapeApplicationTests {

  @Autowired private Flyway flyway;

  @Autowired private DataSource dataSource;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void contextLoadsAndUsesCurrentRepositoryMigrations() {
    IsolatedPostgresTestDatabase.assertRepositoryHeadAndMigration16Applied(flyway, dataSource);
  }
}
