package com.chromascape.web.debug.startup;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.aiplayer.routine.source.SourceDebuggerReadService;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalRepairIncidentService;
import com.chromascape.web.logs.LogWebSocketHandler;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression for the SC-1103 startup blocker.
 *
 * <p>Both {@link SourceDebuggerReadService} and {@link BuilderGoalRepairIncidentService} expose a
 * production constructor plus a package-private test-only convenience constructor. If Spring can no
 * longer resolve a single unambiguous constructor it fails with "No default constructor found" and
 * the whole context fails to refresh, exactly as seen in chroma_bootrun.log. This test fails in
 * that case, and also proves the {@code @Autowired} production constructor was selected so no
 * alternate (test-only) constructor silently drops a required collaborator.
 *
 * <p>RANDOM_PORT is used so the embedded servlet container registers the JSR-356 {@code
 * ServerContainer} that {@code WebSocketConfig#createWebSocketContainer} requires, matching how the
 * application actually runs on port 8090.
 */
@SpringBootTest(
    classes = com.chromascape.web.ChromaScapeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CdiStartupRegressionTest {

  @DynamicPropertySource
  static void registerDatabase(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Autowired private ApplicationContext context;

  /**
   * The logging handler is outside this constructor regression and absent from the test profile.
   */
  @MockBean private LogWebSocketHandler logWebSocketHandler;

  @Test
  void contextCreatesExactlyOneSourceDebuggerReadServiceWithAllCollaborators() throws Exception {
    SourceDebuggerReadService service = context.getBean(SourceDebuggerReadService.class);
    assertSame(service, context.getBean(SourceDebuggerReadService.class), "must be a singleton");

    // Production (7-arg, @Autowired) constructor wires durable content storage. A non-null
    // contentObjects proves the test-only convenience constructor (which passes null) was NOT
    // chosen by Spring.
    Field contentObjects = SourceDebuggerReadService.class.getDeclaredField("contentObjects");
    contentObjects.setAccessible(true);
    assertNotNull(
        contentObjects.get(service), "production constructor must wire contentObjects repository");
  }

  @Test
  void repairIncidentServiceReceivesIntendedDependencies() throws Exception {
    BuilderGoalRepairIncidentService service =
        context.getBean(BuilderGoalRepairIncidentService.class);
    assertSame(service, context.getBean(BuilderGoalRepairIncidentService.class), "singleton");

    Field iterationRepository =
        BuilderGoalRepairIncidentService.class.getDeclaredField("iterationRepository");
    iterationRepository.setAccessible(true);
    Field mutationService =
        BuilderGoalRepairIncidentService.class.getDeclaredField("mutationService");
    mutationService.setAccessible(true);
    assertNotNull(
        iterationRepository.get(service), "iterationRepository must be injected (not dropped)");
    assertNotNull(mutationService.get(service), "mutationService must be injected (not dropped)");
  }
}
