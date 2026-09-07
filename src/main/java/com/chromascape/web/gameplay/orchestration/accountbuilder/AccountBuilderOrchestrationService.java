package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Readiness;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Durable, source-neutral SC-1431 orchestration with no blind retry path. */
@Service
public final class AccountBuilderOrchestrationService {
  private final AccountBuilderOrchestrationValidator validator;
  private final CanonicalGameplayJson canonicalJson;
  private final AccountBuilderOrchestrationStore store;
  private final AccountBuilderOrchestrationGateway gateway;
  private final AccountBuilderOrchestrationProperties properties;
  private final Clock clock;

  @Autowired
  public AccountBuilderOrchestrationService(
      AccountBuilderOrchestrationValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderOrchestrationStore store,
      AccountBuilderOrchestrationGateway gateway,
      AccountBuilderOrchestrationProperties properties) {
    this(validator, canonicalJson, store, gateway, properties, Clock.systemUTC());
  }

  AccountBuilderOrchestrationService(
      AccountBuilderOrchestrationValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderOrchestrationStore store,
      AccountBuilderOrchestrationGateway gateway,
      AccountBuilderOrchestrationProperties properties,
      Clock clock) {
    this.validator = validator;
    this.canonicalJson = canonicalJson;
    this.store = store;
    this.gateway = gateway;
    this.properties = properties;
    this.clock = clock;
  }

  public Submission orchestrate(OrchestrationRequest request) {
    String fingerprint = validator.validateAndFingerprint(request);
    String envelopeDigest = canonicalJson.digest(request.envelope());
    AccountBuilderOrchestrationStore.Claim claim =
        store.claim(request, fingerprint, envelopeDigest);
    if (!claim.created()) {
      return new Submission(claim.view(), false);
    }

    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
    if (request.mode() == Mode.HELD || request.mode() == Mode.SHADOW) {
      return new Submission(
          store.transition(
              request.orchestrationId(), State.HELD, FailureCode.NONE, null, null, false, false),
          true);
    }
    if (request.mode() == Mode.SIMULATION) {
      return terminal(request, State.DENIED, FailureCode.MODE_NOT_DELIVERABLE, null, false);
    }
    if (!properties.isEnabled() || !properties.isLiveDeliveryEnabled()) {
      return terminal(request, State.DENIED, FailureCode.RUNTIME_DISABLED, null, false);
    }

    boolean authorityConsumed = false;
    try {
      CapabilitySnapshot capabilities = gateway.capabilities();
      if (capabilities == null || !capabilities.liveDeliveryReady()) {
        return terminal(request, State.DENIED, FailureCode.GATEWAY_UNAVAILABLE, null, false);
      }
      validator.validateCapabilities(request, capabilities);
      validator.validateAuthority(request, envelopeDigest, request.authority());

      // Authority ownership is its own short transaction and is committed before reserve. If the
      // process dies after this call, the orchestration is recoverable but never redispatched.
      store.consumeAuthority(
          request.orchestrationId(), request.authority(), envelopeDigest, request);
      authorityConsumed = true;
      store.transition(
          request.orchestrationId(), State.AUTHORIZED, FailureCode.NONE, null, null, false, false);

      GatewayReceipt reservation;
      try {
        reservation = gateway.reserve(request, envelopeDigest);
        requireBoundReceipt(request, envelopeDigest, reservation, null);
      } catch (RuntimeException exception) {
        return uncertain(request, uncertaintyCode(exception));
      }

      if (reservation.state() != State.RESERVED) {
        if (provenReservationOutcome(reservation)) {
          FailureCode code =
              reservation.failureCode() == FailureCode.NONE
                  ? FailureCode.RESERVATION_REJECTED
                  : reservation.failureCode();
          return terminal(
              request, reservation.state(), code, withFailureCode(reservation, code), false);
        }
        return uncertain(request, FailureCode.DELIVERY_UNCERTAIN);
      }
      if (!validLease(request, reservation.lease())) {
        return uncertain(request, FailureCode.DELIVERY_UNCERTAIN);
      }
      OrchestrationView reserved =
          store.transition(
              request.orchestrationId(),
              State.RESERVED,
              FailureCode.NONE,
              reservation.lease(),
              reservation,
              false,
              false);
      return new Submission(deliver(request, envelopeDigest, reserved.lease()), true);
    } catch (AccountBuilderOrchestrationException exception) {
      return authorityConsumed
          ? uncertain(request, uncertaintyCode(exception))
          : terminal(request, State.DENIED, exception.failureCode(), null, false);
    } catch (RuntimeException exception) {
      return authorityConsumed
          ? uncertain(request, uncertaintyCode(exception))
          : terminal(request, State.DENIED, FailureCode.GATEWAY_UNAVAILABLE, null, false);
    }
  }

  public OrchestrationView find(String orchestrationId) {
    return store
        .find(orchestrationId)
        .orElseThrow(
            () ->
                new AccountBuilderOrchestrationException(
                    FailureCode.INVALID_CONTRACT, "Orchestration was not found"));
  }

  public OrchestrationView stop(String orchestrationId, String reason) {
    OrchestrationView view = find(orchestrationId);
    if (reason == null
        || reason.isBlank()
        || reason.length() > 256
        || view.state().provenTerminal()
        || view.state() == State.HELD) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STOP_REJECTED, "A bounded stop requires active orchestration state");
    }
    try {
      GatewayCorrelation correlation = store.correlation(orchestrationId);
      Lease lease = store.requireLease(orchestrationId, view.lease());
      GatewayReceipt receipt = gateway.stop(correlation, lease, reason);
      requireBoundReceipt(correlation, receipt, lease);
      State state =
          receipt.terminalReconciled() && receipt.state().provenTerminal()
              ? receipt.state()
              : State.NEEDS_RECONCILIATION;
      FailureCode code =
          state == State.NEEDS_RECONCILIATION
              ? FailureCode.NEEDS_RECONCILIATION
              : receipt.failureCode();
      return store.transition(orchestrationId, state, code, lease, receipt, false, false);
    } catch (RuntimeException exception) {
      return store.transition(
          orchestrationId,
          State.NEEDS_RECONCILIATION,
          FailureCode.NEEDS_RECONCILIATION,
          null,
          null,
          false,
          false);
    }
  }

  public OrchestrationView reconcile(String orchestrationId) {
    OrchestrationView view = find(orchestrationId);
    if (view.state().terminal() && view.state() != State.NEEDS_RECONCILIATION) {
      return view;
    }
    try {
      AccountBuilderOrchestrationStore.NormalizedAggregate aggregate =
          store.readAggregate(orchestrationId);
      GatewayCorrelation correlation = aggregate.correlation();
      if (aggregate.lease() == null) {
        // A consumed authority with no lease is an ambiguous reservation boundary. The only
        // permitted remote operation is a lookup keyed by the durable non-secret correlation.
        if (!aggregate.authorityConsumed() || view.state() != State.NEEDS_RECONCILIATION) {
          return view;
        }
        return reconcileReservation(orchestrationId, correlation, view);
      }
      Lease lease = store.requireLease(orchestrationId, aggregate.lease());
      GatewayReceipt receipt = gateway.reconcile(correlation, lease);
      requireBoundReceipt(correlation, receipt, lease);
      if (!receipt.terminalReconciled() || !receipt.state().provenTerminal()) {
        return store.transition(
            orchestrationId,
            State.NEEDS_RECONCILIATION,
            FailureCode.NEEDS_RECONCILIATION,
            lease,
            receipt,
            false,
            false);
      }
      return store.transition(
          orchestrationId, receipt.state(), receipt.failureCode(), lease, receipt, false, false);
    } catch (RuntimeException exception) {
      return view;
    }
  }

  private OrchestrationView reconcileReservation(
      String orchestrationId, GatewayCorrelation correlation, OrchestrationView current) {
    try {
      GatewayReceipt receipt = gateway.reconcileReservation(correlation);
      requireBoundReceipt(correlation, receipt, null);
      if (receipt.state() == State.DENIED || receipt.state() == State.FAILED) {
        FailureCode code =
            receipt.failureCode() == FailureCode.NONE
                ? FailureCode.RESERVATION_REJECTED
                : receipt.failureCode();
        if (receipt.terminalReconciled()) {
          return store.transition(
              orchestrationId,
              receipt.state(),
              code,
              null,
              withFailureCode(receipt, code),
              false,
              false);
        }
      } else if (receipt.terminalReconciled() && receipt.state().provenTerminal()) {
        FailureCode code =
            receipt.state() == State.DENIED && receipt.failureCode() == FailureCode.NONE
                ? FailureCode.RESERVATION_REJECTED
                : receipt.failureCode();
        return store.transition(
            orchestrationId,
            receipt.state(),
            code,
            receipt.lease(),
            withFailureCode(receipt, code),
            false,
            false);
      } else if (receipt.state() == State.RESERVED && receipt.lease() != null) {
        // A lookup may discover an exact remote reservation. Keep the orchestration quiescent;
        // acquiring the lease is evidence, never permission to redispatch.
        return store.transition(
            orchestrationId,
            State.NEEDS_RECONCILIATION,
            FailureCode.DELIVERY_UNCERTAIN,
            receipt.lease(),
            receipt,
            false,
            false);
      } else {
        // A well-formed but non-terminal lookup response is useful evidence, but it cannot move
        // the orchestration back into a delivery state.
        return store.transition(
            orchestrationId,
            State.NEEDS_RECONCILIATION,
            FailureCode.DELIVERY_UNCERTAIN,
            receipt.lease(),
            receipt,
            false,
            false);
      }
    } catch (RuntimeException exception) {
      // Missing, malformed, cross-request and ambiguous lookup results remain quiescent. In
      // particular, this path never invokes reserve or dispatch as a recovery fallback.
      return current;
    }
    return current;
  }

  public List<OrchestrationView> recoverAfterRestart() {
    return store.recoverable().stream()
        .map(
            view ->
                view.state() == State.NEEDS_RECONCILIATION
                    ? view
                    : store.transition(
                        view.orchestrationId(),
                        State.NEEDS_RECONCILIATION,
                        FailureCode.NEEDS_RECONCILIATION,
                        null,
                        null,
                        false,
                        false))
        .toList();
  }

  public Readiness readiness() {
    CapabilitySnapshot capabilities;
    try {
      capabilities = gateway.capabilities();
    } catch (RuntimeException exception) {
      capabilities = null;
    }
    boolean gatewayAvailable =
        capabilities != null
            && capabilities.expiresAt() != null
            && capabilities.expiresAt().isAfter(clock.instant());
    return new Readiness(
        AccountBuilderOrchestrationModels.VERSION,
        properties.isEnabled(),
        properties.getOperatorKey() != null && properties.getOperatorKey().length() >= 32,
        gatewayAvailable,
        gatewayAvailable && capabilities.liveDeliveryReady() && properties.isLiveDeliveryEnabled(),
        gatewayAvailable ? capabilities.capabilityDigest() : null,
        gatewayAvailable ? FailureCode.NONE : FailureCode.GATEWAY_UNAVAILABLE);
  }

  private OrchestrationView deliver(
      OrchestrationRequest request, String envelopeDigest, Lease lease) {
    try {
      Lease storedLease = store.requireLease(request.orchestrationId(), lease);
      // Persist the submission boundary before the network call. A restart can therefore
      // distinguish an unattempted reservation from a call whose outcome must be reconciled.
      OrchestrationView submitted =
          store.transition(
              request.orchestrationId(),
              State.DISPATCH_SUBMITTED,
              FailureCode.NONE,
              storedLease,
              null,
              false,
              false);
      Lease dispatchLease = store.requireLease(request.orchestrationId(), submitted.lease());
      GatewayReceipt dispatch = gateway.dispatch(request, envelopeDigest, dispatchLease);
      requireBoundReceipt(request, envelopeDigest, dispatch, dispatchLease);
      if (dispatch.state().provenTerminal() && dispatch.terminalReconciled()) {
        return store.transition(
            request.orchestrationId(),
            dispatch.state(),
            dispatch.failureCode(),
            dispatchLease,
            dispatch,
            false,
            false);
      }
      // A transport acknowledgement is not activation. Only an explicitly ACTIVE receipt may
      // cross the dispatch boundary; every other response remains quiescent and reconcile-only.
      if (!dispatch.deliveryAcknowledged() || dispatch.state() != State.ACTIVE) {
        return store.transition(
            request.orchestrationId(),
            State.NEEDS_RECONCILIATION,
            FailureCode.DELIVERY_UNCERTAIN,
            dispatchLease,
            dispatch,
            false,
            false);
      }
      OrchestrationView dispatched =
          store.transition(
              request.orchestrationId(),
              dispatch.state(),
              FailureCode.NONE,
              dispatchLease,
              dispatch,
              false,
              false);
      Lease statusLease = store.requireLease(request.orchestrationId(), dispatched.lease());
      GatewayReceipt status =
          gateway.status(store.correlation(request.orchestrationId()), statusLease);
      requireBoundReceipt(store.correlation(request.orchestrationId()), status, statusLease);
      if (!status.terminalReconciled() || !status.state().provenTerminal()) {
        return store.transition(
            request.orchestrationId(),
            State.NEEDS_RECONCILIATION,
            FailureCode.NEEDS_RECONCILIATION,
            statusLease,
            status,
            false,
            false);
      }
      return store.transition(
          request.orchestrationId(),
          status.state(),
          status.failureCode(),
          statusLease,
          status,
          false,
          false);
    } catch (RuntimeException exception) {
      return store.transition(
          request.orchestrationId(),
          State.NEEDS_RECONCILIATION,
          uncertaintyCode(exception),
          null,
          null,
          false,
          false);
    }
  }

  private Submission terminal(
      OrchestrationRequest request,
      State state,
      FailureCode code,
      GatewayReceipt receipt,
      boolean retryAllowed) {
    return new Submission(
        store.transition(
            request.orchestrationId(),
            state,
            code,
            receipt == null ? null : receipt.lease(),
            receipt,
            false,
            retryAllowed),
        true);
  }

  private Submission uncertain(OrchestrationRequest request, FailureCode code) {
    return new Submission(
        store.transition(
            request.orchestrationId(), State.NEEDS_RECONCILIATION, code, null, null, false, false),
        true);
  }

  private FailureCode uncertaintyCode(RuntimeException exception) {
    if (exception instanceof AccountBuilderOrchestrationException orchestrationException) {
      return switch (orchestrationException.failureCode()) {
        case BOUNDARY_VIOLATION,
            FENCE_MISMATCH,
            LEASE_EXPIRED,
            DELIVERY_UNCERTAIN -> orchestrationException.failureCode();
        default -> FailureCode.DELIVERY_UNCERTAIN;
      };
    }
    return FailureCode.DELIVERY_UNCERTAIN;
  }

  private void requireBoundReceipt(
      OrchestrationRequest request,
      String envelopeDigest,
      GatewayReceipt receipt,
      Lease expectedLease) {
    if (receipt == null
        || !request.requestId().equals(receipt.requestId())
        || !envelopeDigest.equals(receipt.envelopeDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.BOUNDARY_VIOLATION,
          "Gateway receipt identity does not match the immutable request");
    }
    if (expectedLease != null && !expectedLease.equals(receipt.lease())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.FENCE_MISMATCH, "Gateway receipt lease does not match the stored lease");
    }
  }

  private void requireBoundReceipt(
      GatewayCorrelation correlation, GatewayReceipt receipt, Lease expectedLease) {
    if (receipt == null
        || !correlation.requestId().equals(receipt.requestId())
        || !correlation.envelopeDigest().equals(receipt.envelopeDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.BOUNDARY_VIOLATION,
          "Gateway receipt identity does not match the persisted correlation");
    }
    if (expectedLease != null && !expectedLease.equals(receipt.lease())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.FENCE_MISMATCH, "Gateway receipt lease does not match the stored lease");
    }
  }

  private GatewayReceipt withFailureCode(GatewayReceipt receipt, FailureCode failureCode) {
    if (receipt.failureCode() == failureCode) {
      return receipt;
    }
    return new GatewayReceipt(
        receipt.requestId(),
        receipt.envelopeDigest(),
        receipt.phase(),
        receipt.state(),
        failureCode,
        receipt.deliveryAcknowledged(),
        receipt.terminalReconciled(),
        receipt.lease(),
        receipt.evidenceRefs(),
        receipt.at(),
        receipt.receiptId());
  }

  private boolean validLease(OrchestrationRequest request, Lease lease) {
    return lease != null
        && lease.reservationId() != null
        && lease.reservationId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        && lease.generation() >= 1
        && lease.fence() >= 1
        && lease.fenceTokenDigest() != null
        && lease.fenceTokenDigest().matches("sha256:[0-9a-f]{64}")
        && lease.generation() == request.envelope().leaseGeneration()
        && lease.fence() == request.envelope().fence()
        && lease.expiresAt() != null
        && lease.expiresAt().isAfter(clock.instant());
  }

  private boolean provenReservationOutcome(GatewayReceipt receipt) {
    return receipt != null
        && receipt.state() != null
        && receipt.state().provenTerminal()
        && receipt.terminalReconciled()
        && (receipt.lease() == null
            || (receipt.lease().expiresAt() != null
                && receipt.lease().expiresAt().isAfter(clock.instant())));
  }

  public record Submission(OrchestrationView view, boolean created) {}
}
