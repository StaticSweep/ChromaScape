package com.chromascape.web.aiplayer.schema;

import java.util.Set;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Review-only fingerprint export against an explicitly supplied empty disposable database. */
public final class SchemaFingerprintExport {
  private SchemaFingerprintExport() {}

  public static void main(String[] args) throws Exception {
    String url = System.getenv("SC665_TEST_JDBC_URL");
    AiPlayerSchemaTarget.validate(url, Set.of(AiPlayerSchemaTarget.PROFILE));
    var source = new DriverManagerDataSource(url, "sc665", "sc665-disposable-test");
    // The normal tool never updates the pinned fingerprint. This separate exporter is only
    // available on the test source set, for reviewing a new clean schema version.
    AiPlayerCurrentSchema.migrations(source).migrate();
    try (var connection = source.getConnection()) {
      System.out.println("SC665_FINGERPRINT=" + AiPlayerCurrentSchema.fingerprint(connection));
    }
  }
}
