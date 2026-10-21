package com.chromascape.web.debug.tickdebugger.video;

import com.fasterxml.jackson.databind.JsonNode;

/** Normalized, policy-free media facts returned by the bounded probe worker. */
public record VideoProbeResult(
    boolean ok,
    boolean hasVideo,
    String container,
    double durationSeconds,
    int width,
    int height,
    double fps,
    long frameCount,
    String videoCodec,
    int rotationDegrees,
    int audioStreams,
    String code,
    String message,
    JsonNode raw) {

  public static VideoProbeResult unavailable(String code, String message, JsonNode raw) {
    return new VideoProbeResult(false, false, "", 0.0, 0, 0, 0.0, 0L, "", 0, 0, code, message, raw);
  }

  public long effectiveFrameCount() {
    if (frameCount > 0) {
      return frameCount;
    }
    if (fps > 0.0 && durationSeconds > 0.0) {
      return (long) Math.ceil(fps * durationSeconds);
    }
    return 0L;
  }
}
