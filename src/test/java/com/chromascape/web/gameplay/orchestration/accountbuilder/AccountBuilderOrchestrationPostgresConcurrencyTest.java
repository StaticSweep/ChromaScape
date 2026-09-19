package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.AuthorityConsumption;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Committed, independent-transaction PostgreSQL races for SC-1431 identity and evidence. */
@SpringBootTest(
    classes = AccountBuilderOrchestrationPostgresConcurrencyTest.TestBootstrap.class,
    properties = {
      "spring.flyway.target=49",
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1431-read-token",
      "stabilization.auth.write-token=sc1431-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles({"test", "sc1431-postgres-concurrency"})
class AccountBuilderOrchestrationPostgresConcurrencyTest {
  private static final int WORKER_TIMEOUT_SECONDS = 20;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private JpaAccountBuilderOrchestrationStore store;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    // Hibernate is validate-only: Flyway alone must produce every SC-1431 table, column and
    // constraint through the actual repository migration head.
    IsolatedPostgresTestDatabase.registerFlywayValidateOnly(registry);
  }

  @AfterEach
  void assertNoOrphanedCorrelationRows() {
    Integer orphaned =
        jdbcTemplate.queryForObject(
            "select count(*) from ab_gateway_correlation c left join ab_orchestration o"
                + " on o.orchestration_id=c.orchestration_id where o.orchestration_id is null",
            Integer.class);
    assertThat(orphaned).isZero();
  }

  @Test
  void eightIdenticalClaimsProduceOneCommittedOwnerAndCreationEvent() throws Exception {
    OrchestrationRequest request = AccountBuilderPostgresTestFixtures.request("pg-identical");
    CyclicBarrier barrier = new CyclicBarrier(8);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<Object>> futures =
          IntStream.range(0, 8)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () -> {
                            barrier.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            return (Object)
                                store.claim(
                                    request,
                                    request.requestFingerprint(),
                                    AccountBuilderPostgresTestFixtures.envelopeDigest(request),
                                    AccountBuilderPostgresTestFixtures.budget(request));
                          }))
              .toList();
      List<Object> results = awaitAll(futures);

      assertThat(results.stream().filter(AccountBuilderOrchestrationStore.Claim.class::isInstance))
          .hasSize(8);
      assertThat(
              results.stream()
                  .map(AccountBuilderOrchestrationStore.Claim.class::cast)
                  .filter(AccountBuilderOrchestrationStore.Claim::created))
          .hasSize(1);
      assertThat(count("ab_orchestration", request.orchestrationId())).isOne();
      assertThat(count("ab_snapshot_binding", request.orchestrationId())).isOne();
      assertThat(count("ab_gateway_correlation", request.orchestrationId())).isOne();
      assertThat(count("ab_orchestration_event", request.orchestrationId())).isOne();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void requestIdConflictLeavesOnlyTheCommittedOwner() throws Exception {
    OrchestrationRequest owner =
        AccountBuilderPostgresTestFixtures.request(
            "pg-request-owner",
            AccountBuilderOrchestrationModels.Mode.LIVE_TASK,
            "orchestration-pg-request-owner",
            "request-pg-shared",
            "idempotency-pg-owner",
            "authority-pg-owner");
    OrchestrationRequest contender =
        AccountBuilderPostgresTestFixtures.request(
            "pg-request-contender",
            AccountBuilderOrchestrationModels.Mode.LIVE_TASK,
            "orchestration-pg-request-contender",
            "request-pg-shared",
            "idempotency-pg-contender",
            "authority-pg-contender");
    List<Object> results = raceClaims(owner, contender);

    assertThat(results.stream().filter(AccountBuilderOrchestrationStore.Claim.class::isInstance))
        .hasSize(1);
    assertThat(results.stream().filter(value -> value == FailureCode.IDEMPOTENCY_CONFLICT))
        .hasSize(1);
    assertIdentityRows(owner, contender, 1);
  }

  @Test
  void idempotencyKeyConflictLeavesOnlyTheCommittedOwner() throws Exception {
    OrchestrationRequest owner =
        AccountBuilderPostgresTestFixtures.request(
            "pg-idempotency-owner",
            AccountBuilderOrchestrationModels.Mode.LIVE_TASK,
            "orchestration-pg-idempotency-owner",
            "request-pg-idempotency-owner",
            "idempotency-pg-shared",
            "authority-pg-idempotency-owner");
    OrchestrationRequest contender =
        AccountBuilderPostgresTestFixtures.request(
            "pg-idempotency-contender",
            AccountBuilderOrchestrationModels.Mode.LIVE_TASK,
            "orchestration-pg-idempotency-contender",
            "request-pg-idempotency-contender",
            "idempotency-pg-shared",
            "authority-pg-idempotency-contender");
    List<Object> results = raceClaims(owner, contender);

    assertThat(results.stream().filter(AccountBuilderOrchestrationStore.Claim.class::isInstance))
        .hasSize(1);
    assertThat(results.stream().filter(value -> value == FailureCode.IDEMPOTENCY_CONFLICT))
        .hasSize(1);
    assertIdentityRows(owner, contender, 1);
  }

  @Test
  void sameAuthorityRaceHasOneCommittedOwnerAndOneClosedFailure() throws Exception {
    String authorityId = "authority-pg-shared";
    OrchestrationRequest first =
        AccountBuilderPostgresTestFixtures.request(
            "pg-authority-first",
            AccountBuilderOrchestrationModels.Mode.LIVE_TASK,
            "orchestration-pg-authority-first",
            "request-pg-authority-first",
            "idempotency-pg-authority-first",
            authorityId);
    OrchestrationRequest second =
        AccountBuilderPostgresTestFixtures.request(
            "pg-authority-second",
            AccountBuilderOrchestrationModels.Mode.LIVE_TASK,
            "orchestration-pg-authority-second",
            "request-pg-authority-second",
            "idempotency-pg-authority-second",
            authorityId);
    claim(first);
    claim(second);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Object>> futures =
          List.of(first, second).stream()
              .map(
                  request ->
                      executor.submit(
                          () -> {
                            barrier.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            try {
                              return store.consumeAuthority(
                                  request.orchestrationId(),
                                  request.authority(),
                                  AccountBuilderPostgresTestFixtures.envelopeDigest(request),
                                  request);
                            } catch (AccountBuilderOrchestrationException exception) {
                              return exception.failureCode();
                            }
                          }))
              .toList();
      List<Object> results = awaitAll(futures);

      assertThat(results.stream().filter(AuthorityConsumption.class::isInstance)).hasSize(1);
      assertThat(results.stream().filter(value -> value == FailureCode.AUTHORITY_ALREADY_USED))
          .hasSize(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from ab_authority_consumption where authority_id=?",
                  Integer.class,
                  authorityId))
          .isOne();
      assertThat(
              count("ab_orchestration_event", first.orchestrationId())
                  + count("ab_orchestration_event", second.orchestrationId()))
          .isEqualTo(3);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void identicalTransitionRaceHasMonotonicEventAndReceiptSequences() throws Exception {
    OrchestrationRequest request = AccountBuilderPostgresTestFixtures.request("pg-transition");
    claim(request);
    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
    store.consumeAuthority(
        request.orchestrationId(),
        request.authority(),
        AccountBuilderPostgresTestFixtures.envelopeDigest(request),
        request);
    store.transition(
        request.orchestrationId(), State.AUTHORIZED, FailureCode.NONE, null, null, false, false);
    Lease lease = AccountBuilderPostgresTestFixtures.lease(request, "pg-transition");
    GatewayReceipt receipt =
        AccountBuilderPostgresTestFixtures.receipt(
            request, "RESERVATION", State.RESERVED, FailureCode.NONE, lease, false, false);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Object>> futures =
          IntStream.range(0, 2)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () -> {
                            barrier.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            try {
                              return store.transition(
                                  request.orchestrationId(),
                                  State.RESERVED,
                                  FailureCode.NONE,
                                  lease,
                                  receipt,
                                  false,
                                  false);
                            } catch (AccountBuilderOrchestrationException exception) {
                              return exception.failureCode();
                            }
                          }))
              .toList();
      List<Object> results = awaitAll(futures);

      assertThat(
              results.stream()
                  .filter(
                      value ->
                          value instanceof AccountBuilderOrchestrationModels.OrchestrationView))
          .hasSize(2);
      assertThat(count("ab_orchestration_receipt", request.orchestrationId())).isOne();
      assertThat(count("ab_orchestration_event", request.orchestrationId())).isEqualTo(5);
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from ab_orchestration_event where orchestration_id=?"
                      + " and sequence_number between 1 and 5",
                  Integer.class,
                  request.orchestrationId()))
          .isEqualTo(5);
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(distinct sequence_number) from ab_orchestration_receipt"
                      + " where orchestration_id=? and sequence_number=1",
                  Integer.class,
                  request.orchestrationId()))
          .isOne();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void twoDifferentAuthoritiesRacingOneOrchestrationFailClosed() throws Exception {
    var authorityA =
        AccountBuilderPostgresTestFixtures.authorityFor(
            AccountBuilderPostgresTestFixtures.request("pg-two-authorities").envelope(),
            "authority-pg-two-authorities",
            "operator-a",
            AccountBuilderPostgresTestFixtures.digest("scope-a"),
            AccountBuilderPostgresTestFixtures.NOW.minusSeconds(30),
            AccountBuilderPostgresTestFixtures.NOW.plusSeconds(240),
            "hmac-sha256:" + "0".repeat(64));
    OrchestrationRequest request =
        AccountBuilderPostgresTestFixtures.withAuthority(
            AccountBuilderPostgresTestFixtures.request("pg-two-authorities"), authorityA);
    claim(request);
    var authorityB =
        AccountBuilderPostgresTestFixtures.authorityFor(
            request.envelope(),
            request.envelope().authorityId(),
            "operator-b",
            AccountBuilderPostgresTestFixtures.digest("scope-b"),
            AccountBuilderPostgresTestFixtures.NOW.minusSeconds(30),
            AccountBuilderPostgresTestFixtures.NOW.plusSeconds(240),
            "hmac-sha256:" + "0".repeat(64));
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Object>> futures =
          List.of(authorityA, authorityB).stream()
              .map(
                  authority ->
                      executor.submit(
                          () -> {
                            barrier.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            try {
                              return store.consumeAuthority(
                                  request.orchestrationId(),
                                  authority,
                                  AccountBuilderPostgresTestFixtures.envelopeDigest(request),
                                  request);
                            } catch (AccountBuilderOrchestrationException exception) {
                              return exception.failureCode();
                            }
                          }))
              .toList();
      List<Object> results = awaitAll(futures);

      assertThat(results.stream().filter(AuthorityConsumption.class::isInstance)).hasSize(1);
      assertThat(results.stream().filter(value -> value == FailureCode.AUTHORITY_INVALID))
          .hasSize(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from ab_authority_consumption where orchestration_id=?",
                  Integer.class,
                  request.orchestrationId()))
          .isOne();
    } finally {
      executor.shutdownNow();
    }
  }

  private List<Object> raceClaims(OrchestrationRequest first, OrchestrationRequest second)
      throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Object>> futures =
          List.of(first, second).stream()
              .map(
                  request ->
                      executor.submit(
                          () -> {
                            barrier.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            try {
                              return store.claim(
                                  request,
                                  request.requestFingerprint(),
                                  AccountBuilderPostgresTestFixtures.envelopeDigest(request),
                                  AccountBuilderPostgresTestFixtures.budget(request));
                            } catch (AccountBuilderOrchestrationException exception) {
                              return exception.failureCode();
                            }
                          }))
              .toList();
      return awaitAll(futures);
    } finally {
      executor.shutdownNow();
    }
  }

  private void claim(OrchestrationRequest request) {
    assertThat(
            store
                .claim(
                    request,
                    request.requestFingerprint(),
                    AccountBuilderPostgresTestFixtures.envelopeDigest(request),
                    AccountBuilderPostgresTestFixtures.budget(request))
                .created())
        .isTrue();
  }

  private void assertIdentityRows(
      OrchestrationRequest first, OrchestrationRequest second, int expected) {
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ab_orchestration where orchestration_id in (?, ?)",
                Integer.class,
                first.orchestrationId(),
                second.orchestrationId()))
        .isEqualTo(expected);
    assertThat(
            count("ab_snapshot_binding", first.orchestrationId())
                + count("ab_snapshot_binding", second.orchestrationId()))
        .isEqualTo(expected);
    assertThat(
            count("ab_gateway_correlation", first.orchestrationId())
                + count("ab_gateway_correlation", second.orchestrationId()))
        .isEqualTo(expected);
    assertThat(
            count("ab_orchestration_event", first.orchestrationId())
                + count("ab_orchestration_event", second.orchestrationId()))
        .isEqualTo(expected);
  }

  private int count(String table, String orchestrationId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where orchestration_id=?",
        Integer.class,
        orchestrationId);
  }

  private List<Object> awaitAll(List<Future<Object>> futures)
      throws InterruptedException, ExecutionException, TimeoutException {
    return futures.stream()
        .map(
            future -> {
              try {
                return future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
              } catch (ExecutionException | TimeoutException exception) {
                throw new AssertionError(exception);
              }
            })
        .toList();
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("sc1431-postgres-concurrency")
  @Import(TestApplication.class)
  static class TestBootstrap {}

  @TestConfiguration(proxyBeanMethods = false)
  @Profile("sc1431-postgres-concurrency")
  @EnableAutoConfiguration
  @EntityScan(basePackages = "com.chromascape.web.gameplay.orchestration.accountbuilder")
  @Import(JsonConfiguration.class)
  static class TestApplication {
    @Bean
    AccountBuilderAuthorityVerifier authorityVerifier() {
      // The cryptographic verifier has its dedicated negative suite. This explicit deterministic
      // test verifier lets this class isolate committed one-use ownership races across two
      // independently bound envelopes.
      return (authority, envelope) -> true;
    }

    @Bean
    JpaAccountBuilderOrchestrationStore orchestrationStore(
        EntityManager entityManager,
        ObjectMapper objectMapper,
        com.chromascape.web.gameplay.contract.CanonicalGameplayJson canonicalJson,
        AccountBuilderAuthorityVerifier authorityVerifier) {
      return new JpaAccountBuilderOrchestrationStore(
          entityManager,
          objectMapper,
          canonicalJson,
          AccountBuilderPostgresTestFixtures.CLOCK,
          authorityVerifier);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Profile("sc1431-postgres-concurrency")
  static class JsonConfiguration {
    @Bean
    ObjectMapper objectMapper() {
      return AccountBuilderPostgresTestFixtures.OBJECT_MAPPER;
    }

    @Bean
    com.chromascape.web.gameplay.contract.CanonicalGameplayJson canonicalGameplayJson(
        ObjectMapper objectMapper) {
      return new com.chromascape.web.gameplay.contract.CanonicalGameplayJson(objectMapper);
    }
  }
}
