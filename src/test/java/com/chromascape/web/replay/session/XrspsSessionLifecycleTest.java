package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.error.ReplayException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class XrspsSessionLifecycleTest {

  private XrspsSession newSession() {
    return new XrspsSession(
        UUID.randomUUID(),
        XrspsSessionMode.RECORDED_ACTIONS,
        "lifecycle-key-1",
        "scenario:fishing-seed-1");
  }

  @Test
  void happyPathRunsToCompletion() {
    XrspsSession session = newSession();
    assertEquals(XrspsSessionStatus.CREATING, session.getStatus());
    session.markReady();
    session.start();
    assertEquals(XrspsSessionStatus.RUNNING, session.getStatus());
    session.advanceTicks(600);
    assertEquals(600L, session.getTick());
    session.pause();
    session.stepTicks(3);
    assertEquals(603L, session.getTick());
    session.resume();
    session.complete("recording finished");
    assertEquals(XrspsSessionStatus.SUCCEEDED, session.getStatus());
    assertEquals("recording finished", session.getTerminalReason());
  }

  @Test
  void startRequiresReady() {
    XrspsSession session = newSession();
    assertThrows(ReplayException.class, session::start);
  }

  @Test
  void stepRequiresPausedAndBounded() {
    XrspsSession session = newSession();
    session.markReady();
    session.start();
    assertThrows(ReplayException.class, () -> session.stepTicks(1));
    session.pause();
    assertThrows(ReplayException.class, () -> session.stepTicks(0));
    assertThrows(ReplayException.class, () -> session.stepTicks(XrspsSession.MAX_STEP_TICKS + 1));
    assertEquals(0L, session.getTick());
  }

  @Test
  void terminalStatesAreImmutable() {
    XrspsSession session = newSession();
    session.markReady();
    session.start();
    session.cancel("operator stop");
    assertEquals(XrspsSessionStatus.CANCELLED, session.getStatus());
    assertThrows(ReplayException.class, session::resume);
    assertThrows(ReplayException.class, () -> session.complete("late"));
  }

  @Test
  void workerLossReconcilesAndRecoveryFencesTheLease() {
    XrspsSession session = newSession();
    session.markReady();
    session.start();
    String lease = session.getLeaseId();
    session.reportWorkerLoss();
    assertEquals(XrspsSessionStatus.RECONCILING, session.getStatus());
    assertThrows(
        ReplayException.class, () -> session.recoverFromCheckpoint("wrong-lease", "new-lease"));
    assertEquals(XrspsSessionStatus.RECONCILING, session.getStatus());
    session.recoverFromCheckpoint(lease, "new-lease");
    assertEquals(XrspsSessionStatus.PAUSED, session.getStatus());
    assertEquals(1L, session.getEpoch());
    assertEquals("new-lease", session.getLeaseId());
    assertNull(session.getTerminalReason());
  }

  @Test
  void workerLossOutsideLiveSessionFails() {
    XrspsSession session = newSession();
    assertThrows(ReplayException.class, session::reportWorkerLoss);
    assertTrue(XrspsSessionStatus.SUCCEEDED.isTerminal());
  }
}
