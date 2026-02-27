package com.chromascape.web.debug;

import com.chromascape.utils.core.state.BotState;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.utils.core.statistics.StatisticsManager;
import com.chromascape.web.instance.ScriptInstance;
import com.chromascape.web.instance.ScriptInstanceManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically broadcasts structured bot state updates to connected clients. */
@Component
public class BotStateBroadcaster {

  private final BotStateWebSocketHandler handler;
  private final DebugStateStore debugStateStore;
  private final ObjectMapper objectMapper;

  @Autowired
  public BotStateBroadcaster(
      BotStateWebSocketHandler handler,
      DebugStateStore debugStateStore,
      ObjectMapper objectMapper) {
    this.handler = handler;
    this.debugStateStore = debugStateStore;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedRate = 1000)
  public void broadcastBotState() {
    ScriptInstance instance = ScriptInstanceManager.getInstance().getInstanceRef();
    String scriptName = instance != null ? instance.getScriptName() : null;
    BotState state = StateManager.getState();

    Map<String, Object> payload = new HashMap<>();
    payload.put(
        "botState",
        Map.of("state", state.name(), "label", state.getDisplayName(), "css", state.getCssClass()));
    payload.put("script", scriptName);
    payload.put("tick", StatisticsManager.getCycles());
    payload.put("running", instance != null && instance.isRunning());

    DebugStateStore.DecisionSnapshot decision = debugStateStore.getLastDecision();
    if (decision != null) {
      payload.put("decision", decision);
      payload.put("targetEntity", parseJson(decision.targetEntity()));
      payload.put("gameState", parseJson(decision.gameState()));
    } else {
      payload.put("decision", null);
      payload.put("targetEntity", null);
      payload.put("gameState", null);
    }

    try {
      handler.broadcast(objectMapper.writeValueAsString(payload));
    } catch (Exception ignored) {
      // Skip broadcast on serialization error
    }
  }

  private Object parseJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception ignored) {
      return json;
    }
  }
}
