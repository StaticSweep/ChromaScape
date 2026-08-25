package com.chromascape.web.replay.run.dto;

/**
 * Response for a cancel request (accepted asynchronously).
 */
public record CancelReplayRunResponse(String runId, boolean accepted) {}