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
import com.chromascape.web.aiplayer.routine.service.Micro6SourceManifestClient;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chromascape.web.config.SourceTrustProperties;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceManifestBindingServiceTest {
  private static final String HASH =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";

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
        mock(Micro6SourceManifestClient.class), new SourceTrustProperties());
    TaskSourceManifestVerifier.Verified verified = service.verifyAndPersist(
        "EXAMPLE_TASK", SourceManifestTestFixtures.manifest().toString(), null);
    SourceManifestReference reference = captureReference(manifests);
    when(manifests.findByManifestHash(verified.manifest().manifestHash()))
        .thenReturn(Optional.of(reference));

    SourceRunBinding first = service.bindRun("run-1", null, "EXAMPLE_TASK", verified);
    when(bindings.findByRunId("run-1")).thenReturn(Optional.of(first));
    SourceRunBinding second = service.bindRun("run-1", null, "EXAMPLE_TASK", verified);

    assertSame(first, second);
    assertEquals("UNSIGNED", first.getSourceProvenance());
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
        new ObjectMapper(), manifests, bindings, new VerifiedSourceContentCache(10_000),
        mock(CurrentSliceRunService.class), mock(CurrentSliceIdentityValidator.class));
    var manifest = SourceManifestTestFixtures.manifest();
    var verified = new TaskSourceManifestVerifier(false, false, null).verify(manifest);
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
        new VerifiedSourceContentCache(10_000), mock(CurrentSliceRunService.class),
        mock(CurrentSliceIdentityValidator.class));
    String wrongCommit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    var identity = new TaskSourceManifestVerifier.ExpectedIdentity(
        "EXAMPLE_TASK", "micro6", wrongCommit, null, "micro6-build-1", null, null, null);

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> service.verifyAndPersist("EXAMPLE_TASK",
            SourceManifestTestFixtures.manifest().toString(), identity));
    assertEquals(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, exception.code());
  }

  @Test
  void configuredEnvironmentMustMatchTheManifestEnvironment() throws Exception {
    SourceManifestReferenceRepository manifests = mock(SourceManifestReferenceRepository.class);
    SourceManifestBindingService service = new SourceManifestBindingService(
        new ObjectMapper(), manifests, mock(SourceRunBindingRepository.class),
        new VerifiedSourceContentCache(10_000), mock(CurrentSliceRunService.class),
        mock(CurrentSliceIdentityValidator.class));
    setEnvironment(service, "production");

    var manifest = SourceManifestTestFixtures.manifest();
    manifest.put("environment", "staging");
    SourceManifestTestFixtures.reseal(manifest);

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
        new VerifiedSourceContentCache(10_000), mock(CurrentSliceRunService.class),
        mock(CurrentSliceIdentityValidator.class));
    setEnvironment(service, "production");

    var manifest = SourceManifestTestFixtures.manifest();
    manifest.put("environment", "production");
    SourceManifestTestFixtures.reseal(manifest);
    service.verifyAndPersist("EXAMPLE_TASK", manifest.toString(), null);

    var captured = org.mockito.ArgumentCaptor.forClass(SourceManifestReference.class);
    verify(manifests).saveAndFlush(captured.capture());
    assertEquals("production", captured.getValue().getEnvironment());
  }

  private static void setEnvironment(SourceManifestBindingService service, String value)
      throws Exception {
    Field field = SourceManifestBindingService.class.getDeclaredField("trust");
    field.setAccessible(true);
    com.chromascape.web.config.SourceTrustProperties trust =
        new com.chromascape.web.config.SourceTrustProperties();
    trust.setEnvironment(value);
    field.set(service, trust);
  }

  private static SourceManifestReference captureReference(
      SourceManifestReferenceRepository manifests) {
    // The fixture repository mock returns the object that was passed to saveAndFlush; retrieving it
    // through the same identity keeps this helper independent of Mockito captor internals.
    var hash = SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText();
    SourceManifestReference reference = new SourceManifestReference();
    reference.setTaskKey("EXAMPLE_TASK");
    reference.setManifestHash(hash);
    reference.setSnapshotId(SourceManifestTestFixtures.manifest().path("source_snapshot_id").asText());
    reference.setArtifactDigest(SourceManifestTestFixtures.manifest().path("build").path("artifact_digest").asText());
    reference.setVerificationState("UNSIGNED");
    reference.setSourceProvenance("UNSIGNED");
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
}
