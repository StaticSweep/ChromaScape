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
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class TickBatchMessage {
  @com.fasterxml.jackson.annotation.JsonIgnore
  private com.fasterxml.jackson.databind.JsonNode canonicalObservation;

  public com.fasterxml.jackson.databind.JsonNode getCanonicalObservation() {
    return canonicalObservation == null ? null : canonicalObservation.deepCopy();
  }

  public void setCanonicalObservation(com.fasterxml.jackson.databind.JsonNode value) {
    canonicalObservation = value.deepCopy();
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  private com.fasterxml.jackson.databind.JsonNode canonicalFrame;

  public com.fasterxml.jackson.databind.JsonNode getCanonicalFrame() {
    return canonicalFrame == null ? null : canonicalFrame.deepCopy();
  }

  public void setCanonicalFrame(com.fasterxml.jackson.databind.JsonNode value) {
    canonicalFrame = value.deepCopy();
  }

  /** Message type identifier. */
  private String type;

  /** Replay schema version for strict compatibility checks. */
  private Integer schemaVersion;

  /** Stable session-scoped identity of the serialized delivery unit. */
  private String batchId;

  /** SHA-256 of the canonical batch payload with payloadChecksum omitted. */
  private String payloadChecksum;

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

  /** Active server dialogue state and visible options. */
  private Map<String, Object> dialogue;

  /** Visible spell definitions and their server-facing action metadata. */
  private List<Map<String, Object>> spells;

  /** Captured var/varbit values used by the replay initial state and expectations. */
  private Map<String, Object> vars;

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

  /** RSProx packet observations (schema 3); absent/empty for schema-v2 batches. */
  private List<TickPacketEntry> packets;

  /** Optional packet/transport-health metadata (schema 3). */
  private TransportDiagnostics transport;

  @JsonCreator
  public TickBatchMessage(
      @JsonProperty("type") String type,
      @JsonProperty("schemaVersion") Integer schemaVersion,
      @JsonProperty("batchId") String batchId,
      @JsonProperty("payloadChecksum") String payloadChecksum,
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
      @JsonProperty("actions") List<RecordingAction> actions,
      @JsonProperty("dialogue") Map<String, Object> dialogue,
      @JsonProperty("spells") List<Map<String, Object>> spells,
      @JsonProperty("vars") Map<String, Object> vars,
      @JsonProperty("packets") List<TickPacketEntry> packets,
      @JsonProperty("transport") TransportDiagnostics transport) {
    this.type = type;
    this.schemaVersion = schemaVersion;
    this.batchId = batchId;
    this.payloadChecksum = payloadChecksum;
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
    this.dialogue = dialogue;
    this.spells = spells;
    this.vars = vars;
    this.packets = packets != null ? packets : new ArrayList<>();
    this.transport = transport;
  }

  /** Default constructor for programmatic construction. */
  public TickBatchMessage() {
    this.type = "TICK_BATCH";
    this.schemaVersion = 2;
    this.batchId = null;
    this.payloadChecksum = null;
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
    this.dialogue = null;
    this.spells = new ArrayList<>();
    this.vars = new HashMap<>();
    this.npcs = new ArrayList<>();
    this.gameObjects = new ArrayList<>();
    this.groundObjects = new ArrayList<>();
    this.groundItems = new ArrayList<>();
    this.players = new ArrayList<>();
    this.widgets = new ArrayList<>();
    this.actions = new ArrayList<>();
    this.packets = new ArrayList<>();
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

  public String getBatchId() {
    return batchId;
  }

  public void setBatchId(String batchId) {
    this.batchId = batchId;
  }

  public String getPayloadChecksum() {
    return payloadChecksum;
  }

  public void setPayloadChecksum(String payloadChecksum) {
    this.payloadChecksum = payloadChecksum;
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

  public List<TickPacketEntry> getPackets() {
    return packets;
  }

  public void setPackets(List<TickPacketEntry> packets) {
    this.packets = packets;
  }

  public TransportDiagnostics getTransport() {
    return transport;
  }

  public void setTransport(TransportDiagnostics transport) {
    this.transport = transport;
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

  public Map<String, Object> getDialogue() {
    return dialogue;
  }

  public void setDialogue(Map<String, Object> dialogue) {
    this.dialogue = dialogue;
  }

  public List<Map<String, Object>> getSpells() {
    return spells;
  }

  public void setSpells(List<Map<String, Object>> spells) {
    this.spells = spells;
  }

  public Map<String, Object> getVars() {
    return vars;
  }

  public void setVars(Map<String, Object> vars) {
    this.vars = vars;
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

  /** One RSProx packet observation (schema 3). Field names mirror Micro6's envelope. */
  public static class TickPacketEntry {
    private final String schema;
    private final String sourceSessionId;

    /** Per-source delivery identity in v2; legacy capture identity in v1. */
    private final Long sequence;

    /** Original capture identity for v2 real packets; null for v2 GAPs and v1 envelopes. */
    private final Long packetSequence;

    private final String capturedAt;
    private final Long captureDeltaMillis;
    private final String direction;
    private final Integer opcode;
    private final String protocolName;
    private final Integer encodedSize;
    private final String decodedType;
    private final Map<String, Object> decoded;
    private final String sanitizedPayloadBase64;
    private final String decodeStatus;
    private final String decodeError;
    private final Boolean synthetic;
    private final Long tick;

    @JsonCreator
    public TickPacketEntry(
        @JsonProperty("schema") String schema,
        @JsonProperty("sourceSessionId") String sourceSessionId,
        @JsonProperty("sequence") Long sequence,
        @JsonProperty("packetSequence") Long packetSequence,
        @JsonProperty("capturedAt") String capturedAt,
        @JsonProperty("captureDeltaMillis") Long captureDeltaMillis,
        @JsonProperty("direction") String direction,
        @JsonProperty("opcode") Integer opcode,
        @JsonProperty("protocolName") String protocolName,
        @JsonProperty("encodedSize") Integer encodedSize,
        @JsonProperty("decodedType") String decodedType,
        @JsonProperty("decoded") Map<String, Object> decoded,
        @JsonProperty("sanitizedPayloadBase64") String sanitizedPayloadBase64,
        @JsonProperty("decodeStatus") String decodeStatus,
        @JsonProperty("decodeError") String decodeError,
        @JsonProperty("synthetic") Boolean synthetic,
        @JsonProperty("tick") Long tick) {
      this.schema = schema;
      this.sourceSessionId = sourceSessionId;
      this.sequence = sequence;
      this.packetSequence = packetSequence;
      this.capturedAt = capturedAt;
      this.captureDeltaMillis = captureDeltaMillis;
      this.direction = direction;
      this.opcode = opcode;
      this.protocolName = protocolName;
      this.encodedSize = encodedSize;
      this.decodedType = decodedType;
      this.decoded = decoded;
      this.sanitizedPayloadBase64 = sanitizedPayloadBase64;
      this.decodeStatus = decodeStatus;
      this.decodeError = decodeError;
      this.synthetic = synthetic;
      this.tick = tick;
    }

    /** Source-compatible constructor for existing v1 callers and fixtures. */
    public TickPacketEntry(
        String schema,
        String sourceSessionId,
        Long sequence,
        String capturedAt,
        Long captureDeltaMillis,
        String direction,
        Integer opcode,
        String protocolName,
        Integer encodedSize,
        String decodedType,
        Map<String, Object> decoded,
        String sanitizedPayloadBase64,
        String decodeStatus,
        String decodeError,
        Boolean synthetic,
        Long tick) {
      this(
          schema,
          sourceSessionId,
          sequence,
          null,
          capturedAt,
          captureDeltaMillis,
          direction,
          opcode,
          protocolName,
          encodedSize,
          decodedType,
          decoded,
          sanitizedPayloadBase64,
          decodeStatus,
          decodeError,
          synthetic,
          tick);
    }

    public String getSchema() {
      return schema;
    }

    public String getSourceSessionId() {
      return sourceSessionId;
    }

    public Long getSequence() {
      return sequence;
    }

    public Long getPacketSequence() {
      return packetSequence;
    }

    public String getCapturedAt() {
      return capturedAt;
    }

    public Long getCaptureDeltaMillis() {
      return captureDeltaMillis;
    }

    public String getDirection() {
      return direction;
    }

    public Integer getOpcode() {
      return opcode;
    }

    public String getProtocolName() {
      return protocolName;
    }

    public Integer getEncodedSize() {
      return encodedSize;
    }

    public String getDecodedType() {
      return decodedType;
    }

    public Map<String, Object> getDecoded() {
      return decoded;
    }

    public String getSanitizedPayloadBase64() {
      return sanitizedPayloadBase64;
    }

    public String getDecodeStatus() {
      return decodeStatus;
    }

    public String getDecodeError() {
      return decodeError;
    }

    public Boolean getSynthetic() {
      return synthetic;
    }

    public Long getTick() {
      return tick;
    }
  }

  /** Optional packet/transport-health metadata (schema 3). */
  public static class TransportDiagnostics {
    private final Long droppedBatchCount;
    private final Long firstDroppedTick;
    private final Long lastDroppedTick;
    private final Boolean payloadTruncated;
    private final Integer omittedLogCount;
    private final Integer omittedOverlayCount;
    private final Long droppedPacketCount;
    private final Long firstDroppedPacketSequence;
    private final Long lastDroppedPacketSequence;
    private final Integer omittedPacketCount;
    private final Long omittedPacketPayloadBytes;
    private final Long malformedPacketFrameCount;
    private final String packetSourceStatus;

    /** Exact source-scoped loss evidence emitted by the v2 producer. */
    private final List<PacketLossSource> packetLosses;

    @JsonCreator
    public TransportDiagnostics(
        @JsonProperty("droppedBatchCount") Long droppedBatchCount,
        @JsonProperty("firstDroppedTick") Long firstDroppedTick,
        @JsonProperty("lastDroppedTick") Long lastDroppedTick,
        @JsonProperty("payloadTruncated") Boolean payloadTruncated,
        @JsonProperty("omittedLogCount") Integer omittedLogCount,
        @JsonProperty("omittedOverlayCount") Integer omittedOverlayCount,
        @JsonProperty("droppedPacketCount") Long droppedPacketCount,
        @JsonProperty("firstDroppedPacketSequence") Long firstDroppedPacketSequence,
        @JsonProperty("lastDroppedPacketSequence") Long lastDroppedPacketSequence,
        @JsonProperty("omittedPacketCount") Integer omittedPacketCount,
        @JsonProperty("omittedPacketPayloadBytes") Long omittedPacketPayloadBytes,
        @JsonProperty("malformedPacketFrameCount") Long malformedPacketFrameCount,
        @JsonProperty("packetSourceStatus") String packetSourceStatus,
        @JsonProperty("packetLosses") List<PacketLossSource> packetLosses) {
      this.droppedBatchCount = droppedBatchCount;
      this.firstDroppedTick = firstDroppedTick;
      this.lastDroppedTick = lastDroppedTick;
      this.payloadTruncated = payloadTruncated;
      this.omittedLogCount = omittedLogCount;
      this.omittedOverlayCount = omittedOverlayCount;
      this.droppedPacketCount = droppedPacketCount;
      this.firstDroppedPacketSequence = firstDroppedPacketSequence;
      this.lastDroppedPacketSequence = lastDroppedPacketSequence;
      this.omittedPacketCount = omittedPacketCount;
      this.omittedPacketPayloadBytes = omittedPacketPayloadBytes;
      this.malformedPacketFrameCount = malformedPacketFrameCount;
      this.packetSourceStatus = packetSourceStatus;
      this.packetLosses = packetLosses == null ? new ArrayList<>() : packetLosses;
    }

    public Long getDroppedBatchCount() {
      return droppedBatchCount;
    }

    public Long getFirstDroppedTick() {
      return firstDroppedTick;
    }

    public Long getLastDroppedTick() {
      return lastDroppedTick;
    }

    public Boolean getPayloadTruncated() {
      return payloadTruncated;
    }

    public Integer getOmittedLogCount() {
      return omittedLogCount;
    }

    public Integer getOmittedOverlayCount() {
      return omittedOverlayCount;
    }

    public Long getDroppedPacketCount() {
      return droppedPacketCount;
    }

    public Long getFirstDroppedPacketSequence() {
      return firstDroppedPacketSequence;
    }

    public Long getLastDroppedPacketSequence() {
      return lastDroppedPacketSequence;
    }

    public Integer getOmittedPacketCount() {
      return omittedPacketCount;
    }

    public Long getOmittedPacketPayloadBytes() {
      return omittedPacketPayloadBytes;
    }

    public Long getMalformedPacketFrameCount() {
      return malformedPacketFrameCount;
    }

    public String getPacketSourceStatus() {
      return packetSourceStatus;
    }

    public List<PacketLossSource> getPacketLosses() {
      return packetLosses;
    }

    /** Exact loss ranges for one RSProx source session. Counts are not inferred from endpoints. */
    public static class PacketLossSource {
      private String sourceSessionId;
      private long count;
      private Long firstPacketSequence;
      private Long lastPacketSequence;
      private boolean summaryOnly;
      private List<PacketLossRange> ranges = new ArrayList<>();

      public PacketLossSource() {}

      public String getSourceSessionId() {
        return sourceSessionId;
      }

      public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
      }

      public long getCount() {
        return count;
      }

      public void setCount(long count) {
        this.count = count;
      }

      public Long getFirstPacketSequence() {
        return firstPacketSequence;
      }

      public void setFirstPacketSequence(Long firstPacketSequence) {
        this.firstPacketSequence = firstPacketSequence;
      }

      public Long getLastPacketSequence() {
        return lastPacketSequence;
      }

      public void setLastPacketSequence(Long lastPacketSequence) {
        this.lastPacketSequence = lastPacketSequence;
      }

      public boolean isSummaryOnly() {
        return summaryOnly;
      }

      public void setSummaryOnly(boolean summaryOnly) {
        this.summaryOnly = summaryOnly;
      }

      public List<PacketLossRange> getRanges() {
        return ranges;
      }

      public void setRanges(List<PacketLossRange> ranges) {
        this.ranges = ranges == null ? new ArrayList<>() : ranges;
      }
    }

    public static class PacketLossRange {
      private long first;
      private long last;
      private long count;

      public PacketLossRange() {}

      public long getFirst() {
        return first;
      }

      public void setFirst(long first) {
        this.first = first;
      }

      public long getLast() {
        return last;
      }

      public void setLast(long last) {
        this.last = last;
      }

      public long getCount() {
        return count;
      }

      public void setCount(long count) {
        this.count = count;
      }
    }
  }
}
