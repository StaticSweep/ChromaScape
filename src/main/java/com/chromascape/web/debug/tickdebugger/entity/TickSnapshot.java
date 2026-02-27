package com.chromascape.web.debug.tickdebugger.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for storing per-tick bot state snapshots.
 *
 * <p>Each snapshot captures the complete bot state at a specific game tick, including player state,
 * inventory, equipment, threats, projectiles, and cooldowns.
 */
@Entity
@Table(name = "tick_snapshots")
public class TickSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The debug session this snapshot belongs to. */
  @Column(name = "session_id", nullable = false, length = 100)
  private String sessionId;

  /** The game tick number this snapshot represents. */
  @Column(nullable = false)
  private Long tick;

  /** When this tick occurred. */
  @Column(nullable = false)
  private LocalDateTime timestamp;

  /** Player state as JSON (position, HP, prayer, animation, energy, etc.). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private JsonNode playerState;

  /** Inventory contents as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode inventory;

  /** Equipment contents as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode equipment;

  /** Active threats (NPCs, projectiles) as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode threats;

  /** Active projectiles as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode projectiles;

  /** Cooldown state (food, prayer, teleport, etc.) as JSON object. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode cooldowns;

  /** Quest state (name, stepText, changed) as JSON object. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode quest;

  /** NPCs in render distance as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode npcs;

  /** Game objects (walls, doors, interactive objects) as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "game_objects")
  private JsonNode gameObjects;

  /** Ground objects (ground decoration layer) as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "ground_objects")
  private JsonNode groundObjects;

  /** Ground items as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "ground_items")
  private JsonNode groundItems;

  /** Default constructor required by JPA. */
  public TickSnapshot() {}

  /** Constructor for creating tick snapshots. */
  public TickSnapshot(String sessionId, Long tick, LocalDateTime timestamp, JsonNode playerState) {
    this.sessionId = sessionId;
    this.tick = tick;
    this.timestamp = timestamp;
    this.playerState = playerState;
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

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public JsonNode getPlayerState() {
    return playerState;
  }

  public void setPlayerState(JsonNode playerState) {
    this.playerState = playerState;
  }

  public JsonNode getInventory() {
    return inventory;
  }

  public void setInventory(JsonNode inventory) {
    this.inventory = inventory;
  }

  public JsonNode getEquipment() {
    return equipment;
  }

  public void setEquipment(JsonNode equipment) {
    this.equipment = equipment;
  }

  public JsonNode getThreats() {
    return threats;
  }

  public void setThreats(JsonNode threats) {
    this.threats = threats;
  }

  public JsonNode getProjectiles() {
    return projectiles;
  }

  public void setProjectiles(JsonNode projectiles) {
    this.projectiles = projectiles;
  }

  public JsonNode getCooldowns() {
    return cooldowns;
  }

  public void setCooldowns(JsonNode cooldowns) {
    this.cooldowns = cooldowns;
  }

  public JsonNode getQuest() {
    return quest;
  }

  public void setQuest(JsonNode quest) {
    this.quest = quest;
  }

  public JsonNode getNpcs() {
    return npcs;
  }

  public void setNpcs(JsonNode npcs) {
    this.npcs = npcs;
  }

  public JsonNode getGameObjects() {
    return gameObjects;
  }

  public void setGameObjects(JsonNode gameObjects) {
    this.gameObjects = gameObjects;
  }

  public JsonNode getGroundObjects() {
    return groundObjects;
  }

  public void setGroundObjects(JsonNode groundObjects) {
    this.groundObjects = groundObjects;
  }

  public JsonNode getGroundItems() {
    return groundItems;
  }

  public void setGroundItems(JsonNode groundItems) {
    this.groundItems = groundItems;
  }
}
