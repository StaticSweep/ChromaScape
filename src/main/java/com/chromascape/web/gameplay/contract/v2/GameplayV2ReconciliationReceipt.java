package com.chromascape.web.gameplay.contract.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Immutable validated proof that external reconciliation examined a legacy request.
 *
 * <p>A legacy {@code requestId}-only ledger record must never advance on ledger access alone. This
 * receipt binds request identity ({@code idempotencyKey}/{@code requestId}), the recomputed
 * envelope digest, the external outcome, the verifier identity and the confirmation time in one
 * immutable value. Only a receipt signed by the SC-1449 trusted reconciler (HMAC-SHA256 over the
 * canonical receipt bytes with a {@code >=32}-byte secret, carrying the signer {@code keyId}, an
 * {@code expiresAt} bound and a {@code hmac-sha256:} signature) authorizes promotion via {@link
 * GameplayV2EnvelopeRules#confirmLegacyReconciliation(String, String,
 * com.fasterxml.jackson.databind.JsonNode, java.util.concurrent.ConcurrentMap,
 * GameplayV2ReconciliationReceipt, String, String, Instant)}. Unsigned receipts created by {@link
 * #issue} are untrusted shape checks only and never authorize promotion: any caller can compute an
 * envelope digest and mint an otherwise exact {@code CONFIRMED} value, so the trusted overload
 * rejects them.
 */
public final class GameplayV2ReconciliationReceipt {
  /** The only external outcome that authorizes promotion of a legacy record. */
  public static final String CONFIRMED_OUTCOME = "CONFIRMED";

  /** Maximum receipt lifetime: confirmation to expiry, mirroring authority TTL. */
  public static final long MAX_TTL_SECONDS = 900;

  private final String idempotencyKey;
  private final String requestId;
  private final String envelopeDigest;
  private final String externalOutcome;
  private final String verifierIdentity;
  private final Instant confirmedAt;
  private final String keyId;
  private final Instant expiresAt;
  private final String signature;

  private GameplayV2ReconciliationReceipt(
      String idempotencyKey,
      String requestId,
      String envelopeDigest,
      String externalOutcome,
      String verifierIdentity,
      Instant confirmedAt,
      String keyId,
      Instant expiresAt,
      String signature) {
    this.idempotencyKey = idempotencyKey;
    this.requestId = requestId;
    this.envelopeDigest = envelopeDigest;
    this.externalOutcome = externalOutcome;
    this.verifierIdentity = verifierIdentity;
    this.confirmedAt = confirmedAt;
    this.keyId = keyId;
    this.expiresAt = expiresAt;
    this.signature = signature;
  }

  /**
   * Untrusted shape check only. Creates an <em>unsigned</em> receipt (no {@code keyId}, {@code
   * expiresAt} or {@code signature}); any caller can mint an otherwise exact {@code CONFIRMED}
   * value with this factory, so the trusted {@code confirmLegacyReconciliation} overload always
   * rejects its output. Only {@link #issueTrusted} (SC-1449 trusted reconciler boundary, holder of
   * the signing secret) creates receipts that authorize promotion.
   *
   * @throws GameplayV2ContractValidator.GameplayV2ContractViolation with {@code IDENTITY_MISMATCH}
   *     when any field is missing, blank, unknown or not yet plausible.
   * @deprecated Use {@link #issueTrusted}; unsigned receipts never authorize promotion.
   */
  @Deprecated
  public static GameplayV2ReconciliationReceipt issue(
      String idempotencyKey,
      String requestId,
      String envelopeDigest,
      String externalOutcome,
      String verifierIdentity,
      Instant confirmedAt) {
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || requestId == null
        || requestId.isBlank()
        || envelopeDigest == null
        || envelopeDigest.isBlank()
        || verifierIdentity == null
        || verifierIdentity.isBlank()
        || confirmedAt == null) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH",
          "Reconciliation receipt requires request identity, envelope "
              + "digest, verifier identity and confirmation time");
    }
    if (!CONFIRMED_OUTCOME.equals(externalOutcome)) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH",
          "Reconciliation receipt carries an unknown external outcome: " + externalOutcome);
    }
    if (confirmedAt.isAfter(Instant.now().plusSeconds(60))) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH", "Reconciliation receipt confirmation time is in the future");
    }
    return new GameplayV2ReconciliationReceipt(
        idempotencyKey,
        requestId,
        envelopeDigest,
        externalOutcome,
        verifierIdentity,
        confirmedAt,
        null,
        null,
        null);
  }

  /**
   * Issues a cryptographically authenticated receipt from the SC-1449 trusted reconciler boundary.
   * Only the holder of {@code signingSecret} (a {@code >=32}-byte HMAC key) may create receipts
   * that authorize promotion; ordinary callers without the secret cannot forge a valid signature
   * even when every other field is exact.
   *
   * <p>Validates the same shape as {@link #issue} plus: {@code keyId} non-blank, {@code expiresAt}
   * non-null, strictly after {@code confirmedAt} and within {@link #MAX_TTL_SECONDS} of it, and
   * {@code signingSecret} of at least 32 UTF-8 bytes. The {@code signature} is {@code hmac-sha256:}
   * over the canonical JSON bytes of the eight unsigned fields.
   *
   * @throws GameplayV2ContractValidator.GameplayV2ContractViolation with {@code IDENTITY_MISMATCH}
   *     when any field is missing, blank, unknown, expired or not yet plausible, or when the
   *     signing secret is missing, blank or undersized.
   */
  public static GameplayV2ReconciliationReceipt issueTrusted(
      String idempotencyKey,
      String requestId,
      String envelopeDigest,
      String externalOutcome,
      String verifierIdentity,
      Instant confirmedAt,
      String keyId,
      Instant expiresAt,
      String signingSecret) {
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || requestId == null
        || requestId.isBlank()
        || envelopeDigest == null
        || envelopeDigest.isBlank()
        || verifierIdentity == null
        || verifierIdentity.isBlank()
        || confirmedAt == null
        || keyId == null
        || keyId.isBlank()
        || expiresAt == null) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH",
          "Trusted reconciliation receipt requires request identity, "
              + "envelope digest, verifier identity, confirmation time, key ID and expiry");
    }
    if (!CONFIRMED_OUTCOME.equals(externalOutcome)) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH",
          "Reconciliation receipt carries an unknown external outcome: " + externalOutcome);
    }
    if (confirmedAt.isAfter(Instant.now().plusSeconds(60))) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH", "Reconciliation receipt confirmation time is in the future");
    }
    if (!expiresAt.isAfter(confirmedAt)
        || expiresAt.isAfter(confirmedAt.plusSeconds(MAX_TTL_SECONDS))) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH", "Trusted reconciliation receipt expiry is out of bounds");
    }
    if (signingSecret == null
        || signingSecret.isBlank()
        || signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new GameplayV2ContractValidator.GameplayV2ContractViolation(
          "IDENTITY_MISMATCH", "Trusted reconciliation receipt requires a 32-byte secret");
    }
    String signature =
        computeSignature(
            idempotencyKey,
            requestId,
            envelopeDigest,
            externalOutcome,
            verifierIdentity,
            confirmedAt,
            keyId,
            expiresAt,
            signingSecret);
    return new GameplayV2ReconciliationReceipt(
        idempotencyKey,
        requestId,
        envelopeDigest,
        externalOutcome,
        verifierIdentity,
        confirmedAt,
        keyId,
        expiresAt,
        signature);
  }

  /** Canonical unsigned bytes signed by {@link #issueTrusted}. */
  static byte[] unsignedCanonicalBytes(
      String idempotencyKey,
      String requestId,
      String envelopeDigest,
      String externalOutcome,
      String verifierIdentity,
      Instant confirmedAt,
      String keyId,
      Instant expiresAt) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode unsigned = mapper.createObjectNode();
    unsigned.put("confirmedAt", confirmedAt.toString());
    unsigned.put("envelopeDigest", envelopeDigest);
    unsigned.put("expiresAt", expiresAt.toString());
    unsigned.put("externalOutcome", externalOutcome);
    unsigned.put("idempotencyKey", idempotencyKey);
    unsigned.put("keyId", keyId);
    unsigned.put("requestId", requestId);
    unsigned.put("verifierIdentity", verifierIdentity);
    return GameplayV2EligibilityFilter.canonicalDigestBytes(mapper, unsigned);
  }

  static String computeSignature(
      String idempotencyKey,
      String requestId,
      String envelopeDigest,
      String externalOutcome,
      String verifierIdentity,
      Instant confirmedAt,
      String keyId,
      Instant expiresAt,
      String signingSecret) {
    try {
      byte[] canonical =
          unsignedCanonicalBytes(
              idempotencyKey,
              requestId,
              envelopeDigest,
              externalOutcome,
              verifierIdentity,
              confirmedAt,
              keyId,
              expiresAt);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "hmac-sha256:" + HexFormat.of().formatHex(mac.doFinal(canonical));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to sign reconciliation receipt", exception);
    }
  }

  /** Verifies the receipt signature against {@code signingSecret}. */
  static boolean verifySignature(GameplayV2ReconciliationReceipt receipt, String signingSecret) {
    if (receipt == null
        || receipt.signature == null
        || receipt.keyId == null
        || receipt.expiresAt == null
        || signingSecret == null
        || signingSecret.isBlank()
        || signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      return false;
    }
    String expected =
        computeSignature(
            receipt.idempotencyKey,
            receipt.requestId,
            receipt.envelopeDigest,
            receipt.externalOutcome,
            receipt.verifierIdentity,
            receipt.confirmedAt,
            receipt.keyId,
            receipt.expiresAt,
            signingSecret);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        receipt.signature.getBytes(StandardCharsets.UTF_8));
  }

  /** Returns the idempotency identity bound by this receipt. */
  public String idempotencyKey() {
    return idempotencyKey;
  }

  /** Returns the request identity bound by this receipt. */
  public String requestId() {
    return requestId;
  }

  /** Returns the canonical execution-envelope digest. */
  public String envelopeDigest() {
    return envelopeDigest;
  }

  /** Returns the externally verified outcome. */
  public String externalOutcome() {
    return externalOutcome;
  }

  /** Returns the verifier identity that confirmed the outcome. */
  public String verifierIdentity() {
    return verifierIdentity;
  }

  /** Returns when the external outcome was confirmed. */
  public Instant confirmedAt() {
    return confirmedAt;
  }

  /** Returns the signing-key identifier. */
  public String keyId() {
    return keyId;
  }

  /** Returns the receipt expiry instant. */
  public Instant expiresAt() {
    return expiresAt;
  }

  /** Returns the HMAC-SHA-256 signature. */
  public String signature() {
    return signature;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GameplayV2ReconciliationReceipt receipt)) {
      return false;
    }
    return Objects.equals(idempotencyKey, receipt.idempotencyKey)
        && Objects.equals(requestId, receipt.requestId)
        && Objects.equals(envelopeDigest, receipt.envelopeDigest)
        && Objects.equals(externalOutcome, receipt.externalOutcome)
        && Objects.equals(verifierIdentity, receipt.verifierIdentity)
        && Objects.equals(confirmedAt, receipt.confirmedAt)
        && Objects.equals(keyId, receipt.keyId)
        && Objects.equals(expiresAt, receipt.expiresAt)
        && Objects.equals(signature, receipt.signature);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        idempotencyKey,
        requestId,
        envelopeDigest,
        externalOutcome,
        verifierIdentity,
        confirmedAt,
        keyId,
        expiresAt,
        signature);
  }

  @Override
  public String toString() {
    return "GameplayV2ReconciliationReceipt{idempotencyKey="
        + idempotencyKey
        + ", requestId="
        + requestId
        + ", envelopeDigest="
        + envelopeDigest
        + ", externalOutcome="
        + externalOutcome
        + ", verifierIdentity="
        + verifierIdentity
        + ", confirmedAt="
        + confirmedAt
        + ", keyId="
        + keyId
        + ", expiresAt="
        + expiresAt
        + "}";
  }
}
