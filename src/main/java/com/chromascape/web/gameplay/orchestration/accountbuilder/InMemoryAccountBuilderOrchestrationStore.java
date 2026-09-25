package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderBudgetPolicy.OrchestrationBudget;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.AuthorityConsumption;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TerminalResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic store used by contract tests and isolated shadow execution. */
public final class InMemoryAccountBuilderOrchestrationStore
    implements AccountBuilderOrchestrationStore {
  private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";
  private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
  private static final String SIGNATURE_PATTERN = "hmac-sha256:[0-9a-f]{64}";
  private static final Set<State> LEASE_STATES =
      Set.of(
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

  private final Clock clock;
  private final CanonicalGameplayJson canonicalJson;
  private final AccountBuilderAuthorityVerifier authorityVerifier;
  private final Map<String, Entry> entries = new HashMap<>();
  private final Map<String, GatewayCorrelation> correlations = new HashMap<>();
  private final Map<String, String> requestOwners = new HashMap<>();
  private final Map<String, String> idempotencyOwners = new HashMap<>();
  private final Map<String, AuthorityConsumption> authorities = new HashMap<>();
  private final Map<String, String> authorityOwners = new HashMap<>();
  private final Map<String, TerminalResult> terminalResults = new HashMap<>();
  private final Map<String, Long> eventCounts = new HashMap<>();
  private final Map<String, AccountBuilderBudgetPolicy.OrchestrationBudget> budgets =
      new HashMap<>();

  public InMemoryAccountBuilderOrchestrationStore() {
    this(Clock.systemUTC(), (authority, envelope) -> false);
  }

  public InMemoryAccountBuilderOrchestrationStore(Clock clock) {
    this(clock, (authority, envelope) -> false);
  }

  /** Explicit verifier injection is required for deterministic unit tests. */
  public InMemoryAccountBuilderOrchestrationStore(
      AccountBuilderAuthorityVerifier authorityVerifier) {
    this(Clock.systemUTC(), authorityVerifier);
  }

  public InMemoryAccountBuilderOrchestrationStore(
      Clock clock, AccountBuilderAuthorityVerifier authorityVerifier) {
    this(clock, authorityVerifier, defaultCanonicalJson());
  }

  public InMemoryAccountBuilderOrchestrationStore(
      AccountBuilderAuthorityVerifier authorityVerifier, Clock clock) {
    this(clock, authorityVerifier);
  }

  InMemoryAccountBuilderOrchestrationStore(
      Clock clock,
      AccountBuilderAuthorityVerifier authorityVerifier,
      CanonicalGameplayJson canonicalJson) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.authorityVerifier = Objects.requireNonNull(authorityVerifier, "authorityVerifier");
    this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
  }

  @Override
  public synchronized Claim claim(
      OrchestrationRequest request,
      String fingerprint,
      String envelopeDigest,
      AccountBuilderBudgetPolicy.OrchestrationBudget budget) {
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
        || !validDigest(envelopeDigest)
        || budget == null) {
      throw failure(FailureCode.INVALID_CONTRACT, "Durable orchestration identities are malformed");
    }
    Set<String> owners = new HashSet<>();
    if (entries.containsKey(request.orchestrationId())) {
      owners.add(request.orchestrationId());
    }
    if (requestOwners.containsKey(request.requestId())) {
      owners.add(requestOwners.get(request.requestId()));
    }
    if (idempotencyOwners.containsKey(request.idempotencyKey())) {
      owners.add(idempotencyOwners.get(request.idempotencyKey()));
    }
    if (!owners.isEmpty()) {
      if (owners.size() != 1) {
        throw integrity("Identity indexes point at multiple orchestration owners");
      }
      Entry owned = entries.get(owners.iterator().next());
      OrchestrationBudget existingBudget = budgets.get(owned.view.orchestrationId());
      if (existingBudget == null) {
        // A durable claim without a budget is an incomplete aggregate and never replays.
        throw integrity("Durable orchestration claim has no immutable budget");
      }
      if (!sameBudget(existingBudget, budget)
          || owned == null
          || !owned.view.requestFingerprint().equals(fingerprint)
          || !owned.view.orchestrationId().equals(request.orchestrationId())
          || !owned.view.requestId().equals(request.requestId())
          || !owned.view.idempotencyKey().equals(request.idempotencyKey())
          || !owned.view.envelopeDigest().equals(envelopeDigest)) {
        throw conflict();
      }
      return new Claim(owned.view, false);
    }

    Instant now = now();
    OrchestrationView view =
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
    entries.put(request.orchestrationId(), new Entry(request, view));
    correlations.put(request.orchestrationId(), toCorrelation(request, envelopeDigest));
    requestOwners.put(request.requestId(), request.orchestrationId());
    idempotencyOwners.put(request.idempotencyKey(), request.orchestrationId());
    budgets.put(request.orchestrationId(), budget);
    eventCounts.put(request.orchestrationId(), 1L);
    return new Claim(view, true);
  }

  /** Replay compares only the immutable limits; started/expires derive from the claim instant. */
  private boolean sameBudget(OrchestrationBudget persisted, OrchestrationBudget candidate) {
    return persisted.maxActions() == candidate.maxActions()
        && persisted.maxAttempts() == candidate.maxAttempts()
        && persisted.maxElapsedMs() == candidate.maxElapsedMs();
  }

  @Override
  public synchronized Optional<AccountBuilderBudgetPolicy.OrchestrationBudget> findBudget(
      String orchestrationId) {
    return Optional.ofNullable(budgets.get(orchestrationId));
  }

  @Override
  public synchronized AccountBuilderBudgetPolicy.OrchestrationBudget consumeBudget(
      String orchestrationId, boolean action, boolean attempt) {
    OrchestrationBudget budget = budgets.get(orchestrationId);
    if (budget == null) {
      throw failure(
          FailureCode.INVALID_CONTRACT, "No durable budget was claimed for this orchestration");
    }
    Instant now = now();
    if (budget.expiredAt(now)) {
      throw failure(FailureCode.DEADLINE_EXPIRED, "The orchestration budget horizon has expired");
    }
    if ((action && budget.actionsExhausted()) || (attempt && budget.attemptsExhausted())) {
      throw failure(
          FailureCode.BUDGET_EXHAUSTED, "No orchestration budget capacity remains for delivery");
    }
    OrchestrationBudget advanced = budget;
    if (attempt) {
      advanced = advanced.withAttemptUsed();
    }
    if (action) {
      advanced = advanced.withActionUsed();
    }
    budgets.put(orchestrationId, advanced);
    return advanced;
  }

  @Override
  public synchronized AuthorityConsumption consumeAuthority(
      String orchestrationId,
      GameplayContracts.HumanAuthority authority,
      String envelopeDigest,
      OrchestrationRequest originalRequest) {
    Entry entry = require(orchestrationId);
    if (originalRequest == null || !entry.request.equals(originalRequest)) {
      throw conflict();
    }
    validateAuthority(entry.request, authority, envelopeDigest);
    if (!entry.request.mode().deliveryMode()) {
      throw failure(FailureCode.MODE_NOT_DELIVERABLE, "Only delivery modes can consume authority");
    }
    if (!authority.expiresAt().isAfter(clock.instant())) {
      throw failure(FailureCode.AUTHORITY_EXPIRED, "The human authority has expired");
    }
    AuthorityConsumption existing = authorities.get(authority.authorityId());
    if (existing != null) {
      if (!orchestrationId.equals(existing.orchestrationId())) {
        throw failure(
            FailureCode.AUTHORITY_ALREADY_USED,
            "The one-use authority is already owned by another orchestration");
      }
      ensureSameAuthority(existing, authority, envelopeDigest);
      markAuthorityConsumed(entry);
      return existing;
    }
    String ownedAuthority = authorityOwners.get(orchestrationId);
    if (ownedAuthority != null && !ownedAuthority.equals(authority.authorityId())) {
      throw failure(
          FailureCode.AUTHORITY_ALREADY_USED,
          "The orchestration already owns a different one-use authority");
    }
    AuthorityConsumption consumption =
        new AuthorityConsumption(
            authority.authorityId(),
            orchestrationId,
            envelopeDigest,
            authority.scopeDigest(),
            authority.approvedBy(),
            authority.expiresAt(),
            now());
    authorities.put(authority.authorityId(), consumption);
    authorityOwners.put(orchestrationId, authority.authorityId());
    markAuthorityConsumed(entry);
    return consumption;
  }

  @Override
  public synchronized Optional<OrchestrationView> find(String orchestrationId) {
    Entry entry = entries.get(orchestrationId);
    return entry == null ? Optional.empty() : Optional.of(readAggregate(orchestrationId).view());
  }

  @Override
  public synchronized NormalizedAggregate readAggregate(String orchestrationId) {
    Entry entry = require(orchestrationId);
    if (eventCounts.getOrDefault(orchestrationId, 0L) != entry.view.version() + 1) {
      throw integrity("Event sequence is not a complete monotonic history");
    }
    AuthorityConsumption authority = authorities.get(authorityOwners.get(orchestrationId));
    Lease lease = entry.view.lease();
    TerminalResult terminal = terminalResults.get(orchestrationId);
    if (entry.view.authorityConsumed() != (authority != null)) {
      throw integrity("Authority marker disagrees with normalized ownership");
    }
    if (authority != null
        && (entry.request.authority() == null
            || !Objects.equals(
                authority.approvedScopeDigest(), entry.request.authority().scopeDigest())
            || !Objects.equals(authority.operatorId(), entry.request.authority().approvedBy())
            || !Objects.equals(authority.expiresAt(), entry.request.authority().expiresAt())
            || !Objects.equals(authority.envelopeDigest(), entry.view.envelopeDigest()))) {
      throw integrity("Authority ownership differs from the immutable request");
    }
    if (entry.view.state().provenTerminal() != (terminal != null)) {
      throw integrity("Terminal result presence does not match the normalized state");
    }
    if (requiresAuthority(entry.view.state()) && authority == null) {
      throw integrity("A delivery state is missing durable authority ownership");
    }
    if (requiresLease(entry.view.state()) && lease == null) {
      throw integrity("A delivery state is missing its normalized lease");
    }
    if (terminal != null
        && (terminal.outcome() != entry.view.state()
            || terminal.stableCode() != entry.view.failureCode()
            || terminal.retryAllowed() != entry.view.retryAllowed()
            || !Objects.equals(terminal.terminalAt(), entry.view.updatedAt())
            || !Objects.equals(
                terminal.evidenceDigest(),
                terminalEvidenceDigest(
                    entry.view.receipts(), entry.view.state(), entry.view.failureCode())))) {
      throw integrity("Terminal result does not match the normalized root");
    }
    if ((entry.view.mode() == AccountBuilderOrchestrationModels.Mode.HELD
            || entry.view.mode() == AccountBuilderOrchestrationModels.Mode.SHADOW)
        && (authority != null || lease != null)) {
      throw integrity("Held and shadow orchestrations cannot own delivery evidence");
    }
    GatewayCorrelation correlation = correlations.get(orchestrationId);
    if (correlation == null) {
      throw integrity("The normalized gateway correlation is missing");
    }
    return new NormalizedAggregate(
        entry.view,
        correlation,
        redactedRequestSnapshot(entry.request),
        entry.request.bindings(),
        authority,
        lease,
        entry.view.receipts(),
        terminal);
  }

  @Override
  public synchronized Lease requireLease(String orchestrationId, Lease expected) {
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
  public synchronized GatewayCorrelation correlation(String orchestrationId) {
    require(orchestrationId);
    GatewayCorrelation correlation = correlations.get(orchestrationId);
    if (correlation == null) {
      throw integrity("The normalized gateway correlation is missing");
    }
    return correlation;
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

  private OrchestrationRequest redactedRequestSnapshot(OrchestrationRequest request) {
    GameplayContracts.ProviderDecision decision = request.decision();
    GameplayContracts.ExecutionEnvelope envelope = request.envelope();
    GameplayContracts.HumanAuthority authority = request.authority();
    GameplayContracts.ProviderDecision redactedDecision =
        new GameplayContracts.ProviderDecision(
            decision.contractVersion(),
            decision.provider(),
            decision.modelVersion(),
            decision.decisionId(),
            decision.policyConfidence(),
            digestNode(decision.semanticAction()),
            decision.evidenceRefs() == null
                ? List.of()
                : decision.evidenceRefs().stream().map(this::opaqueDigest).toList());
    GameplayContracts.ExecutionEnvelope redactedEnvelope =
        new GameplayContracts.ExecutionEnvelope(
            envelope.contractVersion(),
            envelope.provider(),
            envelope.modelVersion(),
            envelope.decisionId(),
            envelope.requestId(),
            envelope.idempotencyKey(),
            envelope.goalPlanDigest(),
            envelope.taskSkillDigests(),
            envelope.taskKey(),
            digestNode(envelope.settings()),
            digestNode(envelope.identities()),
            envelope.catalogDigest(),
            envelope.schemaDigest(),
            envelope.policyDigest(),
            envelope.knowledgeDigest(),
            envelope.semanticActionDigest(),
            envelope.expectedTransition(),
            envelope.verifierId(),
            envelope.buildId(),
            envelope.expectedObservationRevision(),
            envelope.expectedTaskRevision(),
            envelope.authorityId(),
            envelope.leaseGeneration(),
            envelope.fence(),
            digestNode(envelope.budgets()),
            envelope.deadline(),
            envelope.killSwitchScope());
    GameplayContracts.HumanAuthority redactedAuthority =
        authority == null
            ? null
            : new GameplayContracts.HumanAuthority(
                authority.contractVersion(),
                authority.authorityId(),
                authority.actorType(),
                authority.approvedBy(),
                authority.approvedAt(),
                authority.expiresAt(),
                authority.envelopeDigest(),
                authority.scopeDigest(),
                authority.oneUse(),
                null);
    return new OrchestrationRequest(
        request.orchestrationId(),
        request.requestId(),
        request.idempotencyKey(),
        request.requestFingerprint(),
        request.mode(),
        request.sourceType(),
        redactedDecision,
        redactedEnvelope,
        request.bindings(),
        redactedAuthority,
        digestNode(request.observation()),
        request.deadline());
  }

  private ObjectNode digestNode(Object value) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("digest", canonicalJson.digest(value));
    result.put("redacted", true);
    return result;
  }

  private static CanonicalGameplayJson defaultCanonicalJson() {
    return new CanonicalGameplayJson(new ObjectMapper().findAndRegisterModules());
  }

  @Override
  public synchronized OrchestrationView transition(
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
    Entry entry = require(orchestrationId);
    OrchestrationView previous = readAggregate(orchestrationId).view();
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
      if (receipt != null && !samePersistedReceipt(previous.receipts(), receipt)) {
        throw failure(
            FailureCode.IDEMPOTENCY_CONFLICT, "Terminal orchestration receipt is immutable");
      }
      if (authorityConsumed && !previous.authorityConsumed()) {
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
    if (authorityConsumed && !previous.authorityConsumed()) {
      throw failure(FailureCode.AUTHORITY_INVALID, "Authority consumption has no durable owner");
    }
    if (requiresAuthority(state) && !previous.authorityConsumed()) {
      throw failure(FailureCode.AUTHORITY_INVALID, "Delivery state requires consumed authority");
    }

    Lease candidate = lease;
    if (candidate == null && receipt != null && receipt.lease() != null) {
      candidate = receipt.lease();
    }
    if (candidate != null) {
      validateLease(candidate, entry.request, state);
      candidate = normalizedLease(candidate);
      if (previous.lease() != null && !sameLease(previous.lease(), candidate)) {
        throw failure(FailureCode.FENCE_MISMATCH, "A lease tuple cannot be replaced");
      }
    }
    if (receipt != null) {
      validateReceipt(entry.request, previous, state, failureCode, receipt, candidate);
    }
    GatewayReceipt safeReceipt = receipt == null ? null : safeReceipt(receipt);
    String receiptDigest = safeReceipt == null ? null : receiptDigest(safeReceipt);
    String receiptIdentity =
        safeReceipt == null ? null : receiptIdentity(safeReceipt, receiptDigest);
    boolean duplicate = false;
    List<GatewayReceipt> receipts = new ArrayList<>(previous.receipts());
    if (safeReceipt != null) {
      for (GatewayReceipt existing : receipts) {
        String existingDigest = receiptDigest(existing);
        String existingIdentity = receiptIdentity(existing, existingDigest);
        if (existingIdentity.equals(receiptIdentity) || existingDigest.equals(receiptDigest)) {
          if (!existingDigest.equals(receiptDigest)) {
            throw failure(
                FailureCode.IDEMPOTENCY_CONFLICT, "Receipt identity was reused with new data");
          }
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        receipts.add(safeReceipt);
      }
    }
    if (duplicate
        && (previous.state() != state
            || previous.failureCode() != failureCode
            || previous.retryAllowed() != retryAllowed
            || !Objects.equals(previous.lease(), candidate))) {
      throw failure(
          FailureCode.IDEMPOTENCY_CONFLICT, "A receipt replay cannot change orchestration outcome");
    }

    Lease effectiveLease = candidate == null ? previous.lease() : candidate;
    if (requiresLease(state) && effectiveLease == null) {
      throw failure(FailureCode.FENCE_MISMATCH, "This delivery state requires a persisted lease");
    }
    boolean changed =
        previous.state() != state
            || previous.failureCode() != failureCode
            || !Objects.equals(previous.lease(), effectiveLease)
            || previous.retryAllowed() != retryAllowed
            || !previous.receipts().equals(receipts);
    if (!changed) {
      return previous;
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
            previous.authorityConsumed(),
            retryAllowed,
            previous.version() + 1,
            previous.createdAt(),
            now);
    if (state.provenTerminal()) {
      persistTerminalResult(next, safeReceipt, previous.state());
    }
    entry.view = next;
    eventCounts.compute(orchestrationId, (key, value) -> value + 1);
    return next;
  }

  @Override
  public synchronized List<OrchestrationView> recoverable() {
    return entries.values().stream()
        .map(entry -> entry.view)
        .filter(
            view ->
                !view.state().provenTerminal()
                    && view.state() != State.HELD
                    && view.mode() != AccountBuilderOrchestrationModels.Mode.SHADOW)
        .toList();
  }

  @Override
  public synchronized Optional<TerminalResult> terminalResult(String orchestrationId) {
    require(orchestrationId);
    return Optional.ofNullable(terminalResults.get(orchestrationId));
  }

  private void markAuthorityConsumed(Entry entry) {
    if (entry.view.authorityConsumed()) {
      return;
    }
    OrchestrationView previous = entry.view;
    entry.view =
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
            now());
    eventCounts.compute(entry.view.orchestrationId(), (key, value) -> value + 1);
  }

  private void validateAuthority(
      OrchestrationRequest request,
      GameplayContracts.HumanAuthority authority,
      String envelopeDigest) {
    if (authority == null
        || !GameplayContracts.VERSION.equals(authority.contractVersion())
        || !authority.oneUse()
        || !"HUMAN".equals(authority.actorType())
        || !validId(authority.authorityId())
        || !Objects.equals(authority.authorityId(), request.envelope().authorityId())
        || !validDigest(envelopeDigest)
        || !Objects.equals(authority.envelopeDigest(), envelopeDigest)
        || !validDigest(authority.scopeDigest())
        || authority.approvedBy() == null
        || !authority.approvedBy().matches(ID_PATTERN)
        || authority.approvedAt() == null
        || authority.expiresAt() == null
        || authority.approvedAt().isAfter(clock.instant())
        || !authority.expiresAt().isAfter(authority.approvedAt())
        || authority.signature() == null
        || !authority.signature().matches(SIGNATURE_PATTERN)) {
      throw failure(
          FailureCode.AUTHORITY_INVALID, "The authority does not bind the request exactly");
    }
    if (!authorityVerifier.verifyBoundAuthority(authority, request.envelope())) {
      throw failure(FailureCode.AUTHORITY_INVALID, "Human authority signature or scope is invalid");
    }
  }

  private void ensureSameAuthority(
      AuthorityConsumption existing,
      GameplayContracts.HumanAuthority authority,
      String envelopeDigest) {
    if (!Objects.equals(existing.envelopeDigest(), envelopeDigest)
        || !Objects.equals(existing.approvedScopeDigest(), authority.scopeDigest())
        || !Objects.equals(existing.operatorId(), authority.approvedBy())
        || !Objects.equals(existing.expiresAt(), authority.expiresAt())) {
      throw failure(FailureCode.AUTHORITY_INVALID, "The persisted authority binding is different");
    }
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
  }

  private GatewayReceipt safeReceipt(GatewayReceipt receipt) {
    List<String> evidenceRefs = receipt.evidenceRefs().stream().map(this::opaqueDigest).toList();
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
        receipt.at() == null ? null : truncate(receipt.at()),
        receipt.receiptId() == null ? null : opaqueDigest(receipt.receiptId()),
        receipt.killSwitch());
  }

  private String opaqueDigest(String value) {
    if (value == null || value.isBlank()) {
      throw failure(FailureCode.BOUNDARY_VIOLATION, "Evidence references must be non-empty");
    }
    return validDigest(value) ? value : digest(value);
  }

  private String receiptDigest(GatewayReceipt receipt) {
    return digest(receipt.toString());
  }

  private String receiptIdentity(GatewayReceipt receipt, String receiptDigest) {
    return receipt.receiptId() == null || receipt.receiptId().isBlank()
        ? "canonical:" + receiptDigest
        : "remote:" + digest(receipt.receiptId());
  }

  private Lease normalizedLease(Lease lease) {
    return new Lease(
        lease.reservationId(),
        lease.generation(),
        lease.fence(),
        lease.fenceTokenDigest(),
        truncate(lease.expiresAt()));
  }

  private void persistTerminalResult(
      OrchestrationView view, GatewayReceipt receipt, State previousState) {
    boolean reconciled = previousState == State.NEEDS_RECONCILIATION;
    String evidenceDigest =
        receipt == null || receipt.evidenceRefs().isEmpty()
            ? null
            : digest(receipt.evidenceRefs().toString());
    TerminalResult existing = terminalResults.get(view.orchestrationId());
    if (existing != null) {
      if (existing.outcome() != view.state()
          || existing.stableCode() != view.failureCode()
          || existing.retryAllowed() != view.retryAllowed()
          || existing.reconciled() != reconciled
          || !Objects.equals(existing.evidenceDigest(), evidenceDigest)
          || !Objects.equals(existing.terminalAt(), view.updatedAt())) {
        throw failure(
            FailureCode.IDEMPOTENCY_CONFLICT,
            "Terminal orchestration evidence is immutable and cannot be rewritten");
      }
      return;
    }
    terminalResults.put(
        view.orchestrationId(),
        new TerminalResult(
            view.orchestrationId(),
            view.state(),
            view.failureCode(),
            view.retryAllowed(),
            reconciled,
            evidenceDigest,
            view.updatedAt(),
            0));
  }

  private String terminalEvidenceDigest(
      List<GatewayReceipt> receipts, State outcome, FailureCode failureCode) {
    for (int index = receipts.size() - 1; index >= 0; index--) {
      GatewayReceipt receipt = receipts.get(index);
      if (receipt.state() == outcome && receipt.failureCode() == failureCode) {
        return receipt.evidenceRefs().isEmpty() ? null : digest(receipt.evidenceRefs().toString());
      }
    }
    return null;
  }

  private Entry require(String orchestrationId) {
    Entry entry = entries.get(orchestrationId);
    if (entry == null) {
      throw failure(FailureCode.INVALID_CONTRACT, "Orchestration was not found");
    }
    return entry;
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
    String digest = receiptDigest(safe);
    String identity = receiptIdentity(safe, digest);
    return persisted.stream()
        .anyMatch(
            existing ->
                identity.equals(receiptIdentity(existing, receiptDigest(existing)))
                    && digest.equals(receiptDigest(existing)));
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

  private Instant now() {
    return truncate(clock.instant());
  }

  private Instant truncate(Instant value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
  }

  private String digest(String value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to create orchestration digest", exception);
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

  private static final class Entry {
    private final OrchestrationRequest request;
    private OrchestrationView view;

    private Entry(OrchestrationRequest request, OrchestrationView view) {
      this.request = request;
      this.view = view;
    }
  }
}
