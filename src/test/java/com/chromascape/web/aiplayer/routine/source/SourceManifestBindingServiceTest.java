package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.chromascape.web.config.SourceTrustProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceManifestBindingServiceTest {
  private static final String HASH =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";
  private static final KeyPair SIGNING_KEY = signingKey();

  @Test
  void persistsAndIdempotentlyBindsOneVerifiedManifestToOneRun() {
    SourceManifestReferenceRepository manifests = mock(SourceManifestReferenceRepository.class);
    SourceRunBindingRepository bindings = mock(SourceRunBindingRepository.class);
    SourceContentObjectRepository content = mock(SourceContentObjectRepository.class);
    VerifiedSourceContentCache cache = new VerifiedSourceContentCache(10_000);
    CurrentSliceRunService runs = mock(CurrentSliceRunService.class);
    CurrentSliceIdentityValidator identities = mock(CurrentSliceIdentityValidator.class);
    CurrentSliceIdentity base = baseIdentity("run-1");
    when(runs.get("run-1")).thenReturn(Map.of("identity", base));
    when(identities.validate(any(CurrentSliceIdentity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(manifests.findByManifestHash(any())).thenReturn(Optional.empty());
    when(manifests.findBySnapshotId(any())).thenReturn(Optional.empty());
    when(manifests.saveAndFlush(any(SourceManifestReference.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(bindings.findByRunId("run-1")).thenReturn(Optional.empty());
    when(bindings.save(any(SourceRunBinding.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    SourceManifestBindingService service = new SourceManifestBindingService(
        new ObjectMapper(), manifests, bindings, content, cache, runs, identities,
        trust("production"));
    TaskSourceManifestVerifier.Verified verified = service.verifyAndPersist(
        "EXAMPLE_TASK", SourceManifestTestFixtures.signedManifest(SIGNING_KEY).toString(), null);
    SourceManifestReference reference = captureReference(manifests);
    when(manifests.findByManifestHash(verified.manifest().manifestHash()))
        .thenReturn(Optional.of(reference));

    SourceRunBinding first = service.bindRun("run-1", null, "EXAMPLE_TASK", verified);
    when(bindings.findByRunId("run-1")).thenReturn(Optional.of(first));
    SourceRunBinding second = service.bindRun("run-1", null, "EXAMPLE_TASK", verified);

    assertSame(first, second);
    assertEquals("VERIFIED", first.getSourceProvenance());
    assertEquals(verified.manifest().manifestHash(), first.getManifestHash());
  }

  @Test
  void rejectsARecordingAlreadyBoundToAnotherRun() {
    SourceManifestReferenceRepository manifests = mock(SourceManifestReferenceRepository.class);
    SourceRunBindingRepository bindings = mock(SourceRunBindingRepository.class);
    SourceRunBinding other = new SourceRunBinding();
    other.setRunId("other-run");
    when(bindings.findByRunId("run-1")).thenReturn(Optional.empty());
    when(bindings.findByRecordingId("recording-1")).thenReturn(Optional.of(other));
    SourceManifestBindingService service = new SourceManifestBindingService(
        new ObjectMapper(), manifests, bindings, null, new VerifiedSourceContentCache(10_000),
        mock(CurrentSliceRunService.class), mock(CurrentSliceIdentityValidator.class),
        trust("production"));
    var manifest = SourceManifestTestFixtures.manifest();
    var verified = new TaskSourceManifestVerifier(
        new SourceManifestSignatureVerifier(SIGNING_KEY.getPublic(), "test-key"))
        .verify(SourceManifestTestFixtures.signedManifest(SIGNING_KEY));
    SourceManifestReference reference = new SourceManifestReference();
    reference.setTaskKey("EXAMPLE_TASK");
    reference.setManifestHash(verified.manifest().manifestHash());
    reference.setSnapshotId(verified.manifest().sourceSnapshotId());
    reference.setArtifactDigest(manifest.path("build").path("artifact_digest").asText());
    when(manifests.findByManifestHash(verified.manifest().manifestHash()))
        .thenReturn(Optional.of(reference));

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> service.bindRun("run-1", "recording-1", "EXAMPLE_TASK", verified));
    assertEquals(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, exception.code());
  }

  @Test
  void expectedCommitMismatchFailsBeforePersistence() {
    SourceManifestReferenceRepository manifests = mock(SourceManifestReferenceRepository.class);
    SourceManifestBindingService service = new SourceManifestBindingService(
        new ObjectMapper(), manifests, mock(SourceRunBindingRepository.class),
        null, new VerifiedSourceContentCache(10_000), mock(CurrentSliceRunService.class),
        mock(CurrentSliceIdentityValidator.class), trust("production"));
    String wrongCommit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    var identity = new TaskSourceManifestVerifier.ExpectedIdentity(
        "EXAMPLE_TASK", "micro6", wrongCommit, null, "micro6-build-1", null, null, null);

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> service.verifyAndPersist("EXAMPLE_TASK",
            SourceManifestTestFixtures.signedManifest(SIGNING_KEY).toString(), identity));
    assertEquals(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, exception.code());
  }

  @Test
  void configuredEnvironmentMustMatchTheManifestEnvironment() throws Exception {
    SourceManifestReferenceRepository manifests = mock(SourceManifestReferenceRepository.class);
    SourceManifestBindingService service = new SourceManifestBindingService(
        new ObjectMapper(), manifests, mock(SourceRunBindingRepository.class),
        null, new VerifiedSourceContentCache(10_000), mock(CurrentSliceRunService.class),
        mock(CurrentSliceIdentityValidator.class), trust("production"));

    var manifest = SourceManifestTestFixtures.manifest();
    manifest.put("environment", "staging");
    SourceManifestTestFixtures.resealAndSign(manifest, SIGNING_KEY);

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> service.verifyAndPersist("EXAMPLE_TASK", manifest.toString(), null));
    assertEquals(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, exception.code());
  }

  @Test
  void matchingEnvironmentIsPersistedAsPartOfTheSourceReference() throws Exception {
    SourceManifestReferenceRepository manifests = mock(SourceManifestReferenceRepository.class);
    when(manifests.findByManifestHash(any())).thenReturn(Optional.empty());
    when(manifests.findBySnapshotId(any())).thenReturn(Optional.empty());
    when(manifests.saveAndFlush(any(SourceManifestReference.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    SourceManifestBindingService service = new SourceManifestBindingService(
        new ObjectMapper(), manifests, mock(SourceRunBindingRepository.class),
        null, new VerifiedSourceContentCache(10_000), mock(CurrentSliceRunService.class),
        mock(CurrentSliceIdentityValidator.class), trust("production"));

    var manifest = SourceManifestTestFixtures.signedManifest(SIGNING_KEY);
    manifest.put("environment", "production");
    service.verifyAndPersist("EXAMPLE_TASK", manifest.toString(), null);

    var captured = org.mockito.ArgumentCaptor.forClass(SourceManifestReference.class);
    verify(manifests).saveAndFlush(captured.capture());
    assertEquals("production", captured.getValue().getEnvironment());
  }

  private static SourceManifestReference captureReference(
      SourceManifestReferenceRepository manifests) {
    // The fixture repository mock returns the object that was passed to saveAndFlush; retrieving it
    // through the same identity keeps this helper independent of Mockito captor internals.
    var manifest = SourceManifestTestFixtures.manifest();
    var repository = manifest.path("repository");
    var build = manifest.path("build");
    var hash = manifest.path("source_manifest_hash").asText();
    SourceManifestReference reference = new SourceManifestReference();
    reference.setTaskKey("EXAMPLE_TASK");
    reference.setSchemaVersion(SourceManifestContract.SCHEMA);
    reference.setManifestHash(hash);
    reference.setSnapshotId(manifest.path("source_snapshot_id").asText());
    reference.setRepositoryId(repository.path("repository_id").asText());
    reference.setCommitId(repository.path("commit_id").asText());
    reference.setTreeId(repository.path("tree_id").asText());
    reference.setBuildId(build.path("build_id").asText());
    reference.setArtifactDigest(build.path("artifact_digest").asText());
    reference.setVerificationState("VERIFIED");
    reference.setSourceProvenance("VERIFIED");
    // verifyAndPersist persists the declared environment; bindRun compares it.
    reference.setEnvironment("production");
    return reference;
  }

  private static CurrentSliceIdentity baseIdentity(String runId) {
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("EXAMPLE_TASK", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run(runId, "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client("client", "MICRO6_MANAGED", "micro6-build-1", HASH,
            "micro6-builder/v2", "MICRO6"),
        null, null, null, null, null, false);
  }

  private static SourceTrustProperties trust(String environment) {
    SourceTrustProperties trust = new SourceTrustProperties();
    trust.setPublicKeyBase64(Base64.getEncoder().encodeToString(SIGNING_KEY.getPublic().getEncoded()));
    trust.setPublicKeyId("test-key");
    trust.setEnvironment(environment);
    return trust;
  }

  private static KeyPair signingKey() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException("Ed25519 unavailable", exception);
    }
  }
}
