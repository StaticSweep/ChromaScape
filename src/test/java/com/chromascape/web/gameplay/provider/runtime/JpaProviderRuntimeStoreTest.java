package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.ProviderUsage;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** PostgreSQL proof for SC-1430 locking, session aggregation and durable tool-call charging. */
@SpringBootTest(
    classes = JpaProviderRuntimeStoreTest.JpaTestApplication.class,
    properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
class JpaProviderRuntimeStoreTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String DEFAULT_JDBC_URL =
      "jdbc:postgresql://127.0.0.1:55450/chromascape_sc1430_review?sslmode=disable";

  @DynamicPropertySource
  static void isolatedDatasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> System.getProperty("chromascape.sc1430.jdbc-url", DEFAULT_JDBC_URL));
    registry.add(
        "spring.datasource.username",
        () -> System.getProperty("chromascape.sc1430.jdbc-username", "chromascape"));
    registry.add(
        "spring.datasource.password",
        () -> System.getProperty("chromascape.sc1430.jdbc-password", "chromascape123"));
  }

  @Autowired private JpaProviderRuntimeStore store;
  @Autowired private CanonicalGameplayJson canonicalJson;

  @Test
  void parallelFinishesSerializeAndAggregateAllTurns() throws Exception {
    SessionRequest session = session("parallel-" + System.nanoTime());
    store.createSession(session);
    TurnRequest first = turn(session, "first");
    TurnRequest second = turn(session, "second");
    reserveAndStream(session, first);
    reserveAndStream(session, second);

    runConcurrently(
        () -> store.finish(first.turnId(), TurnState.ABSTAINED, FailureCode.NONE, null, null, null),
        () ->
            store.finish(second.turnId(), TurnState.ABSTAINED, FailureCode.NONE, null, null, null));

    assertEquals(
        SessionState.COMPLETED, store.findSession(session.sessionId()).orElseThrow().state());
  }

  @Test
  void activeAndReconciliationStatesTakePrecedence() {
    SessionRequest session = session("precedence-" + System.nanoTime());
    store.createSession(session);
    TurnRequest first = turn(session, "complete");
    TurnRequest second = turn(session, "active");
    reserveAndStream(session, first);
    reserveAndStream(session, second);

    store.finish(first.turnId(), TurnState.ABSTAINED, FailureCode.NONE, null, null, null);
    assertEquals(SessionState.ACTIVE, store.findSession(session.sessionId()).orElseThrow().state());

    store.markNeedsReconciliation(second.turnId(), FailureCode.FIRST_EVENT_TIMEOUT);
    assertEquals(
        SessionState.NEEDS_RECONCILIATION,
        store.findSession(session.sessionId()).orElseThrow().state());
  }

  @Test
  void toolCallsAreDurableIdempotentAndChargedBeforeExecution() {
    SessionRequest session = session("tools-" + System.nanoTime());
    store.createSession(session);
    TurnRequest turn = turn(session, "tool");
    reserveAndStream(session, turn);

    ProviderRuntimeStore.ToolCallReservation first =
        store.reserveToolCall(turn.turnId(), "tool-call", DecisionToolFacade.OBSERVATION, DIGEST);
    assertTrue(first.created());
    assertEquals(1, store.usage(session.sessionId()).toolCalls());
    assertEquals(1, store.toolCallCount(turn.turnId()));
    store.finishToolCall("tool-call", DIGEST, true);

    ProviderRuntimeStore.ToolCallReservation retry =
        store.reserveToolCall(turn.turnId(), "tool-call", DecisionToolFacade.OBSERVATION, DIGEST);
    assertFalse(retry.created());
    assertTrue(retry.completed());
    assertEquals(DIGEST, retry.resultDigest());
    assertEquals(1, store.usage(session.sessionId()).toolCalls());
    assertEquals(1, store.toolCallCount(turn.turnId()));
    assertThrows(
        ProviderRuntimeException.class,
        () ->
            store.reserveToolCall(
                turn.turnId(),
                "tool-call",
                DecisionToolFacade.OBSERVATION,
                "sha256:" + "b".repeat(64)));
    store.finish(turn.turnId(), TurnState.ABSTAINED, FailureCode.NONE, null, null, null);
    assertThrows(
        ProviderRuntimeException.class,
        () ->
            store.reserveToolCall(
                turn.turnId(), "after-terminal", DecisionToolFacade.OBSERVATION, DIGEST));
  }

  @Test
  void concurrentReservationsCannotOverspendSessionBudgets() throws Exception {
    SessionRequest session =
        session(
            "concurrent-budget-" + System.nanoTime(),
            new BudgetLimits(10, 10, 1, 1, 1, 60_000, 60_000));
    store.createSession(session);
    TurnRequest first = turn(session, "first", 10, 10);
    TurnRequest second = turn(session, "second", 10, 10);

    runConcurrently(
        () -> store.reserveTurn(session.sessionId(), first),
        () -> store.reserveTurn(session.sessionId(), second));

    List<TurnState> states =
        store.turnsForSession(session.sessionId()).stream().map(value -> value.state()).toList();
    assertEquals(1, states.stream().filter(state -> state == TurnState.RESERVED).count());
    assertEquals(1, states.stream().filter(state -> state == TurnState.BUDGET_EXHAUSTED).count());
    assertEquals(1, store.usage(session.sessionId()).providerCalls());
    assertEquals(1, store.usage(session.sessionId()).turns());
    assertEquals(10, store.usage(session.sessionId()).inputTokens());
    assertEquals(10, store.usage(session.sessionId()).outputTokens());
  }

  @Test
  void providerReportedToolCallsMustHaveDurableReservationsAndAreNotDoubleCharged() {
    SessionRequest session = session("reported-tools-" + System.nanoTime());
    store.createSession(session);
    TurnRequest missing = turn(session, "missing");
    reserveAndStream(session, missing);

    var rejected =
        store.finish(
            missing.turnId(),
            TurnState.ABSTAINED,
            FailureCode.NONE,
            null,
            new ProviderUsage(10L, 10L, 1, 10, true),
            null);
    assertEquals(TurnState.BUDGET_EXHAUSTED, rejected.state());
    assertEquals(0, store.usage(session.sessionId()).toolCalls());

    SessionRequest reservedSession = session("reserved-tools-" + System.nanoTime());
    store.createSession(reservedSession);
    TurnRequest reserved = turn(reservedSession, "reserved");
    reserveAndStream(reservedSession, reserved);
    store.reserveToolCall(
        reserved.turnId(), "reserved-tool", DecisionToolFacade.OBSERVATION, DIGEST);
    store.finishToolCall("reserved-tool", DIGEST, true);
    var accepted =
        store.finish(
            reserved.turnId(),
            TurnState.ABSTAINED,
            FailureCode.NONE,
            null,
            new ProviderUsage(10L, 10L, 1, 10, true),
            null);
    assertEquals(TurnState.ABSTAINED, accepted.state());
    assertEquals(1, store.usage(reservedSession.sessionId()).toolCalls());
  }

  @Test
  void exactTokenAndWallTimeBoundariesAreAcceptedAndExcessIsTerminal() {
    SessionRequest exactSession =
        session(
            "exact-budget-" + System.nanoTime(), new BudgetLimits(10, 10, 1, 0, 1, 10_000, 10_000));
    store.createSession(exactSession);
    TurnRequest exact = turn(exactSession, "exact", 10, 10, 9_000);
    reserveAndStream(exactSession, exact);
    var accepted =
        store.finish(
            exact.turnId(),
            TurnState.ABSTAINED,
            FailureCode.NONE,
            null,
            new ProviderUsage(10L, 10L, 0, 10_000, true),
            null);
    assertEquals(TurnState.ABSTAINED, accepted.state());
    assertEquals(10_000, store.usage(exactSession.sessionId()).elapsedMillis());

    SessionRequest excessSession =
        session(
            "excess-budget-" + System.nanoTime(),
            new BudgetLimits(10, 10, 1, 0, 1, 10_000, 10_000));
    store.createSession(excessSession);
    TurnRequest excess = turn(excessSession, "excess", 9, 9, 9_000);
    reserveAndStream(excessSession, excess);
    var rejected =
        store.finish(
            excess.turnId(),
            TurnState.ABSTAINED,
            FailureCode.NONE,
            null,
            new ProviderUsage(11L, 9L, 0, 10_001, true),
            null);
    assertEquals(TurnState.BUDGET_EXHAUSTED, rejected.state());
    assertEquals(FailureCode.BUDGET_EXHAUSTED, rejected.failureCode());
  }

  @Test
  void failedToolCallRetryIsImmutableAndDoesNotExecuteAgain() {
    SessionRequest session = session("failed-tool-" + System.nanoTime());
    store.createSession(session);
    TurnRequest turn = turn(session, "failed");
    reserveAndStream(session, turn);
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    DurableProviderToolGateway gateway =
        new DurableProviderToolGateway(
            store, new DecisionToolFacade(canonicalJson), canonicalJson, properties);

    ProviderRuntimeException first =
        assertThrows(
            ProviderRuntimeException.class,
            () -> gateway.bind(session, turn).invoke("failed-tool", "mutate_game", Map.of()));
    assertEquals(FailureCode.TOOL_NOT_ALLOWED.name(), first.failureCode());
    ProviderRuntimeException retry =
        assertThrows(
            ProviderRuntimeException.class,
            () -> gateway.bind(session, turn).invoke("failed-tool", "mutate_game", Map.of()));
    assertEquals(FailureCode.IDEMPOTENCY_CONFLICT.name(), retry.failureCode());
    assertEquals(1, store.usage(session.sessionId()).toolCalls());
    assertEquals(1, store.toolCallCount(turn.turnId()));
  }

  private void reserveAndStream(SessionRequest session, TurnRequest turn) {
    store.reserveTurn(session.sessionId(), turn);
    store.markSubmitted(turn.turnId(), "provider-session", "provider-turn", List.of());
    store.markStreaming(turn.turnId());
  }

  private void runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> one = executor.submit(await(start, first));
      Future<?> two = executor.submit(await(start, second));
      start.countDown();
      one.get();
      two.get();
    } finally {
      executor.shutdownNow();
    }
  }

  private Callable<Object> await(CountDownLatch start, Callable<?> action) {
    return () -> {
      start.await();
      action.call();
      return null;
    };
  }

  private SessionRequest session(String id) {
    return session(id, new BudgetLimits(10_000, 10_000, 4, 2, 4, 60_000, 240_000));
  }

  private SessionRequest session(String id, BudgetLimits budgets) {
    return new SessionRequest(
        id,
        Provider.CLAUDE,
        "claude-test",
        "task-test",
        "run-test",
        "recording-test",
        DIGEST,
        DIGEST,
        DIGEST,
        DIGEST,
        List.of(DIGEST),
        Instant.now().plusSeconds(300),
        budgets,
        true);
  }

  private TurnRequest turn(SessionRequest session, String suffix) {
    return turn(session, suffix, 10, 10);
  }

  private TurnRequest turn(
      SessionRequest session, String suffix, long estimatedInput, long estimatedOutput) {
    return turn(session, suffix, estimatedInput, estimatedOutput, 30_000);
  }

  private TurnRequest turn(
      SessionRequest session,
      String suffix,
      long estimatedInput,
      long estimatedOutput,
      long deadlineMillis) {
    return new TurnRequest(
        session.sessionId() + "-turn-" + suffix,
        session.sessionId() + "-request-" + suffix,
        session.sessionId() + "-idempotency-" + suffix,
        DIGEST,
        JsonNodeFactory.instance.objectNode(),
        DIGEST,
        JsonNodeFactory.instance.arrayNode(),
        DIGEST,
        JsonNodeFactory.instance.objectNode(),
        "source-test",
        estimatedInput,
        estimatedOutput,
        Instant.now().plusMillis(deadlineMillis),
        true);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackages = "com.chromascape.web.gameplay.provider.runtime")
  @Import({JpaProviderRuntimeStore.class, JsonConfiguration.class})
  static class JpaTestApplication {}

  @TestConfiguration(proxyBeanMethods = false)
  static class JsonConfiguration {
    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder()
          .findAndAddModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();
    }

    @Bean
    CanonicalGameplayJson canonicalGameplayJson(ObjectMapper objectMapper) {
      return new CanonicalGameplayJson(objectMapper);
    }
  }
}
