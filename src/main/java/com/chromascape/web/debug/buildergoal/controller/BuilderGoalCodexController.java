package com.chromascape.web.debug.buildergoal.controller;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalCodexOrchestrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug/builder-goals/codex")
public class BuilderGoalCodexController {

  private final BuilderGoalCodexOrchestrationService orchestrationService;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public BuilderGoalCodexController(BuilderGoalCodexOrchestrationService orchestrationService) {
    this.orchestrationService = orchestrationService;
  }

  @PostMapping("/sessions")
  public ResponseEntity<Object> createSession() {
    return ResponseEntity.ok(orchestrationService.createSession());
  }

  @GetMapping("/sessions/{sessionId}")
  public ResponseEntity<Object> getSession(@PathVariable String sessionId) {
    try {
      return ResponseEntity.ok(orchestrationService.getSession(sessionId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/bind-run")
  public ResponseEntity<Object> bindRun(
      @PathVariable String sessionId, @RequestBody(required = false) JsonNode payload) {
    try {
      String iterationId = textValue(payload, "iterationId");
      return ResponseEntity.ok(orchestrationService.bindRun(sessionId, iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/issue")
  public ResponseEntity<Object> setIssue(
      @PathVariable String sessionId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(
          orchestrationService.setIssueStatement(
              sessionId, textValue(payload, "issueStatement"), textValueAllowBlank(payload, "context")));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/start-run")
  public ResponseEntity<Object> startRun(
      @PathVariable String sessionId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(
          orchestrationService.startRun(
              sessionId,
              textValue(payload, "accountId"),
              textValue(payload, "launcherMachineUuid"),
              textValue(payload, "taskKey"),
              intValue(payload, "world"),
              textValue(payload, "displayName"),
              mapValue(payload, "settings")));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/sessions/{sessionId}/active-run")
  public ResponseEntity<Object> getActiveRun(@PathVariable String sessionId) {
    try {
      return ResponseEntity.ok(orchestrationService.getActiveRun(sessionId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/stop-run")
  public ResponseEntity<Object> stopRun(
      @PathVariable String sessionId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(orchestrationService.stopRun(sessionId, textValue(payload, "reason")));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/open-recovery-workspace")
  public ResponseEntity<Object> openRecoveryWorkspace(@PathVariable String sessionId) {
    try {
      return ResponseEntity.ok(orchestrationService.openRecoveryWorkspace(sessionId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/sessions/{sessionId}/recovery-state")
  public ResponseEntity<Object> getRecoveryState(@PathVariable String sessionId) {
    try {
      return ResponseEntity.ok(orchestrationService.getRecoveryState(sessionId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/refresh-evidence")
  public ResponseEntity<Object> refreshEvidence(
      @PathVariable String sessionId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(orchestrationService.refreshEvidence(sessionId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/generate-patch-plan")
  public ResponseEntity<Object> generatePatchPlan(
      @PathVariable String sessionId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(orchestrationService.generatePatchPlan(sessionId, payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to generate patch plan", "details", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/approve-recovery")
  public ResponseEntity<Object> approveRecovery(@PathVariable String sessionId) {
    try {
      return ResponseEntity.ok(orchestrationService.approveRecovery(sessionId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to approve recovery", "details", e.getMessage()));
    }
  }

  @GetMapping("/sessions/{sessionId}/run-logs")
  public ResponseEntity<Object> getRunLogs(
      @PathVariable String sessionId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String iterationId) {
    try {
      return ResponseEntity.ok(orchestrationService.getRunLogs(sessionId, iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/sessions/{sessionId}/replay-context")
  public ResponseEntity<Object> getReplayContext(
      @PathVariable String sessionId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String iterationId) {
    try {
      return ResponseEntity.ok(orchestrationService.getReplayContext(sessionId, iterationId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/sessions/{sessionId}/replay-session-info")
  public ResponseEntity<Object> getReplaySessionInfo(
      @PathVariable String sessionId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String debugSessionId) {
    try {
      return ResponseEntity.ok(orchestrationService.getReplaySessionInfo(sessionId, debugSessionId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/sessions/{sessionId}/replay-tick-range")
  public ResponseEntity<Object> getReplayTickRange(
      @PathVariable String sessionId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String debugSessionId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) Long startTick,
      @org.springframework.web.bind.annotation.RequestParam(required = false) Long endTick) {
    try {
      return ResponseEntity.ok(
          orchestrationService.getReplayTickRange(sessionId, debugSessionId, startTick, endTick));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/sessions/{sessionId}/replay-tick/{tick}")
  public ResponseEntity<Object> getReplayTick(
      @PathVariable String sessionId,
      @PathVariable Long tick,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String debugSessionId) {
    try {
      return ResponseEntity.ok(orchestrationService.getReplayTick(sessionId, debugSessionId, tick));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/sessions/{sessionId}/messages")
  public ResponseEntity<Object> postMessage(
      @PathVariable String sessionId, @RequestBody(required = false) JsonNode payload) {
    try {
      return ResponseEntity.ok(
          orchestrationService.processMessage(sessionId, textValue(payload, "message"), payload));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Failed to process Codex message", "details", e.getMessage()));
    }
  }

  @GetMapping("/sessions/{sessionId}/context")
  public ResponseEntity<Object> getContext(@PathVariable String sessionId) {
    try {
      return ResponseEntity.ok(orchestrationService.getContext(sessionId));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  private String textValue(JsonNode payload, String field) {
    if (payload == null || payload.isNull()) {
      return null;
    }
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  private String textValueAllowBlank(JsonNode payload, String field) {
    if (payload == null || payload.isNull()) {
      return null;
    }
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    return node.asText(null);
  }

  private Integer intValue(JsonNode payload, String field) {
    if (payload == null || payload.isNull()) {
      return null;
    }
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull()) {
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
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> mapValue(JsonNode payload, String field) {
    if (payload == null || payload.isNull()) {
      return Map.of();
    }
    JsonNode node = payload.path(field);
    if (node == null || !node.isObject()) {
      return Map.of();
    }
    return objectMapper.convertValue(node, Map.class);
  }
}
