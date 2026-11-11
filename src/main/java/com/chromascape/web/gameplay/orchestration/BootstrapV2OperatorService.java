package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator;
import com.chromascape.web.gameplay.bootstrap.BootstrapV2ApprovalService;
import com.chromascape.web.gameplay.bootstrap.BootstrapV2DraftService;
import com.chromascape.web.gameplay.bootstrap.BootstrapV2DraftStore;
import com.chromascape.web.gameplay.bootstrap.BootstrapV2ProviderTurnService;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.OperatorException;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FrozenV2Bundle;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FrozenV2Command;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Operator facade for the frozen SC-1429/v2 bootstrap delivery.
 *
 * <p>Three steps, each durable before the next: assemble and persist the frozen draft, record the
 * owner's approval of its scope digest, then deliver exactly those bytes once through the SC-1431
 * owner. The draft is the only source of the bundle, so nothing a caller sends at dispatch time can
 * change what was approved. This service holds no delivery state machine of its own.
 */
@Service
public final class BootstrapV2OperatorService {
  private final BootstrapGameplayOperatorService operator;
  private final BootstrapV2DraftService drafts;
  private final BootstrapV2DraftStore draftStore;
  private final BootstrapV2ApprovalService approvals;
  private final BootstrapV2ProviderTurnService turns;
  private final BootstrapApprovalStore approvalStore;
  private final AccountBuilderOrchestrationService orchestration;
  private final BootstrapRecordingCoordinator recordings;
  private final CanonicalGameplayJson canonicalJson;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public BootstrapV2OperatorService(
      BootstrapGameplayOperatorService operator,
      BootstrapV2DraftService drafts,
      BootstrapV2DraftStore draftStore,
      BootstrapV2ApprovalService approvals,
      BootstrapV2ProviderTurnService turns,
      BootstrapApprovalStore approvalStore,
      AccountBuilderOrchestrationService orchestration,
      BootstrapRecordingCoordinator recordings,
      CanonicalGameplayJson canonicalJson,
      ObjectMapper objectMapper) {
    this(
        operator,
        drafts,
        draftStore,
        approvals,
        turns,
        approvalStore,
        orchestration,
        recordings,
        canonicalJson,
        objectMapper,
        Clock.systemUTC());
  }

  BootstrapV2OperatorService(
      BootstrapGameplayOperatorService operator,
      BootstrapV2DraftService drafts,
      BootstrapV2DraftStore draftStore,
      BootstrapV2ApprovalService approvals,
      BootstrapV2ProviderTurnService turns,
      BootstrapApprovalStore approvalStore,
      AccountBuilderOrchestrationService orchestration,
      BootstrapRecordingCoordinator recordings,
      CanonicalGameplayJson canonicalJson,
      ObjectMapper objectMapper,
      Clock clock) {
    this.operator = Objects.requireNonNull(operator, "operator");
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.draftStore = Objects.requireNonNull(draftStore, "draftStore");
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.approvalStore = Objects.requireNonNull(approvalStore, "approvalStore");
    this.orchestration = Objects.requireNonNull(orchestration, "orchestration");
    this.recordings = Objects.requireNonNull(recordings, "recordings");
    this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Assembles the frozen draft from durable provider output and attested wire, persists it, and
   * returns the exact scope the owner must approve.
   */
  public DraftView preview(String operatorKey, BootstrapV2DraftService.DraftRequest request) {
    operator.authorizeOperator(operatorKey);
    BootstrapV2DraftService.Preview preview;
    try {
      preview = drafts.preview(request);
    } catch (BootstrapV2DraftService.DraftException exception) {
      throw new OperatorException(
          HttpStatus.UNPROCESSABLE_ENTITY, exception.failureCode(), exception.getMessage());
    }
    BootstrapV2DraftStore.DraftView stored;
    try {
      stored = draftStore.save(preview);
    } catch (BootstrapV2DraftStore.BootstrapV2DraftException exception) {
      throw new OperatorException(
          HttpStatus.CONFLICT, exception.failureCode(), exception.getMessage());
    }
    return new DraftView(
        stored.draftId(),
        stored.requestId(),
        stored.recordingId(),
        stored.scopeDigest(),
        stored.bundleDigest(),
        stored.envelopeDigest(),
        stored.deadline(),
        preview.approvedScope(),
        preview.executionEnvelope(),
        preview.assembledAt());
  }

  /**
   * Asks the provider for one v2 decision and returns the frozen draft it grounds.
   *
   * <p>The observation is captured once inside the turn service and reused for the draft, so the
   * provider's eligibility digest and the bundle describe the same moment.
   */
  public BootstrapV2ProviderTurnService.PreparedTurn prepareTurn(
      String operatorKey, BootstrapV2ProviderTurnService.TurnPreparation request) {
    operator.authorizeOperator(operatorKey);
    try {
      return turns.prepare(request);
    } catch (BootstrapV2ProviderTurnService.TurnException exception) {
      throw new OperatorException(
          HttpStatus.UNPROCESSABLE_ENTITY, exception.failureCode(), exception.getMessage());
    }
  }

  /** Records the owner's approval of one frozen draft scope. */
  public BootstrapApprovalStore.ApprovalView approve(
      String operatorKey, ApprovalRequest request) {
    operator.authorizeOperator(operatorKey);
    if (request == null) {
      throw new OperatorException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "An approval request is required");
    }
    try {
      return approvals.approve(
          request.scopeDigest(),
          request.approvedBy(),
          request.expiresAt(),
          request.confirmation());
    } catch (BootstrapV2ApprovalService.ApprovalException exception) {
      throw new OperatorException(
          HttpStatus.UNPROCESSABLE_ENTITY, exception.failureCode(), exception.getMessage());
    } catch (BootstrapApprovalStore.BootstrapApprovalException exception) {
      throw new OperatorException(
          HttpStatus.CONFLICT, exception.failureCode(), exception.getMessage());
    }
  }

  /**
   * Delivers the approved frozen bytes once.
   *
   * <p>The bundle is read from the persisted draft, never from the request: the approval binds one
   * scope digest, and that digest is the only thing that can select the bytes that run.
   */
  public OrchestrationView dispatch(String operatorKey, String approvalId) {
    operator.authorizeOperator(operatorKey);
    if (approvalId == null || approvalId.isBlank()) {
      throw new OperatorException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "An approval id is required");
    }
    BootstrapApprovalStore.ApprovalView approval;
    try {
      approval = approvalStore.find(approvalId);
    } catch (BootstrapApprovalStore.BootstrapApprovalException exception) {
      throw new OperatorException(
          HttpStatus.NOT_FOUND, exception.failureCode(), exception.getMessage());
    }
    if (!"APPROVED".equals(approval.state()) && !"BOUND".equals(approval.state())) {
      throw new OperatorException(
          HttpStatus.CONFLICT,
          "APPROVAL_NOT_PENDING",
          "The approval is " + approval.state() + " and cannot be delivered");
    }
    if (approval.expiresAt() == null || !approval.expiresAt().isAfter(clock.instant())) {
      throw new OperatorException(
          HttpStatus.CONFLICT, "APPROVAL_EXPIRED", "The approval has expired");
    }
    BootstrapV2DraftStore.DraftView draft =
        draftStore
            .findByScopeDigest(approval.scopeDigest())
            .orElseThrow(
                () ->
                    new OperatorException(
                        HttpStatus.NOT_FOUND,
                        "DRAFT_NOT_FOUND",
                        "No frozen draft is bound to the approved scope"));
    if (!Objects.equals(approval.requestFingerprint(), draft.bundleDigest())) {
      throw new OperatorException(
          HttpStatus.CONFLICT,
          "AUTHORITY_SCOPE_MISMATCH",
          "The approval does not bind the frozen draft bytes");
    }
    if (!draft.deadline().isAfter(clock.instant())) {
      throw new OperatorException(
          HttpStatus.CONFLICT, "DEADLINE_EXPIRED", "The frozen draft deadline has passed");
    }
    FrozenV2Bundle bundle =
        new FrozenV2Bundle(
            draft.bytes(),
            draft.bundleDigest(),
            draft.envelopeDigest(),
            draft.scopeDigest(),
            draft.catalogDigest(),
            draft.observationDigest(),
            draft.planningInputDigest(),
            draft.eligibilityDigest(),
            draft.goalPlanDigest(),
            draft.semanticActionDigest(),
            draft.authorityId(),
            draft.requestId(),
            draft.idempotencyKey(),
            draft.deadline());
    com.fasterxml.jackson.databind.JsonNode bundleNode;
    try {
      bundleNode = objectMapper.readTree(draft.bytes());
    } catch (java.io.IOException exception) {
      throw new OperatorException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVALID_BUNDLE",
          "The frozen draft is not valid JSON");
    }
    com.fasterxml.jackson.databind.JsonNode envelopeNode = bundleNode.path("executionEnvelope");
    // The durable recording projection starts before delivery, exactly as the v1 path does. If the
    // process dies after this point, recovery finds the STARTED projection and resumes
    // reconciliation or finalization only; gameplay is never redispatched.
    recordings.start(
        draft.requestId(),
        draft.recordingId(),
        draft.providerSessionId(),
        draft.providerTurnId(),
        canonicalJson.digest(bundleNode.path("providerDecision")),
        draft.envelopeDigest(),
        envelopeNode.path("provider").asText(null),
        envelopeNode.path("modelVersion").asText(null),
        null,
        null);
    OrchestrationView view =
        orchestration
            .orchestrateFrozenV2(
                new FrozenV2Command(
                    draft.requestId(),
                    draft.requestId(),
                    draft.idempotencyKey(),
                    Mode.BOOTSTRAP_RECORDING,
                    draft.deadline(),
                    draft.providerSessionId(),
                    draft.providerTurnId(),
                    bundle))
            .view();
    if (!view.state().provenTerminal()) {
      // The one-run bound: wait read-only for a proven terminal receipt before projecting the
      // evidence. The wait never redispatches and stays reconciliation-only if the bound elapses.
      view = orchestration.reconcileUntilTerminal(draft.requestId(), draft.deadline());
    }
    return operator.finalizeDelivered(operatorKey, draft.requestId());
  }

  /** Durable state of one delivery, for the operator's bounded wait. */
  public OrchestrationView run(String operatorKey, String orchestrationId) {
    operator.authorizeOperator(operatorKey);
    return orchestration.find(orchestrationId);
  }

  /** Bounded, read-only terminal wait. It never reserves or dispatches again. */
  public OrchestrationView awaitTerminal(
      String operatorKey, String orchestrationId, Instant deadline) {
    operator.authorizeOperator(operatorKey);
    return orchestration.reconcileUntilTerminal(orchestrationId, deadline);
  }

  /** The exact scope the owner approves, with the digests that bind it. */
  public record DraftView(
      String draftId,
      String requestId,
      String recordingId,
      String scopeDigest,
      String bundleDigest,
      String envelopeDigest,
      Instant deadline,
      com.fasterxml.jackson.databind.JsonNode approvedScope,
      com.fasterxml.jackson.databind.JsonNode executionEnvelope,
      Instant assembledAt) {}

  /** Operator-supplied approval material; the scope digest is the one the owner read. */
  public record ApprovalRequest(
      String scopeDigest, String approvedBy, Instant expiresAt, String confirmation) {}
}
