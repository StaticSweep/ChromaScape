package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import java.sql.DriverManager;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class SourceManifestTextPersistenceTest {
  @Test
  void productionSizedJsonRoundTripsAsInlineText() throws Exception {
    try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
      postgres.start();
      var configuration = new Configuration()
          .addAnnotatedClass(SourceManifestReference.class)
          .setProperty("hibernate.connection.url", postgres.getJdbcUrl())
          .setProperty("hibernate.connection.username", postgres.getUsername())
          .setProperty("hibernate.connection.password", postgres.getPassword())
          .setProperty("hibernate.hbm2ddl.auto", "create-drop");
      try (var factory = configuration.buildSessionFactory()) {
        String json = "{\"source\":\"" + "x".repeat(15_300_000) + "\"}";
        var manifest = new SourceManifestReference();
        manifest.setTaskKey("FISHING_TRAINING");
        manifest.setSchemaVersion("task-source-manifest/v1");
        manifest.setManifestHash("sha256:" + "1".repeat(64));
        manifest.setSnapshotId("srcsnap_" + "1".repeat(24));
        manifest.setVerificationState("VERIFIED");
        manifest.setSourceProvenance("MICRO6");
        manifest.setManifestJson(json);
        try (var session = factory.openSession()) {
          var transaction = session.beginTransaction();
          session.persist(manifest);
          transaction.commit();
        }
        // Reading outside an explicit transaction also proves JDBC is not dereferencing an OID.
        try (var session = factory.openSession()) {
          assertEquals(json, session.find(SourceManifestReference.class, manifest.getId()).getManifestJson());
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
            postgres.getUsername(), postgres.getPassword());
            var statement = connection.createStatement();
            var result = statement.executeQuery("SELECT pg_typeof(manifest_json)::text, "
                + "octet_length(manifest_json) FROM source_manifest_references")) {
          org.junit.jupiter.api.Assertions.assertTrue(result.next());
          assertEquals("text", result.getString(1));
          assertEquals(json.length(), result.getInt(2));
        }
      }
    }
  }
}
