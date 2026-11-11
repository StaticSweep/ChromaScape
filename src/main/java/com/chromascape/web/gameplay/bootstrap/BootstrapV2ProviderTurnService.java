package com.chromascape.web.gameplay.bootstrap;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime;
import com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime.Submission;
import com.chromascape.web.gameplay.provider.runtime.ProviderProposalSchemaV2;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnView;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Asks the provider for one sc-1429/v2 decision and hands back the draft it grounds.
 *
 * <p>The observation is captured exactly once. Micro6's live observation route increments its
 * revision on every read, so a second read would describe a different moment and the provider's
 * {@code eligibleInputDigest} could never match the bundle. The captured observation is therefore
 * the one the turn is asked about and the one the draft is assembled from.
 *
 * <p>Every digest pinned in the session is derived from an attested artifact, never chosen: the
 * prompt identity is the proposal schema, the toolset is the catalog's closed operation list, the
 * knowledge is the task descriptors the executor published, the goal-plan identity is the pinned
 * planning input, and the task-skill digests are the same descriptors the provider must reference.
 */
@Service
public final class BootstrapV2ProviderTurnService {
  private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

  private final BootstrapV2Wire wire;
  private final BootstrapV2SnapshotMapper mapper;
  private final GameplayProviderRuntime runtime;
  private final BootstrapV2DraftService drafts;
  private final CanonicalGameplayJson canonicalJson;
  private final Clock clock;

  @Autowired
  public BootstrapV2ProviderTurnService(
      BootstrapV2Wire wire,
      BootstrapV2SnapshotMapper mapper,
      GameplayProviderRuntime runtime,
      BootstrapV2DraftService drafts,
      CanonicalGameplayJson canonicalJson) {
    this(wire, mapper, runtime, drafts, canonicalJson, Clock.systemUTC());
  }

  BootstrapV2ProviderTurnService(
      BootstrapV2Wire wire,
      BootstrapV2SnapshotMapper mapper,
      GameplayProviderRuntime runtime,
      BootstrapV2DraftService drafts,
      CanonicalGameplayJson canonicalJson,
      Clock clock) {
    this.wire = Objects.requireNonNull(wire, "wire");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Captures the wire, asks the provider, waits for the decision and returns the frozen draft.
   *
   * @throws TurnException with a stable code when the wire is incomplete, the provider refuses, or
   *     the turn does not reach a decision inside its deadline
   */
  public PreparedTurn prepare(TurnPreparation request) {
    Objects.requireNonNull(request, "request");
    Instant now = clock.instant();
    if (!request.deadline().isAfter(now)) {
      throw new TurnException("DEADLINE_EXPIRED", "The turn deadline has already passed");
    }
    JsonNode catalogWire;
    JsonNode observeWire;
    JsonNode observationWire;
    try {
      catalogWire = wire.catalog();
      observeWire = wire.observe();
      observationWire = wire.observation();
    } catch (RuntimeException failure) {
      // An unreachable or slow executor is a stable, retryable condition, never a server error.
      throw new TurnException("GATEWAY_UNAVAILABLE", failure.getMessage());
    }
    JsonNode catalogSnapshot;
    JsonNode observation;
    JsonNode planningInput;
    JsonNode eligibilityResult;
    try {
      catalogSnapshot = mapper.catalogSnapshot(catalogWire, now);
      // The observation carries the knowledge digest, so it must be pinned before the observation
      // is built. The knowledge is exactly the task descriptors the executor attested.
      String knowledgeDigest = canonicalJson.digest(catalogSnapshot.path("tasks"));
      observation =
          mapper.semanticObservation(
              observeWire,
              observationWire,
              request.identities(),
              catalogSnapshot,
              knowledgeDigest);
      planningInput =
          mapper.planningInput(
              catalogSnapshot,
              observation,
              request.budgets(),
              request.deadline(),
              request.identities());
      eligibilityResult =
          mapper.eligibilityResult(catalogSnapshot, observation, planningInput, now);
    } catch (IllegalArgumentException violation) {
      throw new TurnException("INVALID_WIRE", violation.getMessage());
    }

    String promptDigest = canonicalJson.digest(ProviderProposalSchemaV2.JSON);
    String toolsetDigest = canonicalJson.digest(catalogWire.path("operations"));
    String knowledgeDigest = canonicalJson.digest(catalogSnapshot.path("tasks"));
    String goalPlanDigest = canonicalJson.digest(planningInput);
    List<String> taskSkillDigests =
        List.of(canonicalJson.digest(catalogSnapshot.path("tasks").path(0)));

    String sessionId = "bootstrap-session-" + UUID.randomUUID().toString().replace("-", "");
    String turnId = "bootstrap-turn-" + UUID.randomUUID().toString().replace("-", "");
    SessionRequest session =
        new SessionRequest(
            sessionId,
            request.provider(),
            request.modelId(),
            request.taskKey(),
            request.runId(),
            request.recordingId(),
            promptDigest,
            toolsetDigest,
            knowledgeDigest,
            goalPlanDigest,
            taskSkillDigests,
            request.deadline(),
            request.limits(),
            true,
            "sc-1429/v2");
    TurnRequest turn =
        new TurnRequest(
            turnId,
            request.requestId(),
            request.idempotencyKey(),
            canonicalJson.digest(observation),
            observation,
            canonicalJson.digest(observation),
            eligibilityResult,
            canonicalJson.digest(eligibilityResult),
            request.identities(),
            request.sourceId(),
            request.estimatedInputTokens(),
            request.estimatedOutputTokens(),
            request.deadline(),
            true);
    try {
      runtime.createSession(session);
      Submission submitted = runtime.submitTurn(sessionId, turn);
      if (submitted.turn().state() == TurnState.ABSTAINED) {
        throw new TurnException(
            "PROVIDER_REFUSED", "The provider did not produce a usable v2 decision");
      }
    } catch (TurnException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new TurnException("PROVIDER_UNAVAILABLE", exception.getMessage());
    }

    TurnView decided = awaitDecision(sessionId, turnId, request.deadline());
    BootstrapV2DraftService.Preview preview =
        drafts.preview(
            new BootstrapV2DraftService.DraftRequest(
                sessionId,
                turnId,
                request.identities(),
                request.budgets(),
                request.deadline(),
                request.requestId(),
                request.idempotencyKey(),
                request.authorityId(),
                request.approvedBy(),
                request.approvalExpiresAt(),
                request.leaseGeneration(),
                request.fence()),
            observation);
    return new PreparedTurn(sessionId, turnId, decided.state().name(), preview);
  }

  private TurnView awaitDecision(String sessionId, String turnId, Instant deadline) {
    while (true) {
      TurnView view = runtime.turn(sessionId, turnId);
      if (view.state() == TurnState.DECIDED) {
        return view;
      }
      if (view.state().terminal()) {
        throw new TurnException(
            "PROVIDER_REFUSED", "The provider turn ended in " + view.state().name());
      }
      if (!clock.instant().isBefore(deadline)) {
        throw new TurnException(
            "PROVIDER_TIMEOUT", "The provider turn did not decide before its deadline");
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new TurnException("PROVIDER_TIMEOUT", "The provider turn wait was interrupted");
      }
    }
  }

  /** Everything the caller must pin for the provider turn. */
  public record TurnPreparation(
      Provider provider,
      String modelId,
      String taskKey,
      String runId,
      String recordingId,
      JsonNode identities,
      JsonNode budgets,
      BudgetLimits limits,
      Instant deadline,
      String requestId,
      String idempotencyKey,
      String authorityId,
      String approvedBy,
      Instant approvalExpiresAt,
      long leaseGeneration,
      long fence,
      String sourceId,
      long estimatedInputTokens,
      long estimatedOutputTokens) {}

  /** The decided turn and the frozen draft it grounds. */
  public record PreparedTurn(
      String sessionId,
      String turnId,
      String turnState,
      BootstrapV2DraftService.Preview preview) {}

  /** Stable, non-secret turn failure. */
  public static final class TurnException extends IllegalArgumentException {
    private final String failureCode;

    public TurnException(String failureCode, String message) {
      super(message == null ? failureCode : message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
