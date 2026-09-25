package com.chromascape.web.replay.security;

import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Centralizes ownership enforcement for replay resources. A resource owned by another user produces
 * a uniform 404 — existence is never disclosed across owners.
 */
@Service
public class ReplayOwnershipService {

  private final RecordingCurationRepository curationRepository;

  public ReplayOwnershipService(RecordingCurationRepository curationRepository) {
    this.curationRepository = curationRepository;
  }

  /**
   * Returns the curation only when it exists and belongs to {@code ownerId}; otherwise a uniform
   * 404.
   */
  public RecordingCuration requireOwnedCuration(UUID curationId, String ownerId) {
    RecordingCuration curation =
        curationRepository
            .findById(curationId)
            .orElseThrow(() -> notFound("Curation " + curationId + " not found"));
    if (ownerId == null || !ownerId.equals(curation.getCreatedBy())) {
      throw notFound("Curation " + curationId + " not found");
    }
    return curation;
  }

  /**
   * Returns the manifest only when its owning curation belongs to {@code ownerId}; otherwise a
   * uniform 404.
   */
  public ScenarioManifest requireOwnedManifest(ScenarioManifest manifest, String ownerId) {
    if (manifest == null) {
      throw notFound("Manifest not found");
    }
    RecordingCuration curation =
        curationRepository
            .findById(manifest.getCurationId())
            .orElseThrow(() -> notFound("Manifest not found"));
    if (ownerId == null || !ownerId.equals(curation.getCreatedBy())) {
      throw notFound("Manifest not found");
    }
    return manifest;
  }

  private static ReplayException notFound(String message) {
    return ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND, message);
  }
}
