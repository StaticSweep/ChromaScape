package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.replay.error.ReplayException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class XrspsSessionStoreTest {

  private final XrspsSessionStore store = new XrspsSessionStore();

  private XrspsSession runningSession(String key, String scenario) {
    XrspsSession session = store.create(XrspsSessionMode.RECORDED_ACTIONS, key, scenario);
    session.markReady();
    session.start();
    return session;
  }

  private XrspsSessionCommand command(
      XrspsSession session, String commandId, long expectedRevision, String payloadHash) {
    return new XrspsSessionCommand(
        commandId,
        session.getId(),
        expectedRevision,
        "INTERACT_FISHING_SPOT",
        payloadHash,
        XrspsCommandSource.RECORDED_ACTION,
        session.getLeaseId());
  }

  private XrspsSessionCommand commandWith(
      XrspsSession session,
      String commandId,
      long expectedRevision,
      String actionType,
      String payloadHash,
      XrspsCommandSource source,
      String leaseId) {
    return new XrspsSessionCommand(
        commandId, session.getId(), expectedRevision, actionType, payloadHash, source, leaseId);
  }

  @Test
  void identicalCreateReturnsExistingSession() {
    XrspsSession first = store.create(XrspsSessionMode.AI_TASK, "idem-key-001", "task:fish-5");
    XrspsSession second = store.create(XrspsSessionMode.AI_TASK, "idem-key-001", "task:fish-5");
    assertSame(first, second);
  }

  @Test
  void keyReuseWithDifferentContentFails() {
    store.create(XrspsSessionMode.AI_TASK, "idem-key-002", "task:fish-5");
    assertThrows(
        ReplayException.class,
        () -> store.create(XrspsSessionMode.RECORDED_ACTIONS, "idem-key-002", "task:fish-5"));
    assertThrows(
        ReplayException.class,
        () -> store.create(XrspsSessionMode.AI_TASK, "idem-key-002", "task:other"));
  }

  @Test
  void acceptedCommandBumpsRevisionAndDuplicateReplaysReceipt() {
    XrspsSession session = runningSession("idem-key-003", "scenario:fish-1");
    String commandId = UUID.randomUUID().toString();
    XrspsSessionReceipt first = store.submit(command(session, commandId, 0L, "hash-a"));
    assertEquals(XrspsReceiptOutcome.ACCEPTED, first.outcome());
    assertEquals(1L, first.revision());
    assertEquals(1L, session.getObservationRevision());

    XrspsSessionReceipt retry = store.submit(command(session, commandId, 0L, "hash-a"));
    assertEquals(XrspsReceiptOutcome.DUPLICATE, retry.outcome());
    assertEquals(first.tick(), retry.tick());
    assertEquals(first.revision(), retry.revision());
    assertEquals(1L, session.getObservationRevision());
  }

  @Test
  void conflictingReuseAndStaleRevisionRejectBeforeMutation() {
    XrspsSession session = runningSession("idem-key-004", "scenario:fish-1");
    String commandId = UUID.randomUUID().toString();
    store.submit(command(session, commandId, 0L, "hash-a"));

    XrspsSessionReceipt conflict = store.submit(command(session, commandId, 1L, "hash-b"));
    assertEquals(XrspsReceiptOutcome.REJECTED_CONFLICT, conflict.outcome());

    XrspsSessionReceipt stale =
        store.submit(command(session, UUID.randomUUID().toString(), 0L, "hash-c"));
    assertEquals(XrspsReceiptOutcome.REJECTED_STALE_REVISION, stale.outcome());
    assertEquals(1L, session.getObservationRevision());
  }

  @Test
  void commandsRequireRunningSession() {
    XrspsSession session = store.create(XrspsSessionMode.AI_TASK, "idem-key-005", "task:fish-5");
    XrspsSessionReceipt receipt =
        store.submit(command(session, UUID.randomUUID().toString(), 0L, "hash-a"));
    assertEquals(XrspsReceiptOutcome.REJECTED_SESSION_NOT_RUNNING, receipt.outcome());
    assertEquals(0L, session.getObservationRevision());
  }

  @Test
  void duplicateIdentityComparesTypeSourceAndHash() {
    XrspsSession session = runningSession("idem-key-006", "scenario:fish-1");
    String commandId = UUID.randomUUID().toString();
    String lease = session.getLeaseId();
    XrspsSessionReceipt first =
        store.submit(
            commandWith(
                session,
                commandId,
                0L,
                "INTERACT_FISHING_SPOT",
                "hash-a",
                XrspsCommandSource.RECORDED_ACTION,
                lease));
    assertEquals(XrspsReceiptOutcome.ACCEPTED, first.outcome());

    XrspsSessionReceipt changedType =
        store.submit(
            commandWith(
                session,
                commandId,
                1L,
                "MOVE_TO_TILE",
                "hash-a",
                XrspsCommandSource.RECORDED_ACTION,
                lease));
    assertEquals(XrspsReceiptOutcome.REJECTED_CONFLICT, changedType.outcome());

    XrspsSessionReceipt changedSource =
        store.submit(
            commandWith(
                session,
                commandId,
                1L,
                "INTERACT_FISHING_SPOT",
                "hash-a",
                XrspsCommandSource.AI_DECISION,
                lease));
    assertEquals(XrspsReceiptOutcome.REJECTED_CONFLICT, changedSource.outcome());

    XrspsSessionReceipt identical =
        store.submit(
            commandWith(
                session,
                commandId,
                1L,
                "INTERACT_FISHING_SPOT",
                "hash-a",
                XrspsCommandSource.RECORDED_ACTION,
                lease));
    assertEquals(XrspsReceiptOutcome.DUPLICATE, identical.outcome());
    assertEquals(1L, session.getObservationRevision());
  }

  @Test
  void staleLeaseIsFencedAndJournalSurvivesRecovery() {
    XrspsSession session = runningSession("idem-key-007", "scenario:fish-1");
    String oldLease = session.getLeaseId();
    String commandId = UUID.randomUUID().toString();
    XrspsSessionReceipt first = store.submit(command(session, commandId, 0L, "hash-a"));
    assertEquals(XrspsReceiptOutcome.ACCEPTED, first.outcome());

    session.reportWorkerLoss();
    session.recoverFromCheckpoint(oldLease, "new-lease-007");
    assertEquals("new-lease-007", session.getLeaseId());

    XrspsSessionReceipt oldWorkerWhilePaused =
        store.submitWhilePaused(
            commandWith(
                session,
                UUID.randomUUID().toString(),
                1L,
                "INTERACT_FISHING_SPOT",
                "hash-b",
                XrspsCommandSource.RECORDED_ACTION,
                oldLease));
    assertEquals(XrspsReceiptOutcome.REJECTED_STALE_LEASE, oldWorkerWhilePaused.outcome());
    assertEquals(1L, session.getObservationRevision());

    session.resume();
    XrspsSessionReceipt oldWorker =
        store.submit(
            commandWith(
                session,
                UUID.randomUUID().toString(),
                1L,
                "INTERACT_FISHING_SPOT",
                "hash-b",
                XrspsCommandSource.RECORDED_ACTION,
                oldLease));
    assertEquals(XrspsReceiptOutcome.REJECTED_STALE_LEASE, oldWorker.outcome());
    assertEquals(1L, session.getObservationRevision());
    XrspsSessionReceipt replay =
        store.submit(
            commandWith(
                session,
                commandId,
                1L,
                "INTERACT_FISHING_SPOT",
                "hash-a",
                XrspsCommandSource.RECORDED_ACTION,
                "new-lease-007"));
    assertEquals(XrspsReceiptOutcome.DUPLICATE, replay.outcome());
    assertEquals(1L, session.getObservationRevision());

    XrspsSessionReceipt next =
        store.submit(
            commandWith(
                session,
                UUID.randomUUID().toString(),
                1L,
                "INTERACT_FISHING_SPOT",
                "hash-b",
                XrspsCommandSource.RECORDED_ACTION,
                "new-lease-007"));
    assertEquals(XrspsReceiptOutcome.ACCEPTED, next.outcome());
    assertEquals(2L, session.getObservationRevision());
  }

  @Test
  void concurrentIdenticalCreatesReturnOneSession() throws Exception {
    XrspsSessionStore shared = new XrspsSessionStore();
    int threads = 16;
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.List<java.util.concurrent.Future<XrspsSession>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return shared.create(
                    XrspsSessionMode.RECORDED_ACTIONS, "idem-key-race-1", "scenario:fish-1");
              }));
    }
    ready.await();
    go.countDown();
    java.util.Set<UUID> ids = new java.util.HashSet<>();
    for (java.util.concurrent.Future<XrspsSession> future : futures) {
      ids.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS).getId());
    }
    pool.shutdownNow();
    assertEquals(1, ids.size());
  }

  @Test
  void concurrentConflictingCreatesNeverDuplicateSessions() throws Exception {
    XrspsSessionStore shared = new XrspsSessionStore();
    int threads = 16;
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.List<java.util.concurrent.Future<XrspsSession>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      final String scenario = i % 2 == 0 ? "scenario:fish-1" : "scenario:fish-2";
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return shared.create(
                    XrspsSessionMode.RECORDED_ACTIONS, "idem-key-race-2", scenario);
              }));
    }
    ready.await();
    go.countDown();
    int ok = 0;
    int conflicts = 0;
    java.util.Set<UUID> ids = new java.util.HashSet<>();
    for (java.util.concurrent.Future<XrspsSession> future : futures) {
      try {
        ids.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS).getId());
        ok++;
      } catch (java.util.concurrent.ExecutionException ex) {
        if (ex.getCause() instanceof ReplayException) {
          conflicts++;
        } else {
          throw ex;
        }
      }
    }
    pool.shutdownNow();
    assertEquals(threads, ok + conflicts);
    assertEquals(1, ids.size());
    org.junit.jupiter.api.Assertions.assertTrue(conflicts > 0);
    org.junit.jupiter.api.Assertions.assertTrue(ok > 0);
  }

  @Test
  void steppedCommandsRequirePausedSession() {
    XrspsSession session = runningSession("idem-key-008", "scenario:fish-1");
    XrspsSessionReceipt rejected =
        store.submitWhilePaused(command(session, UUID.randomUUID().toString(), 0L, "hash-a"));
    assertEquals(XrspsReceiptOutcome.REJECTED_SESSION_NOT_RUNNING, rejected.outcome());

    session.pause();
    XrspsSessionReceipt accepted =
        store.submitWhilePaused(command(session, UUID.randomUUID().toString(), 0L, "hash-a"));
    assertEquals(XrspsReceiptOutcome.ACCEPTED, accepted.outcome());
    assertEquals(1L, session.getObservationRevision());
  }
}
