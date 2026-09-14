package com.chromascape.web.gameplay.orchestration.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Fail-closed operator authentication for the SC-1449 v2 live gameplay boundary.
 *
 * <p>The presented {@code X-Gameplay-Operator-Key} is compared against the configured secret with
 * {@link MessageDigest#isEqual}, so a wrong key cannot be recovered from response timing. Header
 * presence alone is never authentication: an unconfigured or too-short secret makes every call
 * fail, so a deployment that forgot to configure the key cannot accidentally expose live gameplay.
 *
 * <p>Authentication returns a short, non-reversible principal id (the leading bytes of the secret's
 * SHA-256) so operator activity can be attributed in the audit log without the secret ever reaching
 * a log sink.
 */
@Component
public class GameplayV2OperatorAuthenticator {
  /** Minimum configured secret length, matching the other gameplay operator boundaries. */
  static final int MINIMUM_KEY_LENGTH = 32;

  private final byte[] operatorKey;
  private final String principalId;

  public GameplayV2OperatorAuthenticator(
      @Value("${gameplay.v2.operator-key:}") String operatorKey) {
    this.operatorKey =
        operatorKey == null ? new byte[0] : operatorKey.getBytes(StandardCharsets.UTF_8);
    this.principalId =
        this.operatorKey.length >= MINIMUM_KEY_LENGTH ? principalOf(this.operatorKey) : null;
  }

  /** True when a usable operator secret is configured. Never exposes the secret itself. */
  public boolean configured() {
    return operatorKey.length >= MINIMUM_KEY_LENGTH;
  }

  /**
   * Verifies the presented key and returns the audit principal id.
   *
   * @throws GameplayV2OperatorException {@code 503 OPERATOR_KEY_NOT_CONFIGURED} when no usable
   *     secret is configured, or {@code 401 OPERATOR_AUTHENTICATION_FAILED} when the presented key
   *     is missing, blank or wrong.
   */
  public String authenticate(String presentedKey) {
    if (!configured()) {
      throw new GameplayV2OperatorException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "OPERATOR_KEY_NOT_CONFIGURED",
          "No SC-1449 v2 operator key is configured; the live gate is closed");
    }
    byte[] presented =
        presentedKey == null ? new byte[0] : presentedKey.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(operatorKey, presented)) {
      throw new GameplayV2OperatorException(
          HttpStatus.UNAUTHORIZED,
          "OPERATOR_AUTHENTICATION_FAILED",
          "A valid SC-1449 v2 operator key is required");
    }
    return principalId;
  }

  private static String principalOf(byte[] key) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
      byte[] prefix = new byte[6];
      System.arraycopy(digest, 0, prefix, 0, prefix.length);
      return "op-" + HexFormat.of().formatHex(prefix);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  /** Operator boundary failure carrying the exact response status and stable failure code. */
  public static final class GameplayV2OperatorException extends RuntimeException {
    private final HttpStatus status;
    private final String failureCode;

    public GameplayV2OperatorException(HttpStatus status, String failureCode, String message) {
      super(message);
      this.status = status;
      this.failureCode = failureCode;
    }

    public HttpStatus status() {
      return status;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
