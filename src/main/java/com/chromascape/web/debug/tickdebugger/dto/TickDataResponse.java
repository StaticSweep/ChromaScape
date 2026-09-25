package com.chromascape.web.debug.tickdebugger.dto;

import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlay;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import java.util.List;

/**
 * Response containing data for a single tick.
 *
 * @param snapshot Tick snapshot data
 * @param logs List of logs for this tick
 * @param overlays List of overlays for this tick
 */
public record TickDataResponse(
    TickSnapshot snapshot, List<TickLog> logs, List<TickOverlay> overlays) {
  public TickDataResponse {
    if (logs == null) logs = List.of();
    if (overlays == null) overlays = List.of();
  }
}
