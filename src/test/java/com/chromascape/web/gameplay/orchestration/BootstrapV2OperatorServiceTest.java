package com.chromascape.web.gameplay.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator;
import com.chromascape.web.gameplay.bootstrap.BootstrapV2ApprovalService;
import com.chromascape.web.gameplay.bootstrap.BootstrapV2DraftService;
import com.chromascape.web.gameplay.bootstrap.BootstrapV2DraftStore;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.OperatorException;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The v2 operator surface must deliver the approved bytes and nothing else.
 *
 * <p>Every refusal here happens before the delivery owner is reached, so a stale, mismatched or
 * expired approval can never consume a one-use authority.
 */
class BootstrapV2OperatorServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:05:00Z");
  private static final String OPERATOR_KEY = "k".repeat(32);
  private static final String SCOPE_DIGEST = "sha256:" + "a".repeat(64);
  private static final String BUNDLE_DIGEST = "sha256:" + "b".repeat(64);
  private static final String BUNDLE_JSON =
      "{\"executionEnvelope\":{\"provider\":\"CLAUDE\",\"modelVersion\":\"model-v1\"},"
          + "\"providerDecision\":{\"contractVersion\":\"sc-1429/v2\"}}";

  private BootstrapGameplayOperatorService operator;
  private BootstrapV2DraftService drafts;
  private BootstrapV2DraftStore draftStore;
  private BootstrapV2ApprovalService approvals;
  private BootstrapApprovalStore approvalStore;
  private AccountBuilderOrchestrationService orchestration;
  private BootstrapRecordingCoordinator recordings;
  private ObjectMapper objectMapper;
  private BootstrapV2OperatorService service;

  @BeforeEach
  void setUp() {
    operator = mock(BootstrapGameplayOperatorService.class);
    drafts = mock(BootstrapV2DraftService.class);
    draftStore = mock(BootstrapV2DraftStore.class);
    approvals = mock(BootstrapV2ApprovalService.class);
    approvalStore = mock(BootstrapApprovalStore.class);
    orchestration = mock(AccountBuilderOrchestrationService.class);
    recordings = mock(BootstrapRecordingCoordinator.class);
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    service =
        new BootstrapV2OperatorService(
            operator,
            drafts,
            draftStore,
            approvals,
            approvalStore,
            orchestration,
            recordings,
            new CanonicalGameplayJson(objectMapper),
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void dispatchDeliversThePersistedDraftBytesAndNothingElse() throws Exception {
    when(approvalStore.find("approval-1")).thenReturn(approval("APPROVED", NOW.plusSeconds(300)));
    when(draftStore.findByScopeDigest(SCOPE_DIGEST)).thenReturn(Optional.of(draft()));
    when(orchestration.orchestrateFrozenV2(any()))
        .thenReturn(
            new AccountBuilderOrchestrationService.Submission(view("req-1", State.RESERVED), true));
    when(orchestration.reconcileUntilTerminal("req-1", NOW.plusSeconds(300)))
        .thenReturn(view("req-1", State.CONFIRMED));
    when(operator.finalizeDelivered(OPERATOR_KEY, "req-1"))
        .thenReturn(view("req-1", State.CONFIRMED));

    OrchestrationView delivered = service.dispatch(OPERATOR_KEY, "approval-1");

    assertThat(delivered.state()).isEqualTo(State.CONFIRMED);
    verify(recordings)
        .start(
            "req-1",
            "recording-1",
            "session-1",
            "turn-1",
            new CanonicalGameplayJson(objectMapper)
                .digest(objectMapper.readTree(BUNDLE_JSON).path("providerDecision")),
            "sha256:" + "e".repeat(64),
            "CLAUDE",
            "model-v1",
            null,
            null);
    ArgumentCaptor<AccountBuilderOrchestrationModels.FrozenV2Command> captor =
        ArgumentCaptor.forClass(AccountBuilderOrchestrationModels.FrozenV2Command.class);
    verify(orchestration).orchestrateFrozenV2(captor.capture());
    var command = captor.getValue();
    assertThat(command.orchestrationId()).isEqualTo("req-1");
    assertThat(command.mode()).isEqualTo(Mode.BOOTSTRAP_RECORDING);
    assertThat(command.deadline()).isEqualTo(NOW.plusSeconds(300));
    assertThat(command.frozenBundle().bytes())
        .as("the delivered bytes are the persisted draft, never a caller-supplied payload")
        .isEqualTo(BUNDLE_JSON.getBytes(StandardCharsets.UTF_8));
    assertThat(command.frozenBundle().bundleDigest()).isEqualTo(BUNDLE_DIGEST);
    assertThat(command.frozenBundle().scopeDigest()).isEqualTo(SCOPE_DIGEST);
  }

  @Test
  void dispatchRefusesAnApprovalThatIsNoLongerPending() {
    when(approvalStore.find("approval-1")).thenReturn(approval("EXPIRED", NOW.plusSeconds(300)));

    assertThatThrownBy(() -> service.dispatch(OPERATOR_KEY, "approval-1"))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("EXPIRED");
    verify(orchestration, never()).orchestrateFrozenV2(any());
    verifyNoInteractions(recordings);
  }

  @Test
  void dispatchRefusesAnApprovalWhoseWindowHasClosed() {
    when(approvalStore.find("approval-1")).thenReturn(approval("APPROVED", NOW.minusSeconds(1)));

    assertThatThrownBy(() -> service.dispatch(OPERATOR_KEY, "approval-1"))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("expired");
    verify(orchestration, never()).orchestrateFrozenV2(any());
    verifyNoInteractions(recordings);
  }

  @Test
  void dispatchRefusesAnApprovalThatDoesNotBindTheDraftBytes() {
    when(approvalStore.find("approval-1"))
        .thenReturn(
            new BootstrapApprovalStore.ApprovalView(
                "approval-1",
                SCOPE_DIGEST,
                "session-1",
                "turn-1",
                "orch-1",
                "req-1",
                "recording-1",
                "sha256:" + "c".repeat(64),
                "operator-1",
                NOW,
                NOW.plusSeconds(300),
                "APPROVED",
                null,
                "sha256:" + "d".repeat(64),
                NOW,
                NOW));
    when(draftStore.findByScopeDigest(SCOPE_DIGEST)).thenReturn(Optional.of(draft()));

    assertThatThrownBy(() -> service.dispatch(OPERATOR_KEY, "approval-1"))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("does not bind");
    verify(orchestration, never()).orchestrateFrozenV2(any());
    verifyNoInteractions(recordings);
  }

  @Test
  void dispatchRefusesAnExpiredDraftDeadline() {
    when(approvalStore.find("approval-1")).thenReturn(approval("APPROVED", NOW.plusSeconds(300)));
    when(draftStore.findByScopeDigest(SCOPE_DIGEST))
        .thenReturn(Optional.of(draft(NOW.minusSeconds(1))));

    assertThatThrownBy(() -> service.dispatch(OPERATOR_KEY, "approval-1"))
        .isInstanceOf(OperatorException.class)
        .hasMessageContaining("deadline");
    verify(orchestration, never()).orchestrateFrozenV2(any());
    verifyNoInteractions(recordings);
  }

  @Test
  void previewPersistsTheDraftAndReturnsItsScopeDigest() {
    BootstrapV2DraftService.DraftRequest request =
        new BootstrapV2DraftService.DraftRequest(
            "session-1", "turn-1", null, null, NOW.plusSeconds(300), "req-1", "idem-1",
            "authority-1", "operator-1", NOW.plusSeconds(600), 1, 1);
    BootstrapV2DraftService.Preview preview =
        new BootstrapV2DraftService.Preview(
            "session-1",
            "turn-1",
            "req-1",
            "idem-1",
            "recording-1",
            bundle(),
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
            java.util.Map.of(),
            NOW);
    when(drafts.preview(request)).thenReturn(preview);
    when(draftStore.save(preview)).thenReturn(draft());

    BootstrapV2OperatorService.DraftView view = service.preview(OPERATOR_KEY, request);

    assertThat(view.scopeDigest()).isEqualTo(SCOPE_DIGEST);
    assertThat(view.bundleDigest()).isEqualTo(BUNDLE_DIGEST);
    assertThat(view.requestId()).isEqualTo("req-1");
    verify(draftStore).save(preview);
  }

  private BootstrapApprovalStore.ApprovalView approval(String state, Instant expiresAt) {
    return new BootstrapApprovalStore.ApprovalView(
        "approval-1",
        SCOPE_DIGEST,
        "session-1",
        "turn-1",
        "orch-1",
        "req-1",
        "recording-1",
        BUNDLE_DIGEST,
        "operator-1",
        NOW,
        expiresAt,
        state,
        null,
        "sha256:" + "d".repeat(64),
        NOW,
        NOW);
  }

  private AccountBuilderOrchestrationModels.FrozenV2Bundle bundle() {
    return new AccountBuilderOrchestrationModels.FrozenV2Bundle(
        BUNDLE_JSON.getBytes(StandardCharsets.UTF_8),
        BUNDLE_DIGEST,
        "sha256:" + "e".repeat(64),
        SCOPE_DIGEST,
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64),
        "sha256:" + "4".repeat(64),
        "sha256:" + "5".repeat(64),
        "sha256:" + "6".repeat(64),
        "authority-1",
        "req-1",
        "idem-1",
        NOW.plusSeconds(300));
  }

  private BootstrapV2DraftStore.DraftView draft() {
    return draft(NOW.plusSeconds(300));
  }

  private BootstrapV2DraftStore.DraftView draft(Instant deadline) {
    return new BootstrapV2DraftStore.DraftView(
        "draft-req-1",
        "session-1",
        "turn-1",
        "req-1",
        "idem-1",
        "recording-1",
        "authority-1",
        BUNDLE_JSON.getBytes(StandardCharsets.UTF_8),
        BUNDLE_DIGEST,
        "sha256:" + "e".repeat(64),
        SCOPE_DIGEST,
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64),
        "sha256:" + "4".repeat(64),
        "sha256:" + "5".repeat(64),
        "sha256:" + "6".repeat(64),
        deadline,
        NOW);
  }

  private OrchestrationView view(String orchestrationId, State state) {
    return new OrchestrationView(
        AccountBuilderOrchestrationModels.VERSION,
        orchestrationId,
        "req-1",
        "idem-1",
        BUNDLE_DIGEST,
        "sha256:" + "e".repeat(64),
        Mode.BOOTSTRAP_RECORDING,
        "PROVIDER:CLAUDE",
        state,
        FailureCode.NONE,
        null,
        List.of(),
        true,
        false,
        1,
        NOW,
        NOW);
  }
}
