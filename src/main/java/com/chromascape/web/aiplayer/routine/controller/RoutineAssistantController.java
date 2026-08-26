package com.chromascape.web.aiplayer.routine.controller;

import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.service.RoutineCaptureIngestionService;
import com.chromascape.web.aiplayer.routine.service.RoutineAssistantService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai-routine")
public class RoutineAssistantController {
  private final RoutineAssistantService service;
  public RoutineAssistantController(RoutineAssistantService service) { this.service = service; }
  @PostMapping("/runs/{runId}/assistant/conversations") public ResponseEntity<Object> create(@PathVariable String runId) { return ResponseEntity.ok(service.create(runId)); }
  @GetMapping("/assistant/conversations/{id}") public ResponseEntity<Object> status(@PathVariable String id) { return ResponseEntity.ok(service.status(id)); }
  @PostMapping("/assistant/conversations/{id}/messages") public ResponseEntity<Object> message(@PathVariable String id, @RequestBody RoutineApiDtos.AssistantMessageRequest request) { return ResponseEntity.accepted().body(service.message(id, request)); }
  @PostMapping("/assistant/conversations/{id}/stop") public ResponseEntity<Object> stop(@PathVariable String id) { return ResponseEntity.ok(service.stop(id)); }
  @GetMapping(value = "/assistant/conversations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId,
      @RequestParam(value = "after", defaultValue = "0") long after) {
    // Resume from the greater valid nonnegative cursor. The UI persists its last event id and sends
    // it both as Last-Event-ID and as the `after` query parameter on reconnect.
    long cursor = Math.max(0L, Math.max(lastEventId, after));
    SseEmitter emitter = new SseEmitter(0L);
    service.stream(id, cursor, emitter);
    return emitter;
  }
  @PostMapping("/assistant/proposals/{id}/accept") public ResponseEntity<Object> accept(@PathVariable String id) { return ResponseEntity.ok(service.accept(id)); }
  @PostMapping("/assistant/proposals/{id}/reject") public ResponseEntity<Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) { return ResponseEntity.ok(service.reject(id, body == null ? null : body.get("reason"))); }
  @ExceptionHandler(RoutineCaptureIngestionService.RoutineCaptureException.class) public ResponseEntity<Object> error(RoutineCaptureIngestionService.RoutineCaptureException e) { return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage())); }
}
