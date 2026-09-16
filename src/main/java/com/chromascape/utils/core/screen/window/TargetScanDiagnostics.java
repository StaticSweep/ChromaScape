package com.chromascape.utils.core.screen.window;

import java.util.List;

/**
 * Bounded, sanitized diagnostics of one discovery scan.
 *
 * @param scannedEntries number of procfs pid entries examined
 * @param unreadableEntries number of entries skipped as missing, unreadable, malformed or empty
 * @param diagnostics diagnostic codes raised by the scan
 */
public record TargetScanDiagnostics(
    int scannedEntries, int unreadableEntries, List<TargetSelectionDiagnostic> diagnostics) {

  /** Diagnostics of a scan that never ran. */
  public static final TargetScanDiagnostics EMPTY =
      new TargetScanDiagnostics(0, 0, List.of());

  public TargetScanDiagnostics {
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
  }
}
