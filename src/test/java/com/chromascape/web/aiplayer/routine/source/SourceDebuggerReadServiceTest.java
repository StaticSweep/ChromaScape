package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.routine.service.Micro6SourceManifestClient;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObject;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chromascape.web.config.SourceTrustProperties;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceDebuggerReadServiceTest {
  @Test
  void servesOnlyVerifiedCachedContentAndSnakeCaseSymbols() {
    ObjectNode manifest = SourceManifestTestFixtures.manifest();
    ReadModel model = model(manifest);
    model.cache.put(
        SourceManifestTestFixtures.hash(SourceManifestTestFixtures.CONTENT
            .getBytes(StandardCharsets.UTF_8)),
        SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8));
    SourceDebuggerReadService service = model.service();

    Map<String, Object> file = service.file("run-1", SourceManifestTestFixtures.FILE_ID, "actor");
    assertEquals(SourceManifestTestFixtures.CONTENT, file.get("content"));
    assertEquals("src/main/java/com/example/Entry.java", file.get("relative_path"));

    Map<String, Object> symbols = service.symbols(
        "run-1", SourceManifestTestFixtures.FILE_ID, 0, 10, "actor");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> values = (List<Map<String, Object>>) (List<?>) symbols.get("symbols");
    assertEquals(2, values.size());
    assertTrue(values.get(0).containsKey("source_symbol_id"));
    assertTrue(values.get(0).containsKey("start_byte"));
  }

  @Test
  void deniesSensitiveContentEvenWhenTheManifestAllowsTheFile() {
    ObjectNode manifest = SourceManifestTestFixtures.manifest();
    ((ObjectNode) manifest.path("files").get(0)).put("classification", "SENSITIVE");
    SourceManifestTestFixtures.reseal(manifest);
    ReadModel model = model(manifest);

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> model.service().file("run-1", SourceManifestTestFixtures.FILE_ID, "actor"));
    assertEquals(SourceManifestFailureCode.SOURCE_ACCESS_DENIED, exception.code());
  }

  @Test
  void durablePruneMarkerWinsOverAPreviouslyCachedCopy() {
    ObjectNode manifest = SourceManifestTestFixtures.manifest();
    byte[] bytes = SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8);
    SourceContentObject object = SourceContentObject.create(
        SourceManifestTestFixtures.hash(bytes), bytes);
    object.setPrunedAt(Instant.now());
    ReadModel model = model(manifest, object);
    model.cache.put(object.getContentHash(), bytes);

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> model.service().file("run-1", SourceManifestTestFixtures.FILE_ID, "actor"));
    assertEquals(SourceManifestFailureCode.SOURCE_PRUNED, exception.code());
  }

  @Test
  void durableRedactionMarkerWinsOverAPreviouslyCachedCopy() {
    ObjectNode manifest = SourceManifestTestFixtures.manifest();
    byte[] bytes = SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8);
    SourceContentObject object = SourceContentObject.create(
        SourceManifestTestFixtures.hash(bytes), bytes);
    object.setRedactedAt(Instant.now());
    ReadModel model = model(manifest, object);
    model.cache.put(object.getContentHash(), bytes);

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> model.service().file("run-1", SourceManifestTestFixtures.FILE_ID, "actor"));
    assertEquals(SourceManifestFailureCode.SOURCE_REDACTED, exception.code());
  }

  private static ReadModel model(ObjectNode manifest) {
    return model(manifest, null);
  }

  private static ReadModel model(ObjectNode manifest, SourceContentObject durableObject) {
    ObjectMapper mapper = new ObjectMapper();
    SourceManifestReferenceRepository manifests = mock(SourceManifestReferenceRepository.class);
    SourceRunBindingRepository bindings = mock(SourceRunBindingRepository.class);
    SourceContentObjectRepository content = durableObject == null
        ? null : mock(SourceContentObjectRepository.class);
    if (content != null) {
      when(content.findByContentHash(durableObject.getContentHash()))
          .thenReturn(Optional.of(durableObject));
    }
    SourceRunBinding binding = new SourceRunBinding();
    binding.setRunId("run-1");
    binding.setRecordingId("recording-1");
    binding.setTaskKey(manifest.path("task_key").asText());
    binding.setManifestHash(manifest.path("source_manifest_hash").asText());
    binding.setSnapshotId(manifest.path("source_snapshot_id").asText());
    binding.setArtifactDigest(manifest.path("build").path("artifact_digest").asText());
    binding.setVerificationState("UNSIGNED");
    binding.setSourceProvenance("UNSIGNED");
    SourceManifestReference reference = new SourceManifestReference();
    reference.setTaskKey(manifest.path("task_key").asText());
    reference.setSchemaVersion(manifest.path("schema").asText());
    reference.setManifestHash(manifest.path("source_manifest_hash").asText());
    reference.setSnapshotId(manifest.path("source_snapshot_id").asText());
    reference.setRepositoryId(manifest.path("repository").path("repository_id").asText());
    reference.setCommitId(manifest.path("repository").path("commit_id").asText());
    reference.setTreeId(manifest.path("repository").path("tree_id").asText());
    reference.setBuildId(manifest.path("build").path("build_id").asText());
    reference.setArtifactDigest(manifest.path("build").path("artifact_digest").asText());
    reference.setVerificationState("UNSIGNED");
    reference.setSourceProvenance("UNSIGNED");
    reference.setManifestJson(CanonicalJsonHasher.canonical(manifest));
    when(bindings.findByRunId("run-1")).thenReturn(Optional.of(binding));
    when(manifests.findByManifestHash(manifest.path("source_manifest_hash").asText()))
        .thenReturn(Optional.of(reference));
    VerifiedSourceContentCache cache = new VerifiedSourceContentCache(50_000);
    SourceDebuggerReadService service = content == null
        ? new SourceDebuggerReadService(
            mapper, manifests, bindings, cache, mock(Micro6SourceManifestClient.class),
            new SourceReadAuditService())
        : new SourceDebuggerReadService(
            mapper, manifests, bindings, content, cache, mock(Micro6SourceManifestClient.class),
            new SourceReadAuditService(), new SourceTrustProperties());
    return new ReadModel(service, cache);
  }

  private record ReadModel(SourceDebuggerReadService service, VerifiedSourceContentCache cache) {}
}
