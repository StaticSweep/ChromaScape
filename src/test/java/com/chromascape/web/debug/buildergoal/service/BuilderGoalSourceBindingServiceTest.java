package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.service.Micro6SourceManifestClient;
import com.chromascape.web.aiplayer.routine.source.SourceManifestBindingService;
import com.chromascape.web.aiplayer.routine.source.TaskSourceManifestVerifier;
import com.chromascape.web.config.SourceTrustProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BuilderGoalSourceBindingServiceTest {

  @Mock private Micro6SourceManifestClient upstream;
  @Mock private SourceManifestBindingService bindings;
  @Mock private PlatformTransactionManager transactionManager;

  private SourceTrustProperties configuredTrust() {
    SourceTrustProperties trust = new SourceTrustProperties();
    trust.setPublicKeyBase64("MCowBQYDK2VwAyEAoLTKQQMU6E3r1pw/o33YdFhNPa5tyImn+t0KsM8d+iU=");
    trust.setPublicKeyId("sc810-dev-ed25519-611ab9dd95a518af");
    trust.setEnvironment("local-development");
    return trust;
  }

  private BuilderGoalSourceBindingService service(SourceTrustProperties trust) {
    TransactionStatus status = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any())).thenReturn(status);
    return new BuilderGoalSourceBindingService(upstream, bindings, trust, transactionManager);
  }

  @Test
  void reportsNotConfiguredWhenTrustIsIncomplete() {
    BuilderGoalSourceBindingService service = service(new SourceTrustProperties());

    BuilderGoalSourceBindingService.BindingOutcome outcome =
        service.bindRun("run-1", null, "FISHING_TRAINING", null, null);

    assertThat(outcome.state()).isEqualTo(BuilderGoalSourceBindingService.STATE_NOT_CONFIGURED);
    assertThat(outcome.bound()).isFalse();
    // Without trust no signature can be checked, so nothing may be fetched or written.
    verify(upstream, never()).manifest(anyString());
    verify(bindings, never()).bindRun(anyString(), any(), anyString(), any());
  }

  @Test
  void trustConfiguredRequiresAllThreeValues() {
    assertThat(service(configuredTrust()).trustConfigured()).isTrue();

    SourceTrustProperties missingEnvironment = configuredTrust();
    missingEnvironment.setEnvironment("");
    assertThat(service(missingEnvironment).trustConfigured()).isFalse();

    SourceTrustProperties missingKeyId = configuredTrust();
    missingKeyId.setPublicKeyId("");
    assertThat(service(missingKeyId).trustConfigured()).isFalse();
  }

  @Test
  void rejectsMissingRunOrTaskIdentity() {
    BuilderGoalSourceBindingService service = service(configuredTrust());

    assertThat(service.bindRun("", null, "FISHING_TRAINING", null, null).state())
        .isEqualTo(BuilderGoalSourceBindingService.STATE_FAILED);
    assertThat(service.bindRun("run-1", null, "  ", null, null).state())
        .isEqualTo(BuilderGoalSourceBindingService.STATE_FAILED);
    verify(upstream, never()).manifest(anyString());
  }

  @Test
  void reportsFailedWhenManifestCannotBeFetched() {
    BuilderGoalSourceBindingService service = service(configuredTrust());
    when(upstream.manifest("FISHING_TRAINING")).thenThrow(new IllegalStateException("unreachable"));

    BuilderGoalSourceBindingService.BindingOutcome outcome =
        service.bindRun("run-1", null, "FISHING_TRAINING", null, null);

    assertThat(outcome.state()).isEqualTo(BuilderGoalSourceBindingService.STATE_FAILED);
    verify(bindings, never()).bindRun(anyString(), any(), anyString(), any());
  }

  @Test
  void pinsBuildAndArtifactWhenTheExecutorAttestedThem() {
    BuilderGoalSourceBindingService service = service(configuredTrust());
    when(upstream.manifest("FISHING_TRAINING")).thenReturn("{}");

    service.bindRun(
        "run-1",
        null,
        "FISHING_TRAINING",
        "04718fe3bf0f86a27f8eb9d18d769f721748727d",
        "sha256:9c5f");

    ArgumentCaptor<TaskSourceManifestVerifier.ExpectedIdentity> expected =
        ArgumentCaptor.forClass(TaskSourceManifestVerifier.ExpectedIdentity.class);
    verify(bindings).verifyAndPersist(anyString(), anyString(), expected.capture());
    assertThat(expected.getValue().buildId()).isEqualTo("04718fe3bf0f86a27f8eb9d18d769f721748727d");
    assertThat(expected.getValue().artifactDigest()).isEqualTo("sha256:9c5f");
  }

  @Test
  void doesNotFabricateAnExpectedIdentityWhenNothingWasAttested() {
    BuilderGoalSourceBindingService service = service(configuredTrust());
    when(upstream.manifest("FISHING_TRAINING")).thenReturn("{}");

    service.bindRun("run-1", null, "FISHING_TRAINING", null, null);

    ArgumentCaptor<TaskSourceManifestVerifier.ExpectedIdentity> expected =
        ArgumentCaptor.forClass(TaskSourceManifestVerifier.ExpectedIdentity.class);
    verify(bindings).verifyAndPersist(anyString(), anyString(), expected.capture());
    // Echoing the manifest's own build/artifact back as "expected" would be a tautology that
    // reads like verification in the evidence. The task key is pinned; nothing else is invented.
    assertThat(expected.getValue().taskKey()).isEqualTo("FISHING_TRAINING");
    assertThat(expected.getValue().buildId()).isNull();
    assertThat(expected.getValue().artifactDigest()).isNull();
    assertThat(expected.getValue().snapshotId()).isNull();
    assertThat(expected.getValue().manifestHash()).isNull();
  }
}
