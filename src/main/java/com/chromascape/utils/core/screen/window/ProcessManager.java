package com.chromascape.utils.core.screen.window;

import java.util.List;
import java.util.Optional;

/**
 * Platform-neutral discovery of eligible local RuneLite/Micro6 client processes.
 *
 * <p>This contract deliberately does not preserve the upstream first-match {@code int getPid()}
 * behavior: silent first-match is exactly what SC-1663 replaces, because multiple clients must
 * never resolve to whichever procfs entry happened to be scanned first. Implementations must be
 * deterministic, must never throw during a scan, and must never attach, inject, focus or kill.
 */
public interface ProcessManager {

  /**
   * Enumerates all eligible local client targets in deterministic (start time, pid) order. Returns
   * an empty list when no client is eligible; per-entry failures are skipped and counted.
   */
  List<DiscoveredTarget> discoverEligibleLocal();

  /**
   * Re-reads the current identity of a local pid; empty when the process is gone or its identity
   * is unreadable. Read-only: never attaches or mutates the target.
   */
  Optional<ProcessIdentity> resolveLocal(long pid);

  /**
   * Validation helper: returns the discovered target whose identity equals the given identity, or
   * empty when no current candidate matches.
   */
  default Optional<DiscoveredTarget> findLocal(ProcessIdentity identity) {
    if (identity == null) {
      return Optional.empty();
    }
    return discoverEligibleLocal().stream()
        .filter(target -> identity.equals(target.identity()))
        .findFirst();
  }

  /** Bounded, sanitized diagnostics of the most recent scan; empty until a scan ran. */
  default TargetScanDiagnostics lastScanDiagnostics() {
    return TargetScanDiagnostics.EMPTY;
  }

  /** Whether this platform has a real discovery implementation. */
  default boolean isPlatformSupported() {
    return true;
  }
}
