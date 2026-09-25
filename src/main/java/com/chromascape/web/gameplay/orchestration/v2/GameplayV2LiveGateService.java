package com.chromascape.web.gameplay.orchestration.v2;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.Provider;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EnvelopeRules;
import com.chromascape.web.gameplay.contract.v2.GameplayV2ValidatedEligibility;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.Failure;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2Bundle;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2ReserveResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StartResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StatusResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.ValidatedBundleSnapshot;
import com.chromascape.web.gameplay.provider.v2.GameplayV2ProviderAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ChromaScape additive SC-1429/v2 live gate for SC-1449. Enforces the closed request sequence
 * before any Micro6 network delivery: immutable snapshot, schema validation, digest verification,
 * deterministic eligibility replay, provider decision plus GoalPlan verification, full envelope
 * binding, human authority plus exact-scope verification, durable idempotency reservation, lease
 * and fence proof, atomic one-use authority consumption, then a persisted {@code RESERVED} row. All
 * gate state lives behind {@link GameplayV2GateStore}; nothing authoritative is held in memory, so
 * idempotency, authority consumption and reconciliation survive a restart. No HTTP call may occur
 * inside a persistence transaction: {@link #reserve} persists {@code RESERVED} first and only then
 * calls {@link Micro6GameplayV2Gateway#reserve}, while {@link #start} claims the durable {@code
 * STARTING} state first and only the winning caller transmits. Every response receipt is fully
 * verified — HTTP status, request identity, envelope digest, closed state, delivery acknowledgement
 * and evidence — before a row may advance. Denials produce zero gateway calls.
 */
@Service
public class GameplayV2LiveGateService {
  private static final Set<V2State> START_CLAIM_STATES =
      Set.of(V2State.RESERVED, V2State.DELIVERY_PENDING);
  private static final Set<V2State> STOP_CLAIM_STATES =
      Set.of(
          V2State.RESERVED,
          V2State.DELIVERY_PENDING,
          V2State.STARTING,
          V2State.DELIVERED,
          V2State.ACTIVE);
  private static final Set<String> REJECTION_FAILURE_CODES =
      Set.of(
          "VERSION_MISMATCH",
          "MODE_NOT_DELIVERABLE",
          "INVALID_BUNDLE",
          "INVALID_ENVELOPE",
          "INVALID_PROVIDER_DECISION",
          "PROVIDER_MISMATCH",
          "IDENTITY_MISMATCH",
          "VERIFIER_MISMATCH",
          "PROVIDER_BEFORE_ELIGIBILITY",
          "AUTHORITY_MISSING",
          "AUTHORITY_FORBIDDEN",
          "AUTHORITY_SOURCE_FORBIDDEN",
          "AUTHORITY_SCOPE_MISMATCH",
          "AUTHORITY_CHANGED",
          "AUTHORITY_REUSED",
          "AUTHORITY_CONSUMED",
          "AUTHORITY_SIGNING_UNAVAILABLE",
          "DEADLINE_EXPIRED",
          "AUTHORITY_EXPIRED",
          "FENCE_MISMATCH",
          "LEASE_SLOT_OCCUPIED",
          "UNSUPPORTED_CAPABILITY",
          "BUDGET_EXHAUSTED",
          "IDEMPOTENCY_CONFLICT",
          "V2_DISABLED",
          "EXECUTOR_UNAVAILABLE",
          "RESERVATION_NOT_STARTABLE",
          "START_REJECTED",
          "QUEUE_REJECTED");
  private static final Set<V2State> ANY_NON_TERMINAL =
      EnumSet.complementOf(
          EnumSet.of(
              V2State.CONFIRMED,
              V2State.REFUTED,
              V2State.UNVERIFIABLE,
              V2State.FAILED,
              V2State.STOPPED));

  private final GameplayV2ContractValidator validator;
  private final Map<Provider, GameplayV2ProviderAdapter> adapters;
  private final ObjectMapper objectMapper;
  private final Micro6GameplayV2Gateway gateway;
  private final GameplayV2GateStore store;
  private final Clock clock;
  private final boolean liveEnabled;
  private final String signingSecret;

  @Autowired
  public GameplayV2LiveGateService(
      GameplayV2ContractValidator validator,
      List<GameplayV2ProviderAdapter> adapters,
      ObjectMapper objectMapper,
      Micro6GameplayV2Gateway gateway,
      GameplayV2GateStore store,
      @Value("${gameplay.v2.live-enabled:false}") boolean liveEnabled,
      @Value("${gameplay.bootstrap.authority-hmac-secret:}") String signingSecret) {
    this(
        validator,
        adapters,
        objectMapper,
        gateway,
        store,
        Clock.systemUTC(),
        liveEnabled,
        signingSecret);
  }

  GameplayV2LiveGateService(
      GameplayV2ContractValidator validator,
      GameplayV2ProviderAdapter adapter,
      ObjectMapper objectMapper,
      Micro6GameplayV2Gateway gateway,
      GameplayV2GateStore store,
      Clock clock,
      boolean liveEnabled,
      String signingSecret) {
    this(
        validator,
        List.of(adapter),
        objectMapper,
        gateway,
        store,
        clock,
        liveEnabled,
        signingSecret);
  }

  private GameplayV2LiveGateService(
      GameplayV2ContractValidator validator,
      List<GameplayV2ProviderAdapter> adapters,
      ObjectMapper objectMapper,
      Micro6GameplayV2Gateway gateway,
      GameplayV2GateStore store,
      Clock clock,
      boolean liveEnabled,
      String signingSecret) {
    this.validator = Objects.requireNonNull(validator, "validator");
    EnumMap<Provider, GameplayV2ProviderAdapter> adaptersByProvider = new EnumMap<>(Provider.class);
    for (GameplayV2ProviderAdapter adapter : adapters) {
      GameplayV2ProviderAdapter previous = adaptersByProvider.put(adapter.provider(), adapter);
      if (previous != null) {
        throw new IllegalArgumentException("Duplicate v2 provider adapter: " + adapter.provider());
      }
    }
    this.adapters = Map.copyOf(adaptersByProvider);
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.liveEnabled = liveEnabled;
    this.signingSecret = signingSecret == null ? "" : signingSecret;
  }

  /**
   * Reserves a validated live bundle. Validation, durable idempotency, authority consumption and
   * the {@code RESERVED} row all complete before the first Micro6 network call; a transport failure
   * or unprovable receipt after persistence returns {@code NEEDS_RECONCILIATION} without
   * redispatch. Contract violations fail closed as {@code FAILED} results carrying the stable code,
   * so every denial path produces zero gateway calls. Only the exact canonical bytes that passed
   * validation are ever transmitted.
   */
  public V2ReserveResult reserve(V2Bundle bundle) {
    Validated validated;
    try {
      validated = validate(bundle);
    } catch (GameplayV2ContractValidator.GameplayV2ContractViolation violation) {
      return denied(violation.failureCode(), requestIdOf(bundle), null);
    } catch (V2GateException gate) {
      return denied(gate.failureCode(), requestIdOf(bundle), null);
    }
    ValidatedBundleSnapshot snapshot = validated.snapshot();
    String fingerprint = validated.fingerprint();
    String idempotencyKey = snapshot.idempotencyKey();
    String requestId = snapshot.requestId();
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || requestId == null
        || requestId.isBlank()) {
      return denied(Failure.INVALID_BUNDLE, requestId, snapshot.envelopeDigest());
    }
    Optional<GameplayV2GateStore.GateRow> existing = store.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      return replayResult(existing.get(), fingerprint, snapshot.envelopeDigest());
    }
    try {
      store.insertReserved(reservedRow(snapshot, fingerprint));
    } catch (GameplayV2GateStore.GateConflictException conflict) {
      return resolveInsertConflict(conflict, snapshot, fingerprint);
    }
    return deliverReservation(snapshot, fingerprint);
  }

  /**
   * Starts a previously reserved request. The start identity must match the reservation exactly;
   * the persisted envelope deadline and human authority expiry are rechecked against the
   * authoritative row before the durable {@code RESERVED}/{@code DELIVERY_PENDING} to {@code
   * STARTING} compare-and-set is claimed, so only one caller ever dispatches a start and an expired
   * request never reaches Micro6.
   */
  public V2StartResult start(String requestId, long leaseGeneration, long fence) {
    Optional<GameplayV2GateStore.GateRow> found = store.findByRequestId(requestId);
    if (found.isEmpty()) {
      return new V2StartResult(
          V2State.FAILED, Failure.UNKNOWN_REQUEST, false, requestId, null, List.of());
    }
    GameplayV2GateStore.GateRow row = found.get();
    if (row.state().provenTerminal()) {
      return new V2StartResult(
          row.state(), row.failureCode(), true, requestId, row.envelopeDigest(), row.evidence());
    }
    if (row.state() == V2State.ACTIVE) {
      return new V2StartResult(
          V2State.ACTIVE, null, true, requestId, row.envelopeDigest(), row.evidence());
    }
    if (row.state() == V2State.STOPPING) {
      return new V2StartResult(
          V2State.STOPPING,
          Failure.STOP_IN_PROGRESS,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    if (row.state() != V2State.RESERVED && row.state() != V2State.DELIVERY_PENDING) {
      return new V2StartResult(
          row.state(),
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    if (leaseGeneration != row.leaseGeneration() || fence != row.fence()) {
      return new V2StartResult(
          row.state(),
          Failure.FENCE_MISMATCH,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    Instant now = clock.instant();
    // A missing validity window is not permission to start. Rows persisted before the expiry
    // columns existed are backfilled by V42, but treating a null as expired here keeps the start
    // path fail-closed for any row that reaches it without one.
    if (row.envelopeDeadline() == null || !now.isBefore(row.envelopeDeadline())) {
      store.compareAndSet(
          requestId,
          START_CLAIM_STATES,
          V2State.FAILED,
          Failure.DEADLINE_EXPIRED,
          row.acknowledged(),
          null,
          List.of("chromascape:deadline-expired:" + requestId));
      return authoratativeStartResult(requestId, row, Failure.DEADLINE_EXPIRED);
    }
    if (row.authorityExpiresAt() == null || !now.isBefore(row.authorityExpiresAt())) {
      store.compareAndSet(
          requestId,
          START_CLAIM_STATES,
          V2State.FAILED,
          Failure.AUTHORITY_EXPIRED,
          row.acknowledged(),
          null,
          List.of("chromascape:authority-expired:" + requestId));
      return authoratativeStartResult(requestId, row, Failure.AUTHORITY_EXPIRED);
    }
    boolean claimed =
        store.compareAndSet(
            requestId,
            START_CLAIM_STATES,
            V2State.STARTING,
            null,
            false,
            null,
            List.of("chromascape:starting:" + requestId));
    if (!claimed) {
      return authoratativeStartResult(requestId, row, Failure.START_IN_PROGRESS);
    }
    JsonNode started;
    try {
      started = gateway.start(envelopeFrom(row), row.idempotencyKey() + ":start");
    } catch (RuntimeException exception) {
      store.compareAndSet(
          requestId,
          Set.of(V2State.STARTING),
          V2State.NEEDS_RECONCILIATION,
          Failure.TRANSPORT_UNCERTAIN,
          false,
          null,
          List.of());
      return new V2StartResult(
          V2State.NEEDS_RECONCILIATION,
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          List.of());
    }
    String startRejection = definitiveRejection(started);
    if (startRejection != null) {
      store.compareAndSet(
          requestId,
          Set.of(V2State.STARTING),
          V2State.FAILED,
          startRejection,
          false,
          receiptJson(started),
          List.of());
      return new V2StartResult(
          V2State.FAILED, startRejection, false, requestId, row.envelopeDigest(), row.evidence());
    }
    if (!validStartReceipt(row, started)) {
      store.compareAndSet(
          requestId,
          Set.of(V2State.STARTING),
          V2State.NEEDS_RECONCILIATION,
          Failure.TRANSPORT_UNCERTAIN,
          false,
          receiptJson(started),
          List.of());
      return new V2StartResult(
          V2State.NEEDS_RECONCILIATION,
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          List.of());
    }
    boolean activated =
        store.compareAndSet(
            requestId,
            Set.of(V2State.STARTING),
            V2State.ACTIVE,
            null,
            true,
            receiptJson(started),
            List.of("micro6:start:" + requestId));
    if (!activated) {
      return authoratativeStartResult(requestId, row, Failure.NEEDS_RECONCILIATION);
    }
    GameplayV2GateStore.GateRow active = store.findByRequestId(requestId).orElse(row);
    return new V2StartResult(
        V2State.ACTIVE, null, false, requestId, row.envelopeDigest(), active.evidence());
  }

  /**
   * Returns the authoritative start outcome by reloading the durable row. A lost {@code
   * compareAndSet} means a concurrent stop, reconciliation, expiry or duplicate start already owns
   * the row; the loser must report that state instead of manufacturing its intended state.
   */
  private V2StartResult authoratativeStartResult(
      String requestId, GameplayV2GateStore.GateRow fallback, String lostRaceCode) {
    Optional<GameplayV2GateStore.GateRow> current = store.findByRequestId(requestId);
    if (current.isEmpty()) {
      return new V2StartResult(
          V2State.FAILED,
          Failure.UNKNOWN_REQUEST,
          false,
          requestId,
          fallback.envelopeDigest(),
          fallback.evidence());
    }
    GameplayV2GateStore.GateRow row = current.get();
    if (row.state().provenTerminal() || row.state() == V2State.ACTIVE) {
      return new V2StartResult(
          row.state(), row.failureCode(), true, requestId, row.envelopeDigest(), row.evidence());
    }
    return new V2StartResult(
        row.state(),
        row.failureCode() != null ? row.failureCode() : lostRaceCode,
        false,
        requestId,
        row.envelopeDigest(),
        row.evidence());
  }

  /** Observable status of a tracked request. Unknown requests are denied, never invented. */
  public V2StatusResult status(String requestId) {
    Optional<GameplayV2GateStore.GateRow> found = store.findByRequestId(requestId);
    if (found.isEmpty()) {
      return new V2StatusResult(
          V2State.FAILED, Failure.UNKNOWN_REQUEST, false, requestId, null, List.of());
    }
    GameplayV2GateStore.GateRow row = found.get();
    return new V2StatusResult(
        row.state(), row.failureCode(), false, requestId, row.envelopeDigest(), row.evidence());
  }

  /**
   * Stops a tracked request. Terminal rows are immutable; the stop ownership is claimed atomically
   * as {@code STOPPING} before any network call, so duplicate stops and start/stop races resolve to
   * the single winner. The Micro6 stop receipt is fully verified and its evidence is durably
   * persisted before the row moves to {@code STOPPED}, with zero redispatch.
   */
  public V2StatusResult stop(String requestId, String reason, boolean killSwitch) {
    Optional<GameplayV2GateStore.GateRow> found = store.findByRequestId(requestId);
    if (found.isEmpty()) {
      return new V2StatusResult(
          V2State.FAILED, Failure.UNKNOWN_REQUEST, false, requestId, null, List.of());
    }
    GameplayV2GateStore.GateRow row = found.get();
    if (row.state().provenTerminal()) {
      return new V2StatusResult(
          row.state(), row.failureCode(), true, requestId, row.envelopeDigest(), row.evidence());
    }
    if (reason == null || reason.isBlank() || reason.length() > 256) {
      return new V2StatusResult(
          row.state(),
          Failure.INVALID_BUNDLE,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    boolean claimed =
        store.compareAndSet(
            requestId,
            STOP_CLAIM_STATES,
            V2State.STOPPING,
            null,
            row.acknowledged(),
            null,
            List.of(
                killSwitch
                    ? "chromascape:kill-switch-claim:" + requestId
                    : "chromascape:stop-claim:" + requestId));
    if (!claimed) {
      return authoritativeStopResult(requestId, row);
    }
    JsonNode stopped;
    try {
      stopped = gateway.stop(requestId, reason, killSwitch);
    } catch (RuntimeException exception) {
      store.compareAndSet(
          requestId,
          Set.of(V2State.STOPPING),
          V2State.NEEDS_RECONCILIATION,
          Failure.TRANSPORT_UNCERTAIN,
          row.acknowledged(),
          null,
          List.of());
      return new V2StatusResult(
          V2State.NEEDS_RECONCILIATION,
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    if (!validStopReceipt(row, stopped)) {
      store.compareAndSet(
          requestId,
          Set.of(V2State.STOPPING),
          V2State.NEEDS_RECONCILIATION,
          Failure.TRANSPORT_UNCERTAIN,
          row.acknowledged(),
          receiptJson(stopped),
          List.of());
      return new V2StatusResult(
          V2State.NEEDS_RECONCILIATION,
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    List<String> stopEvidence = new ArrayList<>(evidenceOf(stopped));
    stopEvidence.add(
        killSwitch ? "chromascape:kill-switch:" + requestId : "chromascape:stop:" + requestId);
    boolean halted =
        store.compareAndSet(
            requestId,
            Set.of(V2State.STOPPING),
            V2State.STOPPED,
            null,
            row.acknowledged(),
            receiptJson(stopped),
            stopEvidence);
    if (!halted) {
      return authoritativeStopResult(requestId, row);
    }
    GameplayV2GateStore.GateRow stoppedRow = store.findByRequestId(requestId).orElse(row);
    return new V2StatusResult(
        V2State.STOPPED, null, false, requestId, row.envelopeDigest(), stoppedRow.evidence());
  }

  /**
   * Returns the authoritative stop outcome by reloading the durable row. The losing caller reports
   * the winner's state instead of manufacturing {@code STOPPED}.
   */
  private V2StatusResult authoritativeStopResult(
      String requestId, GameplayV2GateStore.GateRow fallback) {
    Optional<GameplayV2GateStore.GateRow> current = store.findByRequestId(requestId);
    if (current.isEmpty()) {
      return new V2StatusResult(
          V2State.FAILED,
          Failure.UNKNOWN_REQUEST,
          false,
          requestId,
          fallback.envelopeDigest(),
          fallback.evidence());
    }
    GameplayV2GateStore.GateRow row = current.get();
    if (row.state().provenTerminal()) {
      return new V2StatusResult(
          row.state(), row.failureCode(), true, requestId, row.envelopeDigest(), row.evidence());
    }
    if (row.state() == V2State.STOPPING) {
      return new V2StatusResult(
          V2State.STOPPING,
          Failure.STOP_IN_PROGRESS,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    return new V2StatusResult(
        row.state(),
        row.failureCode() != null ? row.failureCode() : Failure.NEEDS_RECONCILIATION,
        false,
        requestId,
        row.envelopeDigest(),
        row.evidence());
  }

  /**
   * Reconciles an uncertain transport boundary from the durable row or, when the row is still
   * uncertain, from an independently fetched Micro6 state. Every non-terminal row is rechecked
   * remotely, including already acknowledged {@code DELIVERY_PENDING}/{@code ACTIVE} rows: a
   * ChromaScape or Micro6 restart must converge with Micro6 instead of trusting the local copy. A
   * fingerprint reservation alone is never reported as {@code RECONCILED}: reconciliation requires
   * a stored receipt or a Micro6-confirmed state. A disconnect after a proven Micro6 reservation
   * never redispatches; a Micro6-confirmed {@code RESERVED} restores the row to startable {@code
   * DELIVERY_PENDING}. Status is never replayed as reserve/start solely because Micro6 reported
   * 404; that path stays uncertain for manual recovery.
   */
  public V2StatusResult reconcile(String requestId) {
    Optional<GameplayV2GateStore.GateRow> found = store.findByRequestId(requestId);
    if (found.isEmpty()) {
      return new V2StatusResult(
          V2State.FAILED, Failure.UNKNOWN_REQUEST, false, requestId, null, List.of());
    }
    GameplayV2GateStore.GateRow row = found.get();
    if (row.state().provenTerminal()) {
      return new V2StatusResult(
          row.state(), row.failureCode(), true, requestId, row.envelopeDigest(), row.evidence());
    }
    if (row.state() == V2State.STARTING || row.state() == V2State.STOPPING) {
      return new V2StatusResult(
          row.state(),
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    JsonNode remote;
    try {
      remote = gateway.status(requestId);
    } catch (RuntimeException exception) {
      return new V2StatusResult(
          V2State.NEEDS_RECONCILIATION,
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    if (!validStatusReceipt(row, remote)) {
      return new V2StatusResult(
          V2State.NEEDS_RECONCILIATION,
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    }
    String remoteState = remote.path("status").asText(null);
    return switch (remoteState) {
      case "RESERVED" -> {
        store.compareAndSet(
            requestId,
            Set.of(
                V2State.RESERVED,
                V2State.DELIVERY_PENDING,
                V2State.NEEDS_RECONCILIATION,
                V2State.STOPPING),
            V2State.DELIVERY_PENDING,
            null,
            true,
            receiptJson(remote),
            List.of("chromascape:reconcile:reserved:" + requestId));
        yield status(requestId);
      }
      case "STARTING" -> {
        store.compareAndSet(
            requestId,
            Set.of(
                V2State.RESERVED,
                V2State.DELIVERY_PENDING,
                V2State.NEEDS_RECONCILIATION,
                V2State.STOPPING),
            V2State.STARTING,
            null,
            true,
            receiptJson(remote),
            List.of("chromascape:reconcile:starting:" + requestId));
        yield status(requestId);
      }
      case "ACTIVE" -> {
        store.compareAndSet(
            requestId,
            Set.of(
                V2State.RESERVED,
                V2State.DELIVERY_PENDING,
                V2State.STARTING,
                V2State.DELIVERED,
                V2State.STOPPING,
                V2State.NEEDS_RECONCILIATION),
            V2State.ACTIVE,
            null,
            true,
            receiptJson(remote),
            List.of("micro6:reconcile:active:" + requestId));
        yield status(requestId);
      }
      case "STOPPING" -> {
        store.compareAndSet(
            requestId,
            Set.of(
                V2State.RESERVED,
                V2State.DELIVERY_PENDING,
                V2State.STARTING,
                V2State.DELIVERED,
                V2State.ACTIVE,
                V2State.NEEDS_RECONCILIATION),
            V2State.STOPPING,
            null,
            true,
            receiptJson(remote),
            List.of("micro6:reconcile:stopping:" + requestId));
        yield status(requestId);
      }
      case "STOPPED", "CANCELLED" -> {
        store.compareAndSet(
            requestId,
            ANY_NON_TERMINAL,
            V2State.STOPPED,
            null,
            row.acknowledged(),
            receiptJson(remote),
            List.of("micro6:reconcile:stopped:" + requestId));
        yield status(requestId);
      }
      case "FAILED" -> {
        String failureCode = recognizedFailureCode(remote.path("failureCode").asText(null));
        store.compareAndSet(
            requestId,
            ANY_NON_TERMINAL,
            V2State.FAILED,
            failureCode,
            row.acknowledged(),
            receiptJson(remote),
            List.of("micro6:reconcile:failed:" + requestId));
        yield status(requestId);
      }
      case "COMPLETED" -> {
        store.compareAndSet(
            requestId,
            ANY_NON_TERMINAL,
            V2State.CONFIRMED,
            null,
            row.acknowledged(),
            receiptJson(remote),
            List.of("micro6:reconcile:confirmed:" + requestId));
        yield status(requestId);
      }
      default -> new V2StatusResult(
          V2State.NEEDS_RECONCILIATION,
          Failure.NEEDS_RECONCILIATION,
          false,
          requestId,
          row.envelopeDigest(),
          row.evidence());
    };
  }

  /**
   * Marks rows that were mid-flight at restart as needing reconciliation, never redispatch. The
   * rows come from the durable store, so a restart genuinely finds the mid-flight work again.
   */
  public List<V2StatusResult> recoverAfterRestart() {
    List<V2StatusResult> recovered = new ArrayList<>();
    for (GameplayV2GateStore.GateRow row : store.findRecoverable()) {
      store.compareAndSet(
          row.requestId(),
          Set.of(row.state()),
          V2State.NEEDS_RECONCILIATION,
          Failure.NEEDS_RECONCILIATION,
          row.acknowledged(),
          null,
          List.of());
      GameplayV2GateStore.GateRow updated = store.findByRequestId(row.requestId()).orElse(row);
      recovered.add(
          new V2StatusResult(
              updated.state(),
              updated.failureCode(),
              false,
              updated.requestId(),
              updated.envelopeDigest(),
              updated.evidence()));
    }
    return List.copyOf(recovered);
  }

  /**
   * Returns the rows that are currently uncertain (NEEDS_RECONCILIATION) and should be reconciled
   * against Micro6 on a bounded schedule. Terminal rows are never returned.
   */
  public List<V2StatusResult> statusReconciliationCandidates() {
    List<V2StatusResult> candidates = new ArrayList<>();
    for (GameplayV2GateStore.GateRow row : store.findRecoverable()) {
      if (row.state() == V2State.NEEDS_RECONCILIATION) {
        candidates.add(
            new V2StatusResult(
                row.state(),
                row.failureCode(),
                false,
                row.requestId(),
                row.envelopeDigest(),
                row.evidence()));
      }
    }
    return List.copyOf(candidates);
  }

  private V2ReserveResult deliverReservation(ValidatedBundleSnapshot snapshot, String fingerprint) {
    String requestId = snapshot.requestId();
    JsonNode acknowledged;
    try {
      acknowledged = gateway.reserve(snapshot.bytes(), snapshot.idempotencyKey());
    } catch (RuntimeException exception) {
      return reservationUncertain(snapshot);
    }
    if (!validReserveReceipt(snapshot, acknowledged)) {
      return reservationUncertain(snapshot);
    }
    boolean delivered =
        store.compareAndSet(
            requestId,
            Set.of(V2State.RESERVED),
            V2State.DELIVERY_PENDING,
            null,
            true,
            receiptJson(acknowledged),
            evidenceOf(acknowledged));
    if (!delivered) {
      return authoritativeReserveResult(requestId, snapshot);
    }
    GameplayV2GateStore.GateRow row = store.findByRequestId(requestId).orElseThrow();
    return new V2ReserveResult(
        V2State.DELIVERY_PENDING,
        null,
        false,
        true,
        requestId,
        snapshot.envelopeDigest(),
        row.evidence());
  }

  /**
   * Returns the authoritative reserve outcome after a lost delivery compare-and-set. The losing
   * caller reports the durable winner instead of manufacturing {@code DELIVERY_PENDING}.
   */
  private V2ReserveResult authoritativeReserveResult(
      String requestId, ValidatedBundleSnapshot snapshot) {
    Optional<GameplayV2GateStore.GateRow> current = store.findByRequestId(requestId);
    if (current.isEmpty()) {
      return denied(Failure.TRANSPORT_UNCERTAIN, requestId, snapshot.envelopeDigest());
    }
    GameplayV2GateStore.GateRow row = current.get();
    return new V2ReserveResult(
        row.state(),
        row.failureCode(),
        true,
        row.acknowledged(),
        row.requestId(),
        row.envelopeDigest(),
        row.evidence());
  }

  private V2ReserveResult reservationUncertain(ValidatedBundleSnapshot snapshot) {
    String requestId = snapshot.requestId();
    store.compareAndSet(
        requestId,
        Set.of(V2State.RESERVED),
        V2State.NEEDS_RECONCILIATION,
        Failure.TRANSPORT_UNCERTAIN,
        false,
        null,
        List.of());
    return new V2ReserveResult(
        V2State.NEEDS_RECONCILIATION,
        Failure.NEEDS_RECONCILIATION,
        false,
        false,
        requestId,
        snapshot.envelopeDigest(),
        List.of());
  }

  /** Disambiguates an insert conflict by re-reading the durable rows; never invents a winner. */
  private V2ReserveResult resolveInsertConflict(
      GameplayV2GateStore.GateConflictException conflict,
      ValidatedBundleSnapshot snapshot,
      String fingerprint) {
    String requestId = snapshot.requestId();
    Optional<GameplayV2GateStore.GateRow> sameRequest = store.findByRequestId(requestId);
    if (sameRequest.isPresent()) {
      return replayResult(sameRequest.get(), fingerprint, snapshot.envelopeDigest());
    }
    if (store.findByIdempotencyKey(snapshot.idempotencyKey()).isPresent()) {
      return denied(Failure.IDEMPOTENCY_CONFLICT, requestId, snapshot.envelopeDigest());
    }
    if (store.findByAuthorityId(snapshot.authorityId()).isPresent()) {
      return denied(Failure.AUTHORITY_CONSUMED, requestId, snapshot.envelopeDigest());
    }
    return denied(Failure.LEASE_SLOT_OCCUPIED, requestId, snapshot.envelopeDigest());
  }

  private V2ReserveResult replayResult(
      GameplayV2GateStore.GateRow existing, String fingerprint, String envelopeDigest) {
    if (!fingerprint.equals(existing.fingerprint())) {
      return denied(Failure.IDEMPOTENCY_CONFLICT, existing.requestId(), envelopeDigest);
    }
    return new V2ReserveResult(
        existing.state(),
        existing.failureCode(),
        true,
        existing.acknowledged(),
        existing.requestId(),
        existing.envelopeDigest(),
        existing.evidence());
  }

  private GameplayV2GateStore.GateRow reservedRow(
      ValidatedBundleSnapshot snapshot, String fingerprint) {
    Instant now = clock.instant();
    return new GameplayV2GateStore.GateRow(
        snapshot.requestId(),
        snapshot.idempotencyKey(),
        fingerprint,
        snapshot.envelopeDigest(),
        snapshot.authorityId(),
        snapshot.accountId(),
        snapshot.clientId(),
        snapshot.leaseGeneration(),
        snapshot.fence(),
        snapshot.envelopeDeadline(),
        snapshot.authorityExpiresAt(),
        V2State.RESERVED,
        null,
        false,
        snapshot.bytes(),
        null,
        List.of("chromascape:reserved:" + snapshot.requestId()),
        now,
        now);
  }

  private JsonNode envelopeFrom(GameplayV2GateStore.GateRow row) {
    try {
      return objectMapper.readTree(row.bytes()).path("executionEnvelope");
    } catch (Exception exception) {
      throw new GameplayV2DispatchModels.V2TransportException(
          "Persisted v2 request bytes are unreadable", exception);
    }
  }

  private String receiptJson(JsonNode ack) {
    if (ack == null || ack.isMissingNode()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(ack);
    } catch (Exception exception) {
      return null;
    }
  }

  private List<String> evidenceOf(JsonNode ack) {
    JsonNode refs = ack == null ? null : ack.path("evidenceRefs");
    if (refs == null || !refs.isArray() || refs.isEmpty()) {
      return List.of();
    }
    List<String> evidence = new ArrayList<>();
    refs.forEach(ref -> evidence.add(ref.asText()));
    return evidence;
  }

  /**
   * Returns the failure code when the response is a definitive, zero-side-effect rejection: failed
   * HTTP status with a closed denial payload, a recognized rejection code, and no delivery
   * acknowledgement. Anything else is treated as transport-uncertain.
   */
  private String definitiveRejection(JsonNode response) {
    if (response == null || response.isMissingNode() || !response.isObject()) {
      return null;
    }
    boolean httpFailed = response.has("_httpSuccess") && !response.path("_httpSuccess").asBoolean();
    boolean closedDenial =
        response.path("success").isBoolean()
            && !response.path("success").asBoolean()
            && !response.path("deliveryAcknowledged").asBoolean(true)
            && response.has("failureCode");
    if (httpFailed && closedDenial) {
      String code = response.path("failureCode").asText(null);
      if (code != null && REJECTION_FAILURE_CODES.contains(code)) {
        return code;
      }
      return Failure.INVALID_RECEIPT;
    }
    return null;
  }

  /** Allowlist of failure codes Micro6 may report for a definitive rejection. */
  private String recognizedFailureCode(String code) {
    if (code != null && REJECTION_FAILURE_CODES.contains(code)) {
      return code;
    }
    return Failure.TRANSPORT_UNCERTAIN;
  }

  /** Reserve receipts must prove HTTP success, identity, digest, closed state and evidence. */
  private boolean validReserveReceipt(ValidatedBundleSnapshot snapshot, JsonNode ack) {
    if (!commonReceiptShape(ack)) {
      return false;
    }
    return snapshot.requestId().equals(ack.path("requestId").asText(null))
        && snapshot.envelopeDigest().equals(ack.path("envelopeDigest").asText(null))
        && "RESERVED".equals(ack.path("status").asText(null))
        && ack.path("deliveryAcknowledged").asBoolean(false)
        && !evidenceOf(ack).isEmpty();
  }

  /** Start receipts must prove HTTP success, identity, digest, closed state and evidence. */
  private boolean validStartReceipt(GameplayV2GateStore.GateRow row, JsonNode ack) {
    if (!commonReceiptShape(ack)) {
      return false;
    }
    return row.requestId().equals(ack.path("requestId").asText(null))
        && row.envelopeDigest().equals(ack.path("envelopeDigest").asText(null))
        && "ACTIVE".equals(ack.path("status").asText(null))
        && ack.path("deliveryAcknowledged").asBoolean(false)
        && !evidenceOf(ack).isEmpty();
  }

  /** Stop receipts must prove HTTP success, identity, digest, closed state and evidence. */
  private boolean validStopReceipt(GameplayV2GateStore.GateRow row, JsonNode ack) {
    if (!commonReceiptShape(ack)) {
      return false;
    }
    return row.requestId().equals(ack.path("requestId").asText(null))
        && row.envelopeDigest().equals(ack.path("envelopeDigest").asText(null))
        && "STOPPED".equals(ack.path("status").asText(null))
        && !evidenceOf(ack).isEmpty();
  }

  /**
   * Status receipts must prove HTTP success, exact request identity, exact envelope digest, a
   * closed recognized state, and the delivery acknowledgement shape. The request id and digest bind
   * the receipt to this row; anything else stays uncertain for manual recovery.
   */
  private boolean validStatusReceipt(GameplayV2GateStore.GateRow row, JsonNode remote) {
    if (!commonReceiptShape(remote)) {
      return false;
    }
    if (!row.requestId().equals(remote.path("requestId").asText(null))) {
      return false;
    }
    if (!row.envelopeDigest().equals(remote.path("envelopeDigest").asText(null))) {
      return false;
    }
    String status = remote.path("status").asText(null);
    if (status == null) {
      return false;
    }
    return switch (status) {
      case "RESERVED",
          "STARTING",
          "ACTIVE",
          "STOPPING",
          "STOPPED",
          "CANCELLED",
          "FAILED",
          "COMPLETED" -> true;
      default -> false;
    };
  }

  private boolean commonReceiptShape(JsonNode ack) {
    if (ack == null || ack.isMissingNode() || ack.isNull() || !ack.isObject()) {
      return false;
    }
    if (!ack.has("_httpSuccess") || !ack.path("_httpSuccess").asBoolean(false)) {
      return false;
    }
    if (!ack.has("success") || !ack.path("success").isBoolean()) {
      return false;
    }
    return ack.path("success").asBoolean(false);
  }

  private V2ReserveResult denied(String code, String requestId, String envelopeDigest) {
    return new V2ReserveResult(
        V2State.FAILED, code, false, false, requestId, envelopeDigest, List.of());
  }

  private String requestIdOf(V2Bundle bundle) {
    try {
      if (bundle == null || bundle.executionEnvelope() == null) {
        return null;
      }
      return bundle.executionEnvelope().path("requestId").asText(null);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  /**
   * Runs the closed validation sequence on deep-copied snapshots: version, live mode, every closed
   * schema definition, sealed eligibility replay, provider normalization, full envelope bindings,
   * authority plus exact approved scope, deadline, and lease generation/fence shape. On success it
   * freezes one {@link ValidatedBundleSnapshot} from the validated local copies; nothing from the
   * caller's mutable bundle survives past this point.
   */
  private Validated validate(V2Bundle bundle) {
    Instant now = clock.instant();
    if (bundle == null) {
      throw new V2GateException(Failure.INVALID_BUNDLE, "Live v2 request bundle is required");
    }
    JsonNode envelope = copyOf(bundle.executionEnvelope(), "executionEnvelope");
    JsonNode catalog = copyOf(bundle.catalogSnapshot(), "immutableTaskCatalogSnapshot");
    JsonNode observation = copyOf(bundle.semanticObservation(), "semanticObservation");
    JsonNode planningInput = copyOf(bundle.planningInput(), "planningInput");
    JsonNode eligibilityResult = copyOf(bundle.eligibilityResult(), "eligibilityResult");
    JsonNode providerDecision =
        copyOf(bundle.providerDecision(), GameplayV2DispatchModels.PROVIDER_DECISION_FIELD);
    JsonNode goalPlan = copyOf(bundle.goalPlan(), "goalPlan");
    JsonNode semanticAction = copyOf(bundle.semanticAction(), "semanticAction");
    JsonNode approvedScope = copyOf(bundle.approvedScope(), "approvedScope");
    JsonNode authority = copyOf(bundle.authority(), "authority");
    if (!GameplayV2DispatchModels.VERSION.equals(envelope.path("contractVersion").asText(null))) {
      throw new V2GateException(Failure.VERSION_MISMATCH, "Only sc-1429/v2 is accepted");
    }
    if (!GameplayV2DispatchModels.LIVE_MODE.equals(envelope.path("mode").asText(null))) {
      throw new V2GateException(Failure.MODE_NOT_DELIVERABLE, "Only LIVE_TASK may reserve");
    }
    if (!GameplayV2DispatchModels.FISHING_TASK.equals(envelope.path("taskKey").asText(null))) {
      throw new V2GateException(Failure.MODE_NOT_DELIVERABLE, "Only FISHING_TRAINING may reserve");
    }
    validator.validateWithRules("immutableTaskCatalogSnapshot", catalog);
    validator.validateWithRules("semanticObservation", observation);
    validator.validateWithRules("planningInput", planningInput);
    validator.validateWithRules("eligibilityResult", eligibilityResult);
    validator.validateWithRules(GameplayV2DispatchModels.PROVIDER_DECISION_FIELD, providerDecision);
    validator.validateWithRules("goalPlan", goalPlan);
    validator.validateWithRules("semanticAction", semanticAction);
    validator.validateWithRules("executionEnvelope", envelope);
    validator.validateWithRules("authority", authority);
    validator.validateWithRules("approvedScope", approvedScope);
    GameplayV2ValidatedEligibility sealed =
        GameplayV2ValidatedEligibility.seal(
            validator, catalog, observation, planningInput, eligibilityResult, now);
    Provider provider;
    try {
      provider = Provider.valueOf(envelope.path("provider").asText(null));
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new V2GateException(Failure.INVALID_BUNDLE, "Provider is not allowlisted");
    }
    GameplayV2ProviderAdapter adapter = adapters.get(provider);
    if (adapter == null) {
      throw new V2GateException(Failure.INVALID_BUNDLE, "Provider adapter mismatch");
    }
    GameplayV2Contracts.ProviderDecision decision =
        adapter.adapt(sealed, catalog, envelope.path("modelVersion").asText(), providerDecision);
    if (decision.kind() != GameplayV2Contracts.DecisionKind.SELECTED
        || decision.goalPlan() == null) {
      throw new V2GateException(Failure.INVALID_BUNDLE, "Only SELECTED decisions may reserve");
    }
    String full =
        GameplayV2EnvelopeRules.checkFullBindings(
            envelope,
            catalog,
            observation,
            planningInput,
            eligibilityResult,
            semanticAction,
            goalPlan,
            providerDecision,
            GameplayV2EnvelopeRules.canonicalSchemaDigest(),
            now);
    if (full != null) {
      throw new V2GateException(full, "Full envelope binding failed: " + full);
    }
    String envelopeDigest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
    String authorityCode =
        GameplayV2EnvelopeRules.checkAuthority(
            authority,
            approvedScope,
            envelope,
            envelopeDigest,
            envelope.path("authorityId").asText(null),
            signingSecret,
            now,
            Set.of());
    if (authorityCode != null) {
      throw new V2GateException(authorityCode, "Authority binding failed: " + authorityCode);
    }
    if (!liveEnabled) {
      throw new V2GateException(Failure.LIVE_DISABLED, "v2 live delivery is disabled");
    }
    Instant deadline;
    try {
      deadline = Instant.parse(envelope.path("deadline").asText());
    } catch (RuntimeException exception) {
      throw new V2GateException(Failure.INVALID_BUNDLE, "Envelope deadline is invalid");
    }
    if (!deadline.isAfter(now)) {
      throw new V2GateException(Failure.DEADLINE_EXPIRED, "Envelope deadline has expired");
    }
    long leaseGeneration = envelope.path("leaseGeneration").asLong(0);
    long fence = envelope.path("fence").asLong(0);
    if (leaseGeneration < 1 || fence < 1) {
      throw new V2GateException(Failure.FENCE_MISMATCH, "Lease generation and fence must advance");
    }
    Instant expiresAt;
    try {
      expiresAt = Instant.parse(authority.path("expiresAt").asText());
    } catch (RuntimeException exception) {
      throw new V2GateException(Failure.INVALID_BUNDLE, "Authority expiry is invalid");
    }
    Map<String, Object> fingerprintParts = new LinkedHashMap<>();
    fingerprintParts.put("contractVersion", GameplayV2DispatchModels.VERSION);
    fingerprintParts.put("requestId", envelope.path("requestId").asText());
    fingerprintParts.put("idempotencyKey", envelope.path("idempotencyKey").asText());
    fingerprintParts.put("envelopeDigest", envelopeDigest);
    String fingerprint;
    try {
      fingerprint =
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(
                              objectMapper
                                  .writeValueAsString(fingerprintParts)
                                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new V2GateException(Failure.INVALID_BUNDLE, "Unable to fingerprint live request");
    }
    ValidatedBundleSnapshot snapshot =
        freezeSnapshot(
            envelope,
            catalog,
            observation,
            planningInput,
            eligibilityResult,
            providerDecision,
            goalPlan,
            semanticAction,
            approvedScope,
            authority,
            envelopeDigest,
            requestId(envelope),
            idempotencyKey(envelope),
            leaseGeneration,
            fence,
            deadline,
            expiresAt);
    return new Validated(snapshot, fingerprint);
  }

  /**
   * Serializes the complete validated snapshot exactly once into the canonical wire bytes and
   * freezes every identity needed after validation. The wire key for the provider decision is the
   * canonical {@code providerDecision} shared with Micro6.
   */
  private ValidatedBundleSnapshot freezeSnapshot(
      JsonNode envelope,
      JsonNode catalog,
      JsonNode observation,
      JsonNode planningInput,
      JsonNode eligibilityResult,
      JsonNode providerDecision,
      JsonNode goalPlan,
      JsonNode semanticAction,
      JsonNode approvedScope,
      JsonNode authority,
      String envelopeDigest,
      String requestId,
      String idempotencyKey,
      long leaseGeneration,
      long fence,
      Instant envelopeDeadline,
      Instant expiresAt) {
    ObjectNode root = objectMapper.createObjectNode();
    root.set("catalogSnapshot", catalog);
    root.set("semanticObservation", observation);
    root.set("planningInput", planningInput);
    root.set("eligibilityResult", eligibilityResult);
    root.set(GameplayV2DispatchModels.PROVIDER_DECISION_FIELD, providerDecision);
    root.set("goalPlan", goalPlan);
    root.set("semanticAction", semanticAction);
    root.set("executionEnvelope", envelope);
    root.set("approvedScope", approvedScope);
    root.set("authority", authority);
    byte[] bytes = GameplayV2EligibilityFilter.canonicalDigestBytes(objectMapper, root);
    String bundleDigest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, root);
    return new ValidatedBundleSnapshot(
        bytes,
        bundleDigest,
        envelopeDigest,
        envelope,
        requestId,
        idempotencyKey,
        authority.path("authorityId").asText(),
        observation.path("identities").path("accountId").asText(),
        observation.path("identities").path("clientId").asText(),
        leaseGeneration,
        fence,
        envelopeDeadline,
        expiresAt);
  }

  private String requestId(JsonNode envelope) {
    return envelope.path("requestId").asText(null);
  }

  private String idempotencyKey(JsonNode envelope) {
    return envelope.path("idempotencyKey").asText(null);
  }

  private JsonNode copyOf(JsonNode node, String name) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      throw new V2GateException(Failure.INVALID_BUNDLE, name + " is required");
    }
    return node.deepCopy();
  }

  /** Validated outcome: the frozen snapshot plus its deterministic fingerprint. */
  record Validated(ValidatedBundleSnapshot snapshot, String fingerprint) {}

  /** Stable failure-code carrier for the closed live gate. */
  public static final class V2GateException extends RuntimeException {
    private final String failureCode;

    public V2GateException(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
