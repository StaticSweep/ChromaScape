package com.chromascape.web.aiplayer.routine.controller;

import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.service.BehaviorGraphService;
import com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService;
import com.chromascape.web.aiplayer.routine.service.RoutineCaptureIngestionService;
import com.chromascape.web.aiplayer.routine.service.RoutineCurationService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-routine")
public class RoutineController {
  private final CurrentSliceRunService runs; private final RoutineCaptureIngestionService captures; private final RoutineCurationService curation; private final BehaviorGraphService graphs;
  @Value("${ai-routine.capture.token:}") private String captureToken;
  public RoutineController(CurrentSliceRunService runs, RoutineCaptureIngestionService captures, RoutineCurationService curation, BehaviorGraphService graphs) { this.runs=runs;this.captures=captures;this.curation=curation;this.graphs=graphs; }
  @PostMapping("/runs/from-stabilization/{attemptId}") public ResponseEntity<Object> createRun(@PathVariable String attemptId,@RequestBody RoutineApiDtos.StartRunRequest request){return ResponseEntity.ok(runs.create(attemptId,request.identity()));}
  @GetMapping("/runs/{runId}") public ResponseEntity<Object> getRun(@PathVariable String runId){return ResponseEntity.ok(runs.get(runId));}
  @PostMapping("/capture/sessions") public ResponseEntity<Object> start(@RequestHeader(value="Authorization",required=false) String authorization,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody RoutineApiDtos.CaptureSessionRequest request){authorize(authorization);return ResponseEntity.ok(captures.start(request,key));}
  @GetMapping("/capture/sessions/{sessionId}") public ResponseEntity<Object> status(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable String sessionId){authorize(authorization);return ResponseEntity.ok(captures.status(sessionId));}
  @PostMapping("/capture/sessions/{sessionId}/ticks") public ResponseEntity<Object> ticks(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable String sessionId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody RoutineApiDtos.BatchRequest request){authorize(authorization);return ResponseEntity.ok(captures.ingestTicks(sessionId,request,key));}
  @PostMapping("/capture/sessions/{sessionId}/actions") public ResponseEntity<Object> actions(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable String sessionId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody RoutineApiDtos.BatchRequest request){authorize(authorization);return ResponseEntity.ok(captures.ingestActions(sessionId,request,key));}
  @PostMapping("/capture/sessions/{sessionId}/complete") public ResponseEntity<Object> complete(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable String sessionId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody RoutineApiDtos.CompleteRequest request){authorize(authorization);return ResponseEntity.ok(captures.complete(sessionId,request,key));}
  @PutMapping("/recordings/{recordingId}/curation") public ResponseEntity<Object> curate(@PathVariable String recordingId,@RequestBody RoutineApiDtos.CurationRequest request){return ResponseEntity.ok(curation.put(recordingId,request));}
  @PostMapping("/recordings/{recordingId}/graphs") public ResponseEntity<Object> graph(@PathVariable String recordingId){return ResponseEntity.ok(graphs.generate(recordingId));}
  @GetMapping("/graphs/{graphId}") public ResponseEntity<Object> getGraph(@PathVariable String graphId){return ResponseEntity.ok(graphs.get(graphId));}
  @PostMapping("/graphs/{graphId}/publish") public ResponseEntity<Object> publish(@PathVariable String graphId){return ResponseEntity.ok(graphs.publish(graphId));}
  private void authorize(String authorization) {
    if (captureToken == null || captureToken.isBlank() || authorization == null || !authorization.equals("Bearer " + captureToken)) {
      throw new RoutineCaptureIngestionService.RoutineCaptureException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "capture bearer token is invalid");
    }
  }
  @ExceptionHandler(RoutineCaptureIngestionService.RoutineCaptureException.class) public ResponseEntity<Object> captureError(RoutineCaptureIngestionService.RoutineCaptureException e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));}
  @ExceptionHandler(Exception.class) public ResponseEntity<Object> error(Exception e){return ResponseEntity.unprocessableEntity().body(Map.of("code","IDENTITY_MISMATCH","message",e.getMessage()));}
}
