package com.chromascape.utils.core.screen.window;

import java.util.List;
import java.util.Optional;

/**
 * Discovery for platforms without a client discovery implementation (macOS/unknown).
 *
 * <p>Fails bounded instead of throwing at startup: discovery returns no targets and reports
 * {@link TargetSelectionDiagnostic#UNSUPPORTED_PLATFORM}.
 */
public class UnsupportedProcessManager implements ProcessManager {

  @Override
  public List<DiscoveredTarget> discoverEligibleLocal() {
    return List.of();
  }

  @Override
  public Optional<ProcessIdentity> resolveLocal(long pid) {
    return Optional.empty();
  }

  @Override
  public TargetScanDiagnostics lastScanDiagnostics() {
    return new TargetScanDiagnostics(0, 0, List.of(TargetSelectionDiagnostic.UNSUPPORTED_PLATFORM));
  }

  @Override
  public boolean isPlatformSupported() {
    return false;
  }
}
