package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.service.Micro6SourceManifestClient;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObject;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.chromascape.web.config.SourceTrustProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceDebuggerReadServiceTest {
  private static final KeyPair SIGNING_KEY = signingKey();

  @Test
  void servesOnlyVerifiedCachedContentAndSnakeCaseSymbols() {
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(SIGNING_KEY);
    ReadModel model = model(manifest);
    model.cache.put(
        SourceManifestTestFixtures.hash(
            SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8)),
        SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8));
    SourceDebuggerReadService service = model.service();

    Map<String, Object> file = service.file("run-1", SourceManifestTestFixtures.FILE_ID, "actor");
    assertEquals(SourceManifestTestFixtures.CONTENT, file.get("content"));
    assertEquals("src/main/java/com/example/Entry.java", file.get("relative_path"));

    Map<String, Object> symbols =
        service.symbols("run-1", SourceManifestTestFixtures.FILE_ID, 0, 10, "actor");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> values = (List<Map<String, Object>>) (List<?>) symbols.get("symbols");
    assertEquals(2, values.size());
    assertTrue(values.get(0).containsKey("source_symbol_id"));
    assertTrue(values.get(0).containsKey("start_byte"));
  }

  @Test
  void deniesSensitiveContentEvenWhenTheManifestAllowsTheFile() {
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(SIGNING_KEY);
    ((ObjectNode) manifest.path("files").get(0)).put("classification", "SENSITIVE");
    manifest.put("status", "REDACTED");
    SourceManifestTestFixtures.resealAndSign(manifest, SIGNING_KEY);
    ReadModel model = model(manifest);

    SourceManifestException exception =
        assertThrows(
            SourceManifestException.class,
            () -> model.service().file("run-1", SourceManifestTestFixtures.FILE_ID, "actor"));
    assertEquals(SourceManifestFailureCode.SOURCE_ACCESS_DENIED, exception.code());
  }

  @Test
  void durablePruneMarkerWinsOverAPreviouslyCachedCopy() {
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(SIGNING_KEY);
    byte[] bytes = SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8);
    SourceContentObject object =
        SourceContentObject.create(SourceManifestTestFixtures.hash(bytes), bytes);
    object.setPrunedAt(Instant.now());
    ReadModel model = model(manifest, object);
    model.cache.put(object.getContentHash(), bytes);

    SourceManifestException exception =
        assertThrows(
            SourceManifestException.class,
            () -> model.service().file("run-1", SourceManifestTestFixtures.FILE_ID, "actor"));
    assertEquals(SourceManifestFailureCode.SOURCE_PRUNED, exception.code());
  }

  @Test
  void durableRedactionMarkerWinsOverAPreviouslyCachedCopy() {
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(SIGNING_KEY);
    byte[] bytes = SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8);
    SourceContentObject object =
        SourceContentObject.create(SourceManifestTestFixtures.hash(bytes), bytes);
    object.setRedactedAt(Instant.now());
    ReadModel model = model(manifest, object);
    model.cache.put(object.getContentHash(), bytes);

    SourceManifestException exception =
        assertThrows(
            SourceManifestException.class,
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
    SourceContentObjectRepository content =
        durableObject == null ? null : mock(SourceContentObjectRepository.class);
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
    binding.setRepositoryId(manifest.path("repository").path("repository_id").asText());
    binding.setCommitId(manifest.path("repository").path("commit_id").asText());
    binding.setTreeId(manifest.path("repository").path("tree_id").asText());
    binding.setBuildId(manifest.path("build").path("build_id").asText());
    binding.setArtifactDigest(manifest.path("build").path("artifact_digest").asText());
    binding.setEnvironment(manifest.path("environment").asText());
    binding.setVerificationState("VERIFIED");
    binding.setSourceProvenance("VERIFIED");
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
    reference.setRetentionClass(manifest.path("retention_class").asText());
    reference.setConfidentiality(manifest.path("confidentiality").asText());
    reference.setEnvironment(manifest.path("environment").asText());
    reference.setVerificationState("VERIFIED");
    reference.setSourceProvenance("VERIFIED");
    reference.setManifestJson(SourceManifestCanonicalJson.canonical(manifest));
    when(bindings.findByRunId("run-1")).thenReturn(Optional.of(binding));
    when(manifests.findByManifestHash(manifest.path("source_manifest_hash").asText()))
        .thenReturn(Optional.of(reference));
    VerifiedSourceContentCache cache = new VerifiedSourceContentCache(50_000);
    SourceTrustProperties trust = trust();
    SourceDebuggerReadService service =
        new SourceDebuggerReadService(
            mapper,
            manifests,
            bindings,
            content,
            cache,
            mock(Micro6SourceManifestClient.class),
            mock(SourceReadAuditService.class),
            trust);
    return new ReadModel(service, cache);
  }

  private static SourceTrustProperties trust() {
    SourceTrustProperties trust = new SourceTrustProperties();
    trust.setPublicKeyBase64(
        Base64.getEncoder().encodeToString(SIGNING_KEY.getPublic().getEncoded()));
    trust.setPublicKeyId("test-key");
    trust.setEnvironment("production");
    return trust;
  }

  private static KeyPair signingKey() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException("Ed25519 unavailable", exception);
    }
  }

  private record ReadModel(SourceDebuggerReadService service, VerifiedSourceContentCache cache) {}
}
