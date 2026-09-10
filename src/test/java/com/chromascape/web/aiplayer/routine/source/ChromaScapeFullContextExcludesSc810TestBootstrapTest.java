package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.ChromaScapeApplication;
import com.chromascape.web.accounttask.service.HybridTaskSynthesisService;
import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.accounttask.service.StructuredEvidenceCollectorService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SC-810 test-context isolation proof (full-application side): booting the real {@link
 * ChromaScapeApplication} component scan must never discover {@link Sc810PostgresTestConfiguration}
 * or any bean it defines only for the narrow SC-810 source tests — in particular, the real
 * application must wire the real {@link StabilizationIngestionService}, not the Mockito stub that
 * class defines. {@link Sc810PostgresTestConfiguration} is {@code @TestConfiguration} specifically
 * so Spring Boot's component-scan exclusion filter keeps it out of this context even though it
 * lives inside the {@code com.chromascape.web} package tree that {@link ChromaScapeApplication}
 * scans. See {@link Sc810NarrowContextIsolationTest} for the complementary proof that the narrow
 * SC-810 context contains only its declared services.
 */
@SpringBootTest(
    classes = ChromaScapeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChromaScapeFullContextExcludesSc810TestBootstrapTest {

  @DynamicPropertySource
  static void fullApplicationDatabase(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Autowired private ApplicationContext context;
  @Autowired private StabilizationIngestionService stabilization;

  @Test
  void doesNotDiscoverTheSc810TestBootstrap() {
    assertTrue(
        context.getBeansOfType(Sc810PostgresTestConfiguration.class).isEmpty(),
        "the full application component scan must never pick up the "
            + "@TestConfiguration-annotated Sc810PostgresTestConfiguration");
  }

  @Test
  void wiresTheRealStabilizationIngestionServiceNotTheSc810TestMock() {
    assertFalse(
        Mockito.mockingDetails(stabilization).isMock(),
        "the full application context must wire the real StabilizationIngestionService; "
            + "a Mockito mock here would mean the SC-810 test-only bean leaked into production wiring");
  }

  @Test
  void stillConstructsTheRealAccountTaskServiceGraph() {
    assertEquals(
        1,
        context.getBeansOfType(HybridTaskSynthesisService.class).size(),
        "the full application context must construct its real account-task services");
    assertEquals(
        1,
        context.getBeansOfType(StructuredEvidenceCollectorService.class).size(),
        "the full application context must construct its real account-task services");
  }
}
