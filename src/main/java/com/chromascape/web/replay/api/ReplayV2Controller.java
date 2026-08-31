package com.chromascape.web.replay.api;

import com.chromascape.web.replay.api.dto.ViewerSessionResponse;
import com.chromascape.web.replay.api.dto.ReplayMismatchPageResponse;
import com.chromascape.web.replay.api.dto.ReplayMismatchResponse;
import com.chromascape.web.replay.api.dto.XrspsPreflightResponse;
import com.chromascape.web.replay.api.dto.XrspsRunResponse;
import com.chromascape.web.replay.api.dto.XrspsTraceFramePageResponse;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.run.ReplayV2RunService;
import com.chromascape.web.replay.run.dto.CancelReplayRunResponse;
import com.chromascape.web.replay.run.dto.CreateXrspsRunRequest;
import com.chromascape.web.replay.run.dto.ReplayEventPageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public v2 endpoints for authoritative xRSPS replay and its read-only observer. */
@RestController
@RequestMapping("/api/replay/v2")
@Validated
public class ReplayV2Controller {

  private final ReplayV2RunService runService;

  public ReplayV2Controller(ReplayV2RunService runService) {
    this.runService = runService;
  }

  @GetMapping("/recordings/{recordingId}/preflight")
  public ResponseEntity<XrspsPreflightResponse> preflight(
      @PathVariable String recordingId,
      @RequestParam(required = false) ConversionMode conversionMode,
      Principal principal) {
    requirePrincipal(principal);
    return ResponseEntity.ok(runService.preflight(recordingId, conversionMode));
  }

  @PostMapping("/recordings/{recordingId}/xrsps-runs")
  public ResponseEntity<XrspsRunResponse> create(
      @PathVariable String recordingId,
      @Valid @RequestBody CreateXrspsRunRequest request,
      Principal principal) {
    ReplayV2RunService.Creation creation =
        runService.create(recordingId, request, requirePrincipal(principal));
    return ResponseEntity.status(creation.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(creation.response());
  }

  @GetMapping("/runs/{runId}")
  public ResponseEntity<XrspsRunResponse> get(
      @PathVariable UUID runId, Principal principal) {
    return ResponseEntity.ok(runService.get(runId, requirePrincipal(principal)));
  }

  @GetMapping("/runs/{runId}/events")
  public ResponseEntity<ReplayEventPageResponse> events(
      @PathVariable UUID runId,
      @RequestParam(required = false) @Min(0) Long after,
      @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
      Principal principal) {
    return ResponseEntity.ok(runService.events(runId, after, limit, requirePrincipal(principal)));
  }

  @GetMapping("/runs/{runId}/report")
  public ResponseEntity<?> report(@PathVariable UUID runId, Principal principal) {
    return ResponseEntity.ok(runService.report(runId, requirePrincipal(principal)));
  }

  @GetMapping("/runs/{runId}/mismatches")
  public ResponseEntity<ReplayMismatchPageResponse> mismatches(
      @PathVariable UUID runId,
      @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,
      Principal principal) {
    return ResponseEntity.ok(
        runService.mismatches(runId, requirePrincipal(principal), limit));
  }

  @GetMapping("/runs/{runId}/mismatches/first")
  public ResponseEntity<ReplayMismatchResponse> firstMismatch(
      @PathVariable UUID runId, Principal principal) {
    ReplayMismatchResponse mismatch =
        runService.firstMismatch(runId, requirePrincipal(principal));
    return mismatch == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(mismatch);
  }

  /** Download one verified immutable JSON artifact owned by the authenticated operator. */
  @GetMapping("/runs/{runId}/artifacts/{artifactName}")
  public ResponseEntity<byte[]> artifact(
      @PathVariable UUID runId,
      @PathVariable String artifactName,
      Principal principal) {
    ReplayV2RunService.ArtifactDownload artifact =
        runService.artifact(runId, artifactName, requirePrincipal(principal));
    return ResponseEntity.ok()
        .header(
            "Content-Disposition",
            "attachment; filename=\"" + artifact.filename() + "\"")
        .header("X-Replay-Artifact-Checksum", artifact.checksum())
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .contentType(org.springframework.http.MediaType.parseMediaType(artifact.mediaType()))
        .body(artifact.content());
  }

  @PostMapping("/runs/{runId}/cancel")
  public ResponseEntity<CancelReplayRunResponse> cancel(
      @PathVariable UUID runId, Principal principal) {
    CancelReplayRunResponse response = runService.cancel(runId, requirePrincipal(principal));
    return response.accepted()
        ? ResponseEntity.accepted().body(response)
        : ResponseEntity.ok(response);
  }

  @PostMapping("/runs/{runId}/viewer-session")
  public ResponseEntity<ViewerSessionResponse> viewerSession(
      @PathVariable UUID runId, Principal principal) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(runService.viewerSession(runId, requirePrincipal(principal)));
  }

  @GetMapping("/runs/{runId}/viewer/frames")
  public ResponseEntity<XrspsTraceFramePageResponse> frames(
      @PathVariable UUID runId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
      @RequestParam(required = false) String token,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      Principal principal) {
    String viewerToken = token;
    if ((viewerToken == null || viewerToken.isBlank())
        && authorization != null
        && authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
      viewerToken = authorization.substring("Bearer ".length()).trim();
    }
    String owner = principal == null ? null : principal.getName();
    return ResponseEntity.ok(runService.frames(runId, owner, viewerToken, cursor, limit));
  }

  private static String requirePrincipal(Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.UNAUTHORIZED,
          false,
          "Authentication is required",
          "Sign in before calling replay APIs");
    }
    return principal.getName();
  }
}
