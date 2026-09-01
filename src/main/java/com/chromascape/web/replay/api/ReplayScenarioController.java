package com.chromascape.web.replay.api;

import com.chromascape.web.replay.api.dto.CompileScenarioRequest;
import com.chromascape.web.replay.api.dto.ScenarioManifestResponse;
import com.chromascape.web.replay.compiler.ScenarioCompiler;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.security.ReplayOwnershipService;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/replay")
@Validated
public class ReplayScenarioController {

  private final ScenarioCompiler compiler;
  private final ScenarioManifestRepository manifestRepository;
  private final ReplayPrincipalService principalService;
  private final ReplayOwnershipService ownershipService;

  public ReplayScenarioController(
      ScenarioCompiler compiler,
      ScenarioManifestRepository manifestRepository,
      ReplayPrincipalService principalService,
      ReplayOwnershipService ownershipService) {
    this.compiler = compiler;
    this.manifestRepository = manifestRepository;
    this.principalService = principalService;
    this.ownershipService = ownershipService;
  }

  @PostMapping("/scenarios")
  public ResponseEntity<ScenarioManifestResponse> compile(
      @Valid @RequestBody CompileScenarioRequest request, Principal principal) {
    String user = principalService.requirePrincipal(principal);
    UUID curationId = parseUuid(request.curationId());
    // Ownership first: compiling someone else's curation is a uniform 404.
    ownershipService.requireOwnedCuration(curationId, user);
    ScenarioManifest manifest = compiler.compile(request.recordingId(), curationId, request.seed());
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(manifest));
  }

  @GetMapping("/scenarios/{manifestId}")
  public ResponseEntity<ScenarioManifestResponse> getManifest(
      @PathVariable UUID manifestId, Principal principal) {
    String user = principalService.requirePrincipal(principal);
    ScenarioManifest manifest =
        manifestRepository
            .findById(manifestId)
            .orElseThrow(
                () ->
                    com.chromascape.web.replay.error.ReplayException.notFound(
                        com.chromascape.web.replay.error.ReplayErrorCode.RECORDING_NOT_FOUND,
                        "Manifest not found"));
    // Owner derives from the owning curation.
    ownershipService.requireOwnedManifest(manifest, user);
    return ResponseEntity.ok(toResponse(manifest));
  }

  private static UUID parseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new com.chromascape.web.replay.error.ReplayException(
          com.chromascape.web.replay.error.ReplayErrorCode.VALIDATION_ERROR,
          org.springframework.http.HttpStatus.BAD_REQUEST,
          false,
          "curationId must be a valid UUID",
          null);
    }
  }

  private ScenarioManifestResponse toResponse(ScenarioManifest m) {
    return new ScenarioManifestResponse(
        m.getId(),
        m.getRecordingId(),
        m.getCurationId(),
        m.getManifestChecksum(),
        m.getCompleteness(),
        m.getPublishState(),
        m.getCreatedAt());
  }
}
