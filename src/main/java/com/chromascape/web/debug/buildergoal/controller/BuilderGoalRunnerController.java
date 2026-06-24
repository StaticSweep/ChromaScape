package com.chromascape.web.debug.buildergoal.controller;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalRunnerService;
import com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
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
  private final Micro6OnlineClientRegistryService clientRegistryService;

  public BuilderGoalRunnerController(
      BuilderGoalRunnerService builderGoalRunnerService,
      Micro6OnlineClientRegistryService clientRegistryService) {
    this.builderGoalRunnerService = builderGoalRunnerService;
    this.clientRegistryService = clientRegistryService;
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
      String targetClientId = textValue(payload.path("targetClientId"));
      JsonNode builderPayload = payload.path("builderPayload").isObject() ? payload.path("builderPayload") : payload;
      return ResponseEntity.ok(builderGoalRunnerService.startRun(builderPayload, targetClientId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to start builder goal run", "details", e.getMessage()));
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
      return ResponseEntity.ok(builderGoalRunnerService.fixAndRelaunch(iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(
              Map.of("error", "Failed to fix and relaunch builder goal run", "details", e.getMessage()));
    }
  }

  private String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }
}
