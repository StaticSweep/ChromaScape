package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** In-memory ordered journal scheduler. Acceptance is not proof of kernel/gameplay execution. */
public class XrspsRecordedActionRunner {
  private final XrspsSessionStore store;
  private final UUID sessionId;
  private final List<XrspsRecordedAction> journal;
  private final String leaseId;
  private int cursor;
  private XrspsActionMismatch latchedMismatch;

  public XrspsRecordedActionRunner(
      XrspsSessionStore store, UUID sessionId, List<XrspsRecordedAction> journal) {
    if (store == null || sessionId == null || journal == null || journal.isEmpty()) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.BAD_REQUEST,
          false,
          "Runner needs a store, session, and non-empty journal",
          "Compile the recording to an ordered action journal before running");
    }
    long previousTick = -1;
    for (XrspsRecordedAction action : journal) {
      if (action == null
          || action.commandId() == null
          || action.commandId().isBlank()
          || action.actionType() == null
          || action.actionType().isBlank()
          || action.payloadHash() == null
          || action.payloadHash().isBlank()) {
        throw new ReplayException(
            ReplayErrorCode.VALIDATION_ERROR,
            HttpStatus.BAD_REQUEST,
            false,
            "Journal actions need identity, type, and payload",
            "Recompile the recording with complete typed actions");
      }
      if (action.relativeTick() < 0 || action.expectedRevision() < 0) {
        throw new ReplayException(
            ReplayErrorCode.VALIDATION_ERROR,
            HttpStatus.BAD_REQUEST,
            false,
            "Journal ticks and revisions must be non-negative",
            "Recompile the recording with stable relative ticks");
      }
      if (action.relativeTick() < previousTick) {
        throw new ReplayException(
            ReplayErrorCode.VALIDATION_ERROR,
            HttpStatus.BAD_REQUEST,
            false,
            "Journal is not in stable source order",
            "Sort recorded actions by relative tick before running");
      }
      previousTick = action.relativeTick();
    }
    this.store = store;
    this.sessionId = sessionId;
    this.journal = List.copyOf(journal);
    this.leaseId = store.get(sessionId).getLeaseId();
  }

  /** Keeps cursor and first mismatch across calls; a completed retry returns duplicate receipts. */
  public synchronized XrspsRecordedRunReport runRemaining() {
    XrspsSession session = store.get(sessionId);
    if (latchedMismatch != null) return report(session, List.of(), XrspsRunHaltReason.MISMATCH);
    if (cursor == journal.size()) cursor = 0;
    List<XrspsSessionReceipt> receipts = new ArrayList<>();
    while (cursor < journal.size()) {
      // Fence, tick, revision and journal checks share the lifecycle monitor. Release between
      // actions.
      synchronized (session) {
        if (session.getStatus() != XrspsSessionStatus.RUNNING)
          return report(session, receipts, XrspsRunHaltReason.SESSION_HALTED);
        if (!leaseId.equals(session.getLeaseId())) {
          latch(
              session,
              journal.get(cursor),
              XrspsReceiptOutcome.REJECTED_STALE_LEASE,
              "Runner lease is stale");
          return report(session, receipts, XrspsRunHaltReason.MISMATCH);
        }
        XrspsRecordedAction action = journal.get(cursor);
        if (isUnappliedLate(session, action)) {
          latch(
              session,
              action,
              null,
              "Action tick "
                  + action.relativeTick()
                  + " is behind session tick "
                  + session.getTick());
          return report(session, receipts, XrspsRunHaltReason.MISMATCH);
        }
        if (action.relativeTick() > session.getTick())
          session.advanceTicks(action.relativeTick() - session.getTick());
        if (!submit(session, action, false, receipts))
          return report(session, receipts, XrspsRunHaltReason.MISMATCH);
      }
    }
    return report(session, receipts, XrspsRunHaltReason.COMPLETED);
  }

  /**
   * Executes due actions at their ticks; never-applied overdue actions mismatch before mutation.
   */
  public synchronized XrspsRecordedRunReport stepPaused(int ticks) {
    XrspsSession session = store.get(sessionId);
    if (ticks < 1 || ticks > XrspsSession.MAX_STEP_TICKS)
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "Step tick count out of range");
    if (latchedMismatch != null) return report(session, List.of(), XrspsRunHaltReason.MISMATCH);
    List<XrspsSessionReceipt> receipts = new ArrayList<>();
    for (int advanced = 0; advanced <= ticks; advanced++) {
      synchronized (session) {
        if (session.getStatus() != XrspsSessionStatus.PAUSED) {
          if (advanced == 0)
            throw ReplayException.conflict(
                ReplayErrorCode.VALIDATION_ERROR, "Session must be PAUSED to step");
          return report(session, receipts, XrspsRunHaltReason.SESSION_HALTED);
        }
        if (!leaseId.equals(session.getLeaseId())) {
          // Even a completed stale runner cannot advance ticks.
          latch(
              session,
              journal.get(Math.min(cursor, journal.size() - 1)),
              XrspsReceiptOutcome.REJECTED_STALE_LEASE,
              "Runner lease is stale");
          return report(session, receipts, XrspsRunHaltReason.MISMATCH);
        }
        while (cursor < journal.size() && journal.get(cursor).relativeTick() <= session.getTick()) {
          XrspsRecordedAction action = journal.get(cursor);
          if (isUnappliedLate(session, action)) {
            latch(
                session,
                action,
                null,
                "Action tick "
                    + action.relativeTick()
                    + " is behind session tick "
                    + session.getTick());
            return report(session, receipts, XrspsRunHaltReason.MISMATCH);
          }
          if (!submit(session, action, true, receipts))
            return report(session, receipts, XrspsRunHaltReason.MISMATCH);
        }
        if (cursor == journal.size())
          return report(session, receipts, XrspsRunHaltReason.COMPLETED);
        if (advanced < ticks) session.stepTicks(1);
      }
    }
    return report(session, receipts, XrspsRunHaltReason.SESSION_HALTED);
  }

  private boolean isUnappliedLate(XrspsSession session, XrspsRecordedAction action) {
    return action.relativeTick() < session.getTick()
        && !store.isJournaledIdentical(
            sessionId,
            action.commandId(),
            action.actionType(),
            XrspsCommandSource.RECORDED_ACTION,
            action.payloadHash());
  }

  private boolean submit(
      XrspsSession session,
      XrspsRecordedAction action,
      boolean paused,
      List<XrspsSessionReceipt> receipts) {
    XrspsSessionCommand command =
        new XrspsSessionCommand(
            action.commandId(),
            sessionId,
            action.expectedRevision(),
            action.actionType(),
            action.payloadHash(),
            XrspsCommandSource.RECORDED_ACTION,
            leaseId);
    XrspsSessionReceipt receipt = paused ? store.submitWhilePaused(command) : store.submit(command);
    receipts.add(receipt);
    if (receipt.outcome() == XrspsReceiptOutcome.ACCEPTED
        || receipt.outcome() == XrspsReceiptOutcome.DUPLICATE) {
      cursor++;
      return true;
    }
    latch(session, action, receipt.outcome(), receipt.reason());
    return false;
  }

  private void latch(
      XrspsSession session,
      XrspsRecordedAction action,
      XrspsReceiptOutcome outcome,
      String detail) {
    latchedMismatch =
        new XrspsActionMismatch(
            sessionId,
            action.commandId(),
            action.relativeTick(),
            action.expectedRevision(),
            session.getTick(),
            session.getObservationRevision(),
            outcome,
            detail);
  }

  public synchronized int cursor() {
    return cursor;
  }

  private XrspsRecordedRunReport report(
      XrspsSession session, List<XrspsSessionReceipt> receipts, XrspsRunHaltReason reason) {
    synchronized (session) {
      return new XrspsRecordedRunReport(
          sessionId,
          receipts.size(),
          List.copyOf(receipts),
          session.getTick(),
          session.getObservationRevision(),
          latchedMismatch,
          reason);
    }
  }
}
