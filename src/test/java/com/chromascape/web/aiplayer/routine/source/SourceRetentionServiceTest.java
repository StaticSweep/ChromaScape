package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObject;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceRetentionServiceTest {
  @Test
  void retentionMarksAreMonotonicAndAudited() {
    SourceManifestReferenceRepository manifests = mock(SourceManifestReferenceRepository.class);
    SourceContentObjectRepository content = mock(SourceContentObjectRepository.class);
    SourceReadAuditService audit = mock(SourceReadAuditService.class);
    SourceManifestReference reference = new SourceManifestReference();
    reference.setManifestHash(
        SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText());
    reference.setTaskKey("EXAMPLE_TASK");
    reference.setSnapshotId(
        SourceManifestTestFixtures.manifest().path("source_snapshot_id").asText());
    when(manifests.findByManifestHash(reference.getManifestHash()))
        .thenReturn(Optional.of(reference));
    when(manifests.save(any(SourceManifestReference.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    byte[] bytes = "one".getBytes(StandardCharsets.UTF_8);
    String contentHash = SourceManifestTestFixtures.hash(bytes);
    SourceContentObject object = SourceContentObject.create(contentHash, bytes);
    when(content.findByContentHash(contentHash)).thenReturn(Optional.of(object));
    when(content.save(any(SourceContentObject.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    SourceRetentionService service = new SourceRetentionService(manifests, content, audit);
    service.prune(reference.getManifestHash(), "operator");
    var firstPruned = reference.getPrunedAt();
    service.prune(reference.getManifestHash(), "operator");
    service.redactContent(contentHash, "operator");

    assertNotNull(firstPruned);
    assertNotNull(reference.getPrunedAt());
    assertNotNull(object.getRedactedAt());
    verify(audit)
        .record(
            "operator",
            "retention_prune",
            null,
            null,
            "EXAMPLE_TASK",
            reference.getSnapshotId(),
            null,
            "SOURCE_PRUNED");
    verify(audit)
        .recordContent("operator", "retention_redact_content", contentHash, "SOURCE_REDACTED");
  }

  @Test
  void rejectsMalformedRetentionIdentities() {
    SourceRetentionService service =
        new SourceRetentionService(
            mock(SourceManifestReferenceRepository.class),
            mock(SourceContentObjectRepository.class),
            mock(SourceReadAuditService.class));
    assertThrows(SourceManifestException.class, () -> service.prune("not-a-hash"));
    assertThrows(SourceManifestException.class, () -> service.pruneContent("not-a-hash"));
  }
}
