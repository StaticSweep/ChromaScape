package com.chromascape.web.debug.tickdebugger.dto;

/**
 * Projection for grouped count queries keyed by session ID.
 *
 * <p>Used by repository methods that count tick snapshots, tick logs, or failure markers per
 * session in a single grouped query.
 */
public interface SessionCountProjection {
  String getSessionId();

  Long getCount();
}
