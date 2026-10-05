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
@Table(
    name = "tick_snapshots",
    indexes = {
      @Index(name = "idx_tick_snapshots_session", columnList = "session_id"),
      @Index(name = "idx_tick_snapshots_session_tick", columnList = "session_id, tick")
    })
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

  /**
   * Player state as JSON (position, HP, prayer, animation, energy, etc.). Contains canonical nested
   * fields (position{x,y,z,plane}, hp{current,max,percent}, prayer{current,max,percent}, energy,
   * animation, interacting, skills) and legacy flat aliases (x, z, worldX, worldY, plane,
   * currentHp, maxHp, currentPrayer, maxPrayer, runEnergy, animationId) for backward compatibility.
   */
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

  /** Full Quest Helper structured payload as JSON object. Optional; absent for old clients. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "quest_helper")
  private JsonNode questHelper;

  /** Active dialogue state and visible options as JSON object. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode dialogue;

  /** Visible spell definitions as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode spells;

  /** Captured var/varbit values as JSON object. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode vars;

  /** Inputs actually taken this tick (cursor, button, menu option, clicked entity/tile). */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode inputs;

  /** Walkable-tile collision flags around the player (SC-1976 combat row). */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode tiles;

  /** Active hazards: NPC attackers and incoming projectiles (SC-1976 combat row). */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode hazards;

  /** Encounter phase envelope (SC-1976 combat row). */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode phase;

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

  /** Nearby players as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode players;

  /** Widgets as JSON array. */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode widgets;

  /** Replay schema version for strict pipeline compatibility. */
  @Column(name = "schema_version")
  private Integer schemaVersion;

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

  public JsonNode getQuestHelper() {
    return questHelper;
  }

  public void setQuestHelper(JsonNode questHelper) {
    this.questHelper = questHelper;
  }

  public JsonNode getDialogue() {
    return dialogue;
  }

  public void setDialogue(JsonNode dialogue) {
    this.dialogue = dialogue;
  }

  public JsonNode getSpells() {
    return spells;
  }

  public void setSpells(JsonNode spells) {
    this.spells = spells;
  }

  public JsonNode getVars() {
    return vars;
  }

  public void setVars(JsonNode vars) {
    this.vars = vars;
  }

  public JsonNode getInputs() {
    return inputs;
  }

  public void setInputs(JsonNode inputs) {
    this.inputs = inputs;
  }

  public JsonNode getTiles() {
    return tiles;
  }

  public void setTiles(JsonNode tiles) {
    this.tiles = tiles;
  }

  public JsonNode getHazards() {
    return hazards;
  }

  public void setHazards(JsonNode hazards) {
    this.hazards = hazards;
  }

  public JsonNode getPhase() {
    return phase;
  }

  public void setPhase(JsonNode phase) {
    this.phase = phase;
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

  public JsonNode getPlayers() {
    return players;
  }

  public void setPlayers(JsonNode players) {
    this.players = players;
  }

  public JsonNode getWidgets() {
    return widgets;
  }

  public void setWidgets(JsonNode widgets) {
    this.widgets = widgets;
  }

  public Integer getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(Integer schemaVersion) {
    this.schemaVersion = schemaVersion;
  }
}
