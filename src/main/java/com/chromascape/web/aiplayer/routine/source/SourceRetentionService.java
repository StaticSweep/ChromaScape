package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObject;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator-facing retention transitions for immutable source references. */
@Service
public class SourceRetentionService {
  private final SourceManifestReferenceRepository manifests;
  private final SourceContentObjectRepository contentObjects;
  private final SourceReadAuditService audit;

  @org.springframework.beans.factory.annotation.Autowired
  public SourceRetentionService(
      SourceManifestReferenceRepository manifests,
      SourceContentObjectRepository contentObjects,
      SourceReadAuditService audit) {
    this.manifests = manifests;
    this.contentObjects = contentObjects;
    this.audit = audit;
  }

  /**
   * Marks a manifest unavailable after retention expiry without deleting or reusing its identity.
   */
  @Transactional
  public SourceManifestReference prune(String manifestHash) {
    return prune(manifestHash, "system");
  }

  @Transactional
  public SourceManifestReference prune(String manifestHash, String actor) {
    SourceManifestReference reference = find(manifestHash);
    boolean transitioned = reference.getPrunedAt() == null;
    if (transitioned) reference.setPrunedAt(Instant.now());
    SourceManifestReference result = manifests.save(reference);
    if (transitioned) {
      audit.record(
          actor,
          "retention_prune",
          null,
          null,
          reference.getTaskKey(),
          reference.getSnapshotId(),
          null,
          "SOURCE_PRUNED");
    }
    return result;
  }

  /** Redacts a manifest's content while preserving the audit identity and historical binding. */
  @Transactional
  public SourceManifestReference redact(String manifestHash) {
    return redact(manifestHash, "system");
  }

  @Transactional
  public SourceManifestReference redact(String manifestHash, String actor) {
    SourceManifestReference reference = find(manifestHash);
    boolean transitioned = reference.getRedactedAt() == null;
    if (transitioned) reference.setRedactedAt(Instant.now());
    SourceManifestReference result = manifests.save(reference);
    if (transitioned) {
      audit.record(
          actor,
          "retention_redact",
          null,
          null,
          reference.getTaskKey(),
          reference.getSnapshotId(),
          null,
          "SOURCE_REDACTED");
    }
    return result;
  }

  /** Marks a shared content object pruned without deleting or reusing its content identity. */
  @Transactional
  public SourceContentObject pruneContent(String contentHash, String actor) {
    SourceContentObject object = content(contentHash);
    boolean transitioned = object.getPrunedAt() == null;
    if (transitioned) object.setPrunedAt(Instant.now());
    SourceContentObject result = contentObjects.save(object);
    if (transitioned) {
      audit.recordContent(actor, "retention_prune_content", contentHash, "SOURCE_PRUNED");
    }
    return result;
  }

  public SourceContentObject pruneContent(String contentHash) {
    return pruneContent(contentHash, "system");
  }

  /** Redacts a shared content object while retaining its immutable hash and audit identity. */
  @Transactional
  public SourceContentObject redactContent(String contentHash, String actor) {
    SourceContentObject object = content(contentHash);
    boolean transitioned = object.getRedactedAt() == null;
    if (transitioned) object.setRedactedAt(Instant.now());
    SourceContentObject result = contentObjects.save(object);
    if (transitioned) {
      audit.recordContent(actor, "retention_redact_content", contentHash, "SOURCE_REDACTED");
    }
    return result;
  }

  public SourceContentObject redactContent(String contentHash) {
    return redactContent(contentHash, "system");
  }

  private SourceManifestReference find(String manifestHash) {
    if (!SourceManifestContract.isHash(manifestHash)) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          HttpStatus.BAD_REQUEST,
          "manifest identity is invalid");
    }
    return manifests
        .findByManifestHash(manifestHash)
        .orElseThrow(
            () ->
                new SourceManifestException(
                    SourceManifestFailureCode.SOURCE_UNAVAILABLE,
                    HttpStatus.NOT_FOUND,
                    "source manifest is unavailable"));
  }

  private SourceContentObject content(String contentHash) {
    if (!SourceManifestContract.isHash(contentHash)) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          HttpStatus.BAD_REQUEST,
          "content identity is invalid");
    }
    if (contentObjects == null) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          "source content retention is unavailable");
    }
    return contentObjects
        .findByContentHash(contentHash)
        .orElseThrow(
            () ->
                new SourceManifestException(
                    SourceManifestFailureCode.SOURCE_UNAVAILABLE,
                    HttpStatus.NOT_FOUND,
                    "source content is unavailable"));
  }
}
