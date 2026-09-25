package com.chromascape.web.debug.tickdebugger.dto;

import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlay;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import java.util.List;

/**
 * Response containing batch tick data for replay.
 *
 * @param snapshots List of tick snapshots
 * @param logs List of tick logs
 * @param overlays List of tick overlays
 */
public record TickBatchResponse(
    List<TickSnapshot> snapshots, List<TickLog> logs, List<TickOverlay> overlays) {
  public TickBatchResponse {
    if (snapshots == null) snapshots = List.of();
    if (logs == null) logs = List.of();
    if (overlays == null) overlays = List.of();
  }
}
