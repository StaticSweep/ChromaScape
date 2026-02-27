package com.chromascape.web.integration.leeuwbot.controller;

import com.chromascape.web.integration.leeuwbot.config.LeeuwBotConfig;
import com.chromascape.web.integration.leeuwbot.service.LeeuwBotBridgeService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for LeeuwBot integration endpoints.
 *
 * <p>Provides endpoints to check integration status and control the bridge service.
 */
@RestController
@RequestMapping("/api/leeuwbot")
public class LeeuwBotIntegrationController {

  private final LeeuwBotConfig config;
  private final LeeuwBotBridgeService bridgeService;

  /**
   * Creates a new LeeuwBotIntegrationController.
   *
   * @param config the LeeuwBot configuration
   * @param bridgeService the bridge service
   */
  @Autowired
  public LeeuwBotIntegrationController(LeeuwBotConfig config, LeeuwBotBridgeService bridgeService) {
    this.config = config;
    this.bridgeService = bridgeService;
  }

  /**
   * Returns the current integration status.
   *
   * @return the integration status
   */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> getStatus() {
    Map<String, Object> status = new HashMap<>();
    status.put("enabled", config.isEnabled());
    status.put("running", bridgeService.isRunning());
    status.put("instanceId", bridgeService.getInstanceId());
    status.put("bridgeUrl", config.getBridgeUrl());
    status.put("heartbeatIntervalSeconds", config.getHeartbeatIntervalSeconds());
    status.put("forwardLogs", config.isForwardLogs());
    status.put("reportScriptEvents", config.isReportScriptEvents());
    return ResponseEntity.ok(status);
  }

  /**
   * Starts the bridge service.
   *
   * @return success response
   */
  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> start() {
    Map<String, Object> response = new HashMap<>();

    if (!config.isEnabled()) {
      response.put("success", false);
      response.put("message", "LeeuwBot integration is disabled in configuration");
      return ResponseEntity.badRequest().body(response);
    }

    if (bridgeService.isRunning()) {
      response.put("success", false);
      response.put("message", "Bridge service is already running");
      return ResponseEntity.badRequest().body(response);
    }

    bridgeService.start();
    response.put("success", true);
    response.put("message", "Bridge service started");
    response.put("instanceId", bridgeService.getInstanceId());
    return ResponseEntity.ok(response);
  }

  /**
   * Stops the bridge service.
   *
   * @return success response
   */
  @PostMapping("/stop")
  public ResponseEntity<Map<String, Object>> stop() {
    Map<String, Object> response = new HashMap<>();

    if (!bridgeService.isRunning()) {
      response.put("success", false);
      response.put("message", "Bridge service is not running");
      return ResponseEntity.badRequest().body(response);
    }

    bridgeService.stop();
    response.put("success", true);
    response.put("message", "Bridge service stopped");
    return ResponseEntity.ok(response);
  }

  /**
   * Returns the current configuration.
   *
   * @return the configuration (with API key masked)
   */
  @GetMapping("/config")
  public ResponseEntity<Map<String, Object>> getConfig() {
    Map<String, Object> configMap = new HashMap<>();
    configMap.put("enabled", config.isEnabled());
    configMap.put("bridgeUrl", config.getBridgeUrl());
    configMap.put("apiKey", maskApiKey(config.getApiKey()));
    configMap.put("heartbeatIntervalSeconds", config.getHeartbeatIntervalSeconds());
    configMap.put("connectionTimeoutMs", config.getConnectionTimeoutMs());
    configMap.put("readTimeoutMs", config.getReadTimeoutMs());
    configMap.put("forwardLogs", config.isForwardLogs());
    configMap.put("logBatchSize", config.getLogBatchSize());
    configMap.put("logFlushIntervalSeconds", config.getLogFlushIntervalSeconds());
    configMap.put("reportScriptEvents", config.isReportScriptEvents());
    return ResponseEntity.ok(configMap);
  }

  /**
   * Masks an API key for safe display.
   *
   * @param apiKey the API key
   * @return the masked API key
   */
  private String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.length() <= 4) {
      return "****";
    }
    return apiKey.substring(0, 4) + "****";
  }
}
