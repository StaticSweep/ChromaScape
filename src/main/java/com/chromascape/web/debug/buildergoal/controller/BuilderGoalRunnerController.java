package com.chromascape.web.debug.buildergoal.controller;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalLauncherControlService;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalRunnerService;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalSensitiveTextSanitizer;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalStartException;
import com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug/builder-goals")
public class BuilderGoalRunnerController {

  private static final Logger logger = LoggerFactory.getLogger(BuilderGoalRunnerController.class);

  private final BuilderGoalRunnerService builderGoalRunnerService;
  private final BuilderGoalLauncherControlService launcherControlService;
  private final Micro6OnlineClientRegistryService clientRegistryService;
  private final ObjectMapper objectMapper;

  public BuilderGoalRunnerController(
      BuilderGoalRunnerService builderGoalRunnerService,
      BuilderGoalLauncherControlService launcherControlService,
      Micro6OnlineClientRegistryService clientRegistryService,
      ObjectMapper objectMapper) {
    this.builderGoalRunnerService = builderGoalRunnerService;
    this.launcherControlService = launcherControlService;
    this.clientRegistryService = clientRegistryService;
    this.objectMapper = objectMapper;
  }

  /**
   * Lightweight connectivity probe for MCP wrapper clients.
   *
   * <p>Returns a simple UP/DOWN status without calling the launcher, Micro6, database-heavy catalog
   * generation, or recording lookup. MCP wrappers use this as a connectivity check during
   * registration and at startup.
   */
  @GetMapping("/mcp-health")
  public ResponseEntity<Map<String, Object>> mcpHealth() {
    Map<String, Object> health = new java.util.LinkedHashMap<>();
    health.put("status", "UP");
    health.put("service", "chromascape-builder-goal");
    health.put("timestamp", java.time.Instant.now().toString());
    return ResponseEntity.ok(health);
  }

  @PostMapping("/import")
  public ResponseEntity<Object> importPayload(@RequestBody JsonNode payload) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.importPayload(payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to import builder goal payload", "details", sanitizeFailure(e)));
    }
  }

  @GetMapping("/runs")
  public ResponseEntity<List<Map<String, Object>>> listRuns(
      @RequestParam(defaultValue = "20") int limit) {
    return ResponseEntity.ok(builderGoalRunnerService.listRuns(limit));
  }

  @GetMapping("/clients")
  public ResponseEntity<List<Map<String, Object>>> listClients() {
    return ResponseEntity.ok(clientRegistryService.listClients());
  }

  @GetMapping("/accounts")
  public ResponseEntity<List<Map<String, Object>>> listAccounts() {
    return ResponseEntity.ok(builderGoalRunnerService.listAccounts());
  }

  @GetMapping("/catalog")
  public ResponseEntity<Object> listCatalog(
      @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
    return ResponseEntity.ok(builderGoalRunnerService.listCatalog(refresh));
  }

  @GetMapping("/machines")
  public ResponseEntity<Object> listMachines() {
    try {
      return ResponseEntity.ok(launcherControlService.listMachineSummaries());
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(
              Map.of(
                  "error", "Failed to load launcher machines", "details", sanitizeMachineError(e)));
    }
  }

  @GetMapping("/config/last")
  public ResponseEntity<Object> getLastConfig(
      @RequestParam String accountId, @RequestParam String taskKey) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getLatestConfig(accountId, taskKey));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to load latest builder goal config",
                  "details",
                  sanitizeFailure(e)));
    }
  }

  @PostMapping("/clients/heartbeat")
  public ResponseEntity<Object> recordClientHeartbeat(@RequestBody JsonNode payload) {
    try {
      return ResponseEntity.ok(clientRegistryService.recordHeartbeat(payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to record Micro6 client heartbeat",
                  "details",
                  sanitizeFailure(e)));
    }
  }

  @PostMapping("/launch-account")
  public ResponseEntity<Object> launchAccount(@RequestBody JsonNode payload) {
    try {
      if (payload == null || !payload.isObject()) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "launch payload must be a JSON object"));
      }
      ResponseEntity<Object> targetOverride = rejectTargetOverride(payload);
      if (targetOverride != null) {
        return targetOverride;
      }
      String accountId = textValue(payload.path("accountId"));
      String launcherMachineUuid = textValue(payload.path("launcherMachineUuid"));
      Integer world = intValue(payload.path("world"));
      boolean forceRelaunch = payload.path("forceRelaunch").asBoolean(false);
      // The launch runs on a dedicated executor with a server-side readiness watchdog. The POST
      // returns HTTP 202 immediately; the browser polls the operation via statusUrl.
      Map<String, Object> launchResponse =
          forceRelaunch
              ? builderGoalRunnerService.launchAccount(
                  accountId, launcherMachineUuid, world, true)
              : builderGoalRunnerService.launchAccount(accountId, launcherMachineUuid, world);
      return ResponseEntity.accepted().body(launchResponse);
    } catch (BuilderGoalRunnerService.LaunchSubmissionException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              Map.of(
                  "error", "Launch executor is at capacity",
                  "details", sanitizeFailure(e),
                  "guidance",
                      "A different account launch is in progress. Wait for it to complete (or fail) before launching another account."));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              Map.of(
                  "error",
                  sanitizeMessage(e.getMessage()),
                  "guidance",
                  "Another account or workflow currently owns the local launch coordinator. Retry after the active launch completes."));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to launch builder goal account", "details", sanitizeFailure(e)));
    }
  }

  /**
   * Returns the current state of an asynchronous Builder Goal account launch. Unknown or expired
   * operations (for example after a ChromaScape restart) return {@code 404}.
   */
  @GetMapping("/launch-operations/{operationId}")
  public ResponseEntity<Object> getLaunchOperation(@PathVariable String operationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getLaunchOperation(operationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to load builder goal launch operation",
                  "details",
                  sanitizeFailure(e)));
    }
  }

  @PostMapping("/run")
  public ResponseEntity<Object> runPayload(@RequestBody JsonNode payload) {
    try {
      if (payload == null || !payload.isObject()) {
        return ResponseEntity.badRequest().body(Map.of("error", "run payload must be a JSON object"));
      }
      ResponseEntity<Object> targetOverride = rejectTargetOverride(payload);
      if (targetOverride != null) {
        return targetOverride;
      }
      if (payload.has("forceRelaunch")) {
        return ResponseEntity.badRequest().body(Map.of("error", "forceRelaunch is only supported by /launch-account"));
      }
      JsonNode settingsNode = payload.get("settings");
      if (settingsNode != null && !settingsNode.isObject()) {
        return ResponseEntity.badRequest().body(Map.of("error", "settings must be a JSON object"));
      }
      JsonNode nestedBuilderPayload = payload.get("builderPayload");
      if (nestedBuilderPayload != null && nestedBuilderPayload.isObject()) {
        ResponseEntity<Object> nestedTargetOverride = rejectTargetOverride(nestedBuilderPayload);
        if (nestedTargetOverride != null) {
          return nestedTargetOverride;
        }
        if (nestedBuilderPayload.has("forceRelaunch")) {
          return ResponseEntity.badRequest().body(Map.of("error", "forceRelaunch is only supported by /launch-account"));
        }
        JsonNode nestedSettings = nestedBuilderPayload.get("settings");
        if (nestedSettings != null && !nestedSettings.isObject()) {
          return ResponseEntity.badRequest().body(Map.of("error", "settings must be a JSON object"));
        }
      }
      String accountId = textValue(payload.path("accountId"));
      String launcherMachineUuid = textValue(payload.path("launcherMachineUuid"));
      String taskKey = textValue(payload.path("taskKey"));
      Integer world = intValue(payload.path("world"));
      Map<String, Object> provenance = extractProvenance(payload);
      if (accountId != null && taskKey != null) {
        @SuppressWarnings("unchecked")
        Map<String, Object> settings =
            payload.has("settings")
                ? objectMapper.convertValue(payload.get("settings"), Map.class)
                : Map.of();
        String displayName = textValue(payload.path("displayName"));
        return ResponseEntity.ok(
            builderGoalRunnerService.startCatalogTaskRun(
                accountId, launcherMachineUuid, taskKey, settings, displayName, world, provenance));
      }

      JsonNode builderPayload =
          payload.path("builderPayload").isObject() ? payload.path("builderPayload") : payload;
      return ResponseEntity.ok(
          builderGoalRunnerService.startRun(
              builderPayload, accountId, null, null, null, launcherMachineUuid, world, provenance));
    } catch (BuilderGoalStartException e) {
      return ResponseEntity.status(e.getStatus()).body(e.toResponse());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      String correlationId = "buildergoal_" + UUID.randomUUID().toString().substring(0, 12);
      logger.error(
          "Builder Goal start failed: correlationId={}, type={}, detail={}",
          correlationId,
          e.getClass().getName(),
          sanitizeFailure(e));
      try {
        String accountId = textValue(payload != null ? payload.path("accountId") : null);
        String launcherMachineUuid =
            textValue(payload != null ? payload.path("launcherMachineUuid") : null);
        Integer world = intValue(payload != null ? payload.path("world") : null);
        BuilderGoalStartException recorded =
            builderGoalRunnerService.recordStartFailure(
                payload, accountId, launcherMachineUuid, world, e, correlationId);
        return ResponseEntity.status(recorded.getStatus()).body(recorded.toResponse());
      } catch (Exception persistenceFailure) {
        logger.error(
            "Builder Goal start failure could not be persisted: correlationId={}, type={}, detail={}",
            correlationId,
            persistenceFailure.getClass().getName(),
            sanitizeFailure(persistenceFailure));
        BuilderGoalStartException fallback =
            new BuilderGoalStartException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_START_FAILURE",
                "PREPARING",
                "Unexpected Builder Goal start failure",
                null,
                null,
                correlationId,
                e);
        return ResponseEntity.status(fallback.getStatus()).body(fallback.toResponse());
      }
    }
  }

  @PostMapping("/publish")
  public ResponseEntity<Object> publishTask(@RequestBody JsonNode payload) {
    try {
      if (payload == null || !payload.isObject()) {
        return ResponseEntity.badRequest().body(Map.of("error", "publish payload must be a JSON object"));
      }
      ResponseEntity<Object> targetOverride = rejectTargetOverride(payload);
      if (targetOverride != null) {
        return targetOverride;
      }
      String accountId = textValue(payload.path("accountId"));
      String launcherMachineUuid = textValue(payload.path("launcherMachineUuid"));
      String taskKey = textValue(payload.path("taskKey"));
      Integer world = intValue(payload.path("world"));
      JsonNode targetBaseUrl = payload.get("targetBaseUrl");
      if (targetBaseUrl != null
          && !targetBaseUrl.isNull()
          && (!targetBaseUrl.isTextual() || !targetBaseUrl.asText("").isBlank())) {
        return ResponseEntity.badRequest()
            .body(
                Map.of(
                    "error", "TARGET_OVERRIDE_NOT_ALLOWED",
                    "details",
                        "Dispatch targets are selected only from a matching ready registry client."));
      }
      boolean allowLaunchFallback = payload.path("allowLaunchFallback").asBoolean(false);
      if (accountId == null || taskKey == null) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "accountId and taskKey are required"));
      }
      JsonNode settingsNode = payload.get("settings");
      if (settingsNode != null && !settingsNode.isObject()) {
        return ResponseEntity.badRequest().body(Map.of("error", "settings must be a JSON object"));
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> settings =
          settingsNode == null
              ? Map.of()
              : objectMapper.convertValue(settingsNode, Map.class);
      Map<String, Object> provenance = extractProvenance(payload);
      return ResponseEntity.ok(
          builderGoalRunnerService.publishOnly(
              accountId,
              launcherMachineUuid,
              taskKey,
              settings,
              world,
              allowLaunchFallback,
              provenance));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to publish builder goal task", "details", sanitizeFailure(e)));
    }
  }

  @GetMapping("/runs/{iterationId}")
  public ResponseEntity<Object> getRunSummary(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRunSummary(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to load builder goal run summary",
                  "details",
                  sanitizeFailure(e)));
    }
  }

  @GetMapping("/runs/{iterationId}/config")
  public ResponseEntity<Object> getRunConfig(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRunConfig(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to load builder goal run config",
                  "details",
                  sanitizeFailure(e)));
    }
  }

  @GetMapping("/runs/{iterationId}/logs")
  public ResponseEntity<Object> getRunLogs(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRunLogs(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to load builder goal run logs", "details", sanitizeFailure(e)));
    }
  }

  @GetMapping("/recordings/{recordingId}/run")
  public ResponseEntity<Object> getRunByRecordingId(@PathVariable String recordingId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRunSummaryByRecordingId(recordingId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of("error", "Failed to resolve builder goal run", "details", sanitizeFailure(e)));
    }
  }

  @GetMapping("/recordings/{recordingId}/logs")
  public ResponseEntity<Object> getRunLogsByRecordingId(@PathVariable String recordingId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRunLogsByRecordingId(recordingId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to load builder goal run logs", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/rerun")
  public ResponseEntity<Object> rerun(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.rerun(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to rerun builder goal", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/analysis-draft")
  public ResponseEntity<Object> buildAnalysisDraft(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.buildAnalysisDraft(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to build analysis draft", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/analyze")
  public ResponseEntity<Object> analyzeRun(
      @PathVariable String iterationId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.analyzeFailure(iterationId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of("error", "Failed to analyze builder goal run", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/fix-and-relaunch")
  public ResponseEntity<Object> fixAndRelaunch(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.approveRecovery(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to approve builder goal recovery",
                  "details",
                  sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/approve-recovery")
  public ResponseEntity<Object> approveRecovery(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.approveRecovery(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to approve builder goal recovery",
                  "details",
                  sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/recovery-workspace")
  public ResponseEntity<Object> openRecoveryWorkspace(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.openRecoveryWorkspace(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of("error", "Failed to open recovery workspace", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/recordings/{recordingId}/recovery-workspace")
  public ResponseEntity<Object> openRecoveryWorkspaceByRecordingId(
      @PathVariable String recordingId) {
    try {
      return ResponseEntity.ok(
          builderGoalRunnerService.openRecoveryWorkspaceByRecordingId(recordingId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of("error", "Failed to open recovery workspace", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/recovery-annotations")
  public ResponseEntity<Object> saveRecoveryAnnotations(
      @PathVariable String iterationId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(
          builderGoalRunnerService.saveRecoveryAnnotations(iterationId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to save recovery annotations", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/recovery-evidence")
  public ResponseEntity<Object> resolveRecoveryEvidence(
      @PathVariable String iterationId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(
          builderGoalRunnerService.resolveRecoveryEvidence(iterationId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to resolve recovery evidence", "details", sanitizeFailure(e)));
    }
  }

  @GetMapping("/runs/{iterationId}/recovery-state")
  public ResponseEntity<Object> getRecoveryState(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRecoveryState(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to load recovery state", "details", sanitizeFailure(e)));
    }
  }

  @GetMapping("/recordings/{recordingId}/recovery-state")
  public ResponseEntity<Object> getRecoveryStateByRecordingId(@PathVariable String recordingId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRecoveryStateByRecordingId(recordingId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to load recovery state", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/pause")
  public ResponseEntity<Object> pauseRun(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.pauseRun(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to pause builder goal run", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/resume")
  public ResponseEntity<Object> resumeRun(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.resumeRun(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to resume builder goal run", "details", sanitizeFailure(e)));
    }
  }

  @PostMapping("/runs/{iterationId}/stop")
  public ResponseEntity<Object> stopRun(
      @PathVariable String iterationId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.stopRun(iterationId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", sanitizeMessage(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to stop builder goal run", "details", sanitizeFailure(e)));
    }
  }

  /**
   * Sanitizes a launcher machine-discovery failure into an actionable detail string. The exception
   * type and a non-null message are preserved while credentials (such as a bearer token) are
   * masked.
   */
  private String sanitizeMachineError(Exception e) {
    return sanitizeFailure(e);
  }

  /**
   * Converts unexpected service failures into bounded, credential-free details for the browser. The
   * launcher service owns the sanitizer because it knows the configured launcher token and upstream
   * bearer-header formats. A fallback is retained for lightweight unit-test mocks or a misbehaving
   * sanitizer implementation so HTTP error construction never throws a second error. The result is
   * always passed through {@link BuilderGoalSensitiveTextSanitizer} so no credential pattern can
   * leak.
   */
  private String sanitizeFailure(Throwable failure) {
    String detail;
    try {
      detail = launcherControlService.describeSanitizedFailure(failure);
    } catch (RuntimeException sanitizerFailure) {
      detail = null;
    }
    if (detail == null || detail.isBlank()) {
      if (failure == null) {
        return "Unexpected failure";
      }
      return failure.getClass().getSimpleName();
    }
    return BuilderGoalSensitiveTextSanitizer.sanitize(detail);
  }

  /** Sanitizes a message before it is exposed in an HTTP error body. */
  private String sanitizeMessage(String message) {
    if (message == null || message.isBlank()) {
      return "Unexpected failure";
    }
    return BuilderGoalSensitiveTextSanitizer.sanitize(message);
  }

  private ResponseEntity<Object> rejectTargetOverride(JsonNode payload) {
    if (containsNonBlankTargetOverride(payload)) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error", "TARGET_OVERRIDE_NOT_ALLOWED",
                  "details", "Dispatch targets are selected only from a matching ready registry client."));
    }
    return null;
  }

  /**
   * Caller-selected executor URLs are forbidden at every nesting level, including task_metadata
   * and queue preview envelopes.  Checking only the top-level field leaves a bypass for clients
   * that serialize the old builder_execution shape.
   */
  private boolean containsNonBlankTargetOverride(JsonNode node) {
    if (node == null || node.isNull()) {
      return false;
    }
    if (node.isObject()) {
      java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        java.util.Map.Entry<String, JsonNode> field = fields.next();
        String name = field.getKey();
        JsonNode value = field.getValue();
        if (("targetBaseUrl".equals(name) || "target_base_url".equals(name))
            && value != null
            && !value.isNull()
            && (!value.isTextual() || !value.asText("").isBlank())) {
          return true;
        }
        if (containsNonBlankTargetOverride(value)) {
          return true;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        if (containsNonBlankTargetOverride(child)) {
          return true;
        }
      }
    }
    return false;
  }

  private String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  private Integer intValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isInt() || node.isLong()) {
      return node.asInt();
    }
    String text = node.asText(null);
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Map<String, Object> extractProvenance(JsonNode payload) {
    JsonNode initiatedBy = firstPresent(payload, "initiatedBy", "initiated_by");
    JsonNode inputOrigin = firstPresent(payload, "inputOrigin", "input_origin");
    JsonNode userClicked = firstPresent(payload, "userClicked", "user_clicked");
    JsonNode scriptClicked = firstPresent(payload, "scriptClicked", "script_clicked");
    if (initiatedBy.isMissingNode()
        && inputOrigin.isMissingNode()
        && userClicked.isMissingNode()
        && scriptClicked.isMissingNode()) {
      return null;
    }
    Map<String, Object> provenance = new java.util.LinkedHashMap<>();
    if (!initiatedBy.isMissingNode() && !initiatedBy.isNull()) {
      provenance.put("initiated_by", initiatedBy.asText());
    }
    if (!inputOrigin.isMissingNode() && !inputOrigin.isNull()) {
      provenance.put("input_origin", inputOrigin.asText());
    }
    if (!userClicked.isMissingNode() && !userClicked.isNull()) {
      provenance.put("user_clicked", userClicked.asBoolean());
    }
    if (!scriptClicked.isMissingNode() && !scriptClicked.isNull()) {
      provenance.put("script_clicked", scriptClicked.asBoolean());
    }
    return provenance.isEmpty() ? null : provenance;
  }

  private JsonNode firstPresent(JsonNode payload, String firstField, String secondField) {
    JsonNode first = payload.path(firstField);
    if (!first.isMissingNode()) {
      return first;
    }
    return payload.path(secondField);
  }
}
