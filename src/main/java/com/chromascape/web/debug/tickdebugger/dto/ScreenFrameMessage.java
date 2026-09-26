package com.chromascape.web.debug.tickdebugger.dto;

/**
 * Screen frame pushed by the Micro6 client to ChromaScape.
 *
 * <p>Unlike server-side capture, these pixels arrive over the tick-stream connection and need no
 * input session: the client photographs its own canvas. Schema {@code screen-frame/v1} is additive;
 * tick messages are untouched.
 */
public class ScreenFrameMessage {

  /** Message type identifier. */
  private String type;

  /** Envelope schema version; only {@code 1} ({@code screen-frame/v1}) is accepted. */
  private Integer schemaVersion;

  /** The debug session ID; must match the connection binding. */
  private String sessionId;

  /**
   * Server-negotiated stream ID from the {@code screenFrame} SESSION_START capability. Must match
   * the connection's current stream; a reconnect or new session rotates it.
   */
  private String streamId;

  /** Monotonic client-side frame sequence for ordering. */
  private long frameSeq;

  /** ISO-8601 timestamp of when the client captured the frame. */
  private String capturedAt;

  /** Encoded frame width in pixels. */
  private int width;

  /** Encoded frame height in pixels. */
  private int height;

  /** Base64-encoded JPEG bytes of the downscaled client canvas. */
  private String jpegBase64;

  /**
   * Frame media type; only {@code image/jpeg} is accepted. Declared for forward compatibility of
   * the {@code screen-frame/v1} envelope.
   */
  private String mimeType;

  /** JPEG quality used by the client encoder. */
  private int quality;

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Integer getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(Integer schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getStreamId() {
    return streamId;
  }

  public void setStreamId(String streamId) {
    this.streamId = streamId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public long getFrameSeq() {
    return frameSeq;
  }

  public void setFrameSeq(long frameSeq) {
    this.frameSeq = frameSeq;
  }

  public String getCapturedAt() {
    return capturedAt;
  }

  public void setCapturedAt(String capturedAt) {
    this.capturedAt = capturedAt;
  }

  public int getWidth() {
    return width;
  }

  public void setWidth(int width) {
    this.width = width;
  }

  public int getHeight() {
    return height;
  }

  public void setHeight(int height) {
    this.height = height;
  }

  public String getJpegBase64() {
    return jpegBase64;
  }

  public void setJpegBase64(String jpegBase64) {
    this.jpegBase64 = jpegBase64;
  }

  public String getMimeType() {
    return mimeType;
  }

  public void setMimeType(String mimeType) {
    this.mimeType = mimeType;
  }

  public int getQuality() {
    return quality;
  }

  public void setQuality(int quality) {
    this.quality = quality;
  }
}
