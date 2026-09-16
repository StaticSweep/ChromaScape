package com.chromascape.utils.core.screen.window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Windows discovery: wraps the existing {@link WindowHandler} enumeration, preserving its
 * canvas-preference ranking, sequential ids and auto-detection behavior, and enriches every
 * {@link WindowInfo} with a neutral {@link ProcessIdentity} built from the JDK process start time.
 *
 * <p>Windows has no procfs boot id, so identities carry an empty boot id; pid-reuse detection on
 * Windows relies on the JDK start time.
 */
public class WindowsProcessManager implements ProcessManager {

  private final String hostId;

  /**
   * Creates a Windows process manager.
   *
   * @param hostId local host identity recorded on discovered identities; may be empty
   */
  public WindowsProcessManager(String hostId) {
    this.hostId = hostId == null ? "" : hostId;
  }

  @Override
  public List<DiscoveredTarget> discoverEligibleLocal() {
    List<DiscoveredTarget> targets = new ArrayList<>();
    for (WindowHandler.WindowCandidate candidate : WindowHandler.getUiWindowCandidates()) {
      WindowInfo window = candidate.window();
      ProcessIdentity identity = buildIdentity(window.getPid());
      WindowInfo enriched = identity != null ? window.withIdentity(identity) : window;
      targets.add(
          new DiscoveredTarget(identity, enriched, candidate.source(), candidate.selectionReason()));
    }
    return targets;
  }

  @Override
  public Optional<ProcessIdentity> resolveLocal(long pid) {
    return Optional.ofNullable(buildIdentity(pid));
  }

  private ProcessIdentity buildIdentity(long pid) {
    if (pid <= 0) {
      return null;
    }
    Optional<Instant> startTime =
        ProcessHandle.of(pid).flatMap(handle -> handle.info().startInstant());
    return startTime
        .map(instant -> new ProcessIdentity(ProcessOrigin.LOCAL, hostId, pid, instant, ""))
        .orElse(null);
  }
}
