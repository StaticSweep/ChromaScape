package com.chromascape.web.replay.session;

/** Outcome of one command submission, decided before any mutation. */
public enum XrspsReceiptOutcome {
  /** New command, journaled and applied. */
  ACCEPTED,
  /** Identical retry: previous receipt returned, nothing applied twice. */
  DUPLICATE,
  /** Same command identity with conflicting content: rejected before mutation. */
  REJECTED_CONFLICT,
  /** Expected revision does not match the session revision: rejected before mutation. */
  REJECTED_STALE_REVISION,
  /** Session is not running: rejected before mutation. */
  REJECTED_SESSION_NOT_RUNNING,
  /** Command lease does not match the session lease: rejected before mutation. */
  REJECTED_STALE_LEASE,
  /** Native domain validation refused the action. */
  REJECTED_SEMANTIC_ACTION
}
