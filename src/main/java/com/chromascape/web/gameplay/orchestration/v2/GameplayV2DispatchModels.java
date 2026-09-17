package com.chromascape.web.gameplay.orchestration.v2;

import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EnvelopeRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;

/**
 * Immutable SC-1429/v2 live request bundle for SC-1449. ChromaScape constructs one snapshot
 * containing the catalog, observation, planning input, eligibility result, provider decision,
 * GoalPlan, semantic action, execution envelope, approved scope and human authority, serializes the
 * complete snapshot once, hashes those exact bytes, and never rebuilds the request from mutable
 * domain objects after authority approval. The field name {@code providerDecision} is the canonical
 * wire name shared with the Micro6 v2 gate.
 */
public final class GameplayV2DispatchModels {
  public static final String VERSION = "sc-1429/v2";
  public static final String LIVE_MODE = "LIVE_TASK";
  public static final String FISHING_TASK = "FISHING_TRAINING";

  /** Canonical wire key for the provider decision member; shared with Micro6's v2 gate. */
  public static final String PROVIDER_DECISION_FIELD = "providerDecision";

  private GameplayV2DispatchModels() {}

  /**
   * One immutable live request. Every {@code JsonNode} is deep-copied on construction so later
   * caller mutation cannot alter the validated bytes.
   */
  public record V2Bundle(
      JsonNode catalogSnapshot,
      JsonNode semanticObservation,
      JsonNode planningInput,
      JsonNode eligibilityResult,
      JsonNode providerDecision,
      JsonNode goalPlan,
      JsonNode semanticAction,
      JsonNode executionEnvelope,
      JsonNode approvedScope,
      JsonNode authority,
      String expectedSchemaDigest,
      String provider,
      String modelVersion,
      String orchestrationId,
      String sourceType,
      Instant now) {
    /** Deep-copies every mutable input so the bundle owns its bytes exclusively. */
    public V2Bundle {
      catalogSnapshot = copy(catalogSnapshot);
      semanticObservation = copy(semanticObservation);
      planningInput = copy(planningInput);
      eligibilityResult = copy(eligibilityResult);
      providerDecision = copy(providerDecision);
      goalPlan = copy(goalPlan);
      semanticAction = copy(semanticAction);
      executionEnvelope = copy(executionEnvelope);
      approvedScope = copy(approvedScope);
      authority = copy(authority);
    }

    private static JsonNode copy(JsonNode node) {
      return node == null ? null : node.deepCopy();
    }

    /** Request identity carried by the envelope. */
    public String requestId() {
      return executionEnvelope == null ? null : executionEnvelope.path("requestId").asText(null);
    }

    /** Idempotency identity carried by the envelope. */
    public String idempotencyKey() {
      return executionEnvelope == null
          ? null
          : executionEnvelope.path("idempotencyKey").asText(null);
    }
  }

  /**
   * Closed start contract. Start is a separate operation from reserve: the caller proves it owns
   * the existing reservation with the request id plus the lease generation and fence it was
   * reserved under, and never resubmits the complete bundle. Resubmitting the bundle on start would
   * re-open the validated bytes to caller mutation after authority approval.
   */
  public record V2StartRequest(String requestId, long leaseGeneration, long fence) {
    /** Rejects a blank identity and negative proofs at the contract boundary. */
    public V2StartRequest {
      requestId = requestId == null ? null : requestId.trim();
      if (leaseGeneration < 0L) {
        throw new IllegalArgumentException("leaseGeneration must not be negative");
      }
      if (fence < 0L) {
        throw new IllegalArgumentException("fence must not be negative");
      }
    }

    /** True when the request carries a usable reservation identity. */
    public boolean hasRequestId() {
      return requestId != null && !requestId.isBlank();
    }
  }

  /**
   * The frozen, validated request: the canonical bytes of the complete snapshot plus their digest.
   * The snapshot is serialized once, and the envelope digest is what the authority binds and what
   * Micro6 recomputes from the transmitted envelope. Nothing derived from the original
   * caller-supplied {@link V2Bundle} survives past validation, and every accessor hands out copies,
   * so the bytes used for authority binding, persistence and HTTP delivery are provably the bytes
   * that passed validation.
   */
  public record ValidatedBundleSnapshot(
      byte[] bundleBytes,
      String bundleDigest,
      String envelopeDigest,
      JsonNode executionEnvelope,
      String requestId,
      String idempotencyKey,
      String authorityId,
      String accountId,
      String clientId,
      long leaseGeneration,
      long fence,
      Instant envelopeDeadline,
      Instant authorityExpiresAt) {
    /** Freezes the bytes and the envelope copy at construction time. */
    public ValidatedBundleSnapshot {
      bundleBytes = bundleBytes == null ? new byte[0] : bundleBytes.clone();
      executionEnvelope = executionEnvelope == null ? null : executionEnvelope.deepCopy();
    }

    /** Defensive copy of the exact canonical bytes that passed validation. */
    public byte[] bytes() {
      return bundleBytes.clone();
    }
  }

  /** Recomputed envelope digest from the bundle's own envelope bytes, never a caller hint. */
  public static String envelopeDigestOf(ObjectMapper objectMapper, JsonNode envelope) {
    return GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
  }

  /** Independently derived schema digest; any caller hint must equal it. */
  public static String canonicalSchemaDigest() {
    return GameplayV2EnvelopeRules.canonicalSchemaDigest();
  }

  /** Durable live-gate states. {@code RECONCILED} is an idempotency outcome, never a state. */
  public enum V2State {
    RECEIVED,
    VALIDATED,
    AUTHORIZED,
    RESERVED,
    DELIVERY_PENDING,
    STARTING,
    STOPPING,
    DELIVERED,
    ACTIVE,
    CONFIRMED,
    REFUTED,
    UNVERIFIABLE,
    FAILED,
    STOPPED,
    NEEDS_RECONCILIATION;

    /** Terminal outcomes that produce an immutable durable row. */
    public boolean provenTerminal() {
      return switch (this) {
        case CONFIRMED, REFUTED, UNVERIFIABLE, FAILED, STOPPED -> true;
        default -> false;
      };
    }

    /** Whether the lifecycle graph permits this durable transition. */
    public boolean canTransitionTo(V2State next) {
      if (next == null || this == next) {
        return next != null;
      }
      return switch (this) {
        case RECEIVED -> next == VALIDATED || next == FAILED || next == NEEDS_RECONCILIATION;
        case VALIDATED -> next == AUTHORIZED || next == FAILED || next == NEEDS_RECONCILIATION;
        case AUTHORIZED -> next == RESERVED || next == FAILED || next == NEEDS_RECONCILIATION;
        case RESERVED -> next == DELIVERY_PENDING
            || next == STARTING
            || next == STOPPING
            || next == FAILED
            || next == STOPPED
            || next == NEEDS_RECONCILIATION;
        case DELIVERY_PENDING -> next == STARTING
            || next == STOPPING
            || next == DELIVERED
            || next == FAILED
            || next == NEEDS_RECONCILIATION;
        case STARTING -> next == ACTIVE || next == FAILED || next == NEEDS_RECONCILIATION;
        case STOPPING -> next == STOPPED || next == FAILED || next == NEEDS_RECONCILIATION;
        case DELIVERED -> next == ACTIVE
            || next == FAILED
            || next == STOPPED
            || next == NEEDS_RECONCILIATION;
        case ACTIVE -> next == CONFIRMED
            || next == REFUTED
            || next == UNVERIFIABLE
            || next == FAILED
            || next == STOPPED
            || next == STOPPING
            || next == NEEDS_RECONCILIATION;
        case NEEDS_RECONCILIATION -> next == DELIVERY_PENDING
            || next == ACTIVE
            || next == CONFIRMED
            || next == REFUTED
            || next == UNVERIFIABLE
            || next == FAILED
            || next == STOPPED;
        case CONFIRMED, REFUTED, UNVERIFIABLE, FAILED, STOPPED -> false;
      };
    }
  }

  /** Stable failure codes shared with Micro6 and the operator surface. */
  public static final class Failure {
    public static final String INVALID_BUNDLE = "INVALID_BUNDLE";
    public static final String INVALID_RECEIPT = "INVALID_RECEIPT";
    public static final String VERSION_MISMATCH = "VERSION_MISMATCH";
    public static final String MODE_NOT_DELIVERABLE = "MODE_NOT_DELIVERABLE";
    public static final String LIVE_DISABLED = "LIVE_DISABLED";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String AUTHORITY_CONSUMED = "AUTHORITY_CONSUMED";
    public static final String AUTHORITY_EXPIRED = "AUTHORITY_EXPIRED";
    public static final String FENCE_MISMATCH = "FENCE_MISMATCH";
    public static final String LEASE_SLOT_OCCUPIED = "LEASE_SLOT_OCCUPIED";
    public static final String LEASE_EXPIRED = "LEASE_EXPIRED";
    public static final String DEADLINE_EXPIRED = "DEADLINE_EXPIRED";
    public static final String NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";
    public static final String TRANSPORT_UNCERTAIN = "TRANSPORT_UNCERTAIN";
    public static final String UNKNOWN_REQUEST = "UNKNOWN_REQUEST";
    public static final String START_IN_PROGRESS = "START_IN_PROGRESS";
    public static final String STOP_IN_PROGRESS = "STOP_IN_PROGRESS";

    private Failure() {}
  }

  /** Outcome of a reserve call. Denials carry zero gateway calls by construction. */
  public record V2ReserveResult(
      V2State state,
      String failureCode,
      boolean reconciled,
      boolean deliveryAcknowledged,
      String requestId,
      String envelopeDigest,
      List<String> evidenceRefs) {
    public V2ReserveResult {
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
  }

  /** Outcome of a start call. Only one start per reservation ever dispatches. */
  public record V2StartResult(
      V2State state,
      String failureCode,
      boolean reconciled,
      String requestId,
      String envelopeDigest,
      List<String> evidenceRefs) {
    public V2StartResult {
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
  }

  /** Observable status of a tracked request. */
  public record V2StatusResult(
      V2State state,
      String failureCode,
      boolean reconciled,
      String requestId,
      String envelopeDigest,
      List<String> evidenceRefs) {
    public V2StatusResult {
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
  }

  /** Transport failure carrier. Every transport error after persistence needs reconciliation. */
  public static final class V2TransportException extends RuntimeException {
    public V2TransportException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Transport boundary for the v2 Micro6 routes. Tests substitute a fake; no HTTP in tests. */
  public interface V2Transport {
    /**
     * Reserves the complete bundle on Micro6 without starting gameplay. The bytes must be the exact
     * canonical bytes that passed validation; the transport never re-serializes.
     */
    JsonNode reserve(byte[] bundleBytes, String idempotencyKey);

    /** Starts the previously reserved request. */
    JsonNode start(JsonNode envelope, String idempotencyKey);

    /** Fetches Micro6 state for independent reconciliation. */
    JsonNode status(String requestId);

    /** Stops the request; a kill switch stops the active task and records evidence. */
    JsonNode stop(String requestId, String reason, boolean killSwitch);

    /** Fetches the Micro6 catalog attestation. */
    JsonNode catalog();

    /** Fetches the Micro6 observation attestation. */
    JsonNode observe(String clientId);
  }
}
