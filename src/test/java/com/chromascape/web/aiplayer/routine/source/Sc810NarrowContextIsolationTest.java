package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.accounttask.service.HybridTaskSynthesisService;
import com.chromascape.web.accounttask.service.StructuredEvidenceCollectorService;
import com.chromascape.web.aiplayer.routine.service.CaptureOperationResultService;
import com.chromascape.web.logs.LogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * SC-810 test-context isolation proof (narrow side): {@link Sc810PostgresTestConfiguration}, used
 * by the SC-810 source integration tests, must contain exactly its declared source/run/capture
 * services and repositories, and must never construct the unrelated account-task service graph
 * (whose {@code LogRepository} dependency is not available in this narrow context by design).
 *
 * <p>{@code Sc810PostgresTestConfiguration} carries both {@code @TestConfiguration} and
 * {@code @SpringBootConfiguration}: the former excludes it from full-application component
 * scanning, and the latter makes it discoverable as its own root configuration, so
 * {@code @SpringBootTest}'s search for the nearest {@code @SpringBootConfiguration} from the test
 * class's package finds this class itself instead of falling back to auto-merging the full {@code
 * ChromaScapeApplication} (which happens whenever every explicitly listed {@code classes=} entry is
 * {@code @TestConfiguration} only). See {@link
 * ChromaScapeFullContextExcludesSc810TestBootstrapTest} for the complementary proof that the full
 * application context never discovers this class either.
 */
@SpringBootTest(
    classes = Sc810PostgresTestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {"spring.flyway.target=21", "spring.jpa.hibernate.ddl-auto=update"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class Sc810NarrowContextIsolationTest {

  @DynamicPropertySource
  static void sourceDatabase(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Autowired private ApplicationContext context;

  @Test
  void containsExactlyItsDeclaredSourceRunCaptureServicesAndRepositories() {
    assertEquals(1, context.getBeansOfType(SourceRunOrchestrationService.class).size());
    assertEquals(1, context.getBeansOfType(SourceManifestBindingService.class).size());
    assertEquals(1, context.getBeansOfType(SourceManifestFetchService.class).size());
    assertEquals(1, context.getBeansOfType(CaptureOperationResultService.class).size());
    assertEquals(
        1,
        context
            .getBeansOfType(
                com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService.class)
            .size());
    assertEquals(
        1,
        context
            .getBeansOfType(
                com.chromascape.web.aiplayer.routine.source.persistence
                    .SourceManifestReferenceRepository.class)
            .size());
    assertEquals(
        1,
        context
            .getBeansOfType(
                com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository
                    .class)
            .size());
  }

  @Test
  void neverConstructsTheUnrelatedAccountTaskServiceGraph() {
    assertTrue(
        context.getBeansOfType(HybridTaskSynthesisService.class).isEmpty(),
        "the narrow SC-810 context must never construct the unrelated account-task graph");
    assertTrue(
        context.getBeansOfType(StructuredEvidenceCollectorService.class).isEmpty(),
        "the narrow SC-810 context must never construct StructuredEvidenceCollectorService, "
            + "whose LogRepository dependency this context does not provide");
    assertTrue(
        context.getBeansOfType(LogRepository.class).isEmpty(),
        "LogRepository is out of scope for the narrow SC-810 context by design");
  }
}
