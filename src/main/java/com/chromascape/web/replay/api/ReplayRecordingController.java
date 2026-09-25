package com.chromascape.web.replay.api;

import com.chromascape.web.replay.catalog.ReplayCatalogService;
import com.chromascape.web.replay.catalog.ReplayPreflightService;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightRequest;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightResponse;
import com.chromascape.web.replay.catalog.dto.ReplayRecordingCatalogResponse;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Recording catalog and preflight. Authentication is required, but the catalog itself remains
 * shared diagnostic evidence: recording_sessions has no owner column, so any authenticated operator
 * may read it.
 */
@RestController
@RequestMapping("/api/replay")
public class ReplayRecordingController {

  private final ReplayCatalogService catalogService;
  private final ReplayPreflightService preflightService;
  private final ReplayPrincipalService principalService;

  public ReplayRecordingController(
      ReplayCatalogService catalogService,
      ReplayPreflightService preflightService,
      ReplayPrincipalService principalService) {
    this.catalogService = catalogService;
    this.preflightService = preflightService;
    this.principalService = principalService;
  }

  @GetMapping("/recordings")
  public ResponseEntity<ReplayRecordingCatalogResponse> listRecordings(Principal principal) {
    principalService.requirePrincipal(principal);
    return ResponseEntity.ok(catalogService.catalog());
  }

  @GetMapping("/recordings/{recordingId}/preflight")
  public ResponseEntity<ReplayPreflightResponse> getPreflight(
      @PathVariable String recordingId, Principal principal) {
    principalService.requirePrincipal(principal);
    return ResponseEntity.ok(preflightService.preflight(recordingId, null));
  }

  @PostMapping("/recordings/{recordingId}/preflight")
  public ResponseEntity<ReplayPreflightResponse> postPreflight(
      @PathVariable String recordingId,
      @RequestBody(required = false) ReplayPreflightRequest request,
      Principal principal) {
    principalService.requirePrincipal(principal);
    return ResponseEntity.ok(preflightService.preflight(recordingId, request));
  }
}
