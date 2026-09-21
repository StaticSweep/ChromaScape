package com.chromascape.utils.core.input;

/** Lifecycle state of a target-owned input session. */
public enum InputSessionState {
  /** Constructed but never attached; no native resources are held. */
  DETACHED,
  /** Attachment is in progress. */
  ATTACHING,
  /** Attached and paired with the selected target. */
  ATTACHED,
  /** Close is in progress. */
  CLOSING,
  /** Closed; the native target was released and the attachment lease freed. */
  CLOSED,
  /** Close failed; the native target may still be attached and the lease is retained. */
  FAILED,
  /** A native call timed out; the lane is unusable and the lease is retained as a fence. */
  POISONED
}
