package com.chromascape.web.replay.session;

import java.util.List;
import java.util.UUID;

/** Outcome of one recorded-action runner pass over its remaining journal. */
public record XrspsRecordedRunReport(
    UUID sessionId,
    int submitted,
    List<XrspsSessionReceipt> receipts,
    long lastTick,
    long lastRevision,
    XrspsActionMismatch firstMismatch,
    XrspsRunHaltReason haltReason) {

  /** True only when every journaled action was submitted without rejection. */
  public boolean completed() {
    return haltReason == XrspsRunHaltReason.COMPLETED && firstMismatch == null;
  }
}
