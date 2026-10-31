package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoImportMetadata;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoRequestIdentity;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEvent;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic intake registration. In one transaction the source, quarantined ORIGINAL reference, job,
 * acquisition event and quota charge become visible together, or none of them do. The (owner,
 * request) uniqueness constraints remain the final defense against two concurrent registrations.
 */
@Service
public class MouseVideoRegistrationService {

  public static final String UNQUALIFIED_MEDIA_TYPE = "application/octet-stream";

  private final MouseVideoSourceRepository sources;
  private final MouseVideoJobRepository jobs;
  private final MouseVideoArtifactRefRepository artifacts;
  private final MouseVideoJobEventRepository events;
  private final MouseVideoQuotaService quota;
  private final ObjectMapper mapper;
  private final MouseVideoAdmission admission;

  public MouseVideoRegistrationService(
      MouseVideoSourceRepository sources,
      MouseVideoJobRepository jobs,
      MouseVideoArtifactRefRepository artifacts,
      MouseVideoJobEventRepository events,
      MouseVideoQuotaService quota,
      MouseVideoProperties properties,
      ObjectMapper mapper) {
    this.sources = sources;
    this.jobs = jobs;
    this.artifacts = artifacts;
    this.events = events;
    this.quota = quota;
    this.mapper = mapper;
    this.admission = new MouseVideoAdmission(jobs, properties);
  }

  /** Result of a registration: the authoritative source/job and whether it was newly created. */
  public record Registration(MouseVideoSource source, MouseVideoJob job, boolean created) {}

  /**
   * Rejects already-full owners before an upload body is read. This is only a preflight: concurrent
   * arrivals are still serialized and checked by registerUpload. Existing request identities may
   * retry; registration verifies their body digest before returning any existing result.
   */
  @Transactional(readOnly = true)
  public void checkUploadAdmission(String owner, String requestId) {
    if (jobs.findByOwnerAndRequestId(owner, requestId).isEmpty()) {
      admission.requireCapacity(owner);
    }
  }

  @Transactional
  public Registration registerUpload(
      MouseVideoAccess.Actor actor,
      MouseVideoImportMetadata metadata,
      UUID reservationId,
      String fileChecksum,
      long fileSizeBytes) {
    String digest =
        MouseVideoRequestIdentity.forUpload(
            mapper, actor.owner(), metadata, fileChecksum, fileSizeBytes);
    admission.lock(actor.owner());
    artifacts.lockPublication();
    Optional<Registration> existing = reuseIfPresent(actor.owner(), metadata.requestId(), digest);
    if (existing.isPresent()) {
      quota.release(reservationId);
      return existing.get();
    }
    admission.requireCapacity(actor.owner());
    MouseVideoSource source = new MouseVideoSource(actor.owner(), MouseVideoSource.KIND_UPLOAD);
    applyMetadata(source, metadata);
    source.bindOriginal(fileChecksum, fileSizeBytes, UNQUALIFIED_MEDIA_TYPE);
    sources.saveAndFlush(source);

    MouseVideoArtifactRef original =
        new MouseVideoArtifactRef(
            actor.owner(),
            source.getId(),
            null,
            MouseVideoArtifactRef.ROLE_ORIGINAL,
            fileChecksum,
            fileSizeBytes,
            UNQUALIFIED_MEDIA_TYPE);
    original.quarantine();
    artifacts.save(original);

    MouseVideoJob job =
        jobs.save(
            new MouseVideoJob(
                source.getId(),
                actor.owner(),
                MouseVideoJob.KIND_IMPORT,
                metadata.requestId(),
                digest));
    record(job, "QUEUED");
    jobs.flush();
    quota.commit(reservationId, digest, fileChecksum, fileSizeBytes);
    return new Registration(source, job, true);
  }

  /** Registers a YouTube source and its acquisition job (bytes are acquired by the worker). */
  @Transactional
  public Registration registerYouTube(
      MouseVideoAccess.Actor actor, MouseVideoImportMetadata metadata, String providerId) {
    String digest =
        MouseVideoRequestIdentity.forYouTube(mapper, actor.owner(), metadata, providerId);
    admission.lock(actor.owner());
    Optional<Registration> existing = reuseIfPresent(actor.owner(), metadata.requestId(), digest);
    if (existing.isPresent()) {
      return existing.get();
    }
    admission.requireCapacity(actor.owner());
    MouseVideoSource source = new MouseVideoSource(actor.owner(), MouseVideoSource.KIND_YOUTUBE);
    applyMetadata(source, metadata);
    source.setProviderId(providerId);
    source.setProviderUrl("https://www.youtube.com/watch?v=" + providerId);
    sources.save(source);
    MouseVideoJob job =
        jobs.save(
            new MouseVideoJob(
                source.getId(),
                actor.owner(),
                MouseVideoJob.KIND_IMPORT,
                metadata.requestId(),
                digest));
    record(job, "QUEUED");
    return new Registration(source, job, true);
  }

  /** Attaches an acquired original (for example YouTube) to its source and quarantines it. */
  @Transactional
  public void attachAcquiredOriginal(
      UUID sourceId, String owner, String checksum, long sizeBytes, String declaredMediaType) {
    artifacts.lockPublication();
    MouseVideoSource source =
        sources
            .findByIdAndOwner(sourceId, owner)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND,
                        "Source is not available to this owner"));
    source.bindOriginal(checksum, sizeBytes, declaredMediaType);
    sources.save(source);
    if (artifacts
        .findBySourceIdAndRoleAndAnalysisIdIsNull(sourceId, MouseVideoArtifactRef.ROLE_ORIGINAL)
        .isEmpty()) {
      MouseVideoArtifactRef original =
          new MouseVideoArtifactRef(
              owner,
              sourceId,
              null,
              MouseVideoArtifactRef.ROLE_ORIGINAL,
              checksum,
              sizeBytes,
              declaredMediaType);
      original.quarantine();
      artifacts.save(original);
    }
  }

  private Optional<Registration> reuseIfPresent(String owner, String requestId, String digest) {
    return jobs.findByOwnerAndRequestId(owner, requestId)
        .map(
            job -> {
              if (!job.getRequestDigest().equals(digest)) {
                throw new MouseVideoException(
                    MouseVideoException.CODE_CONFLICT,
                    "This request identifier was already used with different content");
              }
              MouseVideoSource source =
                  sources
                      .findById(job.getSourceId())
                      .orElseThrow(
                          () ->
                              new MouseVideoException(
                                  MouseVideoException.CODE_NOT_FOUND,
                                  "Existing source is missing"));
              return new Registration(source, job, false);
            });
  }

  private void applyMetadata(MouseVideoSource source, MouseVideoImportMetadata metadata) {
    source.setDeclaredOrigin(metadata.declaredOrigin());
    source.setTaskKey(metadata.taskKey());
    source.setSkill(metadata.skill());
    source.setContextLabel(metadata.contextLabel());
    source.setPlaceLabel(metadata.placeLabel());
  }

  private void record(MouseVideoJob job, String type) {
    events.save(
        new MouseVideoJobEvent(
            job.getId(), job.getGeneration(), type, mapper.valueToTree(new LinkedHashMap<>())));
  }
}
