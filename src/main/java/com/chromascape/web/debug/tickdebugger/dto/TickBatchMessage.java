package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Message structure for tick batch data sent from Micro6 to ChromaScape.
 *
 * <p>Each tick batch contains all relevant data for a single game tick including player state,
 * inventory, equipment, threats, projectiles, cooldowns, logs, and overlays.
 */
public class TickBatchMessage {

  /** Message type identifier. */
  private final String type;

  /** The debug session ID. */
  private final String sessionId;

  /** The game tick number. */
  private final long tick;

  /** ISO-8601 timestamp of when this tick occurred. */
  private final String timestamp;

  /** Player state map containing position, HP, prayer, animation, etc. */
  private final Map<String, Object> playerState;

  /** Inventory items list. */
  private final List<Map<String, Object>> inventory;

  /** Equipment items list. */
  private final List<Map<String, Object>> equipment;

  /** Active threats (NPCs, monsters) list. */
  private final List<Map<String, Object>> threats;

  /** Active projectiles list. */
  private final List<Map<String, Object>> projectiles;

  /** Cooldown state map. */
  private final Map<String, Integer> cooldowns;

  /** Log entries for this tick. */
  private final List<TickLogEntry> logs;

  /** Overlay data for this tick. */
  private final List<TickOverlayEntry> overlays;

  @JsonCreator
  public TickBatchMessage(
      @JsonProperty("type") String type,
      @JsonProperty("sessionId") String sessionId,
      @JsonProperty("tick") long tick,
      @JsonProperty("timestamp") String timestamp,
      @JsonProperty("playerState") Map<String, Object> playerState,
      @JsonProperty("inventory") List<Map<String, Object>> inventory,
      @JsonProperty("equipment") List<Map<String, Object>> equipment,
      @JsonProperty("threats") List<Map<String, Object>> threats,
      @JsonProperty("projectiles") List<Map<String, Object>> projectiles,
      @JsonProperty("cooldowns") Map<String, Integer> cooldowns,
      @JsonProperty("logs") List<TickLogEntry> logs,
      @JsonProperty("overlays") List<TickOverlayEntry> overlays) {
    this.type = type;
    this.sessionId = sessionId;
    this.tick = tick;
    this.timestamp = timestamp;
    this.playerState = playerState;
    this.inventory = inventory;
    this.equipment = equipment;
    this.threats = threats;
    this.projectiles = projectiles;
    this.cooldowns = cooldowns;
    this.logs = logs;
    this.overlays = overlays;
  }

  public String getType() {
    return type;
  }

  public String getSessionId() {
    return sessionId;
  }

  public long getTick() {
    return tick;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public Map<String, Object> getPlayerState() {
    return playerState;
  }

  public List<Map<String, Object>> getInventory() {
    return inventory;
  }

  public List<Map<String, Object>> getEquipment() {
    return equipment;
  }

  public List<Map<String, Object>> getThreats() {
    return threats;
  }

  public List<Map<String, Object>> getProjectiles() {
    return projectiles;
  }

  public Map<String, Integer> getCooldowns() {
    return cooldowns;
  }

  public List<TickLogEntry> getLogs() {
    return logs;
  }

  public List<TickOverlayEntry> getOverlays() {
    return overlays;
  }

  /** Individual log entry within a tick batch. */
  public static class TickLogEntry {
    private final String subsystem;
    private final String level;
    private final String eventType;
    private final String message;
    private final Map<String, Object> details;
    private final String correlationId;

    @JsonCreator
    public TickLogEntry(
        @JsonProperty("subsystem") String subsystem,
        @JsonProperty("level") String level,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("message") String message,
        @JsonProperty("details") Map<String, Object> details,
        @JsonProperty("correlationId") String correlationId) {
      this.subsystem = subsystem;
      this.level = level;
      this.eventType = eventType;
      this.message = message;
      this.details = details;
      this.correlationId = correlationId;
    }

    public String getSubsystem() {
      return subsystem;
    }

    public String getLevel() {
      return level;
    }

    public String getEventType() {
      return eventType;
    }

    public String getMessage() {
      return message;
    }

    public Map<String, Object> getDetails() {
      return details;
    }

    public String getCorrelationId() {
      return correlationId;
    }
  }

  /** Individual overlay entry within a tick batch. */
  public static class TickOverlayEntry {
    private final String type;
    private final Map<String, Object> data;

    @JsonCreator
    public TickOverlayEntry(
        @JsonProperty("type") String type, @JsonProperty("data") Map<String, Object> data) {
      this.type = type;
      this.data = data;
    }

    public String getType() {
      return type;
    }

    public Map<String, Object> getData() {
      return data;
    }
  }
}
