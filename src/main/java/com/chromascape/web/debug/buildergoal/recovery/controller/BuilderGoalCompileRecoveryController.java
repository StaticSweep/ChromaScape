package com.chromascape.web.debug.buildergoal.recovery.controller;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.config.RecoveryOperatorContext;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryArtifact;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchApproval;
import com.chromascape.web.debug.buildergoal.recovery.service.BuilderGoalCompileRecoveryQueryService;
import com.chromascape.web.debug.buildergoal.recovery.service.BuilderGoalCompileRecoveryService;
import com.chromascape.web.debug.buildergoal.recovery.service.RecoveryAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run-scoped REST surface for Builder Goal compile recovery. The controller performs request
 * parsing, operator context extraction, and error-envelope mapping only; all workflow logic lives
 * in the recovery services.
 */
@RestController
@RequestMapping("/api/debug/builder-goals/runs/{iterationId}/compile-recovery")
public class BuilderGoalCompileRecoveryController {

  private final BuilderGoalCompileRecoveryService recoveryService;
  private final BuilderGoalCompileRecoveryQueryService queryService;
  private final RecoveryAuditService auditService;

  public BuilderGoalCompileRecoveryController(
      BuilderGoalCompileRecoveryService recoveryService,
      BuilderGoalCompileRecoveryQueryService queryService,
      RecoveryAuditService auditService) {
    this.recoveryService = recoveryService;
    this.queryService = queryService;
    this.auditService = auditService;
  }

  @GetMapping
  public ResponseEntity<Object> snapshot(@PathVariable String iterationId) {
    try {
      return ResponseEntity.ok(queryService.snapshot(iterationId));
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @PostMapping("/proposals")
  public ResponseEntity<Object> startAnalysis(
      @PathVariable String iterationId,
      @RequestBody(required = false) JsonNode payload,
      HttpServletRequest request) {
    try {
      Map<String, Object> body = readPayload(payload);
      Map<String, Object> response =
          recoveryService.startAnalysis(
              iterationId,
              text(body.get("codexSessionId")),
              text(body.get("idempotencyKey")),
              text(body.get("operatorContext")),
              operatorId(request));
      return ResponseEntity.accepted().body(response);
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @GetMapping("/proposals/{proposalId}")
  public ResponseEntity<Object> proposal(
      @PathVariable String iterationId, @PathVariable String proposalId) {
    try {
      return ResponseEntity.ok(queryService.proposalDetail(iterationId, proposalId));
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @PostMapping("/proposals/{proposalId}/approve")
  public ResponseEntity<Object> approve(
      @PathVariable String iterationId,
      @PathVariable String proposalId,
      @RequestBody(required = false) JsonNode payload,
      HttpServletRequest request) {
    try {
      Map<String, Object> body = readPayload(payload);
      Micro6PatchApproval approval =
          recoveryService.approve(
              iterationId,
              proposalId,
              text(body.get("proposalHash")),
              text(body.get("reason")),
              operatorId(request),
              operatorRole(request),
              environment());
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("approvalId", approval.getApprovalId());
      response.put("status", approval.getStatus());
      response.put("proposalId", approval.getProposalId());
      response.put("expiresAt", String.valueOf(approval.getExpiresAt()));
      return ResponseEntity.ok(response);
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @PostMapping("/proposals/{proposalId}/reject")
  public ResponseEntity<Object> reject(
      @PathVariable String iterationId,
      @PathVariable String proposalId,
      HttpServletRequest request) {
    return review(iterationId, proposalId, "reject", request);
  }

  @PostMapping("/proposals/{proposalId}/request-changes")
  public ResponseEntity<Object> requestChanges(
      @PathVariable String iterationId,
      @PathVariable String proposalId,
      HttpServletRequest request) {
    return review(iterationId, proposalId, "request-changes", request);
  }

  private ResponseEntity<Object> review(
      String iterationId, String proposalId, String action, HttpServletRequest request) {
    try {
      return ResponseEntity.ok(
          recoveryService.review(iterationId, proposalId, action, operatorId(request)));
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @PostMapping("/proposals/{proposalId}/validation-jobs")
  public ResponseEntity<Object> queueValidation(
      @PathVariable String iterationId,
      @PathVariable String proposalId,
      @RequestBody(required = false) JsonNode payload,
      HttpServletRequest request) {
    try {
      Map<String, Object> body = readPayload(payload);
      Map<String, Object> response =
          recoveryService.queueValidation(
              iterationId,
              proposalId,
              text(body.get("approvalId")),
              text(body.get("idempotencyKey")),
              operatorId(request));
      return ResponseEntity.accepted().body(response);
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @GetMapping("/validation-jobs/{jobId}")
  public ResponseEntity<Object> validationStatus(
      @PathVariable String iterationId, @PathVariable String jobId) {
    try {
      return ResponseEntity.ok(recoveryService.validationStatus(iterationId, jobId));
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @PostMapping("/validation-jobs/{jobId}/cancel")
  public ResponseEntity<Object> cancelValidation(
      @PathVariable String iterationId, @PathVariable String jobId, HttpServletRequest request) {
    try {
      return ResponseEntity.ok(
          recoveryService.cancelValidation(iterationId, jobId, operatorId(request)));
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @GetMapping("/artifacts/{artifactId}")
  public ResponseEntity<Object> artifact(
      @PathVariable String iterationId,
      @PathVariable String artifactId,
      @RequestParam(name = "view", defaultValue = "full") String view) {
    try {
      BuilderGoalRecoveryArtifact artifact = queryService.loadArtifact(iterationId, artifactId);
      String content = queryService.artifactText(artifact, view);
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("artifactId", artifact.getArtifactId());
      response.put("kind", artifact.getKind());
      response.put("sha256", artifact.getSha256());
      response.put("truncated", artifact.isTruncated());
      response.put("text", content);
      return ResponseEntity.ok(response);
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @GetMapping("/artifacts/{artifactId}/download")
  public ResponseEntity<Object> download(
      @PathVariable String iterationId, @PathVariable String artifactId) {
    try {
      BuilderGoalRecoveryArtifact artifact = queryService.loadArtifact(iterationId, artifactId);
      String content = queryService.artifactText(artifact, "full");
      auditService.record(
          "ARTIFACT_DOWNLOADED",
          null,
          null,
          iterationId,
          artifact.getIncidentId(),
          "artifact",
          artifactId,
          "OK",
          null,
          null);
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\"compile-recovery-"
                  + artifact.getKind().toLowerCase(java.util.Locale.ROOT)
                  + "-"
                  + artifact.getSha256().substring(7, 19)
                  + ".txt\"")
          .contentType(MediaType.TEXT_PLAIN)
          .body(content);
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  @PostMapping("/candidates/{candidateId}/reruns")
  public ResponseEntity<Object> rerun(
      @PathVariable String iterationId,
      @PathVariable String candidateId,
      @RequestBody(required = false) JsonNode payload,
      HttpServletRequest request) {
    try {
      Map<String, Object> body = readPayload(payload);
      Map<String, Object> response =
          recoveryService.requestRerun(
              iterationId,
              candidateId,
              text(body.get("candidateDigest")),
              text(body.get("idempotencyKey")),
              operatorId(request));
      return ResponseEntity.accepted().body(response);
    } catch (RecoveryException e) {
      return error(e, iterationId);
    }
  }

  private static String operatorId(HttpServletRequest request) {
    Object value = request.getAttribute(RecoveryOperatorContext.OPERATOR_ID_ATTRIBUTE);
    return value == null ? null : String.valueOf(value);
  }

  private static String operatorRole(HttpServletRequest request) {
    Object value = request.getAttribute(RecoveryOperatorContext.OPERATOR_ROLE_ATTRIBUTE);
    return value == null ? RecoveryOperatorContext.ROLE_OPERATOR : String.valueOf(value);
  }

  private static String environment() {
    String env = System.getenv("CHROMASCAPE_ENV");
    return env == null || env.isBlank() ? "development" : env;
  }

  private static Map<String, Object> readPayload(JsonNode payload) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (payload != null && payload.isObject()) {
      var fields = payload.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        body.put(
            entry.getKey(),
            entry.getValue().isValueNode() ? entry.getValue().asText(null) : entry.getValue());
      }
    }
    return body;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      return s;
    }
    return String.valueOf(value);
  }

  private ResponseEntity<Object> error(RecoveryException e, String iterationId) {
    auditService.record(
        "REQUEST_FAILED", null, null, iterationId, null, null, null, "FAILED", e.code(), null);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("code", e.code());
    envelope.put("message", e.getMessage());
    envelope.put("correlationId", RecoveryHashes.newId("corr"));
    envelope.put("retryable", e.retryable());
    return ResponseEntity.status(e.status()).body(envelope);
  }
}
