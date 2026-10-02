package com.chromascape.web.recording.provenance;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** Converges guarded provenance indexes/guards after Hibernate creates tables on a fresh boot. */
@Component
@DependsOn("entityManagerFactory")
public class RecordingProvenanceConvergence implements ApplicationRunner {
  private final DataSource source;

  public RecordingProvenanceConvergence(DataSource source) {
    this.source = source;
  }

  @Override
  public void run(ApplicationArguments args) {
    // PostgreSQL procedural blocks contain semicolons; use a delimiter absent from the script.
    var populator =
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V33__recording_provenance.sql"));
    populator.setSeparator(
        org.springframework.jdbc.datasource.init.ScriptUtils.EOF_STATEMENT_SEPARATOR);
    populator.execute(source);
  }
}
