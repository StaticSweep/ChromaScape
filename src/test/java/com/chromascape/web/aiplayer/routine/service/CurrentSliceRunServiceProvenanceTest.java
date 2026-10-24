package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Provenance boundary between SC-572 stabilization rows and a verified source binding. A real
 * signed source must never promote a legacy {@code verified=false} harness row into a source-bound
 * run.
 */
class CurrentSliceRunServiceProvenanceTest {
  private static final String RUN_ID = "sc274exp-authored-fixture-run-0001";
  private static final String ATTEMPT_ID = "sc274exp-authored-fixture-attempt-0001";

  @Test
  void rejectsLegacyUnverifiedPassAttemptEvenWhenSourceVerificationSucceeds() {
    Fixture fixture = new Fixture();
    when(fixture.stabilization.getRunByRunIdAndAttemptId(RUN_ID, ATTEMPT_ID))
        .thenReturn(Optional.of(stabilizationRun("PASS", false)));

    var thrown =
        assertThrows(
            RoutineCaptureIngestionService.RoutineCaptureException.class,
            () -> fixture.service.create(ATTEMPT_ID, micro6Identity()));

    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, thrown.status());
    assertEquals("STABILIZATION_NOT_VERIFIED", thrown.code());
    verify(fixture.runs, never()).save(any(CurrentSliceRun.class));
  }

  @Test
  void rejectsUnverifiedPassAttemptWhenVerifiedFlagIsNull() {
    Fixture fixture = new Fixture();
    when(fixture.stabilization.getRunByRunIdAndAttemptId(RUN_ID, ATTEMPT_ID))
        .thenReturn(Optional.of(stabilizationRun("PASS", null)));

    var thrown =
        assertThrows(
            RoutineCaptureIngestionService.RoutineCaptureException.class,
            () -> fixture.service.create(ATTEMPT_ID, micro6Identity()));

    assertEquals("STABILIZATION_NOT_VERIFIED", thrown.code());
    verify(fixture.runs, never()).save(any(CurrentSliceRun.class));
  }

  @Test
  void acceptsVerifiedPassAttemptFromCertifiedReportBundle() {
    Fixture fixture = new Fixture();
    when(fixture.stabilization.getRunByRunIdAndAttemptId(RUN_ID, ATTEMPT_ID))
        .thenReturn(Optional.of(stabilizationRun("PASS", true)));
    when(fixture.runs.findWithLockByRunId(RUN_ID)).thenReturn(Optional.empty());
    when(fixture.runs.save(any(CurrentSliceRun.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Map<String, Object> result = fixture.service.create(ATTEMPT_ID, micro6Identity());

    assertEquals("BOUND", result.get("stage"));
    verify(fixture.runs).save(any(CurrentSliceRun.class));
  }

  private static ScriptStabilizationRun stabilizationRun(String outcome, Boolean verified) {
    ScriptStabilizationRun run = new ScriptStabilizationRun();
    run.setRunId(RUN_ID);
    run.setAttemptId(ATTEMPT_ID);
    run.setOutcome(outcome);
    run.setVerified(verified);
    return run;
  }

  private static CurrentSliceIdentity micro6Identity() {
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task(
            "FISHING_TRAINING", null, "profile-1", null, null),
        new CurrentSliceIdentity.Run(RUN_ID, ATTEMPT_ID, 0, "executor-1"),
        new CurrentSliceIdentity.Client(
            "client-1",
            "MICRO6_MANAGED",
            "build-1",
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            "adapter/v1",
            "fishing"),
        null,
        new CurrentSliceIdentity.Source(
            "srcsnap_0585920d2af05903264627ba",
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            "repo-1",
            "commit-1",
            "tree-1",
            "build-1",
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            true),
        null,
        null,
        null,
        false);
  }

  private static final class Fixture {
    final CurrentSliceIdentityValidator validator = mock(CurrentSliceIdentityValidator.class);
    final CurrentSliceRunRepository runs = mock(CurrentSliceRunRepository.class);
    final StabilizationIngestionService stabilization = mock(StabilizationIngestionService.class);
    final CurrentSliceRunService service =
        new CurrentSliceRunService(validator, runs, new ObjectMapper(), stabilization);

    Fixture() {
      when(validator.validate(any(CurrentSliceIdentity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));
    }
  }
}
