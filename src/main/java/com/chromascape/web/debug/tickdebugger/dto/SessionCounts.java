package com.chromascape.web.debug.tickdebugger.dto;

/**
 * Aggregate count DTO for bulk session summary queries.
 *
 * <p>Used to reduce N+1 query problems by fetching all counts in a single JOIN query.
 */
public class SessionCounts {
  private final String sessionId;
  private final Long tickCount;
  private final Long eventCount;
  private final Long failureMarkerCount;

  /**
   * Constructor for JPQL projection.
   *
   * @param sessionId the session ID
   * @param tickCount number of tick snapshots
   * @param eventCount number of tick logs
   * @param failureMarkerCount number of failure markers
   */
  public SessionCounts(String sessionId, Long tickCount, Long eventCount, Long failureMarkerCount) {
    this.sessionId = sessionId;
    this.tickCount = tickCount != null ? tickCount : 0L;
    this.eventCount = eventCount != null ? eventCount : 0L;
    this.failureMarkerCount = failureMarkerCount != null ? failureMarkerCount : 0L;
  }

  public String getSessionId() {
    return sessionId;
  }

  public Long getTickCount() {
    return tickCount;
  }

  public Long getEventCount() {
    return eventCount;
  }

  public Long getFailureMarkerCount() {
    return failureMarkerCount;
  }
}
