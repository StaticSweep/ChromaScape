package com.chromascape.web.gameplay.contract.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator.GameplayV2ContractViolation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed idempotency guarantees for SC-1429 v2: concurrent first use admits exactly one fresh
 * dispatch, null/blank inputs never mutate the ledger, deprecated digest-based overloads never
 * admit a dispatch, and legacy {@code requestId}-only records advance only with a validated
 * reconciliation receipt carrying request identity, envelope digest, external outcome, verifier
 * identity and confirmation time.
 */
class GameplayV2IdempotencyTest {
  private ObjectMapper objectMapper;
  private GameplayV2ContractValidator validator;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    validator = new GameplayV2ContractValidator(objectMapper);
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      fixtures = objectMapper.readTree(input);
    }
  }

  @Test
  void concurrentFirstUseAdmitsExactlyOneFreshDispatch() throws Exception {
    JsonNode envelope = fixtures.path("executionEnvelope");
    String key = envelope.path("idempotencyKey").asText();
    String requestId = envelope.path("requestId").asText();
    int racers = 16;
    ConcurrentMap<String, String> ledger = new ConcurrentHashMap<>();
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    CountDownLatch ready = new CountDownLatch(racers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, ledger);
                }));
      }
      ready.await();
      start.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> future : futures) {
        outcomes.add(future.get());
      }
      assertThat(outcomes).filteredOn(outcome -> outcome == null).hasSize(1);
      assertThat(outcomes).filteredOn("RECONCILED"::equals).hasSize(racers - 1);
      assertThat(ledger).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void nullEnvelopeFailsClosedWithoutMutatingTheLedger() {
    JsonNode envelope = fixtures.path("executionEnvelope");
    String key = envelope.path("idempotencyKey").asText();
    String requestId = envelope.path("requestId").asText();
    ConcurrentMap<String, String> ledger = new ConcurrentHashMap<>();
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, (JsonNode) null, ledger))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(ledger).isEmpty();
    com.fasterxml.jackson.databind.node.NullNode nullEnvelope =
        com.fasterxml.jackson.databind.node.NullNode.getInstance();
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, nullEnvelope, ledger))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(ledger).isEmpty();
  }

  @Test
  void blankOrNullKeysAndLedgersFailClosed() {
    JsonNode envelope = fixtures.path("executionEnvelope");
    String key = envelope.path("idempotencyKey").asText();
    String requestId = envelope.path("requestId").asText();
    ConcurrentMap<String, String> ledger = new ConcurrentHashMap<>();
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(null, requestId, envelope, ledger))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(GameplayV2EnvelopeRules.checkIdempotency("  ", requestId, envelope, ledger))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, null, envelope, ledger))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, " ", envelope, ledger))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, null))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(ledger).isEmpty();
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedOverloadsAlwaysFailClosedWithoutMutatingTheLedger() {
    JsonNode envelope = fixtures.path("executionEnvelope");
    String key = envelope.path("idempotencyKey").asText();
    String requestId = envelope.path("requestId").asText();
    String digest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
    ConcurrentMap<String, String> fresh = new ConcurrentHashMap<>();
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, fresh))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, digest, fresh))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(fresh).isEmpty();
    // Even an exact digest replay through the deprecated overload is refused: the hint cannot
    // prove the bytes it claims.
    ConcurrentMap<String, String> seeded = new ConcurrentHashMap<>();
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, seeded)).isNull();
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, digest, seeded))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, seeded))
        .isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  @SuppressWarnings("deprecation")
  void legacyRetryNeverLaundersIntoReconciliation() {
    JsonNode envelope = fixtures.path("executionEnvelope");
    String key = envelope.path("idempotencyKey").asText();
    String requestId = envelope.path("requestId").asText();
    ConcurrentMap<String, String> legacy = new ConcurrentHashMap<>(Map.of(key, requestId));
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, legacy))
        .isEqualTo("NEEDS_RECONCILIATION");
    assertThat(legacy).isEqualTo(Map.of(key, requestId));
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, legacy))
        .isEqualTo("NEEDS_RECONCILIATION");
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, legacy))
        .isEqualTo("NEEDS_RECONCILIATION");
    // The receipt-less overload always fails closed and never mutates the ledger.
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(key, requestId, envelope, legacy))
        .isFalse();
    assertThat(legacy).isEqualTo(Map.of(key, requestId));
    java.time.Instant confirmedAt = java.time.Instant.parse("2026-09-03T12:04:00Z");
    GameplayV2ReconciliationReceipt receipt =
        GameplayV2ReconciliationReceipt.issueTrusted(
            key,
            requestId,
            GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope),
            GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
            "sc-1449-trusted-reconciler",
            confirmedAt,
            "sc-1449-test-key",
            confirmedAt.plusSeconds(600),
            "test-signing-secret-0123456789abcdef-0123456789");
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key,
                requestId,
                envelope,
                legacy,
                receipt,
                "test-signing-secret-0123456789abcdef-0123456789",
                "sc-1449-test-key",
                confirmedAt.plusSeconds(1)))
        .isTrue();
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, legacy))
        .isEqualTo("RECONCILED");
    // Compare-and-set refuses to advance an already-advanced record.
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key,
                requestId,
                envelope,
                legacy,
                receipt,
                "test-signing-secret-0123456789abcdef-0123456789",
                "sc-1449-test-key",
                confirmedAt.plusSeconds(1)))
        .isFalse();
  }

  @Test
  @SuppressWarnings("deprecation")
  void forgedReconciliationReceiptsNeverAdvanceTheLedger() {
    JsonNode envelope = fixtures.path("executionEnvelope");
    String key = envelope.path("idempotencyKey").asText();
    String requestId = envelope.path("requestId").asText();
    String digest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
    java.time.Instant confirmedAt = java.time.Instant.parse("2026-09-03T12:04:00Z");
    java.time.Instant now = confirmedAt.plusSeconds(1);
    String secret = "test-signing-secret-0123456789abcdef-0123456789";
    String keyId = "sc-1449-test-key";
    GameplayV2ReconciliationReceipt wrongDigest =
        GameplayV2ReconciliationReceipt.issueTrusted(
            key,
            requestId,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
            "sc-1449-trusted-reconciler",
            confirmedAt,
            keyId,
            confirmedAt.plusSeconds(600),
            secret);
    ConcurrentMap<String, String> ledger = new ConcurrentHashMap<>(Map.of(key, requestId));
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key, requestId, envelope, ledger, wrongDigest, secret, keyId, now))
        .isFalse();
    assertThat(ledger).isEqualTo(Map.of(key, requestId));
    GameplayV2ReconciliationReceipt wrongKey =
        GameplayV2ReconciliationReceipt.issueTrusted(
            "other-key",
            requestId,
            digest,
            GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
            "sc-1449-trusted-reconciler",
            confirmedAt,
            keyId,
            confirmedAt.plusSeconds(600),
            secret);
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key, requestId, envelope, ledger, wrongKey, secret, keyId, now))
        .isFalse();
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key, requestId, envelope, ledger, null, secret, keyId, now))
        .isFalse();
    assertThat(ledger).isEqualTo(Map.of(key, requestId));
    assertThatThrownBy(
            () ->
                GameplayV2ReconciliationReceipt.issueTrusted(
                    key,
                    requestId,
                    digest,
                    "EXTERNAL_PENDING",
                    "sc-1449-trusted-reconciler",
                    confirmedAt,
                    keyId,
                    confirmedAt.plusSeconds(600),
                    secret))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("IDENTITY_MISMATCH");
    assertThatThrownBy(
            () ->
                GameplayV2ReconciliationReceipt.issueTrusted(
                    key,
                    requestId,
                    digest,
                    GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
                    "  ",
                    confirmedAt,
                    keyId,
                    confirmedAt.plusSeconds(600),
                    secret))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("IDENTITY_MISMATCH");
    // The unsigned shape factory still validates its inputs.
    assertThatThrownBy(
            () ->
                GameplayV2ReconciliationReceipt.issue(
                    key,
                    requestId,
                    digest,
                    "EXTERNAL_PENDING",
                    "sc-1449-trusted-reconciler",
                    confirmedAt))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("IDENTITY_MISMATCH");
    assertThatThrownBy(
            () ->
                GameplayV2ReconciliationReceipt.issue(
                    key,
                    requestId,
                    digest,
                    GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
                    "  ",
                    confirmedAt))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("IDENTITY_MISMATCH");
  }

  @Test
  @SuppressWarnings("deprecation")
  void ordinaryCallerCannotForgeExactConfirmedReceipt() {
    JsonNode envelope = fixtures.path("executionEnvelope");
    String key = envelope.path("idempotencyKey").asText();
    String requestId = envelope.path("requestId").asText();
    String digest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
    java.time.Instant confirmedAt = java.time.Instant.parse("2026-09-03T12:04:00Z");
    java.time.Instant now = confirmedAt.plusSeconds(1);
    String secret = "test-signing-secret-0123456789abcdef-0123456789";
    String keyId = "sc-1449-test-key";
    // An ordinary caller computes the exact envelope digest and mints an otherwise exact
    // CONFIRMED receipt through the public unsigned factory, but without the trusted
    // reconciler secret it carries no signature and must never advance the ledger.
    GameplayV2ReconciliationReceipt forgedExact =
        GameplayV2ReconciliationReceipt.issue(
            key,
            requestId,
            digest,
            GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
            "sc-1449-trusted-reconciler",
            confirmedAt);
    ConcurrentMap<String, String> ledger = new ConcurrentHashMap<>(Map.of(key, requestId));
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key, requestId, envelope, ledger, forgedExact, secret, keyId, now))
        .isFalse();
    assertThat(ledger).isEqualTo(Map.of(key, requestId));
    // The deprecated unsigned overload never promotes either, even for a validly signed
    // receipt: signature verification only happens on the trusted overload.
    GameplayV2ReconciliationReceipt signed =
        GameplayV2ReconciliationReceipt.issueTrusted(
            key,
            requestId,
            digest,
            GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
            "sc-1449-trusted-reconciler",
            confirmedAt,
            keyId,
            confirmedAt.plusSeconds(600),
            secret);
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key, requestId, envelope, ledger, signed))
        .isFalse();
    assertThat(ledger).isEqualTo(Map.of(key, requestId));
    // A signature minted under the wrong secret does not verify.
    GameplayV2ReconciliationReceipt wrongSecret =
        GameplayV2ReconciliationReceipt.issueTrusted(
            key,
            requestId,
            digest,
            GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
            "sc-1449-trusted-reconciler",
            confirmedAt,
            keyId,
            confirmedAt.plusSeconds(600),
            "wrong-signing-secret-0123456789abcdef-01234567");
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key, requestId, envelope, ledger, wrongSecret, secret, keyId, now))
        .isFalse();
    // A receipt presented under the wrong key ID does not verify.
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key, requestId, envelope, ledger, signed, secret, "other-key", now))
        .isFalse();
    // An expired receipt does not verify even with the right secret and key ID.
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key,
                requestId,
                envelope,
                ledger,
                signed,
                secret,
                keyId,
                confirmedAt.plusSeconds(601)))
        .isFalse();
    assertThat(ledger).isEqualTo(Map.of(key, requestId));
    // The validly signed receipt still advances exactly once through the trusted overload.
    assertThat(
            GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                key, requestId, envelope, ledger, signed, secret, keyId, now))
        .isTrue();
    assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, ledger))
        .isEqualTo("RECONCILED");
  }

  @Test
  void goldenReceiptSignatureVectorIsStableAcrossRuntimes() {
    // Cross-runtime pin: Jackson (ChromaScape) and Gson (Micro6) must produce
    // byte-identical canonical JSON and the same HMAC-SHA256 for these inputs.
    // The verifier identity exercises JSON escaping (quotes and backslashes);
    // the expected bytes and signature are pinned identically in both repos.
    String idempotencyKey = "sc1429-golden-key-01";
    String requestId = "sc1429-golden-req-01";
    String envelopeDigest =
        "sha256:aa5edbf178c2c63008346fe0e0091c0951e695d05f9a93f6c269ddbd47b60323";
    String verifierIdentity = "golden-\"verifier\"\\\\test";
    java.time.Instant confirmedAt = java.time.Instant.parse("2026-09-03T12:04:00Z");
    String keyId = "sc-1429-golden-key-01";
    java.time.Instant expiresAt = java.time.Instant.parse("2026-09-03T12:14:00Z");
    String signingSecret = "sc1429-golden-signing-secret-0123456789abcdef";
    String expectedCanonical =
        "{\"confirmedAt\":\"2026-09-03T12:04:00Z\","
            + "\"envelopeDigest\":\"sha256:aa5edbf178c2c63008346fe0e0091c0951e695"
            + "d05f9a93f6c269ddbd47b60323\","
            + "\"expiresAt\":\"2026-09-03T12:14:00Z\","
            + "\"externalOutcome\":\"CONFIRMED\","
            + "\"idempotencyKey\":\"sc1429-golden-key-01\","
            + "\"keyId\":\"sc-1429-golden-key-01\","
            + "\"requestId\":\"sc1429-golden-req-01\","
            + "\"verifierIdentity\":\"golden-\\\"verifier\\\"\\\\\\\\test\"}";
    String expectedSignature =
        "hmac-sha256:decd6deb4a9238a065fb4a368bd72bcbfa1280c1b89e1b5275750462d4f1cb53";
    byte[] canonical =
        GameplayV2ReconciliationReceipt.unsignedCanonicalBytes(
            idempotencyKey,
            requestId,
            envelopeDigest,
            GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
            verifierIdentity,
            confirmedAt,
            keyId,
            expiresAt);
    assertThat(new String(canonical, java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo(expectedCanonical);
    assertThat(
            GameplayV2ReconciliationReceipt.computeSignature(
                idempotencyKey,
                requestId,
                envelopeDigest,
                GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
                verifierIdentity,
                confirmedAt,
                keyId,
                expiresAt,
                signingSecret))
        .isEqualTo(expectedSignature);
    GameplayV2ReconciliationReceipt receipt =
        GameplayV2ReconciliationReceipt.issueTrusted(
            idempotencyKey,
            requestId,
            envelopeDigest,
            GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
            verifierIdentity,
            confirmedAt,
            keyId,
            expiresAt,
            signingSecret);
    assertThat(receipt.signature()).isEqualTo(expectedSignature);
    assertThat(GameplayV2ReconciliationReceipt.verifySignature(receipt, signingSecret)).isTrue();
  }

  @Test
  void headNodeNameNeedNotBeLiteral() {
    JsonNode plan = fixtures.path("goalPlan").deepCopy();
    com.fasterxml.jackson.databind.node.ObjectNode head =
        (com.fasterxml.jackson.databind.node.ObjectNode) plan.path("nodes").path(0);
    head.put("nodeId", "head-node");
    com.fasterxml.jackson.databind.node.ObjectNode planCopy = plan.deepCopy();
    planCopy.remove("goalPlanDigest");
    String resigned = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, planCopy);
    ((com.fasterxml.jackson.databind.node.ObjectNode) plan).put("goalPlanDigest", resigned);
    JsonNode envelope = fixtures.path("executionEnvelope").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelope).put("goalPlanDigest", resigned);
    assertThat(
            GameplayV2EnvelopeRules.checkBindings(
                envelope,
                fixtures.path("catalogSnapshot"),
                fixtures.path("semanticObservation"),
                plan,
                java.time.Instant.parse("2026-09-03T12:04:00Z")))
        .isNull();
    // Ordinal 0 on the head node is still required: re-sign the plan and the envelope
    // after changing the ordinal so the second assertion exercises the ordinal rule
    // rather than a stale digest.
    head.put("ordinal", 3);
    com.fasterxml.jackson.databind.node.ObjectNode staleCopy = plan.deepCopy();
    staleCopy.remove("goalPlanDigest");
    String staleResigned = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, staleCopy);
    ((com.fasterxml.jackson.databind.node.ObjectNode) plan).put("goalPlanDigest", staleResigned);
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelope)
        .put("goalPlanDigest", staleResigned);
    assertThat(
            GameplayV2EnvelopeRules.checkBindings(
                envelope,
                fixtures.path("catalogSnapshot"),
                fixtures.path("semanticObservation"),
                plan,
                java.time.Instant.parse("2026-09-03T12:04:00Z")))
        .isEqualTo("IDENTITY_MISMATCH");
    // The validator surfaces the same mutation with its structural code.
    assertThatThrownBy(() -> validator.validateWithRules("goalPlan", plan))
        .isInstanceOf(GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_ORDINAL_OUT_OF_ORDER");
  }
}
