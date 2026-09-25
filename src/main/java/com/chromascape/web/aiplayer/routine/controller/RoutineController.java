package com.chromascape.web.aiplayer.routine.controller;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.dto.BehaviorGraphDraftDtos;
import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.service.BehaviorGraphDraftService;
import com.chromascape.web.aiplayer.routine.service.BehaviorGraphService;
import com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService;
import com.chromascape.web.aiplayer.routine.service.RoutineCaptureIngestionService;
import com.chromascape.web.aiplayer.routine.service.RoutineCurationService;
import com.chromascape.web.aiplayer.routine.source.SourceManifestException;
import com.chromascape.web.aiplayer.routine.source.SourceRunOrchestrationService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-routine")
public class RoutineController {
  private final CurrentSliceRunService runs;
  private final RoutineCaptureIngestionService captures;
  private final RoutineCurationService curation;
  private final BehaviorGraphService graphs;
  private final SourceRunOrchestrationService sourceOrchestration;
  private final BehaviorGraphDraftService draftService;

  @Value("${ai-routine.capture.token:}")
  private String captureToken;

  @org.springframework.beans.factory.annotation.Autowired
  public RoutineController(
      CurrentSliceRunService runs,
      RoutineCaptureIngestionService captures,
      RoutineCurationService curation,
      BehaviorGraphService graphs,
      SourceRunOrchestrationService sourceOrchestration,
      BehaviorGraphDraftService draftService) {
    this.runs = runs;
    this.captures = captures;
    this.curation = curation;
    this.graphs = graphs;
    this.sourceOrchestration = sourceOrchestration;
    this.draftService = draftService;
  }

  /** Compatibility constructor for controller tests that predate SC-810 run binding. */
  public RoutineController(
      CurrentSliceRunService runs,
      RoutineCaptureIngestionService captures,
      RoutineCurationService curation,
      BehaviorGraphService graphs) {
    this(runs, captures, curation, graphs, null, null);
  }

  @PostMapping("/runs/from-stabilization/{attemptId}")
  public ResponseEntity<Object> createRun(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String attemptId,
      @RequestBody RoutineApiDtos.StartRunRequest request) {
    authorize(authorization);
    CurrentSliceIdentity identity = request == null ? null : request.identity();
    if (sourceOrchestration != null
        && identity != null
        && identity.client() != null
        && "MICRO6_MANAGED".equals(identity.client().clientKind())
        && identity.run() != null
        && identity.run().runId() != null) {
      // The remote manifest fetch/verify happens with no transaction active inside the
      // orchestration service; manifest, run, binding and SOURCE_BOUND then commit atomically.
      return ResponseEntity.ok(sourceOrchestration.createSourceBoundRun(attemptId, identity));
    }
    return ResponseEntity.ok(runs.create(attemptId, identity));
  }

  @PostMapping({"/runs/runelite", "/runs/from-runelite-capture"})
  public ResponseEntity<Object> createRuneLiteRun(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody RoutineApiDtos.StartRunRequest request) {
    authorize(authorization);
    return ResponseEntity.ok(runs.createRunelite(request.identity()));
  }

  @GetMapping("/runs/{runId}")
  public ResponseEntity<Object> getRun(@PathVariable String runId) {
    return ResponseEntity.ok(runs.get(runId));
  }

  @PostMapping("/capture/sessions")
  public ResponseEntity<Object> start(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody RoutineApiDtos.CaptureSessionRequest request) {
    authorize(authorization);
    Map<String, Object> body = captures.start(request, key);
    return accepted(String.valueOf(body.get("session_id")), body);
  }

  @GetMapping("/capture/sessions/{sessionId}")
  public ResponseEntity<Object> status(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String sessionId) {
    authorize(authorization);
    return ResponseEntity.ok(captures.status(sessionId));
  }

  @PostMapping("/capture/sessions/{sessionId}/ticks")
  public ResponseEntity<Object> ticks(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String sessionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody RoutineApiDtos.BatchRequest request) {
    authorize(authorization);
    return ResponseEntity.ok(captures.ingestTicks(sessionId, request, key));
  }

  @PostMapping("/capture/sessions/{sessionId}/actions")
  public ResponseEntity<Object> actions(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String sessionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody RoutineApiDtos.BatchRequest request) {
    authorize(authorization);
    return ResponseEntity.ok(captures.ingestActions(sessionId, request, key));
  }

  @PostMapping("/capture/sessions/{sessionId}/complete")
  public ResponseEntity<Object> complete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String sessionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody RoutineApiDtos.CompleteRequest request) {
    authorize(authorization);
    return accepted(sessionId, captures.complete(sessionId, request, key));
  }

  @PostMapping("/capture/sessions/{sessionId}/reconcile")
  public ResponseEntity<Object> reconcile(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable String sessionId) {
    authorize(authorization);
    return accepted(sessionId, captures.reconcile(sessionId, key));
  }

  @PutMapping("/recordings/{recordingId}/curation")
  public ResponseEntity<Object> curate(
      @PathVariable String recordingId, @RequestBody RoutineApiDtos.CurationRequest request) {
    return ResponseEntity.ok(curation.put(recordingId, request));
  }

  @PostMapping("/recordings/{recordingId}/graphs")
  public ResponseEntity<Object> graph(@PathVariable String recordingId) {
    return ResponseEntity.ok(graphs.generate(recordingId));
  }

  @GetMapping("/graphs/{graphId}")
  public ResponseEntity<Object> getGraph(@PathVariable String graphId) {
    return ResponseEntity.ok(graphs.get(graphId));
  }

  @PostMapping("/graphs/{graphId}/publish")
  public ResponseEntity<Object> publish(@PathVariable String graphId) {
    return ResponseEntity.ok(graphs.publish(graphId));
  }

  @GetMapping("/drafts/{draftId}")
  public ResponseEntity<Object> getDraft(@PathVariable String draftId) {
    return ResponseEntity.ok(requireDrafts().getDraft(draftId));
  }

  @PostMapping("/drafts")
  public ResponseEntity<Object> createDraft(
      @RequestBody BehaviorGraphDraftDtos.DraftCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(requireDrafts().createDraft(request));
  }

  @PutMapping("/drafts/{draftId}")
  public ResponseEntity<Object> updateDraft(
      @PathVariable String draftId,
      @RequestBody BehaviorGraphDraftDtos.DraftUpdateRequest request) {
    return ResponseEntity.ok(requireDrafts().updateDraft(draftId, request));
  }

  @PostMapping("/drafts/{draftId}/publish")
  public ResponseEntity<Object> publishDraft(
      @PathVariable String draftId, @RequestBody BehaviorGraphDraftDtos.PublishRequest request) {
    return ResponseEntity.ok(requireDrafts().publishDraft(draftId, request));
  }

  private BehaviorGraphDraftService requireDrafts() {
    if (draftService == null) throw new IllegalStateException("draft service is unavailable");
    return draftService;
  }

  private void authorize(String authorization) {
    if (captureToken == null
        || captureToken.isBlank()
        || authorization == null
        || !authorization.startsWith("Bearer ")) {
      throw new RoutineCaptureIngestionService.RoutineCaptureException(
          HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "capture bearer token is invalid");
    }
    String supplied = authorization.substring("Bearer ".length()).trim();
    if (!constantTimeEquals(captureToken, supplied)) {
      throw new RoutineCaptureIngestionService.RoutineCaptureException(
          HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "capture bearer token is invalid");
    }
  }

  private static boolean constantTimeEquals(String expected, String supplied) {
    return java.security.MessageDigest.isEqual(
        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private ResponseEntity<Object> accepted(String sessionId, Map<String, Object> body) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(java.net.URI.create("/api/v1/ai-routine/capture/sessions/" + sessionId))
        .body(body);
  }

  @ExceptionHandler(RoutineCaptureIngestionService.RoutineCaptureException.class)
  public ResponseEntity<Object> captureError(
      RoutineCaptureIngestionService.RoutineCaptureException e) {
    return ResponseEntity.status(e.status())
        .body(Map.of("code", e.code(), "message", e.getMessage()));
  }

  @ExceptionHandler(SourceManifestException.class)
  public ResponseEntity<Object> sourceError(SourceManifestException e) {
    return ResponseEntity.status(e.status())
        .body(
            Map.of(
                "schema",
                "source-error/v1",
                "code",
                e.code().name(),
                "message",
                "source request failed"));
  }

  @ExceptionHandler(BehaviorGraphDraftService.DraftException.class)
  public ResponseEntity<Object> draftError(BehaviorGraphDraftService.DraftException e) {
    java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>(e.details());
    body.put("code", e.code());
    body.put("message", e.getMessage());
    return ResponseEntity.status(e.status()).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> error(Exception e) {
    return ResponseEntity.unprocessableEntity()
        .body(Map.of("code", "IDENTITY_MISMATCH", "message", e.getMessage()));
  }
}
