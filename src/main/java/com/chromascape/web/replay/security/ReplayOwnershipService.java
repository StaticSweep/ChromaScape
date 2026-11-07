package com.chromascape.web.replay.security;

import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
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
    if (ownerId == null || ownerId.isBlank()) {
      throw notFound("Manifest not found");
    }
    if (ScenarioManifestV2Compiler.CONVERSION_CREATED_BY.equals(curation.getCreatedBy())) {
      requireOwnedConversionSource(manifest, curation, ownerId);
    } else if (!ownerId.equals(curation.getCreatedBy())) {
      throw notFound("Manifest not found");
    }
    return manifest;
  }

  private void requireOwnedConversionSource(
      ScenarioManifest manifest, RecordingCuration conversion, String ownerId) {
    JsonNode content = conversion.getContentJson();
    JsonNode document = manifest.getManifestJson();
    if (content == null
        || document == null
        || !conversion.isPublished()
        || !"recording-conversion/v1".equals(content.path("schema").asText())
        || !Objects.equals(manifest.getRecordingId(), conversion.getRecordingId())
        || !Objects.equals(manifest.getDebugSessionId(), conversion.getDebugSessionId())) {
      throw notFound("Manifest not found");
    }
    JsonNode selected = content.path("sourceCuration");
    if (!selected.isObject()
        || !selected.equals(document.path("curation").path("sourceCuration"))) {
      throw notFound("Manifest not found");
    }
    UUID sourceId;
    try {
      sourceId = UUID.fromString(selected.path("id").asText());
    } catch (IllegalArgumentException invalid) {
      throw notFound("Manifest not found");
    }
    RecordingCuration source =
        curationRepository.findById(sourceId).orElseThrow(() -> notFound("Manifest not found"));
    var canonical = new CanonicalProjectionService();
    if (!ownerId.equals(source.getCreatedBy())
        || !source.isPublished()
        || ScenarioManifestV2Compiler.CONVERSION_CREATED_BY.equals(source.getCreatedBy())
        || !Objects.equals(conversion.getRecordingId(), source.getRecordingId())
        || !Objects.equals(conversion.getDebugSessionId(), source.getDebugSessionId())
        || conversion.getRole() == null
        || conversion.getRole() != source.getRole()
        || !source.getRole().name().equals(selected.path("role").asText())
        || source.getVersion() != selected.path("version").asInt()
        || !Objects.equals(source.getContentChecksum(), selected.path("checksum").asText())
        || !Objects.equals(source.getContentJson(), selected.path("contentSnapshot"))
        || !canonical
            .sha256Hex(canonical.canonicalJsonBytes(content))
            .equals(conversion.getContentChecksum())
        || !canonical
            .sha256Hex(canonical.canonicalJsonBytes(source.getContentJson()))
            .equals(selected.path("canonicalContentChecksum").asText())) {
      throw notFound("Manifest not found");
    }
  }

  private static ReplayException notFound(String message) {
    return ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND, message);
  }
}
