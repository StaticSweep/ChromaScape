package com.chromascape.web.gameplay.authority;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Creates only human-origin, expiring, exact-scope, one-use live authorities. */
@Service
public final class HumanGameplayAuthorityService {
  private static final Duration MAX_LIFETIME = Duration.ofMinutes(15);
  private final CanonicalGameplayJson canonicalJson;
  private final Clock clock;
  private final byte[] signingSecret;
  private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

  @Autowired
  public HumanGameplayAuthorityService(
      CanonicalGameplayJson canonicalJson,
      @Value("${gameplay.bootstrap.authority-hmac-secret:}") String signingSecret) {
    this(canonicalJson, Clock.systemUTC(), signingSecret);
  }

  HumanGameplayAuthorityService(
      CanonicalGameplayJson canonicalJson, Clock clock, String signingSecret) {
    this.canonicalJson = canonicalJson;
    this.clock = clock;
    if (signingSecret == null || signingSecret.length() < 32) {
      this.signingSecret = null;
    } else {
      this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
    }
  }

  public PendingApproval approveExactScope(
      String approvedBy, Object exactScope, Instant expiresAt) {
    requireSigningConfigured();
    if (exactScope == null) {
      throw new AuthorityException("AUTHORITY_SCOPE_INVALID", "Exact approval scope is required");
    }
    if (approvedBy == null || !approvedBy.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new AuthorityException(
          "AUTHORITY_APPROVER_INVALID", "A stable human operator id is required");
    }
    Instant now = clock.instant();
    if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_LIFETIME))) {
      throw new AuthorityException(
          "AUTHORITY_EXPIRY_INVALID", "Authority must expire within 15 minutes");
    }
    String authorityId = "auth_" + UUID.randomUUID().toString().replace("-", "");
    PendingApproval approval =
        new PendingApproval(
            authorityId, approvedBy, now, expiresAt, canonicalJson.digest(exactScope));
    pending.put(authorityId, approval);
    return approval;
  }

  /**
   * Binds a provider proposal only when its independently computed scope is exactly what the human
   * approved.
   */
  public HumanAuthority bindEnvelope(PendingApproval approval, ExecutionEnvelope envelope) {
    requireSigningConfigured();
    PendingApproval current = pending.remove(approval.authorityId());
    if (!Objects.equals(current, approval)) {
      throw new AuthorityException(
          "AUTHORITY_MISSING", "Pending approval is absent or already bound");
    }
    Instant now = clock.instant();
    if (!approval.expiresAt().isAfter(now)) {
      throw new AuthorityException("AUTHORITY_EXPIRED", "Pending approval expired before binding");
    }
    String scopeDigest = canonicalJson.digest(exactScope(envelope));
    if (!constantTimeEquals(scopeDigest, approval.scopeDigest())) {
      throw new AuthorityException(
          "AUTHORITY_SCOPE_MISMATCH", "Provider proposal changed the approved scope");
    }
    if (!approval.authorityId().equals(envelope.authorityId())) {
      throw new AuthorityException(
          "AUTHORITY_ID_MISMATCH", "Envelope authority id differs from approval");
    }
    String envelopeDigest = canonicalJson.digest(envelope);
    HumanAuthority unsigned =
        new HumanAuthority(
            GameplayContracts.VERSION,
            approval.authorityId(),
            "HUMAN",
            approval.approvedBy(),
            approval.approvedAt(),
            approval.expiresAt(),
            envelopeDigest,
            scopeDigest,
            true,
            "hmac-sha256:" + "0".repeat(64));
    String signature =
        "hmac-sha256:" + hmac(canonicalJson.canonicalBytes(unsignedPayload(unsigned)));
    return new HumanAuthority(
        unsigned.contractVersion(),
        unsigned.authorityId(),
        unsigned.actorType(),
        unsigned.approvedBy(),
        unsigned.approvedAt(),
        unsigned.expiresAt(),
        unsigned.envelopeDigest(),
        unsigned.scopeDigest(),
        unsigned.oneUse(),
        signature);
  }

  /**
   * Verifies the signed authority and the exact scope bound to an envelope.
   *
   * <p>The orchestration persistence layer stores only the resulting ownership facts. It calls
   * this method before creating that durable one-use ownership row, so a syntactically plausible
   * authority cannot be used to bypass the human approval boundary.
   */
  public boolean verifyBoundAuthority(HumanAuthority authority, ExecutionEnvelope envelope) {
    if (signingSecret == null || authority == null || envelope == null) {
      return false;
    }
    try {
      if (!Objects.equals(authority.authorityId(), envelope.authorityId())
          || !Objects.equals(authority.envelopeDigest(), canonicalJson.digest(envelope))
          || !Objects.equals(authority.scopeDigest(), canonicalJson.digest(exactScope(envelope)))) {
        return false;
      }
      HumanAuthority unsigned =
          new HumanAuthority(
              authority.contractVersion(),
              authority.authorityId(),
              authority.actorType(),
              authority.approvedBy(),
              authority.approvedAt(),
              authority.expiresAt(),
              authority.envelopeDigest(),
              authority.scopeDigest(),
              authority.oneUse(),
              "hmac-sha256:" + "0".repeat(64));
      String expected =
          "hmac-sha256:" + hmac(canonicalJson.canonicalBytes(unsignedPayload(unsigned)));
      return authority.signature() != null && constantTimeEquals(expected, authority.signature());
    } catch (RuntimeException exception) {
      return false;
    }
  }

  /** Revokes an unbound approval during operator-session cleanup. */
  public void cancel(PendingApproval approval) {
    if (approval != null) {
      pending.remove(approval.authorityId(), approval);
    }
  }

  /** Returns the only scope shape accepted by {@link #bindEnvelope}; display it verbatim. */
  public Map<String, Object> exactScope(ExecutionEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("contractVersion", envelope.contractVersion());
    scope.put("provider", envelope.provider());
    scope.put("modelVersion", envelope.modelVersion());
    scope.put("identities", envelope.identities().deepCopy());
    scope.put("taskKey", envelope.taskKey());
    scope.put("settings", envelope.settings().deepCopy());
    scope.put("budgets", envelope.budgets().deepCopy());
    scope.put("semanticActionDigest", envelope.semanticActionDigest());
    scope.put("expectedTransition", envelope.expectedTransition());
    scope.put("verifierId", envelope.verifierId());
    scope.put("deadline", envelope.deadline());
    scope.put("killSwitchScope", envelope.killSwitchScope());
    return Map.copyOf(scope);
  }

  private Map<String, Object> unsignedPayload(HumanAuthority authority) {
    return Map.of(
        "contractVersion", authority.contractVersion(),
        "authorityId", authority.authorityId(),
        "actorType", authority.actorType(),
        "approvedBy", authority.approvedBy(),
        "approvedAt", authority.approvedAt(),
        "expiresAt", authority.expiresAt(),
        "envelopeDigest", authority.envelopeDigest(),
        "scopeDigest", authority.scopeDigest(),
        "oneUse", authority.oneUse());
  }

  private String hmac(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to sign gameplay authority", exception);
    }
  }

  private boolean constantTimeEquals(String left, String right) {
    return MessageDigestSupport.constantTimeEquals(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private void requireSigningConfigured() {
    if (signingSecret == null) {
      throw new AuthorityException(
          "AUTHORITY_SIGNING_UNAVAILABLE",
          "gameplay.bootstrap.authority-hmac-secret must contain at least 32 characters");
    }
  }

  public record PendingApproval(
      String authorityId,
      String approvedBy,
      Instant approvedAt,
      Instant expiresAt,
      String scopeDigest) {}

  public static final class AuthorityException extends IllegalStateException {
    private final String failureCode;

    public AuthorityException(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }

  private static final class MessageDigestSupport {
    private static boolean constantTimeEquals(byte[] left, byte[] right) {
      return java.security.MessageDigest.isEqual(left, right);
    }
  }
}
