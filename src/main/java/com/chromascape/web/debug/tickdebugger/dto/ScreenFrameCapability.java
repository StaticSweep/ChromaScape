package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Optional {@code screenFrame} capability advertised on a successful {@code SESSION_START}
 * response.
 *
 * <p>When present, the Micro6 client may push versioned {@code SCREEN_FRAME} messages for the
 * canonical session using the negotiated {@code streamId}. When absent (older servers, disabled
 * feed), the client must treat screen frames as {@code DISABLED} and send none. A reconnect or a
 * new session rotates the stream ID; frames carrying an old ID are rejected.
 */
public class ScreenFrameCapability {

  @JsonProperty("version")
  private int version = 1;

  @JsonProperty("streamId")
  private String streamId;

  @JsonProperty("maxJpegBytes")
  private int maxJpegBytes;

  @JsonProperty("maxLongEdge")
  private int maxLongEdge;

  @JsonProperty("maxFps")
  private int maxFps;

  private boolean enabled = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public ScreenFrameCapability() {}

  public ScreenFrameCapability(String streamId, int maxJpegBytes, int maxLongEdge, int maxFps) {
    this.version = 1;
    this.streamId = streamId;
    this.maxJpegBytes = maxJpegBytes;
    this.maxLongEdge = maxLongEdge;
    this.maxFps = maxFps;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public String getStreamId() {
    return streamId;
  }

  public void setStreamId(String streamId) {
    this.streamId = streamId;
  }

  public int getMaxJpegBytes() {
    return maxJpegBytes;
  }

  public void setMaxJpegBytes(int maxJpegBytes) {
    this.maxJpegBytes = maxJpegBytes;
  }

  public int getMaxLongEdge() {
    return maxLongEdge;
  }

  public void setMaxLongEdge(int maxLongEdge) {
    this.maxLongEdge = maxLongEdge;
  }

  public int getMaxFps() {
    return maxFps;
  }

  public void setMaxFps(int maxFps) {
    this.maxFps = maxFps;
  }
}
