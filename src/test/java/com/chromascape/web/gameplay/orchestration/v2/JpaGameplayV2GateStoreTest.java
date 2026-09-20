package com.chromascape.web.gameplay.orchestration.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2GateStore.GateConflictException;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2GateStore.GateRow;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PostgreSQL proof for the durable SC-1449 v2 gate store.
 *
 * <p>The gate's safety rests on claims the database has to make true, not on Java: an idempotency
 * key, an authority id and an account/client active slot are each usable once, a state transition
 * only applies from an expected state so racing callers cannot both win, and the canonical request
 * bytes come back byte-identical after a fresh persistence context. Each of those is asserted here
 * against a real schema built by Flyway.
 */
@SpringBootTest(
    classes = JpaGameplayV2GateStoreTest.Sc1449JpaBootstrap.class,
    properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles({"test", "sc1449-jpa"})
class JpaGameplayV2GateStoreTest {
  private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Autowired private JpaGameplayV2GateStore store;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  private static GateRow row(String suffix, byte[] bytes) {
    return new GateRow(
        "req-" + suffix,
        "idem-" + suffix,
        "fingerprint-" + suffix,
        "digest-" + suffix,
        "authority-" + suffix,
        "account-" + suffix,
        "client-" + suffix,
        1L,
        1L,
        NOW.plusSeconds(600),
        NOW.plusSeconds(900),
        V2State.RESERVED,
        null,
        false,
        bytes,
        null,
        List.of("chromascape:reserved:req-" + suffix),
        NOW,
        NOW);
  }

  private static byte[] canonicalBytes() {
    // Deliberately includes a NUL, a high byte and multi-byte UTF-8 so a lossy text round trip
    // through the BYTEA column would be visible.
    byte[] json =
        "{\"executionEnvelope\":{\"requestId\":\"req\"},\"note\":\"é中\"}"
            .getBytes(StandardCharsets.UTF_8);
    byte[] bytes = new byte[json.length + 2];
    System.arraycopy(json, 0, bytes, 0, json.length);
    bytes[json.length] = 0x00;
    bytes[json.length + 1] = (byte) 0xFF;
    return bytes;
  }

  @Test
  void canonicalBytesRoundTripExactlyThroughANewPersistenceContext() throws Exception {
    byte[] original = canonicalBytes();
    store.insertReserved(row("bytes", original));

    GateRow reloaded = store.findByRequestId("req-bytes").orElseThrow();
    assertThat(reloaded.bytes()).isEqualTo(original);
    assertThat(reloaded.envelopeDeadline()).isEqualTo(NOW.plusSeconds(600));
    assertThat(reloaded.authorityExpiresAt()).isEqualTo(NOW.plusSeconds(900));
    assertThat(reloaded.evidence()).containsExactly("chromascape:reserved:req-bytes");

    // The accessor hands out a copy, so a caller cannot mutate the persisted bytes.
    reloaded.bytes()[0] = 'X';
    assertThat(store.findByRequestId("req-bytes").orElseThrow().bytes()).isEqualTo(original);
  }

  @Test
  void aRequestIdIsUsableOnce() throws Exception {
    store.insertReserved(row("dup", canonicalBytes()));
    GateRow again =
        new GateRow(
            "req-dup",
            "idem-other",
            "fingerprint-other",
            "digest-other",
            "authority-other",
            "account-other",
            "client-other",
            2L,
            2L,
            NOW.plusSeconds(600),
            NOW.plusSeconds(900),
            V2State.RESERVED,
            null,
            false,
            canonicalBytes(),
            null,
            List.of(),
            NOW,
            NOW);

    assertThatThrownBy(() -> store.insertReserved(again))
        .isInstanceOf(GateConflictException.class)
        .extracting("kind")
        .isEqualTo(GateConflictException.Kind.REQUEST_ID);
  }

  @Test
  void anIdempotencyKeyIsUsableOnce() throws Exception {
    store.insertReserved(row("idem", canonicalBytes()));
    GateRow reused =
        new GateRow(
            "req-idem-2",
            "idem-idem",
            "fingerprint-2",
            "digest-2",
            "authority-2",
            "account-2",
            "client-2",
            1L,
            1L,
            NOW.plusSeconds(600),
            NOW.plusSeconds(900),
            V2State.RESERVED,
            null,
            false,
            canonicalBytes(),
            null,
            List.of(),
            NOW,
            NOW);

    assertThatThrownBy(() -> store.insertReserved(reused))
        .isInstanceOf(GateConflictException.class)
        .extracting("kind")
        .isEqualTo(GateConflictException.Kind.IDEMPOTENCY_KEY);
  }

  @Test
  void anAuthorityIsConsumableOnce() throws Exception {
    store.insertReserved(row("auth", canonicalBytes()));
    GateRow reused =
        new GateRow(
            "req-auth-2",
            "idem-auth-2",
            "fingerprint-2",
            "digest-2",
            "authority-auth",
            "account-2",
            "client-2",
            1L,
            1L,
            NOW.plusSeconds(600),
            NOW.plusSeconds(900),
            V2State.RESERVED,
            null,
            false,
            canonicalBytes(),
            null,
            List.of(),
            NOW,
            NOW);

    assertThatThrownBy(() -> store.insertReserved(reused))
        .isInstanceOf(GateConflictException.class)
        .extracting("kind")
        .isEqualTo(GateConflictException.Kind.AUTHORITY_ID);
  }

  @Test
  void onlyOneNonTerminalRowMayHoldAnAccountClientSlot() throws Exception {
    store.insertReserved(row("slot", canonicalBytes()));
    GateRow sameSlotHigherFence =
        new GateRow(
            "req-slot-2",
            "idem-slot-2",
            "fingerprint-2",
            "digest-2",
            "authority-slot-2",
            "account-slot",
            "client-slot",
            9L,
            9L,
            NOW.plusSeconds(600),
            NOW.plusSeconds(900),
            V2State.RESERVED,
            null,
            false,
            canonicalBytes(),
            null,
            List.of(),
            NOW,
            NOW);

    // The V40 tuple uniqueness would allow this; the V41 partial unique index must not.
    assertThatThrownBy(() -> store.insertReserved(sameSlotHigherFence))
        .isInstanceOf(GateConflictException.class)
        .extracting("kind")
        .isEqualTo(GateConflictException.Kind.SLOT);

    // Once the first row is terminal the slot is free again.
    assertThat(
            store.compareAndSet(
                "req-slot",
                Set.of(V2State.RESERVED),
                V2State.STOPPED,
                null,
                false,
                null,
                List.of("chromascape:stopped:req-slot")))
        .isTrue();
    assertThat(store.insertReserved(sameSlotHigherFence).requestId()).isEqualTo("req-slot-2");
  }

  @Test
  void compareAndSetOnlyAppliesFromAnExpectedState() throws Exception {
    store.insertReserved(row("cas", canonicalBytes()));

    assertThat(
            store.compareAndSet(
                "req-cas", Set.of(V2State.ACTIVE), V2State.CONFIRMED, null, true, null, List.of()))
        .isFalse();
    assertThat(store.findByRequestId("req-cas").orElseThrow().state()).isEqualTo(V2State.RESERVED);

    assertThat(
            store.compareAndSet(
                "req-cas",
                Set.of(V2State.RESERVED, V2State.DELIVERY_PENDING),
                V2State.STARTING,
                null,
                false,
                null,
                List.of("chromascape:starting:req-cas")))
        .isTrue();

    GateRow started = store.findByRequestId("req-cas").orElseThrow();
    assertThat(started.state()).isEqualTo(V2State.STARTING);
    assertThat(started.evidence())
        .containsExactly("chromascape:reserved:req-cas", "chromascape:starting:req-cas");
    assertThat(started.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void exactlyOneOfManyConcurrentStartClaimsWins() throws Exception {
    store.insertReserved(row("race", canonicalBytes()));
    int racers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    try {
      List<Callable<Boolean>> claims =
          IntStream.range(0, racers)
              .<Callable<Boolean>>mapToObj(
                  index ->
                      () ->
                          store.compareAndSet(
                              "req-race",
                              Set.of(V2State.RESERVED),
                              V2State.STARTING,
                              null,
                              false,
                              null,
                              List.of("chromascape:starting:" + index)))
              .toList();
      List<Future<Boolean>> results = pool.invokeAll(claims);
      long winners = 0;
      for (Future<Boolean> result : results) {
        if (Boolean.TRUE.equals(result.get())) {
          winners++;
        }
      }
      assertThat(winners).isEqualTo(1);
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }
    assertThat(store.findByRequestId("req-race").orElseThrow().state()).isEqualTo(V2State.STARTING);
  }

  @Test
  void findRecoverableReturnsOnlyInFlightRows() throws Exception {
    store.insertReserved(row("recoverable", canonicalBytes()));
    store.insertReserved(row("terminal", canonicalBytes()));
    store.compareAndSet(
        "req-terminal", Set.of(V2State.RESERVED), V2State.STOPPED, null, false, null, List.of());

    List<String> recoverable = store.findRecoverable().stream().map(GateRow::requestId).toList();
    assertThat(recoverable).contains("req-recoverable");
    assertThat(recoverable).doesNotContain("req-terminal");
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("sc1449-jpa")
  @Import(Sc1449JpaApplication.class)
  static class Sc1449JpaBootstrap {}

  @TestConfiguration(proxyBeanMethods = false)
  @Profile("sc1449-jpa")
  @EnableAutoConfiguration
  @EntityScan(basePackages = "com.chromascape.web.gameplay.orchestration.v2")
  @EnableJpaRepositories(basePackages = "com.chromascape.web.gameplay.orchestration.v2")
  static class Sc1449JpaApplication {
    @Bean
    JpaGameplayV2GateStore gateStore(
        GameplayV2GateRepository repository, EntityManager entityManager) {
      return new JpaGameplayV2GateStore(repository, entityManager, CLOCK);
    }
  }
}
