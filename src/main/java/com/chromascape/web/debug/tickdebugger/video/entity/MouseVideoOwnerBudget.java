package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-owner disk accounting row. Reservations are counted while an upload is in flight so a burst
 * of concurrent requests cannot bypass the quota; committed bytes are charged when registration
 * commits. The row is always locked transactionally before it is changed.
 */
@Entity
@Table(name = "mouse_video_owner_budgets")
public class MouseVideoOwnerBudget {

  @Id
  @Column(name = "owner", length = 160)
  private String owner;

  @Column(name = "charged_bytes", nullable = false)
  private long chargedBytes;

  @Column(name = "reserved_bytes", nullable = false)
  private long reservedBytes;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MouseVideoOwnerBudget() {}

  public MouseVideoOwnerBudget(String owner) {
    this.owner = owner;
    this.updatedAt = Instant.now();
  }

  public String getOwner() {
    return owner;
  }

  public long getChargedBytes() {
    return chargedBytes;
  }

  public long getReservedBytes() {
    return reservedBytes;
  }

  public long committedBytes() {
    return chargedBytes + reservedBytes;
  }

  public void reserve(long bytes) {
    this.reservedBytes += Math.max(0L, bytes);
    this.updatedAt = Instant.now();
  }

  public void release(long bytes) {
    this.reservedBytes = Math.max(0L, this.reservedBytes - Math.max(0L, bytes));
    this.updatedAt = Instant.now();
  }

  public void charge(long reserved, long actual) {
    this.reservedBytes = Math.max(0L, this.reservedBytes - Math.max(0L, reserved));
    this.chargedBytes += Math.max(0L, actual);
    this.updatedAt = Instant.now();
  }
}
