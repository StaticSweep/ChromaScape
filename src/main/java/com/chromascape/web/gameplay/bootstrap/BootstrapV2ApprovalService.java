package com.chromascape.web.gameplay.bootstrap;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Binds a human approval to one frozen v2 draft.
 *
 * <p>There is exactly one approval surface: this service reuses {@link BootstrapApprovalStore} and
 * its table, so the v2 path inherits the same durable one-use rules and database triggers as v1
 * instead of introducing a second approval state machine.
 *
 * <p>The approval is keyed by the draft's {@code scopeDigest}. A caller-supplied digest that does
 * not match the persisted draft is refused, so an approval can never be created for a scope the
 * operator did not see, and the draft's {@code bundleDigest} is recorded as the request
 * fingerprint so a changed bundle cannot inherit an existing approval.
 */
@Service
public final class BootstrapV2ApprovalService {
  /** The exact confirmation the operator must type. */
  public static final String CONFIRMATION = "CONFIRM";

  private final BootstrapV2DraftStore drafts;
  private final BootstrapApprovalStore approvals;
  private final Clock clock;

  @Autowired
  public BootstrapV2ApprovalService(
      BootstrapV2DraftStore drafts, BootstrapApprovalStore approvals) {
    this(drafts, approvals, Clock.systemUTC());
  }

  BootstrapV2ApprovalService(
      BootstrapV2DraftStore drafts, BootstrapApprovalStore approvals, Clock clock) {
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Records the owner's approval of one draft scope.
   *
   * @param scopeDigest the digest the operator read and typed; it must be the persisted draft's
   * @param expiresAt the approval expiry, which the store bounds to fifteen minutes
   * @throws ApprovalException when the draft is unknown, the digest does not match, the
   *     confirmation is not typed, or the draft's deadline has already passed
   */
  public BootstrapApprovalStore.ApprovalView approve(
      String scopeDigest,
      String approvedBy,
      Instant expiresAt,
      String confirmation) {
    if (!CONFIRMATION.equals(confirmation)) {
      throw new ApprovalException(
          "APPROVAL_CONFIRMATION_REQUIRED", "The operator must type " + CONFIRMATION);
    }
    if (scopeDigest == null || !scopeDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new ApprovalException("INVALID_REQUEST", "A canonical scope digest is required");
    }
    BootstrapV2DraftStore.DraftView draft =
        drafts
            .findByScopeDigest(scopeDigest)
            .orElseThrow(
                () ->
                    new ApprovalException(
                        "DRAFT_NOT_FOUND", "No frozen draft is bound to this scope digest"));
    // The approved bytes are the frozen bytes: the draft's own digest is the request fingerprint,
    // so a re-assembled bundle with different bytes cannot reuse this approval.
    if (!draft.scopeDigest().equals(scopeDigest)) {
      throw new ApprovalException(
          "AUTHORITY_SCOPE_MISMATCH", "Approved scope does not match the frozen draft");
    }
    if (!draft.deadline().isAfter(clock.instant())) {
      throw new ApprovalException("DEADLINE_EXPIRED", "The draft deadline has already passed");
    }
    return approvals
        .create(
            draft.providerSessionId(),
            draft.providerTurnId(),
            draft.requestId(),
            draft.requestId(),
            draft.recordingId(),
            draft.bundleDigest(),
            draft.scopeDigest(),
            approvedBy,
            expiresAt)
        .view();
  }

  /**
   * Binds an approved draft to the signed authority that will execute it. The store enforces
   * APPROVED to BOUND and rejects any later rewrite.
   */
  public BootstrapApprovalStore.ApprovalView bind(
      String approvalId, String authorityId, String signatureDigest) {
    return approvals.bind(approvalId, authorityId, signatureDigest);
  }

  /** Finds the pending approval for a draft, if one exists. */
  public Optional<BootstrapApprovalStore.ApprovalView> findForDraft(String requestId) {
    return approvals.findByOrchestration(requestId);
  }

  /** Stable, non-secret approval failure. */
  public static final class ApprovalException extends IllegalArgumentException {
    private final String failureCode;

    public ApprovalException(String failureCode, String message) {
      super(message == null ? failureCode : message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
