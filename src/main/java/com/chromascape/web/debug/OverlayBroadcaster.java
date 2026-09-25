package com.chromascape.web.debug;

import com.chromascape.utils.core.input.mouse.VirtualMouseUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically broadcasts overlay data (clicks, mouse, highlights) to connected clients. */
@Component
@ConditionalOnProperty(
    name = "overlay-broadcaster.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OverlayBroadcaster {

  private final OverlayWebSocketHandler handler;
  private final DebugStateStore debugStateStore;
  private final ObjectMapper objectMapper;

  @Autowired
  public OverlayBroadcaster(
      OverlayWebSocketHandler handler, DebugStateStore debugStateStore, ObjectMapper objectMapper) {
    this.handler = handler;
    this.debugStateStore = debugStateStore;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedRate = 100)
  public void broadcastOverlay() {
    List<DebugStateStore.OverlayClick> clicks = debugStateStore.getRecentClicks();
    Point mouse = VirtualMouseUtils.getCurrentClientPosition();

    Map<String, Object> payload = new HashMap<>();
    payload.put("clicks", clicks);
    payload.put("mousePosition", mouse != null ? Map.of("x", mouse.x, "y", mouse.y) : null);
    payload.put("targets", List.of());
    payload.put("entities", List.of());

    try {
      handler.broadcast(objectMapper.writeValueAsString(payload));
    } catch (Exception ignored) {
      // Skip broadcast on serialization error
    }
  }
}
