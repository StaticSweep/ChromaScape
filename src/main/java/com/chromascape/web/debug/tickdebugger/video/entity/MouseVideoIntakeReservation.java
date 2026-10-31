package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived intake capacity reservation keyed by (owner, request) so an unauthenticated or
 * duplicated upload burst cannot consume unbounded disk while the body streams. The reservation is
 * held in a short transaction before the request body is read, then committed or released with the
 * registration transaction.
 */
@Entity
@Table(name = "mouse_video_intake_reservations")
public class MouseVideoIntakeReservation {

  public static final String STATE_HELD = "HELD";
  public static final String STATE_COMMITTED = "COMMITTED";
  public static final String STATE_RELEASED = "RELEASED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "owner", nullable = false, length = 160)
  private String owner;

  @Column(name = "request_id", nullable = false, length = 80)
  private String requestId;

  @Column(name = "request_digest", length = 80)
  private String requestDigest;

  @Column(name = "declared_bytes", nullable = false)
  private long declaredBytes;

  @Column(name = "actual_bytes")
  private Long actualBytes;

  @Column(name = "media_checksum", length = 80)
  private String mediaChecksum;

  @Column(name = "state", nullable = false, length = 16)
  private String state = STATE_HELD;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected MouseVideoIntakeReservation() {}

  public MouseVideoIntakeReservation(
      String owner, String requestId, String requestDigest, long declaredBytes, Instant expiresAt) {
    this.owner = owner;
    this.requestId = requestId;
    this.requestDigest = requestDigest;
    this.declaredBytes = declaredBytes;
    this.actualBytes = null;
    this.createdAt = Instant.now();
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public String getOwner() {
    return owner;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getRequestDigest() {
    return requestDigest;
  }

  public long getDeclaredBytes() {
    return declaredBytes;
  }

  public Long getActualBytes() {
    return actualBytes;
  }

  public String getMediaChecksum() {
    return mediaChecksum;
  }

  public String getState() {
    return state;
  }

  public boolean isHeld() {
    return STATE_HELD.equals(state);
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void hold(long bytes, Instant expiresAt) {
    this.declaredBytes = bytes;
    this.state = STATE_HELD;
    this.expiresAt = expiresAt;
  }

  public void renew(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public void commit(String digest, String checksum, long actualBytes) {
    this.requestDigest = digest;
    this.mediaChecksum = checksum;
    this.actualBytes = actualBytes;
    this.state = STATE_COMMITTED;
  }

  public void release() {
    this.state = STATE_RELEASED;
  }
}
