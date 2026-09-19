package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionView;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** Binds the closed decision tools to one turn with durable idempotency and budget charging. */
@Component
public final class DurableProviderToolGateway {
  private final ProviderRuntimeStore store;
  private final DecisionToolFacade facade;
  private final CanonicalGameplayJson canonicalJson;
  private final ProviderRuntimeProperties properties;

  public DurableProviderToolGateway(
      ProviderRuntimeStore store,
      DecisionToolFacade facade,
      CanonicalGameplayJson canonicalJson,
      ProviderRuntimeProperties properties) {
    this.store = store;
    this.facade = facade;
    this.canonicalJson = canonicalJson;
    this.properties = properties;
  }

  public ProviderToolGateway bind(SessionRequest session, TurnRequest turn) {
    return (toolCallId, toolName, arguments) ->
        invoke(session, turn, toolCallId, toolName, arguments);
  }

  private JsonNode invoke(
      SessionRequest session,
      TurnRequest turn,
      String toolCallId,
      String toolName,
      Map<String, String> arguments) {
    String argumentsDigest = canonicalJson.digest(arguments);
    ProviderRuntimeStore.ToolCallReservation reservation =
        store.reserveToolCall(turn.turnId(), toolCallId, toolName, argumentsDigest);
    if (!reservation.created() && !reservation.completed()) {
      throw new ProviderRuntimeException(
          FailureCode.IDEMPOTENCY_CONFLICT.name(),
          "Repeated provider tool call is not a completed immutable result");
    }
    try {
      SessionView status =
          store
              .findSession(session.sessionId())
              .orElseThrow(
                  () ->
                      new ProviderRuntimeException(
                          "SESSION_NOT_FOUND", "Provider session was not found"));
      JsonNode result =
          CompletableFuture.supplyAsync(
                  () -> facade.invoke(toolName, arguments, session, turn, status))
              .get(properties.getToolResponseTimeoutMillis(), TimeUnit.MILLISECONDS);
      String resultDigest = canonicalJson.digest(result);
      if (!reservation.created()
          && reservation.completed()
          && !resultDigest.equals(reservation.resultDigest())) {
        throw new ProviderRuntimeException(
            FailureCode.IDEMPOTENCY_CONFLICT.name(),
            "Repeated provider tool call produced a different result digest");
      }
      store.finishToolCall(toolCallId, resultDigest, true);
      return result;
    } catch (TimeoutException exception) {
      return fail(toolCallId, reservation, FailureCode.TOOL_RESPONSE_TIMEOUT);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return fail(toolCallId, reservation, FailureCode.TOOL_RESPONSE_TIMEOUT);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof ProviderRuntimeException providerFailure) {
        recordFailure(toolCallId, reservation, providerFailure.failureCode());
        throw providerFailure;
      }
      return fail(toolCallId, reservation, FailureCode.INTERNAL_FAILURE);
    } catch (ProviderRuntimeException exception) {
      recordFailure(toolCallId, reservation, exception.failureCode());
      throw exception;
    }
  }

  private JsonNode fail(
      String toolCallId, ProviderRuntimeStore.ToolCallReservation reservation, FailureCode code) {
    recordFailure(toolCallId, reservation, code.name());
    throw new ProviderRuntimeException(code.name(), "Provider tool call failed closed");
  }

  private void recordFailure(
      String toolCallId, ProviderRuntimeStore.ToolCallReservation reservation, String failureCode) {
    if (reservation.created() || !reservation.completed()) {
      store.finishToolCall(toolCallId, canonicalJson.digest(failureCode), false);
    }
  }
}
