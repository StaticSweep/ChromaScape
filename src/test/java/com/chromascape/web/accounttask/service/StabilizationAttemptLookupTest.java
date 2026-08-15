package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.chromascape.web.accounttask.service.StabilizationArtifactStore;
import com.chromascape.web.accounttask.repository.StabilizationArtifactRepository;
import com.chromascape.web.accounttask.repository.StabilizationRunAggregateRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Attempt-aware lookup contract (SC-572): runs expose all attempts and per-attempt lookups. */
@ExtendWith(MockitoExtension.class)
class StabilizationAttemptLookupTest {

  @Mock private ScriptStabilizationRunRepository runRepository;
  @Mock private ScriptFailureTriageRepository triageRepository;
  @Mock private ScriptStabilizationSnapshotRepository snapshotRepository;
  @Mock private StabilizationArtifactStore artifactStore;
  @Mock private StabilizationArtifactRepository artifactRepository;
  @Mock private StabilizationRunAggregateRepository aggregateRepository;
  @Mock private jakarta.persistence.EntityManager entityManager;

  private StabilizationIngestionService service;

  @BeforeEach
  void setUp() {
    service = new StabilizationIngestionService(runRepository, triageRepository, snapshotRepository,
            artifactStore, artifactRepository, aggregateRepository, entityManager);
  }

  private ScriptStabilizationRun run(String runId, String attemptId, String outcome) {
    ScriptStabilizationRun run = new ScriptStabilizationRun();
    run.setRunId(runId);
    run.setAttemptId(attemptId);
    run.setOutcome(outcome);
    run.setScriptKey("FISH_TRAINING");
    return run;
  }

  @Test
  @DisplayName("getRunAttempts returns every attempt for a run ordered by attempt")
  void getRunAttemptsReturnsAllAttempts() {
    ScriptStabilizationRun attempt1 = run("run-1", "attempt-1", "FAIL");
    ScriptStabilizationRun attempt2 = run("run-1", "attempt-2", "PASS");
    when(runRepository.findByRunIdOrderByAttemptIdAsc("run-1"))
        .thenReturn(List.of(attempt1, attempt2));

    List<ScriptStabilizationRun> attempts = service.getRunAttempts("run-1");

    assertThat(attempts).hasSize(2).containsExactly(attempt1, attempt2);
  }

  @Test
  @DisplayName("getRunByRunIdAndAttemptId returns the matching attempt")
  void getRunByRunIdAndAttemptIdReturnsMatch() {
    ScriptStabilizationRun attempt = run("run-1", "attempt-2", "PASS");
    when(runRepository.findFirstByRunIdAndAttemptIdOrderByExecutedAtDesc("run-1", "attempt-2"))
        .thenReturn(Optional.of(attempt));

    Optional<ScriptStabilizationRun> result =
        service.getRunByRunIdAndAttemptId("run-1", "attempt-2");

    assertThat(result).contains(attempt);
  }

  @Test
  @DisplayName("missing attempts return empty")
  void missingAttemptReturnsEmpty() {
    when(runRepository.findFirstByRunIdAndAttemptIdOrderByExecutedAtDesc("run-1", "attempt-9"))
        .thenReturn(Optional.empty());

    assertThat(service.getRunByRunIdAndAttemptId("run-1", "attempt-9")).isEmpty();
  }
}
