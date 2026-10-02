package com.chromascape.utils.core.screen;

import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import java.time.Instant;
import java.util.Objects;

/**
 * Exact, host-qualified identity of the target that produced a frame.
 *
 * <p>{@link ProcessIdentity} intentionally excludes {@code hostId} from equality because it is
 * process-discovery metadata. Frame routing cannot do that: identical pid/start/boot tuples from
 * different hosts must never share a recording or debugger stream. This value therefore includes
 * every process field, the host id, and the selection generation in equality.
 */
public record FrameSourceBinding(
    ProcessOrigin origin,
    String hostId,
    long pid,
    Instant processStartTime,
    String bootId,
    long selectionGeneration) {

  /** Normalizes nullable host/boot metadata while requiring an explicit process origin. */
  public FrameSourceBinding {
    Objects.requireNonNull(origin, "origin");
    hostId = hostId == null ? "" : hostId;
    bootId = bootId == null ? "" : bootId;
  }

  /** Creates an exact frame binding from a discovered process identity. */
  public static FrameSourceBinding from(ProcessIdentity identity, long selectionGeneration) {
    Objects.requireNonNull(identity, "identity");
    return new FrameSourceBinding(
        identity.origin(),
        identity.hostId(),
        identity.pid(),
        identity.startTime(),
        identity.bootId(),
        selectionGeneration);
  }

  /** Sanitized diagnostic value containing no title, account, command line, or credentials. */
  public String toDisplayString() {
    return "host="
        + (hostId.isBlank() ? "unconfigured" : hostId)
        + " pid="
        + pid
        + " generation="
        + selectionGeneration;
  }
}
