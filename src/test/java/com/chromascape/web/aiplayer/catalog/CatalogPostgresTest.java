package com.chromascape.web.aiplayer.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.aiplayer.schema.AiPlayerCurrentSchema;
import com.chromascape.web.aiplayer.schema.AiPlayerSchemaTarget;
import com.chromascape.web.integration.micro6.service.CatalogFixture;
import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Uses only an explicitly validated disposable PostgreSQL database, never a silent skip. */
class CatalogPostgresTest {
  private static CatalogStore store;
  private static JdbcTemplate jdbc;
  private static final ObjectMapper JSON = new ObjectMapper();

  @BeforeAll
  static void database() throws Exception {
    String url = System.getenv("SC665_TEST_JDBC_URL");
    AiPlayerSchemaTarget.validate(url, Set.of(AiPlayerSchemaTarget.PROFILE));
    var source = new DriverManagerDataSource(url, "sc665", "sc665-disposable-test");
    new AiPlayerCurrentSchema(source).reset(AiPlayerSchemaTarget.RESET_CONFIRMATION);
    jdbc = new JdbcTemplate(source);
    store = new CatalogStore(jdbc, JSON, new DataSourceTransactionManager(source));
  }

  static ObjectNode detail() {
    ObjectNode out =
        JSON.createObjectNode()
            .put("catalog_hash", CatalogFixture.GOLDEN_CATALOG_HASH)
            .put("catalog_version", "2");
    ObjectNode task = CatalogFixture.task(true);
    task.putObject("requirements").put("membership", "FREE");
    out.set("task", task);
    out.set("catalog_snapshot", CatalogNormalizer.normalize(CatalogFixture.full(), true));
    out.putObject("source_summary").put("availability", "UNKNOWN");
    out.putObject("method_facts").put("state", "UNKNOWN");
    return out;
  }

  @Test
  void exactSnapshotsPersistAcrossStoreInstancesAndVersionsNeverMutate() {
    ObjectNode values =
        JSON.createObjectNode().put("stopConditionValue", 50).put("targetFish", "TROUT");
    ObjectNode first = store.append(null, "Fishing", detail(), values);
    String id = first.path("profile_id").asText();
    ObjectNode second =
        store.append(id, "Fishing v2", detail(), values.deepCopy().put("stopConditionValue", 60));
    assertEquals(2, second.path("profile_version").asInt());
    assertEquals(first, store.profile(id, 1));
    assertEquals(
        Micro6CanonicalHash.sha256WithPrefix(values), first.path("settings_hash").asText());
    assertEquals("FREE", first.path("requirements_json").path("membership").asText());
    assertEquals(CatalogFixture.schema(), first.path("schema_snapshot"));
    assertEquals(CatalogFixture.GOLDEN_SETTINGS_SCHEMA_HASH, first.path("schema_hash").asText());
    assertEquals(
        Micro6CanonicalHash.sha256WithPrefix(first.path("requirements_json")),
        first.path("requirements_hash").asText());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE ai_player_behavior_profiles SET name='mutated' WHERE profile_id=?", id));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM ai_player_behavior_profiles WHERE profile_id=?", id));
    assertEquals(50, store.profile(id, 1).path("settings_json").path("stopConditionValue").asInt());
  }

  @Test
  void concurrentEditorsAllocateDistinctContiguousVersions() throws Exception {
    ObjectNode values = JSON.createObjectNode().put("targetFish", "TROUT");
    String id = store.append(null, "Concurrent", detail(), values).path("profile_id").asText();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> store.append(id, "Concurrent A", detail(), values));
      var b = pool.submit(() -> store.append(id, "Concurrent B", detail(), values));
      assertEquals(
          Set.of(2, 3),
          Set.of(a.get().path("profile_version").asInt(), b.get().path("profile_version").asInt()));
      assertEquals(1, store.profile(id, 1).path("profile_version").asInt());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cachedCatalogIsDurableAndEndpointAndModeRemainSeparated() {
    ObjectNode payload =
        CatalogNormalizer.normalize(CatalogFixture.full(), true).put("state", "READY");
    store.cache("fixture-ref", "full", payload);
    var reopened =
        new CatalogStore(jdbc, JSON, new DataSourceTransactionManager(jdbc.getDataSource()));
    assertEquals(
        CatalogFixture.GOLDEN_CATALOG_HASH,
        reopened.cached("fixture-ref", "full").path("catalog_hash").asText());
    assertNull(reopened.cached("other-ref", "full"));
    assertNull(reopened.cached("fixture-ref", "lightweight"));
    assertThrows(
        DataAccessException.class, () -> jdbc.update("DELETE FROM ai_player_catalog_snapshots"));
  }

  @Test
  void releasedMigrationCreatesTheSameStorageContractOutsideDevelopmentSchema() throws Exception {
    var source =
        new DriverManagerDataSource(
            System.getenv("SC665_TEST_JDBC_URL")
                .replace("currentSchema=ai_player_dev", "currentSchema=public"),
            "sc665",
            "sc665-disposable-test");
    var directory = java.nio.file.Files.createTempDirectory("sc641-migration-");
    try (var input =
        CatalogPostgresTest.class.getResourceAsStream(
            "/db/migration/V32__sc641_catalog_profiles.sql")) {
      java.nio.file.Files.copy(input, directory.resolve("V32__sc641_catalog_profiles.sql"));
    }
    var flyway =
        org.flywaydb.core.Flyway.configure()
            .dataSource(source)
            .locations("filesystem:" + directory)
            .schemas("public")
            .table("sc641_migration_history")
            .baselineOnMigrate(true)
            .baselineVersion("31")
            .load();
    var publicJdbc = new JdbcTemplate(source);
    try {
      flyway.migrate();
      flyway.validate();
      var released = new CatalogStore(publicJdbc, JSON, new DataSourceTransactionManager(source));
      ObjectNode saved =
          released.append(
              null,
              "Released migration",
              detail(),
              JSON.createObjectNode().put("targetFish", "TROUT"));
      assertEquals(
          detail().path("task").path("settings_schema_hash").asText(),
          saved.path("schema_hash").asText());
      assertThrows(
          DataAccessException.class,
          () -> publicJdbc.update("DELETE FROM ai_player_behavior_profiles"));
    } finally {
      publicJdbc.execute(
          "DROP TABLE IF EXISTS ai_player_behavior_profiles, ai_player_catalog_snapshots, sc641_migration_history CASCADE");
      publicJdbc.execute("DROP FUNCTION IF EXISTS sc641_append_only()");
      java.nio.file.Files.deleteIfExists(directory.resolve("V32__sc641_catalog_profiles.sql"));
      java.nio.file.Files.deleteIfExists(directory);
    }
  }

  @Test
  void unknownProfileCannotBecomeAnImplicitNewIdentity() {
    assertEquals(
        "PROFILE_NOT_FOUND",
        assertThrows(
                CatalogProblem.class,
                () -> store.append("missing", "No", detail(), JSON.createObjectNode()))
            .code());
    assertEquals(
        "PROFILE_NOT_FOUND",
        assertThrows(CatalogProblem.class, () -> store.profile("missing", 1)).code());
  }
}
