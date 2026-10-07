package com.chromascape.web.replay.session;

import java.util.UUID;

/**
 * Lifecycle receipt for one submitted command. A transport acknowledgement is never task
 * completion; only receipts produced here describe what the session did.
 */
public record XrspsSessionReceipt(
    String commandId,
    UUID sessionId,
    XrspsReceiptOutcome outcome,
    long tick,
    long revision,
    String reason) {

  /** Identical idempotent retries return the stored receipt with no duplicated effects. */
  public boolean isDuplicate() {
    return outcome == XrspsReceiptOutcome.DUPLICATE;
  }
}
