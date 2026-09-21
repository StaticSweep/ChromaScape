package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Typed SC-1431 budget model parsed from the frozen SC-1429 contract budgets member.
 *
 * <p>The frozen SC-1429 schema bounds {@code maxActions} to {@code [1, 20]}, {@code maxAttempts} to
 * {@code [1, 3]} and {@code maxElapsedMs} to {@code [1000, 1800000]}. The limits are immutable once
 * persisted; only the used counters advance, and only through atomic conditional updates in the
 * orchestration stores. A timeout after a possible delivery never releases capacity.
 */
public final class AccountBuilderBudgetPolicy {
  private AccountBuilderBudgetPolicy() {}

  /**
   * Immutable budget limits plus the durable usage counters. Limits come from the frozen SC-1429
   * contract; the counters live only in the orchestration store.
   */
  public record OrchestrationBudget(
      int maxActions,
      int maxAttempts,
      long maxElapsedMs,
      int actionsUsed,
      int attemptsUsed,
      Instant startedAt,
      Instant expiresAt) {

    public static final int MIN_ACTIONS = 1;
    public static final int MAX_ACTIONS = 20;
    public static final int MIN_ATTEMPTS = 1;
    public static final int MAX_ATTEMPTS = 3;
    public static final long MIN_ELAPSED_MS = 1_000L;
    public static final long MAX_ELAPSED_MS = 1_800_000L;

    public OrchestrationBudget {
      if (maxActions < MIN_ACTIONS || maxActions > MAX_ACTIONS) {
        throw new IllegalArgumentException("maxActions must be between 1 and 20");
      }
      if (maxAttempts < MIN_ATTEMPTS || maxAttempts > MAX_ATTEMPTS) {
        throw new IllegalArgumentException("maxAttempts must be between 1 and 3");
      }
      if (maxElapsedMs < MIN_ELAPSED_MS || maxElapsedMs > MAX_ELAPSED_MS) {
        throw new IllegalArgumentException("maxElapsedMs must be between 1000 and 1800000");
      }
      if (actionsUsed < 0 || actionsUsed > maxActions) {
        throw new IllegalArgumentException("actionsUsed must be between 0 and maxActions");
      }
      if (attemptsUsed < 0 || attemptsUsed > maxAttempts) {
        throw new IllegalArgumentException("attemptsUsed must be between 0 and maxAttempts");
      }
      if (startedAt == null || expiresAt == null || !expiresAt.isAfter(startedAt)) {
        throw new IllegalArgumentException("expiresAt must be after startedAt");
      }
    }

    /** True when no semantic action capacity remains. */
    public boolean actionsExhausted() {
      return actionsUsed >= maxActions;
    }

    /** True when no external attempt capacity remains. */
    public boolean attemptsExhausted() {
      return attemptsUsed >= maxAttempts;
    }

    /** True when the budget horizon is fully consumed. */
    public boolean expiredAt(Instant now) {
      return !now.isBefore(expiresAt);
    }

    /** True when action, attempt and time capacity all remain. */
    public boolean hasCapacityAt(Instant now) {
      return !actionsExhausted() && !attemptsExhausted() && !expiredAt(now);
    }

    /** Returns a copy advanced by one semantic action, rejecting overflow. */
    public OrchestrationBudget withActionUsed() {
      if (actionsExhausted()) {
        throw new IllegalStateException("Action budget is already exhausted");
      }
      return new OrchestrationBudget(
          maxActions,
          maxAttempts,
          maxElapsedMs,
          actionsUsed + 1,
          attemptsUsed,
          startedAt,
          expiresAt);
    }

    /** Returns a copy advanced by one external attempt, rejecting overflow. */
    public OrchestrationBudget withAttemptUsed() {
      if (attemptsExhausted()) {
        throw new IllegalStateException("Attempt budget is already exhausted");
      }
      return new OrchestrationBudget(
          maxActions,
          maxAttempts,
          maxElapsedMs,
          actionsUsed,
          attemptsUsed + 1,
          startedAt,
          expiresAt);
    }

    /**
     * Parses the immutable limits from the frozen SC-1429 envelope budgets member and derives the
     * expiry as the earlier of the envelope deadline and {@code startedAt + maxElapsedMs}. The
     * frozen resource is never modified; a limit outside the schema bounds fails closed here.
     */
    public static OrchestrationBudget fromEnvelope(
        ExecutionEnvelope envelope, CanonicalGameplayJson canonicalJson, Clock clock) {
      Objects.requireNonNull(envelope, "envelope");
      Objects.requireNonNull(canonicalJson, "canonicalJson");
      JsonNode budgets = envelope.budgets();
      if (budgets == null
          || !budgets.path("maxActions").isInt()
          || !budgets.path("maxAttempts").isInt()
          || !budgets.path("maxElapsedMs").isIntegralNumber()) {
        throw new IllegalArgumentException(
            "Execution envelope budgets member is missing or invalid");
      }
      int maxActions = budgets.path("maxActions").asInt();
      int maxAttempts = budgets.path("maxAttempts").asInt();
      long maxElapsedMs = budgets.path("maxElapsedMs").asLong();
      if (maxActions < MIN_ACTIONS
          || maxActions > MAX_ACTIONS
          || maxAttempts < MIN_ATTEMPTS
          || maxAttempts > MAX_ATTEMPTS
          || maxElapsedMs < MIN_ELAPSED_MS
          || maxElapsedMs > MAX_ELAPSED_MS) {
        throw new IllegalArgumentException(
            "Frozen SC-1429 budget limits are outside the contract bounds");
      }
      Instant now = clock.instant();
      Instant deadline = envelope.deadline();
      if (deadline == null) {
        throw new IllegalArgumentException("Execution envelope deadline is required");
      }
      Instant fromElapsed = now.plus(Duration.ofMillis(maxElapsedMs));
      Instant expiresAt = deadline.isBefore(fromElapsed) ? deadline : fromElapsed;
      if (!expiresAt.isAfter(now)) {
        throw new IllegalArgumentException("Budget horizon is already expired at claim time");
      }
      return new OrchestrationBudget(maxActions, maxAttempts, maxElapsedMs, 0, 0, now, expiresAt);
    }
  }
}
