package com.chromascape.web.replay.api;

import com.chromascape.web.replay.curation.ReplayCurationService;
import com.chromascape.web.replay.curation.dto.CreateCurationRequest;
import com.chromascape.web.replay.curation.dto.CurationResponse;
import com.chromascape.web.replay.curation.dto.UpdateCurationRequest;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/replay")
public class ReplayCurationController {

  private final ReplayCurationService curationService;
  private final ReplayPrincipalService principalService;

  public ReplayCurationController(
      ReplayCurationService curationService, ReplayPrincipalService principalService) {
    this.curationService = curationService;
    this.principalService = principalService;
  }

  @GetMapping("/recordings/{recordingId}/curations")
  public ResponseEntity<List<CurationResponse>> listCurations(
      @PathVariable String recordingId, Principal principal) {
    String user = principalService.requirePrincipal(principal);
    return ResponseEntity.ok(curationService.listCurations(recordingId, user));
  }

  @GetMapping("/curations/{curationId}")
  public ResponseEntity<CurationResponse> getCuration(
      @PathVariable UUID curationId, Principal principal) {
    String user = principalService.requirePrincipal(principal);
    return ResponseEntity.ok(curationService.getCuration(curationId, user));
  }

  @PostMapping("/recordings/{recordingId}/curations")
  public ResponseEntity<CurationResponse> create(
      @PathVariable String recordingId,
      @Valid @RequestBody CreateCurationRequest request,
      Principal principal) {
    String user = principalService.requirePrincipal(principal);
    CurationResponse created = curationService.create(recordingId, request, user);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PutMapping("/curations/{curationId}")
  public ResponseEntity<CurationResponse> update(
      @PathVariable UUID curationId,
      @Valid @RequestBody UpdateCurationRequest request,
      Principal principal) {
    String user = principalService.requirePrincipal(principal);
    return ResponseEntity.ok(curationService.update(curationId, request, user));
  }

  @PostMapping("/curations/{curationId}/publish")
  public ResponseEntity<CurationResponse> publish(
      @PathVariable UUID curationId, Principal principal) {
    String user = principalService.requirePrincipal(principal);
    return ResponseEntity.ok(curationService.publish(curationId, user));
  }
}
