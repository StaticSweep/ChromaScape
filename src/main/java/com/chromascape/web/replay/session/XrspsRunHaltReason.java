package com.chromascape.web.replay.session;

/** Why a recorded-action runner pass stopped. */
public enum XrspsRunHaltReason {
  /** Every remaining journaled action was submitted. */
  COMPLETED,
  /** A rejection or late action stopped the run at the first mismatch. */
  MISMATCH,
  /** The session left RUNNING (paused, cancelled, reconciling, terminal). */
  SESSION_HALTED
}
