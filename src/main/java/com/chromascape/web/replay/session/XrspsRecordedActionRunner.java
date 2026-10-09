package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Submits a compiled recorded-action journal against one session in stable source order at relative
 * ticks. Ticks only move forward; a rejection or a late action latches a first-mismatch and halts
 * instead of skipping ahead. A latched mismatch stays latched: later {@link #runRemaining()} calls
 * return the same mismatch with no new submissions and never execute later actions. Leaving RUNNING
 * (pause, cancel, worker loss) halts submission so lifecycle controls always win over the journal;
 * the caller resumes by calling {@link #runRemaining()} again. Duplicate retries are benign and
 * never a mismatch. Single-step while paused must go through {@link #stepPaused(int)} so due
 * actions execute during the step; stepping the session directly past a pending action leaves it
 * late on the next running pass by design.
 */
public class XrspsRecordedActionRunner {

  private final XrspsSessionStore store;
  private final UUID sessionId;
  private final List<XrspsRecordedAction> journal;
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
    this.cursor = 0;
  }

  /**
   * Submits remaining actions until the journal ends, a mismatch latches, or the session halts. A
   * latched mismatch is returned again with no new submissions.
   */
  public XrspsRecordedRunReport runRemaining() {
    XrspsSession session = store.get(sessionId);
    if (latchedMismatch != null) {
      return report(session, List.of(), latchedMismatch, XrspsRunHaltReason.MISMATCH);
    }
    List<XrspsSessionReceipt> receipts = new ArrayList<>();
    while (cursor < journal.size()) {
      if (session.getStatus() != XrspsSessionStatus.RUNNING) {
        return report(session, receipts, null, XrspsRunHaltReason.SESSION_HALTED);
      }
      XrspsRecordedAction action = journal.get(cursor);
      if (action.relativeTick() < session.getTick()) {
        if (store.isJournaledIdentical(
            sessionId,
            action.commandId(),
            action.actionType(),
            XrspsCommandSource.RECORDED_ACTION,
            action.payloadHash())) {
          XrspsSessionReceipt replayed =
              store.submit(
                  new XrspsSessionCommand(
                      action.commandId(),
                      sessionId,
                      action.expectedRevision(),
                      action.actionType(),
                      action.payloadHash(),
                      XrspsCommandSource.RECORDED_ACTION,
                      session.getLeaseId()));
          if (replayed.outcome() == XrspsReceiptOutcome.DUPLICATE) {
            receipts.add(replayed);
            cursor += 1;
            continue;
          }
        }
        XrspsActionMismatch mismatch =
            new XrspsActionMismatch(
                sessionId,
                action.commandId(),
                action.relativeTick(),
                action.expectedRevision(),
                session.getTick(),
                session.getObservationRevision(),
                null,
                "Action tick "
                    + action.relativeTick()
                    + " is behind session tick "
                    + session.getTick());
        latchedMismatch = mismatch;
        return report(session, receipts, mismatch, XrspsRunHaltReason.MISMATCH);
      }
      if (action.relativeTick() > session.getTick()) {
        session.advanceTicks(action.relativeTick() - session.getTick());
      }
      XrspsSessionReceipt receipt =
          store.submit(
              new XrspsSessionCommand(
                  action.commandId(),
                  sessionId,
                  action.expectedRevision(),
                  action.actionType(),
                  action.payloadHash(),
                  XrspsCommandSource.RECORDED_ACTION,
                  session.getLeaseId()));
      if (receipt.outcome() == XrspsReceiptOutcome.REJECTED_SESSION_NOT_RUNNING) {
        return report(session, receipts, null, XrspsRunHaltReason.SESSION_HALTED);
      }
      receipts.add(receipt);
      if (receipt.outcome() == XrspsReceiptOutcome.ACCEPTED
          || receipt.outcome() == XrspsReceiptOutcome.DUPLICATE) {
        cursor += 1;
        continue;
      }
      XrspsActionMismatch mismatch =
          new XrspsActionMismatch(
              sessionId,
              action.commandId(),
              action.relativeTick(),
              action.expectedRevision(),
              receipt.tick(),
              receipt.revision(),
              receipt.outcome(),
              receipt.reason());
      latchedMismatch = mismatch;
      return report(session, receipts, mismatch, XrspsRunHaltReason.MISMATCH);
    }
    return report(session, receipts, null, XrspsRunHaltReason.COMPLETED);
  }

  /**
   * Advances a paused session by up to {@code ticks} ticks, executing every due journaled action
   * during the step via the paused-submit path. Due means {@code relativeTick <= session tick}:
   * overdue actions at step start execute first, then each single tick advance executes newly due
   * actions in journal order. A rejection latches a first-mismatch like {@link #runRemaining()}.
   * Returns {@code SESSION_HALTED} when steps are exhausted with journal remaining, {@code
   * COMPLETED} when the journal ends, or {@code MISMATCH} on rejection.
   */
  public XrspsRecordedRunReport stepPaused(int ticks) {
    XrspsSession session = store.get(sessionId);
    if (latchedMismatch != null) {
      return report(session, List.of(), latchedMismatch, XrspsRunHaltReason.MISMATCH);
    }
    if (session.getStatus() != XrspsSessionStatus.PAUSED) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.CONFLICT,
          false,
          "Session cannot step while " + session.getStatus(),
          "Move the session to PAUSED first");
    }
    if (ticks < 1 || ticks > XrspsSession.MAX_STEP_TICKS) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.BAD_REQUEST,
          false,
          "Step tick count out of range",
          "Request between 1 and " + XrspsSession.MAX_STEP_TICKS + " ticks while paused");
    }
    List<XrspsSessionReceipt> receipts = new ArrayList<>();
    XrspsActionMismatch mismatch = submitDueWhilePaused(session, receipts);
    if (mismatch != null) {
      latchedMismatch = mismatch;
      return report(session, receipts, mismatch, XrspsRunHaltReason.MISMATCH);
    }
    for (int i = 0; i < ticks; i++) {
      if (cursor >= journal.size()) {
        return report(session, receipts, null, XrspsRunHaltReason.COMPLETED);
      }
      session.stepTicks(1);
      XrspsActionMismatch due = submitDueWhilePaused(session, receipts);
      if (due != null) {
        latchedMismatch = due;
        return report(session, receipts, due, XrspsRunHaltReason.MISMATCH);
      }
    }
    if (cursor >= journal.size()) {
      return report(session, receipts, null, XrspsRunHaltReason.COMPLETED);
    }
    return report(session, receipts, null, XrspsRunHaltReason.SESSION_HALTED);
  }

  private XrspsActionMismatch submitDueWhilePaused(
      XrspsSession session, List<XrspsSessionReceipt> receipts) {
    while (cursor < journal.size() && journal.get(cursor).relativeTick() <= session.getTick()) {
      XrspsRecordedAction action = journal.get(cursor);
      XrspsSessionReceipt receipt =
          store.submitWhilePaused(
              new XrspsSessionCommand(
                  action.commandId(),
                  sessionId,
                  action.expectedRevision(),
                  action.actionType(),
                  action.payloadHash(),
                  XrspsCommandSource.RECORDED_ACTION,
                  session.getLeaseId()));
      if (receipt.outcome() == XrspsReceiptOutcome.REJECTED_SESSION_NOT_RUNNING) {
        return new XrspsActionMismatch(
            sessionId,
            action.commandId(),
            action.relativeTick(),
            action.expectedRevision(),
            receipt.tick(),
            receipt.revision(),
            receipt.outcome(),
            receipt.reason());
      }
      receipts.add(receipt);
      if (receipt.outcome() == XrspsReceiptOutcome.ACCEPTED
          || receipt.outcome() == XrspsReceiptOutcome.DUPLICATE) {
        cursor += 1;
        continue;
      }
      return new XrspsActionMismatch(
          sessionId,
          action.commandId(),
          action.relativeTick(),
          action.expectedRevision(),
          receipt.tick(),
          receipt.revision(),
          receipt.outcome(),
          receipt.reason());
    }
    return null;
  }

  /** Number of journaled actions submitted so far (across passes). */
  public int cursor() {
    return cursor;
  }

  private XrspsRecordedRunReport report(
      XrspsSession session,
      List<XrspsSessionReceipt> receipts,
      XrspsActionMismatch mismatch,
      XrspsRunHaltReason reason) {
    return new XrspsRecordedRunReport(
        sessionId,
        receipts.size(),
        List.copyOf(receipts),
        session.getTick(),
        session.getObservationRevision(),
        mismatch,
        reason);
  }
}
