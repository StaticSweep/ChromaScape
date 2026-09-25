package com.chromascape.web.debug.tickdebugger.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for storing overlay data per tick for visualization.
 *
 * <p>Overlays include hitboxes, projectiles, threat zones, player tiles, and other visual debugging
 * information rendered on the canvas during replay or live sessions.
 */
@Entity
@Table(
    name = "tick_overlays",
    indexes = {@Index(name = "idx_tick_overlays_session_tick", columnList = "session_id, tick")})
public class TickOverlay {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The debug session this overlay belongs to. */
  @Column(name = "session_id", nullable = false, length = 100)
  private String sessionId;

  /** The game tick number this overlay is associated with. */
  @Column(nullable = false)
  private Long tick;

  /**
   * Type of overlay (e.g., "HITBOX", "PROJECTILE", "THREAT_ZONE", "PLAYER_TILE", "MOVEMENT_PATH").
   */
  @Column(name = "overlay_type", nullable = false, length = 50)
  private String overlayType;

  /** The overlay data as JSON (coordinates, dimensions, colors, etc.). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private JsonNode data;

  /** When this overlay was created. */
  @Column(nullable = false)
  private LocalDateTime createdAt;

  /** Default constructor required by JPA. */
  public TickOverlay() {}

  /** Constructor for creating overlay entries. */
  public TickOverlay(String sessionId, Long tick, String overlayType, JsonNode data) {
    this.sessionId = sessionId;
    this.tick = tick;
    this.overlayType = overlayType;
    this.data = data;
    this.createdAt = LocalDateTime.now();
  }

  // Getters and Setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Long getTick() {
    return tick;
  }

  public void setTick(Long tick) {
    this.tick = tick;
  }

  public String getOverlayType() {
    return overlayType;
  }

  public void setOverlayType(String overlayType) {
    this.overlayType = overlayType;
  }

  public JsonNode getData() {
    return data;
  }

  public void setData(JsonNode data) {
    this.data = data;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
