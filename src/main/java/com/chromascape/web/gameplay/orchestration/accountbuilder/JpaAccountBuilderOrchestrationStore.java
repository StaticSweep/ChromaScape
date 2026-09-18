package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.AuthorityConsumption;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TerminalResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** Transactional normalized persistence for SC-1431 orchestration identities and evidence. */
@Repository
public class JpaAccountBuilderOrchestrationStore implements AccountBuilderOrchestrationStore {
  private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";
  private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
  private static final String SIGNATURE_PATTERN = "hmac-sha256:[0-9a-f]{64}";
  private static final Set<State> LEASE_STATES =
      EnumSet.of(
          State.RESERVED,
          State.DISPATCH_SUBMITTED,
          State.ACTIVE,
          State.VERIFYING,
          State.CONFIRMED,
          State.REFUTED,
          State.UNVERIFIABLE,
          State.STOPPED,
          State.FAILED,
          State.NEEDS_RECONCILIATION);

  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;
  private final CanonicalGameplayJson canonicalJson;
  private final Clock clock;
  private final AccountBuilderAuthorityVerifier authorityVerifier;

  @Autowired
  public JpaAccountBuilderOrchestrationStore(
      EntityManager entityManager,
      ObjectMapper objectMapper,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderAuthorityVerifier authorityVerifier) {
    this(entityManager, objectMapper, canonicalJson, Clock.systemUTC(), authorityVerifier);
  }

  JpaAccountBuilderOrchestrationStore(
      EntityManager entityManager,
      ObjectMapper objectMapper,
      CanonicalGameplayJson canonicalJson,
      Clock clock,
      AccountBuilderAuthorityVerifier authorityVerifier) {
    this.entityManager = entityManager;
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.clock = clock;
    this.authorityVerifier = Objects.requireNonNull(authorityVerifier, "authorityVerifier");
  }

  @Override
  @Transactional
  public Claim claim(
      OrchestrationRequest request,
      String fingerprint,
      String envelopeDigest,
      AccountBuilderBudgetPolicy.OrchestrationBudget budget) {
    requireIdentity(request, fingerprint, envelopeDigest);
    Objects.requireNonNull(budget, "budget");
    Instant now = now();
    OrchestrationView initial =
        new OrchestrationView(
            AccountBuilderOrchestrationModels.VERSION,
            request.orchestrationId(),
            request.requestId(),
            request.idempotencyKey(),
            fingerprint,
            envelopeDigest,
            request.mode(),
            request.sourceType(),
            State.CREATED,
            FailureCode.NONE,
            null,
            List.of(),
            false,
            false,
            0,
            now,
            now);

    // A native INSERT ... ON CONFLICT avoids a transaction marked rollback-only after two
    // requests race on different unique identity indexes. The follow-up read is authoritative.
    int inserted =
        entityManager
            .createNativeQuery(
                """
                INSERT INTO ab_orchestration (
                    orchestration_id, request_id, idempotency_key, request_fingerprint,
                    envelope_digest, source_type, mode, state, failure_code, request_json,
                    view_json, retry_allowed, view_version, created_at, updated_at, entity_version
                ) VALUES (
                    :orchestrationId, :requestId, :idempotencyKey, :requestFingerprint,
                    :envelopeDigest, :sourceType, :mode, :state, :failureCode, :requestJson,
                    :viewJson, FALSE, 0, :createdAt, :updatedAt, 0
                ) ON CONFLICT DO NOTHING
                """)
            .setParameter("orchestrationId", request.orchestrationId())
            .setParameter("requestId", request.requestId())
            .setParameter("idempotencyKey", request.idempotencyKey())
            .setParameter("requestFingerprint", fingerprint)
            .setParameter("envelopeDigest", envelopeDigest)
            .setParameter("sourceType", request.sourceType())
            .setParameter("mode", request.mode().name())
            .setParameter("state", State.CREATED.name())
            .setParameter("failureCode", FailureCode.NONE.name())
            .setParameter("requestJson", writePersistedRequest(request))
            .setParameter("viewJson", write(initial))
            .setParameter("createdAt", now)
            .setParameter("updatedAt", now)
            .executeUpdate();

    if (inserted == 0) {
      AccountBuilderOrchestrationEntity existing = findByAnyIdentity(request);
      if (existing == null) {
        throw integrity("An identity conflict did not expose its durable owner");
      }
      if (!Objects.equals(existing.requestFingerprint, fingerprint)
          || !Objects.equals(existing.orchestrationId, request.orchestrationId())
          || !Objects.equals(existing.requestId, request.requestId())
          || !Objects.equals(existing.idempotencyKey, request.idempotencyKey())
          || !Objects.equals(existing.envelopeDigest, envelopeDigest)) {
        throw conflict();
      }
      AccountBuilderOrchestrationBudgetEntity existingBudget =
          entityManager.find(
              AccountBuilderOrchestrationBudgetEntity.class, existing.orchestrationId);
      if (existingBudget == null) {
        // A durable claim without a budget is an incomplete aggregate. Replay must never present
        // it as a healthy claim; only a specifically tested recovery transaction may repair it.
        throw integrity("Durable orchestration claim has no immutable budget row");
      }
      requireSameBudget(existingBudget, budget);
      return new Claim(readNormalized(existing).view(), false);
    }

    AccountBuilderOrchestrationEntity entity =
        entityManager.find(
            AccountBuilderOrchestrationEntity.class,
            request.orchestrationId(),
            LockModeType.PESSIMISTIC_WRITE);
    if (entity == null) {
      throw integrity("The inserted orchestration owner could not be loaded");
    }
    persistBinding(request);
    persistCorrelation(request, envelopeDigest);
    persistBudget(request.orchestrationId(), budget);
    appendEvent(entity, "ORCHESTRATION_CREATED", fingerprint);
    entityManager.flush();
    return new Claim(initial, true);
  }

  private void persistBudget(
      String orchestrationId, AccountBuilderBudgetPolicy.OrchestrationBudget budget) {
    AccountBuilderOrchestrationBudgetEntity created = new AccountBuilderOrchestrationBudgetEntity();
    created.orchestrationId = orchestrationId;
    created.maxActions = budget.maxActions();
    created.maxAttempts = budget.maxAttempts();
    created.maxElapsedMs = budget.maxElapsedMs();
    created.actionsUsed = budget.actionsUsed();
    created.attemptsUsed = budget.attemptsUsed();
    created.startedAt = budget.startedAt();
    created.expiresAt = budget.expiresAt();
    entityManager.persist(created);
  }

  /** Replay compares only the immutable limits; started/expires derive from the claim instant. */
  private void requireSameBudget(
      AccountBuilderOrchestrationBudgetEntity persisted,
      AccountBuilderBudgetPolicy.OrchestrationBudget budget) {
    if (persisted.maxActions != budget.maxActions()
        || persisted.maxAttempts != budget.maxAttempts()
        || persisted.maxElapsedMs != budget.maxElapsedMs()) {
      throw conflict();
    }
  }

  @Override
  @Transactional
  public Optional<AccountBuilderBudgetPolicy.OrchestrationBudget> findBudget(
      String orchestrationId) {
    return Optional.ofNullable(
            entityManager.find(AccountBuilderOrchestrationBudgetEntity.class, orchestrationId))
        .map(this::toBudget);
  }

  @Override
  @Transactional
  public AccountBuilderBudgetPolicy.OrchestrationBudget consumeBudget(
      String orchestrationId, boolean action, boolean attempt) {
    Instant now = now();
    // A single conditional UPDATE is the atomic capacity check. Rows are only advanced when both
    // the action and the attempt capacity remain at the instant of the update, so two racing
    // callers can never both take the final unit of capacity.
    int advanced =
        entityManager
            .createNativeQuery(
                """
                UPDATE ab_orchestration_budget
                SET actions_used = CASE WHEN :action THEN actions_used + 1 ELSE actions_used END,
                    attempts_used = CASE WHEN :attempt THEN attempts_used + 1 ELSE attempts_used END,
                    version = version + 1
                WHERE orchestration_id = :orchestrationId
                  AND (CASE WHEN :action THEN actions_used < max_actions ELSE TRUE END)
                  AND (CASE WHEN :attempt THEN attempts_used < max_attempts ELSE TRUE END)
                  AND expires_at > :now
                """)
            .setParameter("action", action)
            .setParameter("attempt", attempt)
            .setParameter("orchestrationId", orchestrationId)
            .setParameter("now", now)
            .executeUpdate();
    if (advanced > 0) {
      entityManager.flush();
      AccountBuilderOrchestrationBudgetEntity advancedEntity =
          entityManager.find(AccountBuilderOrchestrationBudgetEntity.class, orchestrationId);
      if (advancedEntity != null) {
        return toBudget(advancedEntity);
      }
      throw integrity("An advanced budget row could not be reloaded");
    }
    AccountBuilderOrchestrationBudgetEntity entity =
        entityManager.find(AccountBuilderOrchestrationBudgetEntity.class, orchestrationId);
    if (entity == null) {
      throw failure(
          FailureCode.INVALID_CONTRACT, "No durable budget was claimed for this orchestration");
    }
    if (!entity.expiresAt.isAfter(now)) {
      throw failure(FailureCode.DEADLINE_EXPIRED, "The orchestration budget horizon has expired");
    }
    if ((action && entity.actionsUsed >= entity.maxActions)
        || (attempt && entity.attemptsUsed >= entity.maxAttempts)) {
      throw failure(
          FailureCode.BUDGET_EXHAUSTED, "No orchestration budget capacity remains for delivery");
    }
    // The conditional update lost a race with an exhausted row; re-read the authoritative state.
    if (action && entity.actionsUsed >= entity.maxActions) {
      throw failure(FailureCode.BUDGET_EXHAUSTED, "Action capacity was consumed concurrently");
    }
    if (attempt && entity.attemptsUsed >= entity.maxAttempts) {
      throw failure(FailureCode.BUDGET_EXHAUSTED, "Attempt capacity was consumed concurrently");
    }
    throw integrity("A budget consumption race did not expose a stable outcome");
  }

  private AccountBuilderBudgetPolicy.OrchestrationBudget toBudget(
      AccountBuilderOrchestrationBudgetEntity entity) {
    return new AccountBuilderBudgetPolicy.OrchestrationBudget(
        entity.maxActions,
        entity.maxAttempts,
        entity.maxElapsedMs,
        entity.actionsUsed,
        entity.attemptsUsed,
        entity.startedAt,
        entity.expiresAt);
  }

  @Override
  @Transactional
  public AuthorityConsumption consumeAuthority(
      String orchestrationId,
      GameplayContracts.HumanAuthority authority,
      String envelopeDigest,
      OrchestrationRequest originalRequest) {
    AccountBuilderOrchestrationEntity orchestration =
        require(orchestrationId, LockModeType.PESSIMISTIC_WRITE);
    OrchestrationRequest redactedRequestSnapshot =
        readRedactedRequestSnapshot(orchestration.requestJson);
    validateOriginalRequest(
        orchestration, redactedRequestSnapshot, originalRequest, envelopeDigest);
    validateAuthority(orchestrationId, originalRequest, authority, envelopeDigest);
    if (!originalRequest.mode().deliveryMode()) {
      throw failure(FailureCode.MODE_NOT_DELIVERABLE, "Only delivery modes can consume authority");
    }
    // Validate the normalized aggregate while the root row is locked. This prevents a malformed
    // view snapshot from becoming the basis for the authority-consumed marker.
    readNormalized(orchestration);

    Instant current = clock.instant();
    if (!authority.expiresAt().isAfter(current)) {
      throw failure(FailureCode.AUTHORITY_EXPIRED, "The human authority has expired");
    }

    AccountBuilderAuthorityConsumptionEntity existing =
        entityManager.find(
            AccountBuilderAuthorityConsumptionEntity.class,
            authority.authorityId(),
            LockModeType.PESSIMISTIC_WRITE);
    if (existing != null) {
      if (!orchestrationId.equals(existing.orchestrationId)) {
        throw failure(
            FailureCode.AUTHORITY_ALREADY_USED,
            "The one-use authority is already owned by another orchestration");
      }
      ensureSameAuthority(existing, authority, envelopeDigest);
      markAuthorityConsumed(orchestration);
      entityManager.flush();
      return toAuthorityConsumption(existing);
    }

    AccountBuilderAuthorityConsumptionEntity ownedByOrchestration =
        entityManager
            .createQuery(
                "select a from AccountBuilderAuthorityConsumptionEntity a where"
                    + " a.orchestrationId=:orchestrationId",
                AccountBuilderAuthorityConsumptionEntity.class)
            .setParameter("orchestrationId", orchestrationId)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .orElse(null);
    if (ownedByOrchestration != null
        && !authority.authorityId().equals(ownedByOrchestration.authorityId)) {
      throw failure(
          FailureCode.AUTHORITY_ALREADY_USED,
          "The orchestration already owns a different one-use authority");
    }

    Instant consumedAt = now();
    entityManager
        .createNativeQuery(
            """
            INSERT INTO ab_authority_consumption (
                authority_id, orchestration_id, envelope_digest, approved_scope_digest,
                operator_id, expires_at, consumed_at
            ) VALUES (
                :authorityId, :orchestrationId, :envelopeDigest, :scopeDigest,
                :operatorId, :expiresAt, :consumedAt
            ) ON CONFLICT (authority_id) DO NOTHING
            """)
        .setParameter("authorityId", authority.authorityId())
        .setParameter("orchestrationId", orchestrationId)
        .setParameter("envelopeDigest", envelopeDigest)
        .setParameter("scopeDigest", authority.scopeDigest())
        .setParameter("operatorId", authority.approvedBy())
        .setParameter("expiresAt", truncate(authority.expiresAt()))
        .setParameter("consumedAt", consumedAt)
        .executeUpdate();

    existing =
        entityManager.find(
            AccountBuilderAuthorityConsumptionEntity.class,
            authority.authorityId(),
            LockModeType.PESSIMISTIC_WRITE);
    if (existing == null) {
      throw integrity("The consumed authority owner could not be loaded");
    }
    if (!orchestrationId.equals(existing.orchestrationId)) {
      throw failure(
          FailureCode.AUTHORITY_ALREADY_USED,
          "The one-use authority is already owned by another orchestration");
    }
    ensureSameAuthority(existing, authority, envelopeDigest);
    markAuthorityConsumed(orchestration);
    entityManager.flush();
    return toAuthorityConsumption(existing);
  }

  @Override
  @Transactional
  public Optional<OrchestrationView> find(String orchestrationId) {
    AccountBuilderOrchestrationEntity entity =
        entityManager.find(AccountBuilderOrchestrationEntity.class, orchestrationId);
    return entity == null ? Optional.empty() : Optional.of(readNormalized(entity).view());
  }

  @Override
  @Transactional
  public NormalizedAggregate readAggregate(String orchestrationId) {
    return readNormalized(require(orchestrationId, LockModeType.NONE));
  }

  @Override
  @Transactional
  public Lease requireLease(String orchestrationId, Lease expected) {
    if (expected == null) {
      throw failure(FailureCode.FENCE_MISMATCH, "A persisted lease is required");
    }
    Lease stored = readAggregate(orchestrationId).lease();
    if (stored == null || !sameLease(stored, expected)) {
      throw failure(
          FailureCode.FENCE_MISMATCH, "Lease evidence does not match the persisted lease");
    }
    if (stored.expiresAt() == null || !stored.expiresAt().isAfter(clock.instant())) {
      throw failure(FailureCode.LEASE_EXPIRED, "The persisted orchestration lease has expired");
    }
    return stored;
  }

  @Override
  @Transactional
  public GatewayCorrelation correlation(String orchestrationId) {
    return readAggregate(orchestrationId).correlation();
  }

  @Override
  @Transactional
  public OrchestrationView transition(
      String orchestrationId,
      State state,
      FailureCode failureCode,
      Lease lease,
      GatewayReceipt receipt,
      boolean authorityConsumed,
      boolean retryAllowed) {
    if (state == null || failureCode == null) {
      throw failure(FailureCode.INVALID_CONTRACT, "State and failure code are required");
    }
    AccountBuilderOrchestrationEntity entity =
        require(orchestrationId, LockModeType.PESSIMISTIC_WRITE);
    NormalizedAggregate aggregate = readNormalized(entity);
    OrchestrationView previous = aggregate.view();
    if (previous.state().terminal() && previous.state() != State.NEEDS_RECONCILIATION) {
      if (previous.state() != state
          || previous.failureCode() != failureCode
          || previous.retryAllowed() != retryAllowed) {
        throw failure(
            FailureCode.IDEMPOTENCY_CONFLICT, "Terminal orchestration state is immutable");
      }
      if (lease != null && (previous.lease() == null || !sameLease(previous.lease(), lease))) {
        throw failure(
            FailureCode.IDEMPOTENCY_CONFLICT, "Terminal orchestration lease is immutable");
      }
      if (receipt != null && !samePersistedReceipt(aggregate.receipts(), receipt)) {
        throw failure(
            FailureCode.IDEMPOTENCY_CONFLICT, "Terminal orchestration receipt is immutable");
      }
      if (authorityConsumed && !aggregate.authorityConsumed()) {
        throw failure(FailureCode.AUTHORITY_INVALID, "Authority consumption has no durable owner");
      }
      return previous;
    }
    if (previous.state() == State.NEEDS_RECONCILIATION
        && state != State.NEEDS_RECONCILIATION
        && !state.provenTerminal()) {
      throw failure(
          FailureCode.IDEMPOTENCY_CONFLICT,
          "Reconciliation-needed orchestration cannot return to delivery");
    }
    if (!previous.state().canTransitionTo(state)) {
      throw failure(
          FailureCode.BOUNDARY_VIOLATION,
          "The requested orchestration state skips a durable lifecycle boundary");
    }

    if (authorityConsumed && !aggregate.authorityConsumed()) {
      throw failure(FailureCode.AUTHORITY_INVALID, "Authority consumption has no durable owner");
    }
    if (requiresAuthority(state) && !aggregate.authorityConsumed()) {
      throw failure(FailureCode.AUTHORITY_INVALID, "Delivery state requires consumed authority");
    }

    OrchestrationRequest redactedRequestSnapshot = aggregate.redactedRequestSnapshot();
    Lease candidate = lease;
    if (candidate == null && receipt != null && receipt.lease() != null) {
      candidate = receipt.lease();
    }
    if (candidate != null) {
      validateLease(candidate, redactedRequestSnapshot, state);
      candidate = normalizedLease(candidate);
      if (aggregate.lease() != null && !sameLease(aggregate.lease(), candidate)) {
        throw failure(FailureCode.FENCE_MISMATCH, "A lease tuple cannot be replaced");
      }
    }
    if (receipt != null) {
      validateReceipt(redactedRequestSnapshot, previous, state, failureCode, receipt, candidate);
    }
    GatewayReceipt persistedReceipt = receipt == null ? null : safeReceipt(receipt);
    String receiptDigest = persistedReceipt == null ? null : canonicalJson.digest(persistedReceipt);
    String receiptIdentity =
        persistedReceipt == null ? null : receiptIdentity(persistedReceipt, receiptDigest);
    ReceiptInsert receiptInsert =
        persistedReceipt == null
            ? ReceiptInsert.none()
            : appendReceipt(entity, persistedReceipt, receiptIdentity, receiptDigest);

    Lease effectiveLease = candidate == null ? aggregate.lease() : candidate;
    if (requiresLease(state) && effectiveLease == null) {
      throw failure(FailureCode.FENCE_MISMATCH, "This delivery state requires a persisted lease");
    }
    List<GatewayReceipt> receipts = new ArrayList<>(previous.receipts());
    if (receiptInsert.inserted()) {
      receipts.add(persistedReceipt);
    }
    boolean changed =
        previous.state() != state
            || previous.failureCode() != failureCode
            || !Objects.equals(previous.lease(), effectiveLease)
            || previous.authorityConsumed() != aggregate.authorityConsumed()
            || previous.retryAllowed() != retryAllowed
            || !previous.receipts().equals(receipts);
    if (!changed) {
      return previous;
    }

    if (receiptInsert.duplicate()
        && (previous.state() != state
            || previous.failureCode() != failureCode
            || previous.retryAllowed() != retryAllowed
            || !Objects.equals(previous.lease(), effectiveLease))) {
      throw failure(
          FailureCode.IDEMPOTENCY_CONFLICT, "A receipt replay cannot change orchestration outcome");
    }

    Instant now = now();
    OrchestrationView next =
        new OrchestrationView(
            previous.contractVersion(),
            previous.orchestrationId(),
            previous.requestId(),
            previous.idempotencyKey(),
            previous.requestFingerprint(),
            previous.envelopeDigest(),
            previous.mode(),
            previous.sourceType(),
            state,
            failureCode,
            effectiveLease,
            receipts,
            aggregate.authorityConsumed(),
            retryAllowed,
            previous.version() + 1,
            previous.createdAt(),
            now);

    if (state.provenTerminal()) {
      persistTerminalResult(entity, next, persistedReceipt, previous.state());
    }
    entity.state = state.name();
    entity.failureCode = failureCode.name();
    entity.retryAllowed = retryAllowed;
    entity.viewVersion = next.version();
    entity.viewJson = write(next);
    entity.updatedAt = now;
    if (effectiveLease != null) {
      upsertLease(entity.orchestrationId, effectiveLease, state);
    }
    appendEvent(entity, "ORCHESTRATION_" + state.name(), canonicalJson.digest(next));
    entityManager.flush();
    return next;
  }

  @Override
  @Transactional
  public List<OrchestrationView> recoverable() {
    List<String> provenTerminal =
        Arrays.stream(State.values()).filter(State::provenTerminal).map(Enum::name).toList();
    return entityManager
        .createQuery(
            "select o from AccountBuilderOrchestrationEntity o where"
                + " o.state not in :provenTerminal and o.state <> :held and o.mode <> :shadow",
            AccountBuilderOrchestrationEntity.class)
        .setParameter("provenTerminal", provenTerminal)
        .setParameter("held", State.HELD.name())
        .setParameter("shadow", AccountBuilderOrchestrationModels.Mode.SHADOW.name())
        .getResultList()
        .stream()
        .map(entity -> readNormalized(entity).view())
        .toList();
  }

  @Override
  @Transactional
  public Optional<TerminalResult> terminalResult(String orchestrationId) {
    return Optional.ofNullable(readAggregate(orchestrationId).terminalResult());
  }

  private NormalizedAggregate readNormalized(AccountBuilderOrchestrationEntity entity) {
    OrchestrationRequest redactedRequestSnapshot = readRedactedRequestSnapshot(entity.requestJson);
    if (redactedRequestSnapshot == null
        || redactedRequestSnapshot.envelope() == null
        || redactedRequestSnapshot.bindings() == null
        || !entity.orchestrationId.equals(redactedRequestSnapshot.orchestrationId())
        || !entity.requestId.equals(redactedRequestSnapshot.requestId())
        || !entity.idempotencyKey.equals(redactedRequestSnapshot.idempotencyKey())
        || !entity.requestFingerprint.equals(redactedRequestSnapshot.requestFingerprint())
        || !entity.requestId.equals(redactedRequestSnapshot.envelope().requestId())
        || !entity.idempotencyKey.equals(redactedRequestSnapshot.envelope().idempotencyKey())
        || !Objects.equals(
            redactedRequestSnapshot.deadline(), redactedRequestSnapshot.envelope().deadline())) {
      throw integrity("The persisted request identity differs from its root row");
    }
    verifyPersistedRequestSnapshot(
        entity.requestJson, redactedRequestSnapshot, entity.envelopeDigest);
    AccountBuilderOrchestrationModels.Mode mode;
    State state;
    FailureCode failureCode;
    try {
      mode = AccountBuilderOrchestrationModels.Mode.valueOf(entity.mode);
      state = State.valueOf(entity.state);
      failureCode = FailureCode.valueOf(entity.failureCode);
    } catch (IllegalArgumentException exception) {
      throw integrity("The root row contains an unsupported orchestration enum value");
    }
    AccountBuilderOrchestrationSnapshotBindingEntity bindingEntity =
        entityManager.find(
            AccountBuilderOrchestrationSnapshotBindingEntity.class, entity.orchestrationId);
    if (bindingEntity == null) {
      throw integrity("The normalized snapshot binding is missing");
    }
    SnapshotBindings bindings = toBindings(bindingEntity);
    if (!Objects.equals(redactedRequestSnapshot.bindings(), bindings)
        || !Objects.equals(redactedRequestSnapshot.mode(), mode)
        || !Objects.equals(redactedRequestSnapshot.sourceType(), entity.sourceType)) {
      throw integrity("The normalized snapshot binding differs from the immutable request");
    }
    if (!Objects.equals(
            redactedRequestSnapshot.envelope().goalPlanDigest(), bindings.goalPlanDigest())
        || !Objects.equals(
            redactedRequestSnapshot.envelope().taskSkillDigests(), bindings.taskSkillDigests())
        || !Objects.equals(
            redactedRequestSnapshot.envelope().catalogDigest(), bindings.catalogDigest())
        || !Objects.equals(
            redactedRequestSnapshot.envelope().schemaDigest(), bindings.toolSchemaDigest())
        || !Objects.equals(
            redactedRequestSnapshot.envelope().knowledgeDigest(), bindings.knowledgeDigest())
        || !Objects.equals(
            redactedRequestSnapshot.envelope().policyDigest(), bindings.policyDigest())
        || !Objects.equals(
            redactedRequestSnapshot.envelope().verifierId(), bindings.verifierDigest())
        || !Objects.equals(redactedRequestSnapshot.envelope().buildId(), bindings.buildId())
        || redactedRequestSnapshot.envelope().expectedObservationRevision()
            != bindings.expectedObservationRevision()
        || redactedRequestSnapshot.envelope().expectedTaskRevision()
            != bindings.expectedTaskRevision()) {
      throw integrity("The persisted envelope differs from the normalized snapshot binding");
    }

    AccountBuilderGatewayCorrelationEntity correlationEntity =
        entityManager.find(AccountBuilderGatewayCorrelationEntity.class, entity.orchestrationId);
    if (correlationEntity == null) {
      throw integrity("The normalized gateway correlation is missing");
    }
    GatewayCorrelation correlation = toCorrelation(correlationEntity);
    verifyCorrelation(
        correlation, correlationEntity, entity, redactedRequestSnapshot, bindingEntity);

    AccountBuilderAuthorityConsumptionEntity authorityEntity =
        entityManager
            .createQuery(
                "select a from AccountBuilderAuthorityConsumptionEntity a where"
                    + " a.orchestrationId=:orchestrationId",
                AccountBuilderAuthorityConsumptionEntity.class)
            .setParameter("orchestrationId", entity.orchestrationId)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .orElse(null);
    AuthorityConsumption authority =
        authorityEntity == null ? null : toAuthorityConsumption(authorityEntity);
    if (authority != null
        && (!validId(authority.authorityId())
            || !validId(authority.orchestrationId())
            || !Objects.equals(authority.orchestrationId(), entity.orchestrationId)
            || !Objects.equals(authority.authorityId(), correlation.authorityId())
            || !Objects.equals(authority.envelopeDigest(), correlation.envelopeDigest())
            || !validDigest(authority.approvedScopeDigest())
            || !validId(authority.operatorId())
            || authority.expiresAt() == null
            || authority.consumedAt() == null
            || !authority.expiresAt().isAfter(authority.consumedAt()))) {
      throw integrity("The normalized authority ownership differs from immutable evidence");
    }

    AccountBuilderOrchestrationLeaseEntity leaseEntity =
        entityManager.find(AccountBuilderOrchestrationLeaseEntity.class, entity.orchestrationId);
    Lease lease = leaseEntity == null ? null : toLease(leaseEntity);
    if (lease != null
        && (!LEASE_STATES.contains(state)
            || !validId(lease.reservationId())
            || lease.generation() < 1
            || lease.fence() < 1
            || !validDigest(lease.fenceTokenDigest())
            || lease.expiresAt() == null
            || lease.generation() != redactedRequestSnapshot.envelope().leaseGeneration()
            || lease.fence() != redactedRequestSnapshot.envelope().fence()
            || !Objects.equals(leaseEntity.state, entity.state))) {
      throw integrity("The normalized lease tuple differs from the immutable root");
    }
    if (requiresAuthority(state) && authority == null) {
      throw integrity("A delivery state is missing durable authority ownership");
    }
    if (requiresLease(state) && lease == null) {
      throw integrity("A delivery state is missing its normalized lease");
    }

    List<AccountBuilderOrchestrationReceiptEntity> receiptEntities =
        entityManager
            .createQuery(
                "select r from AccountBuilderOrchestrationReceiptEntity r where"
                    + " r.orchestrationId=:orchestrationId order by r.sequence asc",
                AccountBuilderOrchestrationReceiptEntity.class)
            .setParameter("orchestrationId", entity.orchestrationId)
            .getResultList();
    List<GatewayReceipt> receipts = new ArrayList<>();
    for (AccountBuilderOrchestrationReceiptEntity receiptEntity : receiptEntities) {
      GatewayReceipt receipt = read(receiptEntity.receiptJson, GatewayReceipt.class);
      String digest = canonicalJson.digest(receipt);
      String identity = receiptIdentity(receipt, digest);
      String evidenceDigest =
          receipt.evidenceRefs().isEmpty() ? null : canonicalJson.digest(receipt.evidenceRefs());
      if (!Objects.equals(receiptEntity.receiptDigest, digest)
          || !Objects.equals(receiptEntity.receiptIdentity, identity)
          || !Objects.equals(receiptEntity.phase, receipt.phase())
          || !Objects.equals(receiptEntity.outcome, receipt.state().name())
          || !Objects.equals(receiptEntity.stableCode, receipt.failureCode().name())
          || !Objects.equals(receiptEntity.evidenceDigest, evidenceDigest)) {
        throw integrity("A normalized receipt identity or digest is inconsistent");
      }
      receipts.add(receipt);
    }

    AccountBuilderOrchestrationTerminalResultEntity terminalEntity =
        entityManager.find(
            AccountBuilderOrchestrationTerminalResultEntity.class, entity.orchestrationId);
    TerminalResult terminal = terminalEntity == null ? null : toTerminalResult(terminalEntity);
    OrchestrationView normalized =
        new OrchestrationView(
            AccountBuilderOrchestrationModels.VERSION,
            entity.orchestrationId,
            entity.requestId,
            entity.idempotencyKey,
            entity.requestFingerprint,
            entity.envelopeDigest,
            mode,
            entity.sourceType,
            state,
            failureCode,
            lease,
            receipts,
            authority != null,
            entity.retryAllowed,
            entity.viewVersion,
            entity.createdAt,
            entity.updatedAt);
    OrchestrationView stored = read(entity.viewJson, OrchestrationView.class);
    if (!stored.equals(normalized)) {
      throw integrity("The denormalized orchestration view disagrees with normalized evidence");
    }
    verifyTerminal(normalized, receipts, terminal);
    verifyEvents(entity);
    if (normalized.mode() == AccountBuilderOrchestrationModels.Mode.HELD
        || normalized.mode() == AccountBuilderOrchestrationModels.Mode.SHADOW) {
      if (authority != null || lease != null) {
        throw integrity("Held and shadow orchestrations cannot own delivery evidence");
      }
    }
    return new NormalizedAggregate(
        normalized,
        correlation,
        redactedRequestSnapshot,
        bindings,
        authority,
        lease,
        receipts,
        terminal);
  }

  private AccountBuilderOrchestrationEntity findByAnyIdentity(OrchestrationRequest request) {
    List<AccountBuilderOrchestrationEntity> values =
        entityManager
            .createQuery(
                "select o from AccountBuilderOrchestrationEntity o where"
                    + " o.orchestrationId=:orchestrationId"
                    + " or o.requestId=:requestId or o.idempotencyKey=:idempotencyKey",
                AccountBuilderOrchestrationEntity.class)
            .setParameter("orchestrationId", request.orchestrationId())
            .setParameter("requestId", request.requestId())
            .setParameter("idempotencyKey", request.idempotencyKey())
            .getResultList();
    if (values.isEmpty()) {
      return null;
    }
    String owner = values.get(0).orchestrationId;
    if (values.stream().anyMatch(value -> !owner.equals(value.orchestrationId))) {
      throw integrity("Identity indexes point at multiple orchestration owners");
    }
    return values.get(0);
  }

  private AccountBuilderOrchestrationEntity require(String id, LockModeType lockMode) {
    if (id == null || !id.matches(ID_PATTERN)) {
      throw failure(FailureCode.INVALID_CONTRACT, "A valid orchestration id is required");
    }
    AccountBuilderOrchestrationEntity entity =
        lockMode == LockModeType.NONE
            ? entityManager.find(AccountBuilderOrchestrationEntity.class, id)
            : entityManager.find(AccountBuilderOrchestrationEntity.class, id, lockMode);
    if (entity == null) {
      throw failure(FailureCode.INVALID_CONTRACT, "Orchestration was not found");
    }
    return entity;
  }

  private void appendEvent(
      AccountBuilderOrchestrationEntity orchestration, String eventType, String digest) {
    Long maximum =
        entityManager
            .createQuery(
                "select coalesce(max(e.sequence), 0) from"
                    + " AccountBuilderOrchestrationEventEntity e where"
                    + " e.orchestrationId=:orchestrationId",
                Long.class)
            .setParameter("orchestrationId", orchestration.orchestrationId)
            .getSingleResult();
    AccountBuilderOrchestrationEventEntity event = new AccountBuilderOrchestrationEventEntity();
    event.eventId = java.util.UUID.randomUUID().toString();
    event.orchestrationId = orchestration.orchestrationId;
    event.sequence = maximum + 1;
    event.eventType = eventType;
    event.payloadDigest = digest;
    event.occurredAt = now();
    entityManager.persist(event);
  }

  private void persistBinding(OrchestrationRequest request) {
    AccountBuilderOrchestrationSnapshotBindingEntity binding =
        new AccountBuilderOrchestrationSnapshotBindingEntity();
    binding.orchestrationId = request.orchestrationId();
    binding.decisionDigest = request.bindings().decisionDigest();
    binding.semanticActionDigest = request.bindings().semanticActionDigest();
    binding.catalogDigest = request.bindings().catalogDigest();
    binding.capabilityDigest = request.bindings().capabilityDigest();
    binding.goalPlanDigest = request.bindings().goalPlanDigest();
    binding.taskSkillDigestsJson = write(request.bindings().taskSkillDigests());
    binding.toolSchemaDigest = request.bindings().toolSchemaDigest();
    binding.knowledgeDigest = request.bindings().knowledgeDigest();
    binding.policyDigest = request.bindings().policyDigest();
    binding.verifierDigest = request.bindings().verifierDigest();
    binding.buildId = request.bindings().buildId();
    binding.expectedObservationRevision = request.bindings().expectedObservationRevision();
    binding.expectedTaskRevision = request.bindings().expectedTaskRevision();
    binding.observationDigest = request.bindings().observationDigest();
    binding.promptDigest = request.bindings().promptDigest();
    entityManager.persist(binding);
  }

  private void persistCorrelation(OrchestrationRequest request, String envelopeDigest) {
    GatewayCorrelation correlation = toCorrelation(request, envelopeDigest);
    AccountBuilderGatewayCorrelationEntity entity = new AccountBuilderGatewayCorrelationEntity();
    entity.orchestrationId = correlation.orchestrationId();
    entity.contractVersion = correlation.contractVersion();
    entity.requestId = correlation.requestId();
    entity.idempotencyKey = correlation.idempotencyKey();
    entity.envelopeDigest = correlation.envelopeDigest();
    entity.mode = correlation.mode().name();
    entity.sourceType = correlation.sourceType();
    entity.taskKey = correlation.taskKey();
    entity.authorityId = correlation.authorityId();
    entity.identitiesDigest = correlation.identitiesDigest();
    entity.settingsDigest = correlation.settingsDigest();
    entity.budgetsDigest = correlation.budgetsDigest();
    entity.expectedObservationRevision = correlation.expectedObservationRevision();
    entity.expectedTaskRevision = correlation.expectedTaskRevision();
    entity.deadline = truncate(correlation.deadline());
    entityManager.persist(entity);
  }

  private void upsertLease(String orchestrationId, Lease lease, State state) {
    AccountBuilderOrchestrationLeaseEntity existing =
        entityManager.find(AccountBuilderOrchestrationLeaseEntity.class, orchestrationId);
    if (existing != null) {
      if (!Objects.equals(existing.reservationId, lease.reservationId())
          || existing.generation != lease.generation()
          || existing.fence != lease.fence()
          || !Objects.equals(existing.fenceTokenDigest, lease.fenceTokenDigest())
          || !Objects.equals(existing.expiresAt, truncate(lease.expiresAt()))) {
        throw failure(FailureCode.FENCE_MISMATCH, "A persisted lease tuple cannot be replaced");
      }
      existing.state = state.name();
      return;
    }
    AccountBuilderOrchestrationLeaseEntity entity = new AccountBuilderOrchestrationLeaseEntity();
    entity.orchestrationId = orchestrationId;
    entity.reservationId = lease.reservationId();
    entity.generation = lease.generation();
    entity.fence = lease.fence();
    entity.fenceTokenDigest = lease.fenceTokenDigest();
    entity.expiresAt = truncate(lease.expiresAt());
    entity.state = state.name();
    entityManager.persist(entity);
  }

  private ReceiptInsert appendReceipt(
      AccountBuilderOrchestrationEntity orchestration,
      GatewayReceipt receipt,
      String identity,
      String digest) {
    AccountBuilderOrchestrationReceiptEntity byIdentity =
        entityManager
            .createQuery(
                "select r from AccountBuilderOrchestrationReceiptEntity r where"
                    + " r.orchestrationId=:orchestrationId and r.receiptIdentity=:identity",
                AccountBuilderOrchestrationReceiptEntity.class)
            .setParameter("orchestrationId", orchestration.orchestrationId)
            .setParameter("identity", identity)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .orElse(null);
    AccountBuilderOrchestrationReceiptEntity byDigest =
        entityManager
            .createQuery(
                "select r from AccountBuilderOrchestrationReceiptEntity r where"
                    + " r.orchestrationId=:orchestrationId and r.phase=:phase"
                    + " and r.receiptDigest=:digest",
                AccountBuilderOrchestrationReceiptEntity.class)
            .setParameter("orchestrationId", orchestration.orchestrationId)
            .setParameter("phase", receipt.phase())
            .setParameter("digest", digest)
            .setMaxResults(1)
            .getResultStream()
            .findFirst()
            .orElse(null);
    AccountBuilderOrchestrationReceiptEntity existing = byIdentity != null ? byIdentity : byDigest;
    if (existing != null) {
      if (!Objects.equals(existing.receiptDigest, digest)) {
        throw failure(
            FailureCode.IDEMPOTENCY_CONFLICT, "Receipt identity was reused with new data");
      }
      return new ReceiptInsert(false, true);
    }

    Long maximum =
        entityManager
            .createQuery(
                "select coalesce(max(r.sequence), 0) from"
                    + " AccountBuilderOrchestrationReceiptEntity r where"
                    + " r.orchestrationId=:orchestrationId",
                Long.class)
            .setParameter("orchestrationId", orchestration.orchestrationId)
            .getSingleResult();
    AccountBuilderOrchestrationReceiptEntity entity =
        new AccountBuilderOrchestrationReceiptEntity();
    entity.receiptId =
        canonicalJson.digest(
            Map.of("orchestrationId", orchestration.orchestrationId, "identity", identity));
    entity.orchestrationId = orchestration.orchestrationId;
    entity.receiptIdentity = identity;
    entity.receiptDigest = digest;
    entity.sequence = maximum + 1;
    entity.phase = receipt.phase();
    entity.outcome = receipt.state().name();
    entity.stableCode = receipt.failureCode().name();
    entity.receiptJson = write(receipt);
    entity.evidenceDigest =
        receipt.evidenceRefs().isEmpty() ? null : canonicalJson.digest(receipt.evidenceRefs());
    entity.occurredAt = receipt.at() == null ? orchestration.updatedAt : truncate(receipt.at());
    entityManager.persist(entity);
    return new ReceiptInsert(true, false);
  }

  private void persistTerminalResult(
      AccountBuilderOrchestrationEntity orchestration,
      OrchestrationView view,
      GatewayReceipt receipt,
      State previousState) {
    boolean reconciled = previousState == State.NEEDS_RECONCILIATION;
    String evidenceDigest =
        receipt == null || receipt.evidenceRefs().isEmpty()
            ? null
            : canonicalJson.digest(receipt.evidenceRefs());
    AccountBuilderOrchestrationTerminalResultEntity existing =
        entityManager.find(
            AccountBuilderOrchestrationTerminalResultEntity.class, orchestration.orchestrationId);
    if (existing != null) {
      if (!existing.outcome.equals(view.state().name())
          || !existing.stableCode.equals(view.failureCode().name())
          || existing.retryAllowed != view.retryAllowed()
          || existing.reconciled != reconciled
          || !Objects.equals(existing.evidenceDigest, evidenceDigest)
          || !Objects.equals(existing.terminalAt, view.updatedAt())) {
        throw failure(
            FailureCode.IDEMPOTENCY_CONFLICT,
            "Terminal orchestration evidence is immutable and cannot be rewritten");
      }
      return;
    }
    AccountBuilderOrchestrationTerminalResultEntity entity =
        new AccountBuilderOrchestrationTerminalResultEntity();
    entity.orchestrationId = orchestration.orchestrationId;
    entity.outcome = view.state().name();
    entity.stableCode = view.failureCode().name();
    entity.retryAllowed = view.retryAllowed();
    entity.reconciled = reconciled;
    entity.evidenceDigest = evidenceDigest;
    entity.terminalAt = view.updatedAt();
    entityManager.persist(entity);
  }

  private void validateAuthority(
      String orchestrationId,
      OrchestrationRequest request,
      GameplayContracts.HumanAuthority authority,
      String envelopeDigest) {
    if (!request.orchestrationId().equals(orchestrationId)
        || authority == null
        || !GameplayContracts.VERSION.equals(authority.contractVersion())
        || !authority.oneUse()
        || !"HUMAN".equals(authority.actorType())
        || !validId(authority.authorityId())
        || !authority.authorityId().equals(request.envelope().authorityId())
        || !validDigest(envelopeDigest)
        || !Objects.equals(authority.envelopeDigest(), envelopeDigest)
        || !validDigest(authority.scopeDigest())
        || authority.approvedBy() == null
        || !authority.approvedBy().matches(ID_PATTERN)
        || authority.approvedAt() == null
        || authority.expiresAt() == null
        || authority.approvedAt().isAfter(clock.instant())
        || !authority.expiresAt().isAfter(authority.approvedAt())
        || !authority.expiresAt().isAfter(clock.instant())
        || authority.signature() == null
        || !authority.signature().matches(SIGNATURE_PATTERN)) {
      throw failure(
          FailureCode.AUTHORITY_INVALID, "The authority does not bind the request exactly");
    }
    if (!authorityVerifier.verifyBoundAuthority(authority, request.envelope())) {
      throw failure(FailureCode.AUTHORITY_INVALID, "Human authority signature or scope is invalid");
    }
  }

  private void validateOriginalRequest(
      AccountBuilderOrchestrationEntity orchestration,
      OrchestrationRequest persistedRequest,
      OrchestrationRequest originalRequest,
      String envelopeDigest) {
    if (originalRequest == null
        || persistedRequest == null
        || originalRequest.envelope() == null
        || persistedRequest.envelope() == null
        || !Objects.equals(orchestration.orchestrationId, persistedRequest.orchestrationId())
        || !Objects.equals(orchestration.requestId, persistedRequest.requestId())
        || !Objects.equals(orchestration.idempotencyKey, persistedRequest.idempotencyKey())
        || !Objects.equals(orchestration.requestFingerprint, persistedRequest.requestFingerprint())
        || !Objects.equals(orchestration.orchestrationId, originalRequest.orchestrationId())
        || !Objects.equals(orchestration.requestId, originalRequest.requestId())
        || !Objects.equals(orchestration.idempotencyKey, originalRequest.idempotencyKey())
        || !Objects.equals(orchestration.requestFingerprint, originalRequest.requestFingerprint())
        || !Objects.equals(orchestration.requestId, persistedRequest.envelope().requestId())
        || !Objects.equals(
            orchestration.idempotencyKey, persistedRequest.envelope().idempotencyKey())
        || !Objects.equals(orchestration.requestId, originalRequest.envelope().requestId())
        || !Objects.equals(
            orchestration.idempotencyKey, originalRequest.envelope().idempotencyKey())
        || !validDigest(envelopeDigest)
        || !Objects.equals(orchestration.envelopeDigest, envelopeDigest)
        || !Objects.equals(canonicalJson.digest(originalRequest.envelope()), envelopeDigest)) {
      throw conflict();
    }
  }

  private void ensureSameAuthority(
      AccountBuilderAuthorityConsumptionEntity existing,
      GameplayContracts.HumanAuthority authority,
      String envelopeDigest) {
    if (!Objects.equals(existing.envelopeDigest, envelopeDigest)
        || !Objects.equals(existing.approvedScopeDigest, authority.scopeDigest())
        || !Objects.equals(existing.operatorId, authority.approvedBy())
        || !Objects.equals(existing.expiresAt, truncate(authority.expiresAt()))) {
      throw failure(FailureCode.AUTHORITY_INVALID, "The persisted authority binding is different");
    }
  }

  private void markAuthorityConsumed(AccountBuilderOrchestrationEntity orchestration) {
    OrchestrationView previous = read(orchestration.viewJson, OrchestrationView.class);
    if (previous.authorityConsumed()) {
      return;
    }
    Instant now = now();
    OrchestrationView next =
        new OrchestrationView(
            previous.contractVersion(),
            previous.orchestrationId(),
            previous.requestId(),
            previous.idempotencyKey(),
            previous.requestFingerprint(),
            previous.envelopeDigest(),
            previous.mode(),
            previous.sourceType(),
            previous.state(),
            previous.failureCode(),
            previous.lease(),
            previous.receipts(),
            true,
            previous.retryAllowed(),
            previous.version() + 1,
            previous.createdAt(),
            now);
    orchestration.retryAllowed = next.retryAllowed();
    orchestration.viewVersion = next.version();
    orchestration.viewJson = write(next);
    orchestration.updatedAt = now;
    appendEvent(orchestration, "AUTHORITY_CONSUMED", canonicalJson.digest(next));
  }

  private void validateLease(Lease lease, OrchestrationRequest request, State state) {
    if (!LEASE_STATES.contains(state)
        || !validId(lease.reservationId())
        || lease.generation() < 1
        || lease.fence() < 1
        || !validDigest(lease.fenceTokenDigest())
        || lease.expiresAt() == null
        || !lease.expiresAt().isAfter(clock.instant())
        || lease.generation() != request.envelope().leaseGeneration()
        || lease.fence() != request.envelope().fence()) {
      throw failure(FailureCode.FENCE_MISMATCH, "Lease evidence is stale, malformed or expired");
    }
  }

  private void validateReceipt(
      OrchestrationRequest request,
      OrchestrationView previous,
      State state,
      FailureCode failureCode,
      GatewayReceipt receipt,
      Lease candidate) {
    if (receipt.requestId() == null
        || !request.requestId().equals(receipt.requestId())
        || !Objects.equals(receipt.envelopeDigest(), previous.envelopeDigest())
        || receipt.phase() == null
        || receipt.phase().isBlank()
        || receipt.phase().length() > 64
        || receipt.state() == null
        || receipt.failureCode() == null) {
      throw failure(FailureCode.BOUNDARY_VIOLATION, "Receipt identity does not match the request");
    }
    if (state != State.NEEDS_RECONCILIATION
        && (receipt.state() != state || receipt.failureCode() != failureCode)) {
      throw failure(
          FailureCode.BOUNDARY_VIOLATION, "Receipt outcome does not match the transition");
    }
    if (state == State.NEEDS_RECONCILIATION && receipt.state().provenTerminal()) {
      throw failure(
          FailureCode.BOUNDARY_VIOLATION,
          "A proven terminal receipt cannot be recorded as reconciliation-needed");
    }
    if (previous.lease() != null && receipt.lease() == null) {
      throw failure(FailureCode.FENCE_MISMATCH, "A receipt after reservation must carry its lease");
    }
    if (receipt.lease() != null && (candidate == null || !sameLease(receipt.lease(), candidate))) {
      throw failure(
          FailureCode.FENCE_MISMATCH, "Receipt lease does not match the transition lease");
    }
    if (receipt.receiptId() != null && receipt.receiptId().length() > 256) {
      throw failure(FailureCode.BOUNDARY_VIOLATION, "Receipt identity exceeds the bounded limit");
    }
  }

  private GatewayReceipt safeReceipt(GatewayReceipt receipt) {
    List<String> evidenceRefs = receipt.evidenceRefs().stream().map(this::opaqueDigest).toList();
    Instant at = receipt.at() == null ? null : truncate(receipt.at());
    Lease lease = receipt.lease() == null ? null : normalizedLease(receipt.lease());
    return new GatewayReceipt(
        receipt.requestId(),
        receipt.envelopeDigest(),
        receipt.phase(),
        receipt.state(),
        receipt.failureCode(),
        receipt.deliveryAcknowledged(),
        receipt.terminalReconciled(),
        lease,
        evidenceRefs,
        at,
        receipt.receiptId() == null ? null : opaqueDigest(receipt.receiptId()),
        receipt.killSwitch());
  }

  private String receiptIdentity(GatewayReceipt receipt, String digest) {
    if (receipt.receiptId() != null && !receipt.receiptId().isBlank()) {
      return "remote:" + canonicalJson.digest(receipt.receiptId());
    }
    return "canonical:" + digest;
  }

  private Lease normalizedLease(Lease lease) {
    return new Lease(
        lease.reservationId(),
        lease.generation(),
        lease.fence(),
        lease.fenceTokenDigest(),
        truncate(lease.expiresAt()));
  }

  private GatewayCorrelation toCorrelation(OrchestrationRequest request, String envelopeDigest) {
    return new GatewayCorrelation(
        AccountBuilderOrchestrationModels.VERSION,
        request.orchestrationId(),
        request.requestId(),
        request.idempotencyKey(),
        envelopeDigest,
        request.mode(),
        request.sourceType(),
        request.envelope().taskKey(),
        request.envelope().authorityId(),
        canonicalJson.digest(request.envelope().identities()),
        canonicalJson.digest(request.envelope().settings()),
        canonicalJson.digest(request.envelope().budgets()),
        request.envelope().expectedObservationRevision(),
        request.envelope().expectedTaskRevision(),
        truncate(request.deadline()));
  }

  private GatewayCorrelation toCorrelation(AccountBuilderGatewayCorrelationEntity entity) {
    try {
      return new GatewayCorrelation(
          entity.contractVersion,
          entity.orchestrationId,
          entity.requestId,
          entity.idempotencyKey,
          entity.envelopeDigest,
          AccountBuilderOrchestrationModels.Mode.valueOf(entity.mode),
          entity.sourceType,
          entity.taskKey,
          entity.authorityId,
          entity.identitiesDigest,
          entity.settingsDigest,
          entity.budgetsDigest,
          entity.expectedObservationRevision,
          entity.expectedTaskRevision,
          entity.deadline);
    } catch (IllegalArgumentException exception) {
      throw integrity("The normalized gateway correlation contains an unsupported mode");
    }
  }

  private void verifyCorrelation(
      GatewayCorrelation correlation,
      AccountBuilderGatewayCorrelationEntity correlationEntity,
      AccountBuilderOrchestrationEntity orchestration,
      OrchestrationRequest redactedRequestSnapshot,
      AccountBuilderOrchestrationSnapshotBindingEntity binding) {
    JsonNode persistedRoot;
    try {
      persistedRoot = objectMapper.readTree(orchestration.requestJson);
    } catch (JsonProcessingException exception) {
      throw integrity("The persisted request snapshot is not valid JSON");
    }
    JsonNode envelope = persistedRoot.path("envelope");
    if (!validId(correlation.orchestrationId())
        || !validId(correlation.requestId())
        || !validId(correlation.idempotencyKey())
        || !validDigest(correlation.envelopeDigest())
        || !Objects.equals(AccountBuilderOrchestrationModels.VERSION, correlation.contractVersion())
        || correlation.mode() == null
        || correlation.sourceType() == null
        || correlation.sourceType().isBlank()
        || correlation.sourceType().length() > 128
        || !validId(correlation.taskKey())
        || !validId(correlation.authorityId())
        || !validDigest(correlation.identitiesDigest())
        || !validDigest(correlation.settingsDigest())
        || !validDigest(correlation.budgetsDigest())
        || correlation.expectedObservationRevision() < 0
        || correlation.expectedTaskRevision() < 0
        || correlation.deadline() == null
        || !Objects.equals(correlation.orchestrationId(), orchestration.orchestrationId)
        || !Objects.equals(correlation.requestId(), orchestration.requestId)
        || !Objects.equals(correlation.idempotencyKey(), orchestration.idempotencyKey)
        || !Objects.equals(correlation.envelopeDigest(), orchestration.envelopeDigest)
        || !Objects.equals(correlation.mode(), redactedRequestSnapshot.mode())
        || !Objects.equals(correlation.sourceType(), orchestration.sourceType)
        || !Objects.equals(correlation.requestId(), redactedRequestSnapshot.envelope().requestId())
        || !Objects.equals(
            correlation.idempotencyKey(), redactedRequestSnapshot.envelope().idempotencyKey())
        || !Objects.equals(correlation.taskKey(), redactedRequestSnapshot.envelope().taskKey())
        || !Objects.equals(
            correlation.authorityId(), redactedRequestSnapshot.envelope().authorityId())
        || !Objects.equals(
            correlation.expectedObservationRevision(),
            redactedRequestSnapshot.envelope().expectedObservationRevision())
        || !Objects.equals(
            correlation.expectedTaskRevision(),
            redactedRequestSnapshot.envelope().expectedTaskRevision())
        || !Objects.equals(correlation.deadline(), truncate(redactedRequestSnapshot.deadline()))
        || !Objects.equals(
            correlation.expectedObservationRevision(), binding.expectedObservationRevision)
        || !Objects.equals(correlation.expectedTaskRevision(), binding.expectedTaskRevision)
        || !Objects.equals(correlation.identitiesDigest(), redactedDigest(envelope, "identities"))
        || !Objects.equals(correlation.settingsDigest(), redactedDigest(envelope, "settings"))
        || !Objects.equals(correlation.budgetsDigest(), redactedDigest(envelope, "budgets"))
        || !Objects.equals(correlationEntity.orchestrationId, correlation.orchestrationId())
        || !Objects.equals(correlationEntity.contractVersion, correlation.contractVersion())
        || !Objects.equals(correlationEntity.requestId, correlation.requestId())
        || !Objects.equals(correlationEntity.idempotencyKey, correlation.idempotencyKey())
        || !Objects.equals(correlationEntity.envelopeDigest, correlation.envelopeDigest())
        || !Objects.equals(correlationEntity.mode, correlation.mode().name())
        || !Objects.equals(correlationEntity.sourceType, correlation.sourceType())
        || !Objects.equals(correlationEntity.taskKey, correlation.taskKey())
        || !Objects.equals(correlationEntity.authorityId, correlation.authorityId())
        || !Objects.equals(correlationEntity.identitiesDigest, correlation.identitiesDigest())
        || !Objects.equals(correlationEntity.settingsDigest, correlation.settingsDigest())
        || !Objects.equals(correlationEntity.budgetsDigest, correlation.budgetsDigest())
        || correlationEntity.expectedObservationRevision
            != correlation.expectedObservationRevision()
        || correlationEntity.expectedTaskRevision != correlation.expectedTaskRevision()
        || !Objects.equals(correlationEntity.deadline, correlation.deadline())) {
      throw integrity("The normalized gateway correlation differs from immutable evidence");
    }
  }

  private String redactedDigest(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    String digest = value.path("digest").asText(null);
    if (!value.path("redacted").asBoolean(false) || !validDigest(digest)) {
      throw integrity("The persisted correlation field is not safely redacted: " + field);
    }
    return digest;
  }

  private SnapshotBindings toBindings(AccountBuilderOrchestrationSnapshotBindingEntity entity) {
    return new SnapshotBindings(
        entity.decisionDigest,
        entity.semanticActionDigest,
        entity.catalogDigest,
        entity.capabilityDigest,
        entity.goalPlanDigest,
        read(entity.taskSkillDigestsJson, new TypeReference<List<String>>() {}),
        entity.toolSchemaDigest,
        entity.knowledgeDigest,
        entity.policyDigest,
        entity.verifierDigest,
        entity.buildId,
        entity.expectedObservationRevision,
        entity.expectedTaskRevision,
        entity.observationDigest,
        entity.promptDigest);
  }

  private Lease toLease(AccountBuilderOrchestrationLeaseEntity entity) {
    return new Lease(
        entity.reservationId,
        entity.generation,
        entity.fence,
        entity.fenceTokenDigest,
        entity.expiresAt);
  }

  private AuthorityConsumption toAuthorityConsumption(
      AccountBuilderAuthorityConsumptionEntity entity) {
    return new AuthorityConsumption(
        entity.authorityId,
        entity.orchestrationId,
        entity.envelopeDigest,
        entity.approvedScopeDigest,
        entity.operatorId,
        entity.expiresAt,
        entity.consumedAt);
  }

  private TerminalResult toTerminalResult(AccountBuilderOrchestrationTerminalResultEntity entity) {
    return new TerminalResult(
        entity.orchestrationId,
        State.valueOf(entity.outcome),
        FailureCode.valueOf(entity.stableCode),
        entity.retryAllowed,
        entity.reconciled,
        entity.evidenceDigest,
        entity.terminalAt,
        0);
  }

  private void verifyTerminal(
      OrchestrationView view, List<GatewayReceipt> receipts, TerminalResult terminal) {
    if (view.state().provenTerminal() != (terminal != null)) {
      throw integrity("Terminal result presence does not match the normalized state");
    }
    if (terminal != null
        && (terminal.outcome() != view.state()
            || terminal.stableCode() != view.failureCode()
            || terminal.retryAllowed() != view.retryAllowed()
            || !Objects.equals(terminal.terminalAt(), view.updatedAt())
            || !Objects.equals(
                terminal.evidenceDigest(),
                terminalEvidenceDigest(receipts, view.state(), view.failureCode())))) {
      throw integrity("Terminal result does not match the normalized root");
    }
  }

  private String terminalEvidenceDigest(
      List<GatewayReceipt> receipts, State outcome, FailureCode failureCode) {
    for (int index = receipts.size() - 1; index >= 0; index--) {
      GatewayReceipt receipt = receipts.get(index);
      if (receipt.state() == outcome && receipt.failureCode() == failureCode) {
        return receipt.evidenceRefs().isEmpty()
            ? null
            : canonicalJson.digest(receipt.evidenceRefs());
      }
    }
    return null;
  }

  private void verifyEvents(AccountBuilderOrchestrationEntity entity) {
    Long count =
        entityManager
            .createQuery(
                "select count(e) from AccountBuilderOrchestrationEventEntity e where"
                    + " e.orchestrationId=:orchestrationId",
                Long.class)
            .setParameter("orchestrationId", entity.orchestrationId)
            .getSingleResult();
    Long maximum =
        entityManager
            .createQuery(
                "select coalesce(max(e.sequence), 0) from"
                    + " AccountBuilderOrchestrationEventEntity e where"
                    + " e.orchestrationId=:orchestrationId",
                Long.class)
            .setParameter("orchestrationId", entity.orchestrationId)
            .getSingleResult();
    if (count != entity.viewVersion + 1 || maximum != count) {
      throw integrity("Event sequence is not a complete monotonic history");
    }
  }

  private void requireIdentity(
      OrchestrationRequest request, String fingerprint, String envelopeDigest) {
    if (request == null
        || !validId(request.orchestrationId())
        || !validId(request.requestId())
        || !validId(request.idempotencyKey())
        || request.mode() == null
        || request.sourceType() == null
        || request.sourceType().isBlank()
        || request.sourceType().length() > 128
        || request.decision() == null
        || request.envelope() == null
        || request.bindings() == null
        || request.observation() == null
        || request.deadline() == null
        || !validDigest(fingerprint)
        || !validDigest(envelopeDigest)) {
      throw failure(FailureCode.INVALID_CONTRACT, "Durable orchestration identities are malformed");
    }
  }

  private boolean requiresAuthority(State state) {
    return state == State.AUTHORIZED
        || (LEASE_STATES.contains(state) && state != State.NEEDS_RECONCILIATION);
  }

  private boolean requiresLease(State state) {
    // A proven pre-submission terminal failure (connection refused before submission) carries no
    // lease and never needs one; a lease attached to a terminal state is still validated.
    return LEASE_STATES.contains(state)
        && state != State.NEEDS_RECONCILIATION
        && state != State.FAILED;
  }

  private boolean samePersistedReceipt(List<GatewayReceipt> persisted, GatewayReceipt candidate) {
    GatewayReceipt safe = safeReceipt(candidate);
    String digest = canonicalJson.digest(safe);
    String identity = receiptIdentity(safe, digest);
    return persisted.stream()
        .anyMatch(
            existing ->
                identity.equals(receiptIdentity(existing, canonicalJson.digest(existing)))
                    && digest.equals(canonicalJson.digest(existing)));
  }

  private boolean sameLease(Lease left, Lease right) {
    return Objects.equals(left.reservationId(), right.reservationId())
        && left.generation() == right.generation()
        && left.fence() == right.fence()
        && Objects.equals(left.fenceTokenDigest(), right.fenceTokenDigest())
        && Objects.equals(truncate(left.expiresAt()), truncate(right.expiresAt()));
  }

  private boolean validId(String value) {
    return value != null && value.matches(ID_PATTERN);
  }

  private boolean validDigest(String value) {
    return value != null && value.matches(DIGEST_PATTERN);
  }

  private String opaqueDigest(String value) {
    if (value == null || value.isBlank()) {
      throw failure(FailureCode.BOUNDARY_VIOLATION, "Evidence references must be non-empty");
    }
    return validDigest(value) ? value : canonicalJson.digest(value);
  }

  private Instant now() {
    return truncate(clock.instant());
  }

  private Instant truncate(Instant value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
  }

  private String writePersistedRequest(OrchestrationRequest request) {
    ObjectNode root = objectMapper.valueToTree(request);
    root.put("_envelopeDigest", canonicalJson.digest(request.envelope()));
    ObjectNode authority = objectNode(root, "authority");
    if (authority != null) {
      authority.remove("signature");
    }
    root.set("observation", digestNode(request.observation()));
    ObjectNode decision = objectNode(root, "decision");
    if (decision != null) {
      decision.set("semanticAction", digestNode(request.decision().semanticAction()));
      decision.set("evidenceRefs", digestArray(request.decision().evidenceRefs()));
    }
    ObjectNode envelope = objectNode(root, "envelope");
    if (envelope != null) {
      for (String field : List.of("settings", "identities", "budgets")) {
        JsonNode value = envelope.get(field);
        if (value != null && !value.isNull()) {
          envelope.set(field, digestNode(value));
        }
      }
    }
    return write(root);
  }

  private OrchestrationRequest readRedactedRequestSnapshot(String persistedJson) {
    try {
      JsonNode parsed = objectMapper.readTree(persistedJson);
      if (!(parsed instanceof ObjectNode root)) {
        throw integrity("The persisted request snapshot is not an object");
      }
      root.remove("_envelopeDigest");
      return objectMapper.treeToValue(root, OrchestrationRequest.class);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw integrity("The persisted request snapshot cannot be reconstructed");
    }
  }

  private ObjectNode objectNode(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    return value instanceof ObjectNode object ? object : null;
  }

  private ObjectNode digestNode(Object value) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("digest", canonicalJson.digest(value));
    result.put("redacted", true);
    return result;
  }

  private ArrayNode digestArray(List<String> values) {
    ArrayNode result = objectMapper.createArrayNode();
    if (values != null) {
      values.forEach(value -> result.add(opaqueDigest(value)));
    }
    return result;
  }

  private void verifyPersistedRequestSnapshot(
      String persistedJson,
      OrchestrationRequest reconstructedRequest,
      String expectedEnvelopeDigest) {
    final JsonNode root;
    try {
      root = objectMapper.readTree(persistedJson);
    } catch (JsonProcessingException exception) {
      throw integrity("The persisted request snapshot is not valid JSON");
    }
    if (!(root instanceof ObjectNode)) {
      throw integrity("The persisted request snapshot is not an object");
    }
    if (!Objects.equals(
            root.path("orchestrationId").asText(null), reconstructedRequest.orchestrationId())
        || !Objects.equals(root.path("requestId").asText(null), reconstructedRequest.requestId())
        || !Objects.equals(
            root.path("idempotencyKey").asText(null), reconstructedRequest.idempotencyKey())
        || !Objects.equals(
            root.path("requestFingerprint").asText(null), reconstructedRequest.requestFingerprint())
        || !Objects.equals(root.path("_envelopeDigest").asText(null), expectedEnvelopeDigest)
        || !Objects.equals(root.path("mode").asText(null), reconstructedRequest.mode().name())
        || !Objects.equals(
            root.path("sourceType").asText(null), reconstructedRequest.sourceType())) {
      throw integrity("The persisted request snapshot identity differs from its root row");
    }
    requireRedactedDigest(root, "observation");

    JsonNode decision = root.path("decision");
    if (!decision.isObject()
        || !Objects.equals(
            decision.path("semanticAction").path("digest").asText(null),
            reconstructedRequest.bindings().semanticActionDigest())
        || !decision.path("evidenceRefs").isArray()
        || !allValidDigests(decision.path("evidenceRefs"))) {
      throw integrity("The persisted decision snapshot is not safely redacted");
    }

    JsonNode envelope = root.path("envelope");
    if (!envelope.isObject()
        || !Objects.equals(
            envelope.path("requestId").asText(null), reconstructedRequest.envelope().requestId())
        || !Objects.equals(
            envelope.path("idempotencyKey").asText(null),
            reconstructedRequest.envelope().idempotencyKey())) {
      throw integrity("The persisted envelope snapshot identity differs from its root row");
    }
    for (String field : List.of("settings", "identities", "budgets")) {
      requireRedactedDigest(envelope, field);
    }

    JsonNode authority = root.get("authority");
    if (authority != null && !authority.isNull() && authority.has("signature")) {
      throw integrity("The persisted request snapshot contains an authority signature");
    }
  }

  private void requireRedactedDigest(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    if (!value.isObject()
        || !value.path("redacted").asBoolean(false)
        || !validDigest(value.path("digest").asText(null))) {
      throw integrity("The persisted request field is not safely redacted: " + field);
    }
  }

  private boolean allValidDigests(JsonNode values) {
    for (JsonNode value : values) {
      if (!validDigest(value.asText(null))) {
        return false;
      }
    }
    return true;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw failure(FailureCode.INTERNAL_FAILURE, "Could not persist orchestration JSON");
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw failure(FailureCode.INTERNAL_FAILURE, "Could not read orchestration JSON");
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw failure(FailureCode.INTERNAL_FAILURE, "Could not read normalized JSON");
    }
  }

  private AccountBuilderOrchestrationException conflict() {
    return failure(
        FailureCode.IDEMPOTENCY_CONFLICT,
        "Orchestration, request or idempotency identity has a different fingerprint");
  }

  private AccountBuilderOrchestrationException integrity(String message) {
    return failure(FailureCode.INTERNAL_FAILURE, message);
  }

  private AccountBuilderOrchestrationException failure(FailureCode code, String message) {
    return new AccountBuilderOrchestrationException(code, message);
  }

  private record ReceiptInsert(boolean inserted, boolean duplicate) {
    private static ReceiptInsert none() {
      return new ReceiptInsert(false, false);
    }
  }
}
