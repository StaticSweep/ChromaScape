package com.chromascape.web.debug.buildergoal.controller;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalRunnerService;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalLauncherControlService;
import com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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

  @PostMapping("/import")
  public ResponseEntity<Object> importPayload(@RequestBody JsonNode payload) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.importPayload(payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to import builder goal payload", "details", e.getMessage()));
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
  public ResponseEntity<List<Map<String, Object>>> listMachines() {
    return ResponseEntity.ok(launcherControlService.listMachineSummaries());
  }

  @GetMapping("/config/last")
  public ResponseEntity<Object> getLastConfig(
      @RequestParam String accountId, @RequestParam String taskKey) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getLatestConfig(accountId, taskKey));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to load latest builder goal config", "details", e.getMessage()));
    }
  }

  @PostMapping("/clients/heartbeat")
  public ResponseEntity<Object> recordClientHeartbeat(@RequestBody JsonNode payload) {
    try {
      return ResponseEntity.ok(clientRegistryService.recordHeartbeat(payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to record Micro6 client heartbeat", "details", e.getMessage()));
    }
  }

  @PostMapping("/run")
  public ResponseEntity<Object> runPayload(@RequestBody JsonNode payload) {
    try {
      String accountId = textValue(payload.path("accountId"));
      String launcherMachineUuid = textValue(payload.path("launcherMachineUuid"));
      String taskKey = textValue(payload.path("taskKey"));
      Integer world = intValue(payload.path("world"));
      if (accountId != null && taskKey != null) {
        @SuppressWarnings("unchecked")
        Map<String, Object> settings =
            payload.path("settings").isObject()
                ? objectMapper.convertValue(payload.path("settings"), Map.class)
                : Map.of();
        String displayName = textValue(payload.path("displayName"));
        return ResponseEntity.ok(
            builderGoalRunnerService.startCatalogTaskRun(
                accountId, launcherMachineUuid, taskKey, settings, displayName, world));
      }

      JsonNode builderPayload = payload.path("builderPayload").isObject() ? payload.path("builderPayload") : payload;
      return ResponseEntity.ok(
          builderGoalRunnerService.startRun(
              builderPayload, accountId, null, null, null, launcherMachineUuid, world));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to start builder goal run", "details", e.getMessage()));
    }
  }

  @GetMapping("/runs/{iterationId}/config")
  public ResponseEntity<Object> getRunConfig(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRunConfig(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to load builder goal run config", "details", e.getMessage()));
    }
  }

  @GetMapping("/runs/{iterationId}/logs")
  public ResponseEntity<Object> getRunLogs(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRunLogs(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to load builder goal run logs", "details", e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/rerun")
  public ResponseEntity<Object> rerun(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.rerun(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to rerun builder goal", "details", e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/analysis-draft")
  public ResponseEntity<Object> buildAnalysisDraft(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.buildAnalysisDraft(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to build analysis draft", "details", e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/analyze")
  public ResponseEntity<Object> analyzeRun(
      @PathVariable String iterationId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.analyzeFailure(iterationId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to analyze builder goal run", "details", e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/fix-and-relaunch")
  public ResponseEntity<Object> fixAndRelaunch(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.approveRecovery(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of("error", "Failed to approve builder goal recovery", "details", e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/approve-recovery")
  public ResponseEntity<Object> approveRecovery(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.approveRecovery(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of("error", "Failed to approve builder goal recovery", "details", e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/recovery-workspace")
  public ResponseEntity<Object> openRecoveryWorkspace(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.openRecoveryWorkspace(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to open recovery workspace", "details", e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/recovery-annotations")
  public ResponseEntity<Object> saveRecoveryAnnotations(
      @PathVariable String iterationId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(
          builderGoalRunnerService.saveRecoveryAnnotations(iterationId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to save recovery annotations",
                  "details",
                  e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/recovery-evidence")
  public ResponseEntity<Object> resolveRecoveryEvidence(
      @PathVariable String iterationId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(
          builderGoalRunnerService.resolveRecoveryEvidence(iterationId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error",
                  "Failed to resolve recovery evidence",
                  "details",
                  e.getMessage()));
    }
  }

  @GetMapping("/runs/{iterationId}/recovery-state")
  public ResponseEntity<Object> getRecoveryState(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.getRecoveryState(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of(
                  "error", "Failed to load recovery state", "details", e.getMessage()));
    }
  }

  @PostMapping("/runs/{iterationId}/stop")
  public ResponseEntity<Object> stopRun(
      @PathVariable String iterationId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(builderGoalRunnerService.stopRun(iterationId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to stop builder goal run", "details", e.getMessage()));
    }
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
}
