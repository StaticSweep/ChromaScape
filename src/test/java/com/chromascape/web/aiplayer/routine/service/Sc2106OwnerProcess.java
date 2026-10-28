package com.chromascape.web.aiplayer.routine.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.config.Sc2106ExecutionProperties;
import com.chromascape.web.artifact.ImmutableArtifactStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A ChromaScape owner in its own JVM for the SC-2106 R6 owner-crash test: it reserves and runs one
 * job through the real host on the PostgreSQL attempt store, exactly as the service does in
 * production, and is SIGKILLed by the test while the run is in flight.
 */
public final class Sc2106OwnerProcess {
  private Sc2106OwnerProcess() {}

  public static void main(String[] args) throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    JsonNode config = mapper.readTree(Path.of(args[0]).toFile());
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            config.path("jdbcUrl").asText(),
            config.path("user").asText(),
            config.path("password").asText());
    JdbcCandidateAttemptStore store =
        new JdbcCandidateAttemptStore(
            new JdbcTemplate(dataSource), mapper, new DataSourceTransactionManager(dataSource));
    PatchCandidateWorkflowService workflow = mock(PatchCandidateWorkflowService.class);
    Path bundle = Path.of(config.path("bundle").asText());
    when(workflow.registeredCandidate(Sc2106RealHost.PROPOSAL))
        .thenReturn(
            new PatchCandidateWorkflowService.RegisteredCandidate(
                Sc2106RealHost.PROPOSAL,
                bundle.getFileName().toString(),
                bundle,
                config.path("jarName").asText(),
                config.path("sbomName").asText()));
    // R5.1: the owner publishes the attempt journal to the shared artifact store as it runs.
    CandidateExecutionJobService service =
        new CandidateExecutionJobService(
            workflow,
            mapper.treeToValue(config.path("properties"), Sc2106ExecutionProperties.class),
            store,
            journals(config.path("artifactRoot").asText(), store, mapper));
    @SuppressWarnings("unchecked")
    Map<String, Object> fault = mapper.treeToValue(config.path("fault"), Map.class);
    service.injectFault(fault);
    System.out.println("SC2106_OWNER_STARTED");
    CandidateExecutionJobService.ExecutionJob job =
        service.execute(
            Sc2106RealHost.PROPOSAL,
            mapper.treeToValue(config.path("spec"), CandidateExecutionJobService.JobSpec.class));
    // Reaching this line means the owner was not killed in time; the test fails on it.
    System.out.println("SC2106_OWNER_FINISHED " + job.outcome());
  }

  /** The managed journal archive over the artifact store every owner of the test shares. */
  static CandidateAttemptJournalArchive journals(
      String artifactRoot, CandidateAttemptStore store, ObjectMapper mapper) {
    return new CandidateAttemptJournalArchive(
        store,
        new ImmutableArtifactStore(Path.of(artifactRoot), 1L << 26, 1L << 30, mapper),
        mapper);
  }
}
