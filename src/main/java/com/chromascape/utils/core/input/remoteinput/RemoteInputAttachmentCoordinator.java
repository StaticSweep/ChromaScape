package com.chromascape.utils.core.input.remoteinput;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide attachment lease coordinator.
 *
 * <p>A lease is acquired <b>before</b> any attachment side effect (enumeration, injection, pairing,
 * backend construction), so two concurrent opens for the same identity can never both drive the
 * native target; the second is refused with {@link InputSessionDiagnostic#TARGET_ALREADY_ATTACHED}.
 */
public final class RemoteInputAttachmentCoordinator {

  private final Map<ProcessIdentity, Lease> active = new HashMap<>();

  /**
   * Acquires the per-identity lease or fails when the identity is already owned.
   *
   * @param identity the identity to attach to
   * @return the lease
   * @throws InputSessionException with {@link InputSessionDiagnostic#TARGET_ALREADY_ATTACHED} when
   *     a live lease already owns the identity
   */
  public synchronized Lease acquire(ProcessIdentity identity) {
    if (active.containsKey(identity)) {
      throw new InputSessionException(
          InputSessionDiagnostic.TARGET_ALREADY_ATTACHED,
          identity,
          -1,
          "Target already has an active input session");
    }
    Lease lease = new Lease(identity, this);
    active.put(identity, lease);
    return lease;
  }

  private synchronized void release(Lease lease) {
    if (active.get(lease.identity) == lease) {
      active.remove(lease.identity);
    }
  }

  /** One identity's exclusive attachment lease. */
  public static final class Lease implements AutoCloseable {

    private final ProcessIdentity identity;
    private final RemoteInputAttachmentCoordinator owner;
    private volatile boolean closed;

    private Lease(ProcessIdentity identity, RemoteInputAttachmentCoordinator owner) {
      this.identity = identity;
      this.owner = owner;
    }

    /** Returns the identity this lease fences. */
    public ProcessIdentity identity() {
      return identity;
    }

    /** Returns whether the lease has been released. */
    public boolean isClosed() {
      return closed;
    }

    @Override
    public void close() {
      closed = true;
      owner.release(this);
    }
  }
}
