package com.chromascape.web.aiplayer.routine.controller;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.service.RoutineCaptureIngestionService;
import com.chromascape.web.aiplayer.routine.service.RoutineReplayShadowService;
import com.chromascape.web.aiplayer.routine.service.RoutineShadowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-routine")
public class RoutineReplayController {
  private final RoutineReplayShadowService service;
  private final RoutineShadowService shadows;
  private final ObjectMapper mapper;

  public RoutineReplayController(
      RoutineReplayShadowService service, RoutineShadowService shadows, ObjectMapper mapper) {
    this.service = service;
    this.shadows = shadows;
    this.mapper = mapper;
  }

  @PostMapping("/graphs/{graphId}/models")
  public ResponseEntity<Object> model(@PathVariable String graphId, @RequestBody JsonNode request) {
    try {
      return ResponseEntity.ok(
          service.buildModel(
              graphId, mapper.treeToValue(request.get("identity"), CurrentSliceIdentity.class)));
    } catch (Exception e) {
      return ResponseEntity.unprocessableEntity()
          .body(Map.of("code", "IDENTITY_MISMATCH", "message", e.getMessage()));
    }
  }

  @PostMapping("/models/{modelId}/replays")
  public ResponseEntity<Object> replay(
      @PathVariable String modelId, @RequestBody Map<String, String> request) {
    return ResponseEntity.ok(service.replay(modelId, request.get("recordingId")));
  }

  @PostMapping("/models/{modelId}/shadow")
  public ResponseEntity<Object> shadow(
      @PathVariable String modelId, @RequestBody Map<String, String> request) {
    return ResponseEntity.ok(service.shadow(modelId, request.get("sessionId")));
  }

  @PostMapping("/models/{modelId}/shadow-runs")
  public ResponseEntity<Object> createShadowRun(
      @PathVariable String modelId, @RequestBody RoutineApiDtos.ShadowRunRequest request) {
    if (request == null || !"routine-shadow-run-request/v1".equals(request.schema()))
      throw new com.chromascape.web.aiplayer.routine.service.RoutineCaptureIngestionService
          .RoutineCaptureException(
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          "SHADOW_REQUEST_INVALID",
          "unsupported shadow request schema");
    return ResponseEntity.ok(shadows.create(modelId, request.sessionId()));
  }

  @GetMapping("/shadow-runs/{shadowRunId}")
  public ResponseEntity<Object> getShadowRun(@PathVariable String shadowRunId) {
    return ResponseEntity.ok(shadows.status(shadowRunId));
  }

  @PostMapping("/shadow-runs/{shadowRunId}/complete")
  public ResponseEntity<Object> completeShadowRun(
      @PathVariable String shadowRunId, @RequestBody RoutineApiDtos.ShadowCompleteRequest request) {
    if (request == null || !"routine-shadow-complete/v1".equals(request.schema()))
      throw new com.chromascape.web.aiplayer.routine.service.RoutineCaptureIngestionService
          .RoutineCaptureException(
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          "SHADOW_REQUEST_INVALID",
          "unsupported shadow completion schema");
    return ResponseEntity.ok(shadows.complete(shadowRunId));
  }

  @ExceptionHandler(RoutineCaptureIngestionService.RoutineCaptureException.class)
  public ResponseEntity<Object> shadowError(
      RoutineCaptureIngestionService.RoutineCaptureException error) {
    return ResponseEntity.status(error.status())
        .body(Map.of("code", error.code(), "message", error.getMessage()));
  }
}
