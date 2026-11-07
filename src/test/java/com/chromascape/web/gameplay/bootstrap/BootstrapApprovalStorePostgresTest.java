package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore.ApprovalView;
import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore.BootstrapApprovalException;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** PostgreSQL concurrency and restart durability for SC-1498 approvals. */
@SpringBootTest(
    classes = BootstrapApprovalStorePostgresTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class BootstrapApprovalStorePostgresTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);

  @Autowired private BootstrapApprovalStore store;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void aSubMillisecondExpiryIsNormalizedInsteadOfFailingTheDatabase() {
    String scope = "sha256:" + "7".repeat(64);
    String fingerprint = "sha256:" + "8".repeat(64);
    Instant now = CLOCK.instant();
    // 500 microseconds ahead is strictly valid, and is exactly the window the old code stored at or
    // before approved_at and then lost to the strict expiry constraint.
    var claim =
        store.create(
            "session-precise",
            "turn-precise",
            "orch-precise-1",
            "req-precise-1",
            "rec-precise-1",
            fingerprint,
            scope,
            "operator-sc1498",
            now.plusNanos(500_000));

    assertThat(claim.view().expiresAt()).isAfter(claim.view().approvedAt());
  }

  @Test
  void anExpiryThatNormalizesAwayIsRejectedWithAStableCode() {
    String scope = "sha256:" + "7".repeat(64);
    String fingerprint = "sha256:" + "8".repeat(64);
    assertThatThrownBy(
            () ->
                store.create(
                    "session-precise-2",
                    "turn-precise-2",
                    "orch-precise-2",
                    "req-precise-2",
                    "rec-precise-2",
                    fingerprint,
                    scope,
                    "operator-sc1498",
                    CLOCK.instant().plusNanos(500)))
        .isInstanceOfSatisfying(
            BootstrapApprovalException.class,
            exception -> assertThat(exception.failureCode()).isEqualTo("AUTHORITY_EXPIRY_INVALID"));
  }

  @Test
  void platformUnsafeRecordingIdsNeverReachTheDatabase() {
    String scope = "sha256:" + "9".repeat(64);
    String fingerprint = "sha256:" + "6".repeat(64);
    for (String recordingId : java.util.List.of("run:1", "rec.", "CON", "nul.mp4")) {
      assertThatThrownBy(
              () ->
                  store.create(
                      "session-unsafe",
                      "turn-unsafe",
                      "orch-unsafe-" + recordingId.replaceAll("[^A-Za-z0-9]", "_"),
                      "req-unsafe-" + recordingId.replaceAll("[^A-Za-z0-9]", "_"),
                      recordingId,
                      fingerprint,
                      scope,
                      "operator-sc1498",
                      CLOCK.instant().plusSeconds(300)))
          .as("recording id %s", recordingId)
          .isInstanceOfSatisfying(
              BootstrapApprovalException.class,
              exception -> assertThat(exception.failureCode()).isEqualTo("RECORDING_ID_UNSAFE"));
    }
    assertThat(
            store.findByOrchestration("orch-unsafe-run_1"))
        .as("no approval row is written for an unsafe recording id")
        .isEmpty();
  }

  @Test
  void identicalCreatesReturnSameDurableOwner() throws Exception {
    String scope = "sha256:" + "a".repeat(64);
    String fingerprint = "sha256:" + "b".repeat(64);
    CyclicBarrier barrier = new CyclicBarrier(4);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      List<Future<BootstrapApprovalStore.Claim>> futures =
          IntStream.range(0, 4)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            barrier.await();
                            return store.create(
                                "session-1",
                                "turn-1",
                                "orch-approval-1",
                                "req-approval-1",
                                "rec-approval-1",
                                fingerprint,
                                scope,
                                "operator-1",
                                CLOCK.instant().plusSeconds(300));
                          }))
              .toList();
      List<BootstrapApprovalStore.Claim> claims =
          futures.stream()
              .map(
                  future -> {
                    try {
                      return future.get(20, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception exception) {
                      throw new AssertionError(exception);
                    }
                  })
              .toList();
      long created = claims.stream().filter(BootstrapApprovalStore.Claim::created).count();
      assertThat(created).isEqualTo(1);
      assertThat(claims.stream().map(c -> c.view().approvalId()).distinct()).hasSize(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void conflictingReuseIsStableConflictAndBindIsSingleUse() {
    String scope = "sha256:" + "c".repeat(64);
    String fingerprint = "sha256:" + "d".repeat(64);
    var claim =
        store.create(
            "session-2",
            "turn-2",
            "orch-approval-2",
            "req-approval-2",
            "rec-approval-2",
            fingerprint,
            scope,
            "operator-1",
            CLOCK.instant().plusSeconds(300));
    assertThat(claim.created()).isTrue();

    assertThatThrownBy(
            () ->
                store.create(
                    "session-2",
                    "turn-2",
                    "orch-approval-2",
                    "req-approval-2",
                    "rec-approval-2",
                    "sha256:" + "e".repeat(64),
                    scope,
                    "operator-1",
                    CLOCK.instant().plusSeconds(300)))
        .isInstanceOf(BootstrapApprovalException.class)
        .hasMessageContaining("different scope");

    ApprovalView bound =
        store.bind(claim.view().approvalId(), "auth-1", "sha256:" + "f".repeat(64));
    assertThat(bound.state()).isEqualTo("BOUND");

    ApprovalView replay =
        store.bind(claim.view().approvalId(), "auth-1", "sha256:" + "f".repeat(64));
    assertThat(replay.approvalId()).isEqualTo(bound.approvalId());

    assertThatThrownBy(
            () -> store.bind(claim.view().approvalId(), "auth-2", "sha256:" + "0".repeat(64)))
        .isInstanceOf(BootstrapApprovalException.class)
        .hasMessageContaining("different authority");
  }

  @Test
  void recordingIdOnlyReuseConflictIsStable() {
    store.create(
        "session-3",
        "turn-3",
        "orch-approval-3",
        "req-approval-3",
        "rec-approval-3",
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        "operator-1",
        CLOCK.instant().plusSeconds(300));

    // Only the unique recording id is reused: every other identity differs. The conflict must still
    // resolve to a stable APPROVAL_CONFLICT with the durable owner, not an opaque ownership
    // failure.
    assertThatThrownBy(
            () ->
                store.create(
                    "session-4",
                    "turn-4",
                    "orch-approval-4",
                    "req-approval-4",
                    "rec-approval-3",
                    "sha256:" + "3".repeat(64),
                    "sha256:" + "4".repeat(64),
                    "operator-1",
                    CLOCK.instant().plusSeconds(300)))
        .isInstanceOf(BootstrapApprovalException.class)
        .hasMessageContaining("different scope");
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackages = "com.chromascape.web.gameplay.bootstrap")
  @Import(JsonConfiguration.class)
  static class TestApplication {
    @Bean
    BootstrapApprovalStore approvalStore(
        EntityManager entityManager, CanonicalGameplayJson canonicalJson) {
      return new BootstrapApprovalStore(entityManager, canonicalJson, CLOCK);
    }
  }

  @TestConfiguration
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
