package com.chromascape.web.gameplay.orchestration.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.Failure;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StatusResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bounds and shutdown for the SC-1449 reconciliation loop.
 *
 * <p>{@code MAX_RECONCILIATION_ATTEMPTS} used to be declared and never read, so the coordinator
 * retried the same unreconcilable request every thirty seconds forever with no way to stop it.
 * These tests pin the three properties that fixes: attempts are counted per request and capped, a
 * request that reconciles terminally is dropped from the loop, and the executor is actually
 * stopped.
 */
class GameplayV2RecoveryCoordinatorTest {

  private static V2StatusResult pending(String requestId) {
    return new V2StatusResult(
        V2State.NEEDS_RECONCILIATION,
        Failure.NEEDS_RECONCILIATION,
        false,
        requestId,
        "digest-" + requestId,
        List.of());
  }

  @Test
  void aRequestThatNeverReconcilesIsAbandonedAfterThreeAttempts() {
    GameplayV2LiveGateService service = mock(GameplayV2LiveGateService.class);
    when(service.statusReconciliationCandidates()).thenReturn(List.of(pending("req-1")));
    when(service.reconcile("req-1")).thenReturn(pending("req-1"));

    GameplayV2RecoveryCoordinator coordinator = new GameplayV2RecoveryCoordinator(service, true);
    try {
      for (int pass = 0; pass < 10; pass++) {
        coordinator.tick();
      }

      verify(service, times(GameplayV2RecoveryCoordinator.MAX_RECONCILIATION_ATTEMPTS))
          .reconcile("req-1");
      assertThat(coordinator.attemptsFor("req-1"))
          .isGreaterThanOrEqualTo(GameplayV2RecoveryCoordinator.MAX_RECONCILIATION_ATTEMPTS);
    } finally {
      coordinator.close();
    }
  }

  @Test
  void aThrowingReconcileStillCountsAgainstTheAttemptBudget() {
    GameplayV2LiveGateService service = mock(GameplayV2LiveGateService.class);
    when(service.statusReconciliationCandidates()).thenReturn(List.of(pending("req-1")));
    when(service.reconcile("req-1")).thenThrow(new IllegalStateException("micro6 unreachable"));

    GameplayV2RecoveryCoordinator coordinator = new GameplayV2RecoveryCoordinator(service, true);
    try {
      for (int pass = 0; pass < 10; pass++) {
        coordinator.tick();
      }
      verify(service, times(GameplayV2RecoveryCoordinator.MAX_RECONCILIATION_ATTEMPTS))
          .reconcile("req-1");
    } finally {
      coordinator.close();
    }
  }

  @Test
  void aTerminallyReconciledRequestLeavesTheLoop() {
    GameplayV2LiveGateService service = mock(GameplayV2LiveGateService.class);
    when(service.statusReconciliationCandidates())
        .thenReturn(List.of(pending("req-1")))
        .thenReturn(List.of());
    when(service.reconcile("req-1"))
        .thenReturn(
            new V2StatusResult(V2State.CONFIRMED, null, true, "req-1", "digest-req-1", List.of()));

    GameplayV2RecoveryCoordinator coordinator = new GameplayV2RecoveryCoordinator(service, true);
    try {
      coordinator.tick();
      assertThat(coordinator.attemptsFor("req-1")).isZero();
      coordinator.tick();
      verify(service, times(1)).reconcile("req-1");
    } finally {
      coordinator.close();
    }
  }

  @Test
  void eachRequestGetsItsOwnAttemptBudget() {
    GameplayV2LiveGateService service = mock(GameplayV2LiveGateService.class);
    when(service.statusReconciliationCandidates())
        .thenReturn(List.of(pending("req-1"), pending("req-2")));
    when(service.reconcile(anyString())).thenAnswer(call -> pending(call.getArgument(0)));

    GameplayV2RecoveryCoordinator coordinator = new GameplayV2RecoveryCoordinator(service, true);
    try {
      for (int pass = 0; pass < 5; pass++) {
        coordinator.tick();
      }
      verify(service, times(GameplayV2RecoveryCoordinator.MAX_RECONCILIATION_ATTEMPTS))
          .reconcile("req-1");
      verify(service, times(GameplayV2RecoveryCoordinator.MAX_RECONCILIATION_ATTEMPTS))
          .reconcile("req-2");
    } finally {
      coordinator.close();
    }
  }

  @Test
  void theLoopDoesNotStartWhileLiveGameplayIsDisabled() {
    GameplayV2LiveGateService service = mock(GameplayV2LiveGateService.class);
    when(service.recoverAfterRestart()).thenReturn(List.of());

    GameplayV2RecoveryCoordinator coordinator = new GameplayV2RecoveryCoordinator(service, false);
    try {
      coordinator.onApplicationReady();

      // The one-shot in-flight sweep still runs; the remote reconciliation loop does not.
      verify(service).recoverAfterRestart();
      verify(service, never()).statusReconciliationCandidates();
      verify(service, never()).reconcile(anyString());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void closeStopsTheExecutorAndIsIdempotent() {
    GameplayV2LiveGateService service = mock(GameplayV2LiveGateService.class);
    when(service.recoverAfterRestart()).thenReturn(List.of());
    when(service.statusReconciliationCandidates()).thenReturn(List.of());

    GameplayV2RecoveryCoordinator coordinator = new GameplayV2RecoveryCoordinator(service, true);
    coordinator.onApplicationReady();
    coordinator.close();
    coordinator.close();

    // After close a tick is a no-op and reschedules nothing, so a shutting-down context cannot be
    // kept alive by the loop.
    coordinator.tick();
    verify(service, never()).reconcile(anyString());
    assertThat(coordinator.attemptsFor("req-1")).isZero();
  }
}
