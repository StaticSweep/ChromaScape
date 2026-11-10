package com.chromascape.web.gameplay.bootstrap;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FrozenV2Bundle;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds the complete SC-1429/v2 bootstrap draft from durable provider output and attested Micro6
 * wire, then freezes it.
 *
 * <p>This is the only production entry point that produces a {@link FrozenV2Bundle}. It reads three
 * routes, maps them to contract objects, seals eligibility, assembles and validates the bundle, and
 * returns the exact scope the operator must approve. It never reserves, starts or mutates executor
 * state, and it holds no delivery state machine — SC-1431 remains the single delivery owner.
 */
@Service
public final class BootstrapV2DraftService {
  private final ProviderDecisionHandoff handoff;
  private final BootstrapV2Wire wire;
  private final BootstrapV2SnapshotMapper mapper;
  private final BootstrapV2BundleAssembler assembler;
  private final Clock clock;

  @Autowired
  public BootstrapV2DraftService(
      ProviderDecisionHandoff handoff,
      BootstrapV2Wire wire,
      BootstrapV2SnapshotMapper mapper,
      BootstrapV2BundleAssembler assembler) {
    this(handoff, wire, mapper, assembler, Clock.systemUTC());
  }

  BootstrapV2DraftService(
      ProviderDecisionHandoff handoff,
      BootstrapV2Wire wire,
      BootstrapV2SnapshotMapper mapper,
      BootstrapV2BundleAssembler assembler,
      Clock clock) {
    this.handoff = Objects.requireNonNull(handoff, "handoff");
    this.wire = Objects.requireNonNull(wire, "wire");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.assembler = Objects.requireNonNull(assembler, "assembler");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Assembles the draft for one persisted provider turn.
   *
   * @throws DraftException with a stable code when the turn is not a proven v2 decision, the wire
   *     is incomplete, or the assembled bundle fails any contract rule
   */
  public Preview preview(DraftRequest request) {
    Objects.requireNonNull(request, "request");
    Instant now = clock.instant();
    if (!request.deadline().isAfter(now)) {
      throw new DraftException("DEADLINE_EXPIRED", "The draft deadline has already passed");
    }
    ProviderDecisionHandoff.PersistedV2Decision decision =
        handoff
            .findValidatedV2Decision(request.providerSessionId(), request.providerTurnId())
            .orElseThrow(
                () ->
                    new DraftException(
                        "PERSISTED_V2_DECISION_MISSING",
                        "No proven sc-1429/v2 decision exists for this session and turn"));

    JsonNode catalogSnapshot = mapper.catalogSnapshot(wire.catalog(), now);
    JsonNode observation =
        mapper.semanticObservation(
            wire.observe(),
            wire.observation(),
            request.identities(),
            catalogSnapshot,
            decision.knowledgeDigest());
    JsonNode planningInput =
        mapper.planningInput(
            catalogSnapshot, observation, request.budgets(), request.deadline(), request.identities());
    JsonNode eligibilityResult =
        mapper.eligibilityResult(catalogSnapshot, observation, planningInput, now);

    BootstrapV2BundleAssembler.Assembled assembled;
    try {
      assembled =
          assembler.assemble(
              new BootstrapV2BundleAssembler.Draft(
                  catalogSnapshot,
                  observation,
                  planningInput,
                  eligibilityResult,
                  decision.decision(),
                  request.identities(),
                  request.budgets(),
                  request.deadline(),
                  request.requestId(),
                  request.idempotencyKey(),
                  request.authorityId(),
                  request.approvedBy(),
                  now,
                  request.approvalExpiresAt(),
                  request.leaseGeneration(),
                  request.fence(),
                  now));
    } catch (com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator
            .GameplayV2ContractViolation
        violation) {
      throw new DraftException(violation.failureCode(), violation.getMessage());
    } catch (IllegalArgumentException violation) {
      throw new DraftException("INVALID_BUNDLE", violation.getMessage());
    }
    return new Preview(
        decision.sessionId(),
        decision.turnId(),
        request.requestId(),
        request.idempotencyKey(),
        request.identities().path("recordingId").asText(),
        assembled.frozen(),
        assembled.approvedScope(),
        assembled.executionEnvelope(),
        assembled.members(),
        now);
  }

  /** Operator-supplied identities, budgets and approval material for one draft. */
  public record DraftRequest(
      String providerSessionId,
      String providerTurnId,
      JsonNode identities,
      JsonNode budgets,
      Instant deadline,
      String requestId,
      String idempotencyKey,
      String authorityId,
      String approvedBy,
      Instant approvalExpiresAt,
      long leaseGeneration,
      long fence) {}

  /**
   * The exact scope the operator must approve. Every digest is the digest of the frozen bytes, so a
   * later mutation cannot change what was approved.
   */
  public record Preview(
      String providerSessionId,
      String providerTurnId,
      String requestId,
      String idempotencyKey,
      String recordingId,
      FrozenV2Bundle frozen,
      JsonNode approvedScope,
      JsonNode executionEnvelope,
      java.util.Map<String, JsonNode> members,
      Instant assembledAt) {}

  /** Stable, non-secret draft failure. */
  public static final class DraftException extends IllegalArgumentException {
    private final String failureCode;

    public DraftException(String failureCode, String message) {
      super(message == null ? failureCode : message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
