package com.chromascape.web.gameplay.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService;
import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore;
import com.chromascape.web.gameplay.bootstrap.BootstrapEvidenceManifestService;
import com.chromascape.web.gameplay.bootstrap.BootstrapProposalAssembler;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.ApprovalRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.DispatchRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.OperatorException;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.PreviewRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderCapabilityPreflight;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TaskCapability;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff.PersistedDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BootstrapGameplayOperatorServiceTest {
  private static final String AUTHORITY_SECRET = "authority-test-secret-at-least-32-bytes";
  private static final String OPERATOR_KEY = "operator-test-key-at-least-32-bytes";
  private static final Instant NOW = Instant.parse("2026-08-30T12:01:00Z");

  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private JsonNode fixtures;

  @Mock private BootstrapApprovalStore approvalStore;
  @Mock private BootstrapRecordingCoordinator recordingCoordinator;
  @Mock private BootstrapEvidenceManifestService manifestService;
  @Mock private AccountBuilderOrchestrationService orchestrationService;
  @Mock private AccountBuilderCapabilityPreflight capabilityPreflight;

  @Mock
  private com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime providerRuntime;

  private BootstrapGameplayOperatorService service;
  private HumanGameplayAuthorityService authorityService;
  private BootstrapProposalAssembler assembler;
  private Clock fixed;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    fixtures =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    fixed = Clock.fixed(NOW, ZoneOffset.UTC);
    authorityService = new HumanGameplayAuthorityService(canonicalJson, fixed, AUTHORITY_SECRET);
    FakeHandoff handoff = new FakeHandoff();
    assembler = new BootstrapProposalAssembler(handoff, authorityService, canonicalJson);
    lenient()
        .when(capabilityPreflight.probe(any()))
        .thenReturn(eligibleSnapshot());
    service = service(true, true);
  }

  private BootstrapGameplayOperatorService service(
      boolean recordingEnabled, boolean micro6V2Enabled) {
    return new BootstrapGameplayOperatorService(
        canonicalJson,
        authorityService,
        assembler,
        approvalStore,
        recordingCoordinator,
        manifestService,
        orchestrationService,
        capabilityPreflight,
        providerRuntime,
        objectMapper,
        fixed,
        true,
        OPERATOR_KEY,
        recordingEnabled,
        micro6V2Enabled);
  }

  private CapabilitySnapshot eligibleSnapshot() {
    return new CapabilitySnapshot(
        "sha256:" + "c".repeat(64),
        "sha256:" + "a".repeat(64),
        "build-v1",
        7,
        9,
        NOW,
        NOW.plusSeconds(60),
        true,
        java.util.List.of(
            new TaskCapability(
                "FISHING_TRAINING",
                true,
                "sha256:" + "d".repeat(64),
                "build-sc1429",
                List.of())));
  }

  @Test
  void anAbsentCatalogTaskRefusesBeforeAnyApprovalOrRecordingSideEffect() {
    lenient()
        .when(capabilityPreflight.probe(any()))
        .thenReturn(
            new CapabilitySnapshot(
                "sha256:" + "c".repeat(64),
                "sha256:" + "a".repeat(64),
                "build-v1",
                7,
                9,
                NOW,
                NOW.plusSeconds(60),
                true,
                java.util.List.of()));

    assertThatDispatchRefusesWithoutSideEffects("TASK_INELIGIBLE");
  }

  @Test
  void anIneligibleCatalogTaskRefusesBeforeAnyApprovalOrRecordingSideEffect() {
    lenient()
        .when(capabilityPreflight.probe(any()))
        .thenReturn(
            new CapabilitySnapshot(
                "sha256:" + "c".repeat(64),
                "sha256:" + "a".repeat(64),
                "build-v1",
                7,
                9,
                NOW,
                NOW.plusSeconds(60),
                true,
                java.util.List.of(
                    new TaskCapability(
                        "FISHING_TRAINING",
                        false,
                        "sha256:" + "d".repeat(64),
                        "build-v1",
                        List.of("TASK_MAINTENANCE")))));

    assertThatDispatchRefusesWithoutSideEffects("TASK_INELIGIBLE");
  }

  @Test
  void anEligibleTaskWithAMismatchedSchemaDigestRefusesBeforeAnySideEffect() {
    lenient()
        .when(capabilityPreflight.probe(any()))
        .thenReturn(
            new CapabilitySnapshot(
                "sha256:" + "c".repeat(64),
                "sha256:" + "a".repeat(64),
                "build-v1",
                7,
                9,
                NOW,
                NOW.plusSeconds(60),
                true,
                java.util.List.of(
                    new TaskCapability(
                        "FISHING_TRAINING",
                        true,
                        "sha256:" + "e".repeat(64),
                        "build-v1",
                        List.of()))));

    assertThatDispatchRefusesWithoutSideEffects("TASK_INELIGIBLE");
  }

  private void assertThatDispatchRefusesWithoutSideEffects(String expectedCode) {
    ExecutionEnvelope envelope = envelopeWithFutureDeadline();
    var bindings = bindings(envelope);
    org.assertj.core.api.Assertions.assertThat(bindings).isNotNull();
    // A valid, already-approved bind: the refusal must come from the capability attestation, after
    // assembly and provider-command validation, and before any durable or recorded side effect.
    var assembled =
        assembler.assemble("session-fixture", "turn-fixture", observation(), envelope, bindings);
    String approvalId = "approval-ineligible";
    when(approvalStore.find(approvalId))
        .thenReturn(
            new BootstrapApprovalStore.ApprovalView(
                approvalId,
                assembled.scopeDigest(),
                "session-fixture",
                "turn-fixture",
                assembled.orchestrationId(),
                assembled.requestId(),
                assembled.recordingId(),
                assembled.previewFingerprint(),
                "operator-sc1498",
                NOW,
                NOW.plus(5, ChronoUnit.MINUTES),
                "APPROVED",
                null,
                null,
                NOW,
                NOW));

    assertThatThrownBy(
            () ->
                service.dispatch(
                    OPERATOR_KEY,
                    new DispatchRequest(
                        approvalId, observation(), envelope, bindings)))
        .isInstanceOf(BootstrapGameplayOperatorService.OperatorException.class)
        .satisfies(
            exception ->
                org.assertj.core.api.Assertions.assertThat(
                        ((BootstrapGameplayOperatorService.OperatorException) exception)
                            .failureCode())
                    .isEqualTo(expectedCode));
    org.mockito.Mockito.verify(approvalStore, org.mockito.Mockito.never())
        .bind(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(recordingCoordinator, org.mockito.Mockito.never())
        .start(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void previewApproveAndDispatchUsePersistedDecisionOnly() {
    ExecutionEnvelope envelope = envelopeWithFutureDeadline();
    var bindings = bindings(envelope);
    PreviewRequest previewRequest =
        new PreviewRequest("session-fixture", "turn-fixture", observation(), envelope, bindings);

    var preview = service.preview(OPERATOR_KEY, previewRequest);
    assertThat(preview.scopeDigest()).startsWith("sha256:");

    String approvalId = "approval-1";
    Instant expiresAt = NOW.plus(5, ChronoUnit.MINUTES);
    when(approvalStore.create(
            eq("session-fixture"),
            eq("turn-fixture"),
            any(),
            any(),
            any(),
            any(),
            eq(preview.scopeDigest()),
            eq("operator-sc1498"),
            any()))
        .thenReturn(
            new BootstrapApprovalStore.Claim(
                new BootstrapApprovalStore.ApprovalView(
                    approvalId,
                    preview.scopeDigest(),
                    "session-fixture",
                    "turn-fixture",
                    preview.orchestrationId(),
                    preview.requestId(),
                    preview.recordingId(),
                    preview.previewFingerprint(),
                    "operator-sc1498",
                    NOW,
                    expiresAt,
                    "APPROVED",
                    null,
                    null,
                    NOW,
                    NOW),
                true));

    var approval =
        service.approve(
            OPERATOR_KEY,
            new ApprovalRequest(
                "session-fixture",
                "turn-fixture",
                observation(),
                envelope,
                bindings,
                "operator-sc1498",
                expiresAt,
                preview.scopeDigest(),
                true,
                "CONFIRM"));
    assertThat(approval.approvalId()).isEqualTo(approvalId);

    var storedApproval =
        new BootstrapApprovalStore.ApprovalView(
            approvalId,
            preview.scopeDigest(),
            "session-fixture",
            "turn-fixture",
            preview.orchestrationId(),
            preview.requestId(),
            preview.recordingId(),
            preview.previewFingerprint(),
            "operator-sc1498",
            NOW,
            expiresAt,
            "APPROVED",
            null,
            null,
            NOW,
            NOW);
    when(approvalStore.find(approvalId)).thenReturn(storedApproval);
    String authorityId = "auth_approval-1";
    String signatureDigest = "sha256:" + "f".repeat(64);
    var boundApproval =
        new BootstrapApprovalStore.ApprovalView(
            approvalId,
            preview.scopeDigest(),
            "session-fixture",
            "turn-fixture",
            preview.orchestrationId(),
            preview.requestId(),
            preview.recordingId(),
            preview.previewFingerprint(),
            "operator-sc1498",
            NOW,
            expiresAt,
            "BOUND",
            authorityId,
            signatureDigest,
            NOW,
            NOW);
    when(approvalStore.bind(eq(approvalId), any(), any())).thenReturn(boundApproval);
    var recording =
        new BootstrapRecordingCoordinator.RecordingView(
            preview.orchestrationId(),
            preview.recordingId(),
            "session-fixture",
            "turn-fixture",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "CLAUDE",
            envelope.modelVersion(),
            "STARTED",
            null,
            null,
            null,
            null,
            null,
            NOW,
            null);
    when(recordingCoordinator.start(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(recording);
    when(recordingCoordinator.stopForFinalization(any()))
        .thenReturn(
            Optional.of(
                new BootstrapRecordingCoordinator.RecordingArtifact(
                    preview.recordingId(),
                    "target/rec-fixture.mp4",
                    "sha256:" + "9".repeat(64),
                    1000L,
                    30L,
                    30,
                    "libx264",
                    1L,
                    LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
                    LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))));
    OrchestrationView terminalView = terminalView(preview.orchestrationId());
    // Delivery is delegated to the hardened SC-1431 provider route; the operator only assembles,
    // approves, starts the durable recording and projects the terminal evidence. The real
    // two-stage assembly above it is what this verifies.
    when(orchestrationService.orchestrateProvider(any()))
        .thenReturn(new AccountBuilderOrchestrationService.Submission(terminalView, true));
    when(manifestService.buildManifest(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new BootstrapEvidenceManifestService.ManifestPublication(
                Map.of("manifestDigest", "sha256:" + "d".repeat(64)), "sha256:" + "d".repeat(64)));
    when(manifestService.publish(any(), any(), any()))
        .thenReturn(
            java.nio.file.Path.of(".chromascape/bootstrap-recordings/rec-fixture.sc-1498.json"));

    var dispatched =
        service.dispatch(
            OPERATOR_KEY, new DispatchRequest(approvalId, observation(), envelope, bindings));
    assertThat(dispatched.state()).isEqualTo(AccountBuilderOrchestrationModels.State.CONFIRMED);

    // The persisted provider turn is validated before the durable recording projection is created,
    // so a stale command can never leave an orphan STARTED recording.
    var order = org.mockito.Mockito.inOrder(orchestrationService, recordingCoordinator);
    order.verify(orchestrationService).validateProviderCommand(any());
    order
        .verify(recordingCoordinator)
        .start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    order.verify(orchestrationService).orchestrateProvider(any());
  }

  @Test
  void dispatchIsRejectedBeforeAnyRecordingWhenAGateIsDisabled() {
    ExecutionEnvelope envelope = envelopeWithFutureDeadline();
    DispatchRequest request =
        new DispatchRequest("approval-1", observation(), envelope, bindings(envelope));

    BootstrapGameplayOperatorService recordingDisabled = service(false, true);
    assertThatThrownBy(() -> recordingDisabled.dispatch(OPERATOR_KEY, request))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("recording");

    BootstrapGameplayOperatorService v2Disabled = service(true, false);
    assertThatThrownBy(() -> v2Disabled.dispatch(OPERATOR_KEY, request))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("Micro6 v2");

    org.mockito.Mockito.verifyNoInteractions(recordingCoordinator);
    org.mockito.Mockito.verifyNoInteractions(approvalStore);
  }

  @Test
  void approvalRequiresHumanConfirmationOfTheCurrentDigest() {
    ExecutionEnvelope envelope = envelopeWithFutureDeadline();
    var bindings = bindings(envelope);
    var preview =
        service.preview(
            OPERATOR_KEY,
            new PreviewRequest(
                "session-fixture", "turn-fixture", observation(), envelope, bindings));

    assertThatThrownBy(
            () ->
                service.approve(
                    OPERATOR_KEY,
                    new ApprovalRequest(
                        "session-fixture",
                        "turn-fixture",
                        observation(),
                        envelope,
                        bindings,
                        "operator-sc1498",
                        NOW.plus(5, ChronoUnit.MINUTES),
                        preview.scopeDigest(),
                        false,
                        "CONFIRM")))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("explicit human confirmation");
    assertThatThrownBy(
            () ->
                service.approve(
                    OPERATOR_KEY,
                    new ApprovalRequest(
                        "session-fixture",
                        "turn-fixture",
                        observation(),
                        envelope,
                        bindings,
                        "operator-sc1498",
                        NOW.plus(5, ChronoUnit.MINUTES),
                        "sha256:" + "0".repeat(64),
                        true,
                        "CONFIRM")))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void operatorAuthenticationFailsClosed() {
    assertThatThrownBy(
            () ->
                service.preview(
                    "wrong-key",
                    new PreviewRequest(
                        "session-fixture",
                        "turn-fixture",
                        observation(),
                        envelopeWithFutureDeadline(),
                        bindings(envelopeWithFutureDeadline()))))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("valid SC-1498 operator key");
  }

  private JsonNode observation() {
    return fixtures.path("observation").deepCopy();
  }

  private ExecutionEnvelope envelopeWithFutureDeadline() {
    ObjectNode node = fixtures.path("executionEnvelope").deepCopy();
    node.put("deadline", NOW.plus(10, ChronoUnit.MINUTES).toString());
    node.put("authorityId", "authority-draft");
    node.put("semanticActionDigest", canonicalJson.digest(fixtures.path("semanticAction")));
    return objectMapper.convertValue(node, ExecutionEnvelope.class);
  }

  private AccountBuilderOrchestrationModels.SnapshotBindings bindings(ExecutionEnvelope envelope) {
    FakeHandoff handoff = new FakeHandoff();
    var persisted = handoff.findValidatedDecision("session-fixture", "turn-fixture").orElseThrow();
    return new AccountBuilderOrchestrationModels.SnapshotBindings(
        persisted.decisionDigest(),
        persisted.semanticActionDigest(),
        envelope.catalogDigest(),
        "sha256:" + "c".repeat(64),
        envelope.goalPlanDigest(),
        envelope.taskSkillDigests(),
        envelope.schemaDigest(),
        envelope.knowledgeDigest(),
        envelope.policyDigest(),
        envelope.verifierId(),
        envelope.buildId(),
        envelope.expectedObservationRevision(),
        envelope.expectedTaskRevision(),
        persisted.observationDigest(),
        persisted.promptDigest(),
        persisted.sessionId(),
        persisted.turnId());
  }

  private OrchestrationView terminalView(String orchestrationId) {
    return new OrchestrationView(
        "sc-1431/v1",
        orchestrationId,
        "fixture-request",
        "fixture-idempotency",
        "sha256:" + "f".repeat(64),
        "sha256:" + "e".repeat(64),
        AccountBuilderOrchestrationModels.Mode.BOOTSTRAP_RECORDING,
        "PROVIDER:CLAUDE",
        AccountBuilderOrchestrationModels.State.CONFIRMED,
        AccountBuilderOrchestrationModels.FailureCode.NONE,
        null,
        List.of(),
        true,
        false,
        5,
        NOW,
        NOW);
  }

  private final class FakeHandoff implements ProviderDecisionHandoff {
    @Override
    public Optional<PersistedDecision> findValidatedDecision(String sessionId, String turnId) {
      if (!"session-fixture".equals(sessionId) || !"turn-fixture".equals(turnId)) {
        return Optional.empty();
      }
      JsonNode action = fixtures.path("semanticAction").deepCopy();
      ExecutionEnvelope envelope = envelopeWithFutureDeadline();
      ProviderDecision decision =
          new ProviderDecision(
              "sc-1429/v1",
              Provider.CLAUDE,
              envelope.modelVersion(),
              envelope.decisionId(),
              0.9,
              action,
              List.of("observation:observation-fixture"));
      String transcriptDigest = "sha256:" + "7".repeat(64);
      return Optional.of(
          new PersistedDecision(
              sessionId,
              turnId,
              envelope.requestId(),
              Provider.CLAUDE,
              envelope.modelVersion(),
              canonicalJson.digest(decision),
              canonicalJson.digest(action),
              canonicalJson.digest(observation()),
              canonicalJson.digest(fixtures.path("claudeProposal")),
              canonicalJson.digest(action),
              envelope.knowledgeDigest(),
              envelope.goalPlanDigest(),
              envelope.taskSkillDigests(),
              transcriptDigest,
              transcriptDigest,
              ProviderRuntimeModels.TurnState.DECIDED,
              ProviderRuntimeModels.FailureCode.NONE,
              decision));
    }
  }
}
