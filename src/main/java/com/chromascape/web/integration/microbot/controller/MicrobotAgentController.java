package com.chromascape.web.integration.microbot.controller;

import com.chromascape.web.integration.adapter.BotRuntimeAdapter;
import com.chromascape.web.integration.adapter.RuntimeAction;
import com.chromascape.web.integration.adapter.RuntimeAdapterRegistry;
import com.chromascape.web.integration.adapter.RuntimeStateSnapshot;
import com.chromascape.web.integration.microbot.adapter.MicrobotAgentServerAdapter;
import com.chromascape.web.integration.microbot.config.MicrobotAgentConfig;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Microbot Agent Server integration endpoints.
 *
 * <p>Provides adapter health checks, state snapshots, and action dispatch for the Microbot Agent
 * Server runtime.
 */
@RestController
@RequestMapping("/api/integration/microbot")
@ConditionalOnBean(MicrobotAgentServerAdapter.class)
public class MicrobotAgentController {

  private static final Logger logger = LoggerFactory.getLogger(MicrobotAgentController.class);

  private final RuntimeAdapterRegistry adapterRegistry;
  private final MicrobotAgentConfig config;

  @Autowired
  public MicrobotAgentController(
      RuntimeAdapterRegistry adapterRegistry, MicrobotAgentConfig config) {
    this.adapterRegistry = adapterRegistry;
    this.config = config;
  }

  /** Health check for the Microbot Agent Server adapter. */
  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> health() {
    Map<String, Object> response = new HashMap<>();
    response.put("configuredBaseUrl", config.getBaseUrl());
    var adapter = adapterRegistry.getAdapter("microbot-agent");
    if (adapter.isPresent()) {
      BotRuntimeAdapter a = adapter.get();
      response.put("adapterId", a.getAdapterId());
      response.put("displayName", a.getDisplayName());
      response.put("available", a.isAvailable());
    } else {
      response.put("adapterId", "microbot-agent");
      response.put("available", false);
      response.put("reason", "Adapter not registered");
    }
    return ResponseEntity.ok(response);
  }

  /** Fetches a state snapshot from the Microbot Agent Server. */
  @GetMapping("/state")
  public ResponseEntity<Object> stateSnapshot() {
    var adapter = adapterRegistry.getAdapter("microbot-agent");
    if (adapter.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    RuntimeStateSnapshot snapshot = adapter.get().fetchStateSnapshot();
    return ResponseEntity.ok(snapshot);
  }

  /** Dispatches an action through the Microbot Agent Server. */
  @PostMapping("/action")
  public ResponseEntity<Object> dispatchAction(@RequestBody Map<String, Object> body) {
    var adapter = adapterRegistry.getAdapter("microbot-agent");
    if (adapter.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    String actionType = (String) body.getOrDefault("action", "unknown");
    @SuppressWarnings("unchecked")
    Map<String, Object> params = (Map<String, Object>) body.getOrDefault("parameters", Map.of());
    RuntimeAction action = adapter.get().dispatchAction(new RuntimeAction(actionType, params));
    return ResponseEntity.ok(action);
  }

  /** Script lifecycle passthrough - receives script start/stop events. */
  @PostMapping("/script-lifecycle")
  public ResponseEntity<Map<String, Object>> scriptLifecycle(
      @RequestBody Map<String, Object> event) {
    logger.info("Script lifecycle event from Microbot Agent: {}", event);
    var adapter = adapterRegistry.getAdapter("microbot-agent");
    if (adapter.isPresent() && adapter.get() instanceof MicrobotAgentServerAdapter agentAdapter) {
      agentAdapter.publishEvent("SCRIPT_LIFECYCLE", event);
    }
    Map<String, Object> response = new HashMap<>();
    response.put("accepted", true);
    return ResponseEntity.ok(response);
  }
}
