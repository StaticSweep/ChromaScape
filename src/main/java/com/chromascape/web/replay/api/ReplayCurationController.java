package com.chromascape.web.replay.api;

import com.chromascape.web.replay.curation.ReplayCurationService;
import com.chromascape.web.replay.curation.dto.CreateCurationRequest;
import com.chromascape.web.replay.curation.dto.CurationResponse;
import com.chromascape.web.replay.curation.dto.UpdateCurationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/replay")
public class ReplayCurationController {

    private final ReplayCurationService curationService;

    public ReplayCurationController(ReplayCurationService curationService) {
        this.curationService = curationService;
    }

    @GetMapping("/recordings/{recordingId}/curations")
    public ResponseEntity<List<CurationResponse>> listCurations(@PathVariable String recordingId) {
        return ResponseEntity.ok(curationService.listCurations(recordingId));
    }

    @GetMapping("/curations/{curationId}")
    public ResponseEntity<CurationResponse> getCuration(@PathVariable UUID curationId) {
        return ResponseEntity.ok(curationService.getCuration(curationId));
    }

    @PostMapping("/recordings/{recordingId}/curations")
    public ResponseEntity<CurationResponse> create(
            @PathVariable String recordingId,
            @Valid @RequestBody CreateCurationRequest request,
            Principal principal) {
        String user = requirePrincipal(principal);
        CurationResponse created = curationService.create(recordingId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/curations/{curationId}")
    public ResponseEntity<CurationResponse> update(
            @PathVariable UUID curationId,
            @Valid @RequestBody UpdateCurationRequest request,
            Principal principal) {
        String user = requirePrincipal(principal);
        return ResponseEntity.ok(curationService.update(curationId, request, user));
    }

    @PostMapping("/curations/{curationId}/publish")
    public ResponseEntity<CurationResponse> publish(
            @PathVariable UUID curationId,
            Principal principal) {
        String user = requirePrincipal(principal);
        return ResponseEntity.ok(curationService.publish(curationId, user));
    }

    private static String requirePrincipal(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ReplayException(ReplayErrorCode.VALIDATION_ERROR,
                    org.springframework.http.HttpStatus.UNAUTHORIZED, false,
                    "Authentication is required", "Sign in before calling replay APIs");
        }
        return principal.getName();
    }
}