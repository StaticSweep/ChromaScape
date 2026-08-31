package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PatchCandidateBuildDispatcherTest {

  @Test
  void boundsWorkerFailureCodeToDurableColumnLength() {
    PatchCandidateRun entity = buildingCandidate("attempt-1");
    PatchCandidateBuildDispatcher dispatcher = dispatcher(entity);
    String failureCode = "BUILD_FAILED;OUTPUT=" + "x".repeat(512);
    IsolatedPatchWorker.BuildResult result =
        new IsolatedPatchWorker.BuildResult("FAILED", "base", List.of(), failureCode, null);

    assertTrue(dispatcher.persistBuildResult("proposal-1", "attempt-1", result,
        "{\"status\":\"FAILED\"}"));

    assertEquals("BUILD_FAILED", entity.getState());
    assertEquals(160, entity.getLastFailureCode().length());
    assertEquals(failureCode.substring(0, 160), entity.getLastFailureCode());
  }

  @Test
  void boundsUnhandledFailureCodeWhilePreservingFullDiagnosticResult() {
    PatchCandidateRun entity = buildingCandidate("attempt-2");
    PatchCandidateBuildDispatcher dispatcher = dispatcher(entity);
    String failureCode = "WORKER_EXCEPTION:" + "y".repeat(512);

    assertTrue(dispatcher.persistFailure("proposal-1", "attempt-2", failureCode));

    assertEquals("BUILD_FAILED", entity.getState());
    assertEquals(failureCode.substring(0, 160), entity.getLastFailureCode());
    assertTrue(entity.getResultJson().contains(failureCode));
  }

  private static PatchCandidateRun buildingCandidate(String attemptId) {
    PatchCandidateRun entity = new PatchCandidateRun();
    entity.setState("BUILDING");
    entity.setWorkerAttemptId(attemptId);
    return entity;
  }

  private static PatchCandidateBuildDispatcher dispatcher(PatchCandidateRun entity) {
    PatchCandidateRunRepository repository = mock(PatchCandidateRunRepository.class);
    when(repository.findWithLockByProposalId("proposal-1")).thenReturn(Optional.of(entity));
    return new PatchCandidateBuildDispatcher(repository);
  }
}
