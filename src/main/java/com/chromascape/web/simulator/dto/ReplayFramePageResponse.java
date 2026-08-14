package com.chromascape.web.simulator.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for the replay frame pagination endpoint.
 *
 * <p>Returns a page of tick snapshot frames for a specific recording, suitable for consumption by
 * the OpenRune tick replay client.
 */
public class ReplayFramePageResponse {

  private String recordingId;
  private String sessionId;
  private List<FrameData> frames;
  private Long nextFromTick;
  private boolean hasMore;
  private String accountName;
  private Long startTick;
  private Long endTick;

  public ReplayFramePageResponse() {}

  public ReplayFramePageResponse(
      String recordingId,
      String sessionId,
      List<FrameData> frames,
      Long nextFromTick,
      boolean hasMore) {
    this.recordingId = recordingId;
    this.sessionId = sessionId;
    this.frames = frames;
    this.nextFromTick = nextFromTick;
    this.hasMore = hasMore;
  }

  public ReplayFramePageResponse(
      String recordingId,
      String sessionId,
      List<FrameData> frames,
      Long nextFromTick,
      boolean hasMore,
      String accountName,
      Long startTick,
      Long endTick) {
    this(recordingId, sessionId, frames, nextFromTick, hasMore);
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

  public Long getNextFromTick() {
    return nextFromTick;
  }

  public void setNextFromTick(Long nextFromTick) {
    this.nextFromTick = nextFromTick;
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

  /** A single tick frame with all captured state sections. */
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
    private JsonNode npcs;
    private JsonNode gameObjects;
    private JsonNode groundObjects;
    private JsonNode groundItems;
    private JsonNode players;
    private JsonNode widgets;
    private Integer schemaVersion;

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
}
