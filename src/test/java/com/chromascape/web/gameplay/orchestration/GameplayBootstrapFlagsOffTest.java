package com.chromascape.web.gameplay.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService;
import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore;
import com.chromascape.web.gameplay.bootstrap.BootstrapEvidenceManifestService;
import com.chromascape.web.gameplay.bootstrap.BootstrapProposalAssembler;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderCapabilityPreflight;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * SC-1498 flags-off proof: with every bootstrap flag at its default (false) the surface reports
 * disabled and every mutation refuses with its own stable code before touching a collaborator.
 */
class GameplayBootstrapFlagsOffTest {
  private static final String OPERATOR_KEY = "operator-test-key-at-least-32-bytes";

  private final BootstrapApprovalStore approvalStore = mock(BootstrapApprovalStore.class);
  private final BootstrapRecordingCoordinator recordingCoordinator =
      mock(BootstrapRecordingCoordinator.class);
  private final BootstrapEvidenceManifestService manifestService =
      mock(BootstrapEvidenceManifestService.class);
  private final AccountBuilderOrchestrationService orchestrationService =
      mock(AccountBuilderOrchestrationService.class);
  private final AccountBuilderCapabilityPreflight capabilityPreflight =
      mock(AccountBuilderCapabilityPreflight.class);
  private final GameplayProviderRuntime providerRuntime = mock(GameplayProviderRuntime.class);

  @Test
  void availabilityReportsDisabledAndMutationsRefuseBeforeAnyCollaborator() {
    var service = service(false, false, false);
    assertThat(service.publicAvailability()).containsEntry("enabled", false);

    assertRefused(
        service, "BOOTSTRAP_OPERATOR_DISABLED");
  }

  @Test
  void recordingGateRefusesWithItsOwnCodeOnceTheOperatorIsEnabled() {
    var service = service(true, false, false);
    assertRefused(service, "BOOTSTRAP_RECORDING_DISABLED");
  }

  @Test
  void micro6V2GateRefusesWithItsOwnCodeOnceRecordingIsEnabled() {
    var service = service(true, true, false);
    assertRefused(service, "MICRO6_V2_DISABLED");
  }

  private void assertRefused(BootstrapGameplayOperatorService service, String expectedCode) {
    assertThatThrownBy(
            () ->
                service.preview(
                    OPERATOR_KEY,
                    new BootstrapGameplayOperatorService.PreviewRequest(
                        "session-off", "turn-off", null, null, null)))
        .isInstanceOf(BootstrapGameplayOperatorService.OperatorException.class)
        .satisfies(
            exception ->
                assertThat(
                        ((BootstrapGameplayOperatorService.OperatorException) exception)
                            .failureCode())
                    .isEqualTo(expectedCode));
    verifyNoInteractions(
        approvalStore,
        recordingCoordinator,
        manifestService,
        orchestrationService,
        capabilityPreflight,
        providerRuntime);
  }

  private BootstrapGameplayOperatorService service(
      boolean enabled, boolean recordingEnabled, boolean micro6V2Enabled) {
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    CanonicalGameplayJson canonicalJson = new CanonicalGameplayJson(objectMapper);
    return new BootstrapGameplayOperatorService(
        canonicalJson,
        mock(HumanGameplayAuthorityService.class),
        mock(BootstrapProposalAssembler.class),
        approvalStore,
        recordingCoordinator,
        manifestService,
        orchestrationService,
        capabilityPreflight,
        providerRuntime,
        objectMapper,
        Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC),
        enabled,
        OPERATOR_KEY,
        recordingEnabled,
        micro6V2Enabled);
  }
}
