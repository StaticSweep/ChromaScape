package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashMap;
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
  private String type;

  /** Replay schema version for strict compatibility checks. */
  private Integer schemaVersion;

  /** The debug session ID. */
  private String sessionId;

  /** The game tick number. */
  private long tick;

  /** ISO-8601 timestamp of when this tick occurred. */
  private String timestamp;

  /**
   * Player state map containing position, HP, prayer, animation, etc. Contains both canonical
   * nested fields (position, hp, prayer, energy, animation) and legacy flat aliases (x, z,
   * currentHp, runEnergy, animationId).
   */
  private Map<String, Object> playerState;

  /** Inventory items list. */
  private List<Map<String, Object>> inventory;

  /** Equipment items list. */
  private List<Map<String, Object>> equipment;

  /** Active threats (NPCs, monsters) list. */
  private List<Map<String, Object>> threats;

  /** Active projectiles list. */
  private List<Map<String, Object>> projectiles;

  /** Cooldown state map. */
  private Map<String, Integer> cooldowns;

  /** Log entries for this tick. */
  private List<TickLogEntry> logs;

  /** Overlay data for this tick. */
  private List<TickOverlayEntry> overlays;

  /** Quest state for this tick (name, stepText, changed). */
  private Map<String, Object> quest;

  /** Full Quest Helper structured payload (activeQuest, currentStep, steps, requirements, ...). */
  private Map<String, Object> questHelper;

  /** All NPCs in render distance. */
  private List<Map<String, Object>> npcs;

  /** Game objects (walls, doors, interactive objects). */
  private List<Map<String, Object>> gameObjects;

  /** Ground objects (ground decoration layer). */
  private List<Map<String, Object>> groundObjects;

  /** Ground items. */
  private List<Map<String, Object>> groundItems;

  /** Nearby players. */
  private List<Map<String, Object>> players;

  /** Widget state subset. */
  private List<Map<String, Object>> widgets;

  /** Optional Microbot runtime status for repair incident detection. */
  private String microbotStatus;

  /** Optional canonical Account Builder task key. */
  private String activeTaskKey;

  /** Optional active Microbot plugin class names. */
  private List<String> activePluginClasses;

  /** Typed semantic actions captured at client dispatch boundaries. */
  private List<RecordingAction> actions;

  @JsonCreator
  public TickBatchMessage(
      @JsonProperty("type") String type,
      @JsonProperty("schemaVersion") Integer schemaVersion,
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
      @JsonProperty("overlays") List<TickOverlayEntry> overlays,
      @JsonProperty("quest") Map<String, Object> quest,
      @JsonProperty("questHelper") Map<String, Object> questHelper,
      @JsonProperty("npcs") List<Map<String, Object>> npcs,
      @JsonProperty("gameObjects") List<Map<String, Object>> gameObjects,
      @JsonProperty("groundObjects") List<Map<String, Object>> groundObjects,
      @JsonProperty("groundItems") List<Map<String, Object>> groundItems,
      @JsonProperty("players") List<Map<String, Object>> players,
      @JsonProperty("widgets") List<Map<String, Object>> widgets,
      @JsonProperty("microbotStatus") String microbotStatus,
      @JsonProperty("activeTaskKey") String activeTaskKey,
      @JsonProperty("activePluginClasses") List<String> activePluginClasses,
      @JsonProperty("actions") List<RecordingAction> actions) {
    this.type = type;
    this.schemaVersion = schemaVersion;
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
    this.quest = quest;
    this.questHelper = questHelper;
    this.npcs = npcs;
    this.gameObjects = gameObjects;
    this.groundObjects = groundObjects;
    this.groundItems = groundItems;
    this.players = players;
    this.widgets = widgets;
    this.microbotStatus = microbotStatus;
    this.activeTaskKey = activeTaskKey;
    this.activePluginClasses = activePluginClasses;
    this.actions = actions != null ? actions : new ArrayList<>();
  }

  /** Default constructor for programmatic construction. */
  public TickBatchMessage() {
    this.type = "TICK_BATCH";
    this.schemaVersion = 2;
    this.sessionId = null;
    this.tick = 0;
    this.timestamp = null;
    this.playerState = null;
    this.inventory = new ArrayList<>();
    this.equipment = new ArrayList<>();
    this.threats = new ArrayList<>();
    this.projectiles = new ArrayList<>();
    this.cooldowns = new HashMap<>();
    this.logs = new ArrayList<>();
    this.overlays = new ArrayList<>();
    this.quest = null;
    this.questHelper = null;
    this.npcs = new ArrayList<>();
    this.gameObjects = new ArrayList<>();
    this.groundObjects = new ArrayList<>();
    this.groundItems = new ArrayList<>();
    this.players = new ArrayList<>();
    this.widgets = new ArrayList<>();
    this.actions = new ArrayList<>();
    this.activePluginClasses = new ArrayList<>();
  }

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

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public long getTick() {
    return tick;
  }

  public void setTick(long tick) {
    this.tick = tick;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  public Map<String, Object> getPlayerState() {
    return playerState;
  }

  public void setPlayerState(Map<String, Object> playerState) {
    this.playerState = playerState;
  }

  public String getMicrobotStatus() {
    return microbotStatus;
  }

  public void setMicrobotStatus(String microbotStatus) {
    this.microbotStatus = microbotStatus;
  }

  public String getActiveTaskKey() {
    return activeTaskKey;
  }

  public void setActiveTaskKey(String activeTaskKey) {
    this.activeTaskKey = activeTaskKey;
  }

  public List<String> getActivePluginClasses() {
    return activePluginClasses;
  }

  public void setActivePluginClasses(List<String> activePluginClasses) {
    this.activePluginClasses = activePluginClasses;
  }

  public List<RecordingAction> getActions() {
    return actions;
  }

  public void setActions(List<RecordingAction> actions) {
    this.actions = actions;
  }

  public List<Map<String, Object>> getInventory() {
    return inventory;
  }

  public void setInventory(List<Map<String, Object>> inventory) {
    this.inventory = inventory;
  }

  public List<Map<String, Object>> getEquipment() {
    return equipment;
  }

  public void setEquipment(List<Map<String, Object>> equipment) {
    this.equipment = equipment;
  }

  public List<Map<String, Object>> getThreats() {
    return threats;
  }

  public void setThreats(List<Map<String, Object>> threats) {
    this.threats = threats;
  }

  public List<Map<String, Object>> getProjectiles() {
    return projectiles;
  }

  public void setProjectiles(List<Map<String, Object>> projectiles) {
    this.projectiles = projectiles;
  }

  public Map<String, Integer> getCooldowns() {
    return cooldowns;
  }

  public void setCooldowns(Map<String, Integer> cooldowns) {
    this.cooldowns = cooldowns;
  }

  public List<TickLogEntry> getLogs() {
    return logs;
  }

  public void setLogs(List<TickLogEntry> logs) {
    this.logs = logs;
  }

  public List<TickOverlayEntry> getOverlays() {
    return overlays;
  }

  public void setOverlays(List<TickOverlayEntry> overlays) {
    this.overlays = overlays;
  }

  public Map<String, Object> getQuest() {
    return quest;
  }

  public void setQuest(Map<String, Object> quest) {
    this.quest = quest;
  }

  public Map<String, Object> getQuestHelper() {
    return questHelper;
  }

  public void setQuestHelper(Map<String, Object> questHelper) {
    this.questHelper = questHelper;
  }

  public List<Map<String, Object>> getNpcs() {
    return npcs;
  }

  public void setNpcs(List<Map<String, Object>> npcs) {
    this.npcs = npcs;
  }

  public List<Map<String, Object>> getGameObjects() {
    return gameObjects;
  }

  public void setGameObjects(List<Map<String, Object>> gameObjects) {
    this.gameObjects = gameObjects;
  }

  public List<Map<String, Object>> getGroundObjects() {
    return groundObjects;
  }

  public void setGroundObjects(List<Map<String, Object>> groundObjects) {
    this.groundObjects = groundObjects;
  }

  public List<Map<String, Object>> getGroundItems() {
    return groundItems;
  }

  public void setGroundItems(List<Map<String, Object>> groundItems) {
    this.groundItems = groundItems;
  }

  public List<Map<String, Object>> getPlayers() {
    return players;
  }

  public void setPlayers(List<Map<String, Object>> players) {
    this.players = players;
  }

  public List<Map<String, Object>> getWidgets() {
    return widgets;
  }

  public void setWidgets(List<Map<String, Object>> widgets) {
    this.widgets = widgets;
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
