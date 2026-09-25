package com.chromascape.utils.core.screen.window;

import java.time.Instant;
import java.util.Objects;

/**
 * Platform-neutral, stable identity of a discovered process target.
 *
 * <p>A recycled PID cannot silently retarget a selection because equality includes the process
 * start time and the host boot id alongside the pid. The identity is self-describing and safe to
 * serialise for diagnostics: it never contains command lines, account names, credentials or other
 * process content.
 *
 * @param origin where the process was observed
 * @param hostId local host identity recorded for session binding; excluded from equality
 * @param pid the process id
 * @param startTime process start time; part of equality so a reused pid is detectable
 * @param bootId host boot id (procfs {@code boot_id}); part of equality so a host reboot is
 *     detectable; empty when unavailable (e.g. Windows)
 */
public record ProcessIdentity(
    ProcessOrigin origin, String hostId, long pid, Instant startTime, String bootId) {

  public ProcessIdentity {
    hostId = hostId == null ? "" : hostId;
    bootId = bootId == null ? "" : bootId;
  }

  /**
   * Equality excludes {@code hostId}: the host id is session-binding metadata (it can be unset in
   * development) and must not change whether two identities describe the same process.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ProcessIdentity other)) {
      return false;
    }
    return pid == other.pid
        && origin == other.origin
        && Objects.equals(startTime, other.startTime)
        && Objects.equals(bootId, other.bootId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(origin, pid, startTime, bootId);
  }

  /** Returns whether this identity was discovered on the local host. */
  public boolean isLocal() {
    return origin == ProcessOrigin.LOCAL;
  }

  /** Short, sanitized description for diagnostics; never contains process content. */
  public String toDisplayString() {
    return "pid="
        + pid
        + " origin="
        + origin
        + " startTime="
        + (startTime != null ? startTime : "unknown")
        + " bootId="
        + (bootId.isEmpty() ? "unavailable" : "present");
  }
}
