package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Exact boundary and atomic-capacity behavior of the SC-1431 lifecycle budget. */
class AccountBuilderOrchestrationBudgetTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:05:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(600);

  private static AccountBuilderBudgetPolicy.OrchestrationBudget budget(
      int maxActions, int maxAttempts, long maxElapsedMs) {
    return new AccountBuilderBudgetPolicy.OrchestrationBudget(
        maxActions, maxAttempts, maxElapsedMs, 0, 0, NOW, DEADLINE);
  }

  @Test
  void exactLowerAndUpperBoundsAreAccepted() {
    assertThat(budget(1, 1, 1_000).maxActions()).isEqualTo(1);
    assertThat(budget(20, 3, 1_800_000L).maxElapsedMs()).isEqualTo(1_800_000L);
  }

  @Test
  void oneStepOutsideEveryBoundIsRejected() {
    assertThatThrownBy(() -> budget(0, 1, 1_000)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> budget(21, 1, 1_000)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> budget(1, 0, 1_000)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> budget(1, 4, 1_000)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> budget(1, 1, 999)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> budget(1, 1, 1_800_001L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void usageCountersCanNeverExceedLimits() {
    AccountBuilderBudgetPolicy.OrchestrationBudget exhaustedActions =
        new AccountBuilderBudgetPolicy.OrchestrationBudget(1, 3, 10_000, 1, 0, NOW, DEADLINE);
    assertThatThrownBy(exhaustedActions::withActionUsed).isInstanceOf(IllegalStateException.class);
    AccountBuilderBudgetPolicy.OrchestrationBudget exhaustedAttempts =
        new AccountBuilderBudgetPolicy.OrchestrationBudget(1, 1, 10_000, 0, 1, NOW, DEADLINE);
    assertThatThrownBy(exhaustedAttempts::withAttemptUsed)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void expiryIsTheEarlierOfDeadlineAndElapsedWindow() throws Exception {
    ObjectMapper mapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    CanonicalGameplayJson canonicalJson = new CanonicalGameplayJson(mapper);
    var budgets =
        mapper
            .createObjectNode()
            .put("maxActions", 5)
            .put("maxAttempts", 2)
            .put("maxElapsedMs", 30_000);
    var node = mapper.createObjectNode();
    node.set("budgets", budgets);
    Instant shortDeadline = NOW.plusSeconds(10);
    var envelopeRecord =
        new ExecutionEnvelope(
            "sc-1429/v1",
            com.chromascape.web.gameplay.contract.GameplayContracts.Provider.CLAUDE,
            "claude-test",
            "decision-1",
            "request-1",
            "idempotency-1",
            "sha256:" + "a".repeat(64),
            java.util.List.of("sha256:" + "b".repeat(64)),
            "FISHING_TRAINING",
            node,
            node,
            "sha256:" + "c".repeat(64),
            "sha256:" + "d".repeat(64),
            "sha256:" + "e".repeat(64),
            "sha256:" + "f".repeat(64),
            "sha256:" + "0".repeat(64),
            "CONFIRMED",
            "verifier-1",
            "build-1",
            1,
            1,
            "authority-1",
            1,
            1,
            budgets,
            shortDeadline,
            "RUN");
    var parsed =
        AccountBuilderBudgetPolicy.OrchestrationBudget.fromEnvelope(
            envelopeRecord, canonicalJson, Clock.fixed(NOW, ZoneOffset.UTC));
    // The deadline (NOW + 10s) is earlier than NOW + 30s, so it wins.
    assertThat(parsed.expiresAt()).isEqualTo(shortDeadline);
    assertThatThrownBy(
            () ->
                AccountBuilderBudgetPolicy.OrchestrationBudget.fromEnvelope(
                    envelopeRecord,
                    canonicalJson,
                    Clock.fixed(NOW.plusSeconds(11), ZoneOffset.UTC)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storeRejectsExhaustedAndExpiredBudgetsBeforeGatewayAccess() {
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    store.claimBudget("orchestration-1", budget(1, 1, 10_000));
    store.consumeBudget("orchestration-1", false, true);
    assertThatThrownBy(() -> store.consumeBudget("orchestration-1", false, true))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(AccountBuilderOrchestrationModels.FailureCode.BUDGET_EXHAUSTED);

    InMemoryAccountBuilderOrchestrationStore expiredStore =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC), (a, e) -> true);
    expiredStore.claimBudget(
        "orchestration-2",
        new AccountBuilderBudgetPolicy.OrchestrationBudget(
            1, 1, 10_000, 0, 0, NOW, NOW.plusSeconds(60)));
    assertThatThrownBy(() -> expiredStore.consumeBudget("orchestration-2", false, true))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(AccountBuilderOrchestrationModels.FailureCode.DEADLINE_EXPIRED);
  }

  @Test
  void concurrentFinalAttemptConsumptionYieldsExactlyOneWinner() throws Exception {
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    store.claimBudget("orchestration-race", budget(5, 1, 600_000));
    int contenders = 8;
    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    for (int i = 0; i < contenders; i++) {
      pool.submit(
          () -> {
            start.await();
            try {
              store.consumeBudget("orchestration-race", false, true);
              winners.incrementAndGet();
            } catch (AccountBuilderOrchestrationException expected) {
              // Losers must observe BUDGET_EXHAUSTED, never a gateway mutation.
            }
            return null;
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    assertThat(winners.get()).isEqualTo(1);
    assertThat(store.findBudget("orchestration-race").orElseThrow().attemptsUsed()).isEqualTo(1);
  }
}
