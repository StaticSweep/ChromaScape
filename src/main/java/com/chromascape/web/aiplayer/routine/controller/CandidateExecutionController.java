package com.chromascape.web.aiplayer.routine.controller;

import com.chromascape.web.aiplayer.routine.service.CandidateExecutionJobService;
import com.chromascape.web.aiplayer.routine.service.PatchCandidateWorkflowService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The SC-815 request/status/control surface for executing an approved candidate (SC-2106 R9.2,
 * R9.5). A job is named by the caller's idempotency key, so a refresh, another process or a
 * restarted server reads the same job. Disabled unless {@code sc2106.execution.enabled=true}.
 */
@RestController
@RequestMapping("/api/v1/ai-routine/patches/{proposalId}/executions")
public class CandidateExecutionController {
  private final CandidateExecutionJobService service;

  public CandidateExecutionController(CandidateExecutionJobService service) {
    this.service = service;
  }

  /** Reserves the job and starts it in the background; a repeat returns the same job. */
  @PostMapping
  public ResponseEntity<Object> submit(
      @PathVariable String proposalId, @RequestBody CandidateExecutionJobService.JobSpec spec) {
    CandidateExecutionJobService.JobStatus status = service.submit(proposalId, spec);
    return accepted(proposalId, status);
  }

  @GetMapping("/{idempotencyKey}")
  public ResponseEntity<Object> status(
      @PathVariable String proposalId, @PathVariable String idempotencyKey) {
    return ResponseEntity.ok(service.status(proposalId, idempotencyKey));
  }

  @PostMapping("/{idempotencyKey}/cancel")
  public ResponseEntity<Object> cancel(
      @PathVariable String proposalId, @PathVariable String idempotencyKey) {
    return ResponseEntity.ok(service.requestCancel(proposalId, idempotencyKey));
  }

  @PostMapping("/{idempotencyKey}/pause")
  public ResponseEntity<Object> pause(
      @PathVariable String proposalId, @PathVariable String idempotencyKey) {
    return ResponseEntity.ok(service.requestPause(proposalId, idempotencyKey, true));
  }

  @PostMapping("/{idempotencyKey}/resume")
  public ResponseEntity<Object> resume(
      @PathVariable String proposalId, @PathVariable String idempotencyKey) {
    return ResponseEntity.ok(service.requestPause(proposalId, idempotencyKey, false));
  }

  /** Explicitly recovers the named job as a new job under the body's own idempotency key. */
  @PostMapping("/{idempotencyKey}/recover")
  public ResponseEntity<Object> recover(
      @PathVariable String proposalId,
      @PathVariable String idempotencyKey,
      @RequestBody CandidateExecutionJobService.JobSpec spec) {
    return accepted(proposalId, service.submitRecovery(proposalId, idempotencyKey, spec));
  }

  private static ResponseEntity<Object> accepted(
      String proposalId, CandidateExecutionJobService.JobStatus status) {
    return ResponseEntity.accepted()
        .header(
            "Location",
            "/api/v1/ai-routine/patches/"
                + URLEncoder.encode(proposalId, StandardCharsets.UTF_8)
                + "/executions/"
                + URLEncoder.encode(status.idempotencyKey(), StandardCharsets.UTF_8))
        .body(status);
  }

  @ExceptionHandler(PatchCandidateWorkflowService.PatchWorkflowException.class)
  public ResponseEntity<Object> error(PatchCandidateWorkflowService.PatchWorkflowException e) {
    return ResponseEntity.status(e.status())
        .body(Map.of("code", e.code(), "message", e.getMessage()));
  }
}
