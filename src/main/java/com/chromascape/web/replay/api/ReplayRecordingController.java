package com.chromascape.web.replay.api;

import com.chromascape.web.replay.catalog.ReplayCatalogService;
import com.chromascape.web.replay.catalog.ReplayPreflightService;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightRequest;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightResponse;
import com.chromascape.web.replay.catalog.dto.ReplayRecordingCatalogResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/replay")
public class ReplayRecordingController {

    private final ReplayCatalogService catalogService;
    private final ReplayPreflightService preflightService;

    public ReplayRecordingController(ReplayCatalogService catalogService,
                                     ReplayPreflightService preflightService) {
        this.catalogService = catalogService;
        this.preflightService = preflightService;
    }

    @GetMapping("/recordings")
    public ResponseEntity<ReplayRecordingCatalogResponse> listRecordings() {
        return ResponseEntity.ok(catalogService.catalog());
    }

    @GetMapping("/recordings/{recordingId}/preflight")
    public ResponseEntity<ReplayPreflightResponse> getPreflight(@PathVariable String recordingId) {
        return ResponseEntity.ok(preflightService.preflight(recordingId, null));
    }

    @PostMapping("/recordings/{recordingId}/preflight")
    public ResponseEntity<ReplayPreflightResponse> postPreflight(
            @PathVariable String recordingId,
            @RequestBody(required = false) ReplayPreflightRequest request) {
        return ResponseEntity.ok(preflightService.preflight(recordingId, request));
    }
}