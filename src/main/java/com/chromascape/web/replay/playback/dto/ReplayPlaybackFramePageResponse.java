package com.chromascape.web.replay.playback.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/** A bounded page of captured state frames for observed replay. */
public class ReplayPlaybackFramePageResponse {

  public static final String SCHEMA = "replay-playback-frame-page/v1";

  private final String schema = SCHEMA;
  private String recordingId;
  private String sessionId;
  private List<FrameData> frames = List.of();
  private String nextCursor;
  private boolean hasMore;
  private String accountName;
  private Long startTick;
  private Long endTick;

  public ReplayPlaybackFramePageResponse() {}

  public ReplayPlaybackFramePageResponse(
      String recordingId,
      String sessionId,
      List<FrameData> frames,
      String nextCursor,
      boolean hasMore) {
    this.recordingId = recordingId;
    this.sessionId = sessionId;
    this.frames = frames;
    this.nextCursor = nextCursor;
    this.hasMore = hasMore;
  }

  public String getSchema() {
    return schema;
  }

  public ReplayPlaybackFramePageResponse(
      String recordingId,
      String sessionId,
      List<FrameData> frames,
      String nextCursor,
      boolean hasMore,
      String accountName,
      Long startTick,
      Long endTick) {
    this(recordingId, sessionId, frames, nextCursor, hasMore);
    this.accountName = accountName;
    this.startTick = startTick;
    this.endTick = endTick;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public List<FrameData> getFrames() {
    return frames;
  }

  public void setFrames(List<FrameData> frames) {
    this.frames = frames;
  }

  public String getNextCursor() {
    return nextCursor;
  }

  public void setNextCursor(String nextCursor) {
    this.nextCursor = nextCursor;
  }

  public boolean isHasMore() {
    return hasMore;
  }

  public void setHasMore(boolean hasMore) {
    this.hasMore = hasMore;
  }

  public String getAccountName() {
    return accountName;
  }

  public void setAccountName(String accountName) {
    this.accountName = accountName;
  }

  public Long getStartTick() {
    return startTick;
  }

  public void setStartTick(Long startTick) {
    this.startTick = startTick;
  }

  public Long getEndTick() {
    return endTick;
  }

  public void setEndTick(Long endTick) {
    this.endTick = endTick;
  }

  /** One immutable-in-practice response object representing a captured tick. */
  public static class FrameData {

    private long tick;
    private LocalDateTime timestamp;
    private JsonNode playerState;
    private JsonNode inventory;
    private JsonNode equipment;
    private JsonNode threats;
    private JsonNode projectiles;
    private JsonNode cooldowns;
    private JsonNode quest;
    private JsonNode questHelper;
    private JsonNode dialogue;
    private JsonNode spells;
    private JsonNode vars;
    private JsonNode npcs;
    private JsonNode gameObjects;
    private JsonNode groundObjects;
    private JsonNode groundItems;
    private JsonNode players;
    private JsonNode widgets;
    private Integer schemaVersion;
    private List<ReplayPlaybackLog> logs = new java.util.ArrayList<>();

    public FrameData() {}

    public long getTick() {
      return tick;
    }

    public void setTick(long tick) {
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

    public List<ReplayPlaybackLog> getLogs() {
      return logs;
    }

    public void setLogs(List<ReplayPlaybackLog> logs) {
      this.logs = logs == null ? new java.util.ArrayList<>() : logs;
    }
  }
}
