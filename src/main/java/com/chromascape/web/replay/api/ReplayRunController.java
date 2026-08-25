package com.chromascape.web.replay.api;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/replay")
@Validated
public class ReplayRunController {

    private final ReplayRunService runService;

    public ReplayRunController(ReplayRunService runService) {
        this.runService = runService;
    }

    @PostMapping("/runs")
    public ResponseEntity<ReplayRunResponse> create(
            @Valid @RequestBody CreateReplayRunRequest request, Principal principal) {
        String owner = requirePrincipal(principal);
        ReplayRunResponse response = runService.create(request, owner);
        HttpStatus status = response.terminalAt() == null ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/runs")
    public ResponseEntity<Page<ReplayRunSummary>> list(
            @RequestParam(required = false) ReplayMode mode,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Principal principal) {
        String owner = requirePrincipal(principal);
        return ResponseEntity.ok(runService.list(owner, mode, page, size));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ReplayRunResponse> get(@PathVariable UUID runId, Principal principal) {
        return ResponseEntity.ok(runService.getOwned(runId, requirePrincipal(principal)));
    }

    @GetMapping("/runs/{runId}/events")
    public ResponseEntity<ReplayEventPageResponse> events(
            @PathVariable UUID runId,
            @RequestParam(required = false) Long after,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            Principal principal) {
        String owner = requirePrincipal(principal);
        return ResponseEntity.ok(runService.events(runId, after, limit, owner));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<CancelReplayRunResponse> cancel(
            @PathVariable UUID runId, Principal principal) {
        String owner = requirePrincipal(principal);
        CancelReplayRunResponse response = runService.cancel(runId, owner);
        if (!response.accepted()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.accepted().body(response);
    }

    @PostMapping("/runs/{runId}/retry")
    public ResponseEntity<ReplayRunResponse> retry(
            @PathVariable UUID runId, @Valid @RequestBody RetryReplayRunRequest request,
            Principal principal) {
        String owner = requirePrincipal(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(runService.retry(runId, request, owner));
    }

    @PostMapping("/runs/{runId}/branches")
    public ResponseEntity<ReplayRunResponse> branch(
            @PathVariable UUID runId, @Valid @RequestBody BranchReplayRunRequest request,
            Principal principal) {
        String owner = requirePrincipal(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(runService.branch(runId, request, owner));
    }

    @GetMapping("/runs/{runId}/report")
    public ResponseEntity<ReplayReport> report(@PathVariable UUID runId, Principal principal) {
        return ResponseEntity.ok(runService.getReportForOwner(runId, requirePrincipal(principal)));
    }

    private static String requirePrincipal(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ReplayException(ReplayErrorCode.VALIDATION_ERROR,
                    HttpStatus.UNAUTHORIZED, false,
                    "Authentication is required", "Sign in before calling replay APIs");
        }
        return principal.getName();
    }
}
