package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderAdapter.ProviderInvocation;
import com.chromascape.web.gameplay.provider.runtime.ProviderAdapter.ProviderTurnResult;
import com.chromascape.web.gameplay.provider.runtime.ProviderOutputNormalizer.NormalizedDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.Readiness;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionView;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnView;
import jakarta.annotation.PreDestroy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/** Durable decision-only application service for Claude and Codex turns. */
@Service
public final class GameplayProviderRuntime implements ProviderDecisionHandoff {
  private final ProviderRuntimeProperties properties;
  private final ProviderRuntimeValidator validator;
  private final ProviderRuntimeStore store;
  private final ProviderPromptBuilder promptBuilder;
  private final ProviderOutputNormalizer normalizer;
  private final RedactedTranscriptStore transcripts;
  private final DurableProviderToolGateway toolGateway;
  private final Map<Provider, ProviderAdapter> adapters;
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  public GameplayProviderRuntime(
      ProviderRuntimeProperties properties,
      ProviderRuntimeValidator validator,
      ProviderRuntimeStore store,
      ProviderPromptBuilder promptBuilder,
      ProviderOutputNormalizer normalizer,
      RedactedTranscriptStore transcripts,
      DurableProviderToolGateway toolGateway,
      List<ProviderAdapter> adapters) {
    this.properties = properties;
    this.validator = validator;
    this.store = store;
    this.promptBuilder = promptBuilder;
    this.normalizer = normalizer;
    this.transcripts = transcripts;
    this.toolGateway = toolGateway;
    EnumMap<Provider, ProviderAdapter> byProvider = new EnumMap<>(Provider.class);
    adapters.forEach(adapter -> byProvider.put(adapter.provider(), adapter));
    this.adapters = Map.copyOf(byProvider);
  }

  public Readiness readiness() {
    EnumMap<Provider, ProviderRuntimeModels.ProviderReadiness> values =
        new EnumMap<>(Provider.class);
    adapters.forEach((provider, adapter) -> values.put(provider, adapter.readiness()));
    return new Readiness(
        ProviderRuntimeModels.VERSION,
        properties.isEnabled(),
        properties.getOperatorKey() != null && properties.getOperatorKey().length() >= 32,
        Map.copyOf(values));
  }

  public SessionView createSession(SessionRequest request) {
    requireEnabled();
    validator.validateSession(request);
    if (!allowsModel(request.provider(), request.modelId())) {
      throw new ProviderRuntimeException("MODEL_NOT_ALLOWED", "Provider model is not allowlisted");
    }
    ProviderAdapter adapter = adapter(request.provider());
    if (!adapter.readiness().available()) {
      throw new ProviderRuntimeException(
          FailureCode.PROVIDER_UNAVAILABLE.name(), "Requested provider is unavailable");
    }
    return store.createSession(request);
  }

  public Submission submitTurn(String sessionId, TurnRequest request) {
    requireEnabled();
    SessionRequest session = store.sessionRequest(sessionId);
    validator.validateTurn(session, request);
    ProviderRuntimeStore.TurnReservation reservation = store.reserveTurn(sessionId, request);
    if (reservation.created() && reservation.turn().state() == TurnState.RESERVED) {
      executor.execute(() -> execute(session, request));
    }
    return new Submission(reservation.turn(), reservation.created());
  }

  public SessionView session(String sessionId) {
    return store
        .findSession(sessionId)
        .orElseThrow(
            () ->
                new ProviderRuntimeException(
                    "SESSION_NOT_FOUND", "Provider session was not found"));
  }

  public TurnView turn(String sessionId, String turnId) {
    return store
        .findTurn(sessionId, turnId)
        .orElseThrow(
            () -> new ProviderRuntimeException("TURN_NOT_FOUND", "Provider turn was not found"));
  }

  public List<TurnView> turns(String sessionId) {
    session(sessionId);
    return store.turnsForSession(sessionId);
  }

  public TurnView reconcile(String sessionId, String turnId) {
    TurnView turn = turn(sessionId, turnId);
    if (turn.state() != TurnState.NEEDS_RECONCILIATION) {
      return turn;
    }
    ProviderAdapter.ReconciliationResult result =
        adapter(turn.provider()).reconcile(turn.providerSessionRef(), turn.providerTurnRef());
    if (!result.terminalProven()) {
      return turn;
    }
    if (result.rawDecision() == null) {
      return turn;
    }
    TurnRequest request = store.turnRequest(turnId);
    NormalizedDecision normalized =
        normalizer.normalize(result.rawDecision(), turn.provider(), turn.modelId(), request);
    return store.finish(
        turnId,
        normalized.state(),
        FailureCode.NONE,
        normalized.decision(),
        result.usage(),
        turn.transcriptRef());
  }

  public TurnView cancel(String sessionId, String turnId, String reason) {
    TurnView turn = turn(sessionId, turnId);
    if (turn.state().terminal()) {
      return turn;
    }
    ProviderAdapter.CancelResult result =
        adapter(turn.provider()).cancel(turn.providerSessionRef(), turn.providerTurnRef(), reason);
    if (result.terminalProven()) {
      return store.finish(
          turnId, TurnState.CANCELLED, FailureCode.CANCELLED, null, null, turn.transcriptRef());
    }
    return store.markNeedsReconciliation(turnId, FailureCode.NEEDS_RECONCILIATION);
  }

  @Override
  public Optional<PersistedDecision> findValidatedDecision(String sessionId, String turnId) {
    Optional<TurnView> turn = store.findTurn(sessionId, turnId);
    if (turn.isEmpty()
        || turn.get().state() != TurnState.DECIDED
        || turn.get().decision() == null) {
      return Optional.empty();
    }
    TurnView value = turn.get();
    SessionView session = store.findSession(sessionId).orElse(null);
    if (session == null) {
      return Optional.empty();
    }
    // The transcript artifact itself is verified here: the exact stored bytes are re-hashed and
    // compared against the recorded reference. A transcriptSha256 value is therefore never a
    // digest-shaped assumption; it is the verified content hash of the durable artifact.
    RedactedTranscriptStore.Verification transcript = transcripts.verify(value.transcriptRef());
    String transcriptSha256 = transcript.digest();
    return Optional.of(
        new PersistedDecision(
            value.sessionId(),
            value.turnId(),
            value.requestId(),
            value.provider(),
            value.modelId(),
            value.decisionDigest(),
            value.semanticActionDigest(),
            value.observationDigest(),
            session.promptDigest(),
            session.toolsetDigest(),
            session.knowledgeDigest(),
            session.goalPlanDigest(),
            session.taskSkillDigests(),
            value.transcriptRef(),
            transcriptSha256,
            value.state(),
            value.failureCode(),
            value.decision()));
  }

  @Override
  public Optional<String> findProviderTranscript(String sessionId, String turnId) {
    Optional<TurnView> turn = store.findTurn(sessionId, turnId);
    if (turn.isEmpty() || turn.get().transcriptRef() == null) {
      return Optional.empty();
    }
    // The store verifies the artifact bytes against the recorded reference before returning them,
    // so a transcript that does not match its digest can never reach a consumer.
    return Optional.of(transcripts.read(turn.get().transcriptRef()));
  }

  void recoverAtStartup() {
    for (TurnView turn : store.recoverableTurns()) {
      if (turn.state().submitted()) {
        store.markNeedsReconciliation(turn.turnId(), FailureCode.NEEDS_RECONCILIATION);
      } else {
        SessionRequest session = store.sessionRequest(turn.sessionId());
        TurnRequest request = store.turnRequest(turn.turnId());
        executor.execute(() -> execute(session, request));
      }
    }
  }

  private void execute(SessionRequest session, TurnRequest request) {
    ProviderAdapter adapter = adapter(session.provider());
    String providerSessionRef = session.provider().name().toLowerCase() + ":" + session.sessionId();
    String providerTurnRef = session.provider().name().toLowerCase() + ":" + request.turnId();
    try {
      // Conservative ordering: once invocation is attempted, restart recovery never retries
      // blindly.
      store.markSubmitted(request.turnId(), providerSessionRef, providerTurnRef, List.of());
      ProviderTurnResult result =
          adapter.startTurn(
              new ProviderInvocation(
                  session.sessionId(),
                  request.turnId(),
                  session.modelId(),
                  promptBuilder.build(session, request),
                  request.deadline(),
                  toolGateway.bind(session, request)));
      if (!result.terminal()) {
        if (result.submitted()) {
          store.markStreaming(request.turnId());
          FailureCode reconciliationCode =
              result.failureCode() == FailureCode.NONE
                  ? FailureCode.NEEDS_RECONCILIATION
                  : result.failureCode();
          store.markNeedsReconciliation(request.turnId(), reconciliationCode);
        } else {
          store.finish(
              request.turnId(),
              failureState(result.failureCode()),
              result.failureCode(),
              null,
              result.usage(),
              null);
        }
        return;
      }
      RedactedTranscriptStore.Artifact artifact = transcripts.store(result.redactedTranscript());
      store.recordTranscript(request.turnId(), artifact.reference(), artifact.byteLength());
      if (result.submitted()) {
        store.markStreaming(request.turnId());
      }
      if (result.failureCode() != FailureCode.NONE) {
        store.finish(
            request.turnId(),
            failureState(result.failureCode()),
            result.failureCode(),
            null,
            result.usage(),
            artifact.reference());
        return;
      }
      store.markNormalizing(request.turnId());
      NormalizedDecision normalized =
          normalizer.normalize(
              result.rawDecision(), session.provider(), session.modelId(), request);
      store.finish(
          request.turnId(),
          normalized.state(),
          FailureCode.NONE,
          normalized.decision(),
          result.usage(),
          artifact.reference());
    } catch (ProviderRuntimeException exception) {
      FailureCode code = parseFailure(exception.failureCode());
      TurnView current = store.findTurn(session.sessionId(), request.turnId()).orElse(null);
      if (current != null && !current.state().terminal()) {
        if (current.state() == TurnState.SUBMITTED || current.state() == TurnState.STREAMING) {
          store.markNeedsReconciliation(request.turnId(), code);
        } else {
          TurnState state =
              code == FailureCode.TRANSCRIPT_PERSISTENCE_FAILED
                  ? TurnState.INTERNAL_FAILURE
                  : TurnState.INVALID_OUTPUT;
          store.finish(request.turnId(), state, code, null, null, current.transcriptRef());
        }
      }
    } catch (RuntimeException exception) {
      TurnView current = store.findTurn(session.sessionId(), request.turnId()).orElse(null);
      if (current != null && !current.state().terminal()) {
        store.markNeedsReconciliation(request.turnId(), FailureCode.INTERNAL_FAILURE);
      }
    }
  }

  private FailureCode parseFailure(String value) {
    try {
      return FailureCode.valueOf(value);
    } catch (IllegalArgumentException exception) {
      return FailureCode.INVALID_OUTPUT;
    }
  }

  private TurnState failureState(FailureCode code) {
    return switch (code) {
      case PROVIDER_UNAVAILABLE -> TurnState.PROVIDER_UNAVAILABLE;
      case PROCESS_START_TIMEOUT,
          FIRST_EVENT_TIMEOUT,
          TOOL_RESPONSE_TIMEOUT,
          PROCESS_TIMEOUT_BEFORE_SUBMISSION,
          DEADLINE_EXPIRED -> TurnState.TIMEOUT;
      case BUDGET_EXHAUSTED -> TurnState.BUDGET_EXHAUSTED;
      default -> TurnState.INTERNAL_FAILURE;
    };
  }

  private boolean allowsModel(Provider provider, String modelId) {
    return switch (provider) {
      case CLAUDE -> properties.getClaude().allowsModel(modelId);
      case CODEX -> properties.getCodex().allowsModel(modelId);
      case MISTRAL -> properties.getMistral().allowsModel(modelId);
    };
  }

  private ProviderAdapter adapter(Provider provider) {
    ProviderAdapter result = adapters.get(provider);
    if (result == null) {
      throw new ProviderRuntimeException(
          FailureCode.PROVIDER_UNAVAILABLE.name(), "Requested provider adapter is unavailable");
    }
    return result;
  }

  private void requireEnabled() {
    if (!properties.isEnabled()) {
      throw new ProviderRuntimeException(
          FailureCode.RUNTIME_DISABLED.name(), "Provider runtime is disabled");
    }
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  public record Submission(TurnView turn, boolean created) {}
}
