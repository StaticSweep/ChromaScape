package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies short-lived, server-signed write nonces bound to the authenticated owner. A
 * nonce is not an authentication token: it binds a same-origin browser mutation to the principal
 * resolved for that request and prevents a cross-origin page from replaying a write using ambient
 * credentials.
 */
@Component
public class MouseVideoWriteNonceService {

  public static final String HEADER = "X-Mouse-Video-Nonce";
  private static final String HMAC = "HmacSHA256";

  private final byte[] secret;
  private final long ttlSeconds;
  private final Clock clock;

  @Autowired
  public MouseVideoWriteNonceService(MouseVideoProperties properties) {
    this(properties, Clock.systemUTC());
  }

  MouseVideoWriteNonceService(MouseVideoProperties properties, Clock clock) {
    this.secret = secret(properties.getWriteNonceSecret());
    this.ttlSeconds = Math.max(30L, properties.getWriteNonceTtlSeconds());
    this.clock = clock;
  }

  public record Issued(String nonce, String expiresAt) {}

  public Issued issue(String owner) {
    long issuedAt = Instant.now(clock).getEpochSecond();
    String payload = owner + "|" + issuedAt;
    String signature = sign(payload);
    return new Issued(
        issuedAt + "." + signature, Instant.ofEpochSecond(issuedAt + ttlSeconds).toString());
  }

  public void verify(String owner, String nonce) {
    if (nonce == null || nonce.isBlank()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_FORBIDDEN, "A server-issued write nonce is required");
    }
    int dot = nonce.indexOf('.');
    if (dot <= 0 || dot == nonce.length() - 1) {
      throw new MouseVideoException(
          MouseVideoException.CODE_FORBIDDEN, "The write nonce is malformed");
    }
    long issuedAt;
    try {
      issuedAt = Long.parseLong(nonce.substring(0, dot));
    } catch (NumberFormatException invalid) {
      throw new MouseVideoException(
          MouseVideoException.CODE_FORBIDDEN, "The write nonce is malformed");
    }
    long now = Instant.now(clock).getEpochSecond();
    if (issuedAt > now + 30L || now - issuedAt > ttlSeconds) {
      throw new MouseVideoException(
          MouseVideoException.CODE_FORBIDDEN, "The write nonce has expired; reload the page");
    }
    String expected = sign(owner + "|" + issuedAt);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        nonce.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
      throw new MouseVideoException(
          MouseVideoException.CODE_FORBIDDEN, "The write nonce is not valid for this owner");
    }
  }

  private String sign(String payload) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(secret, HMAC));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("HMAC-SHA256 is always available", impossible);
    }
  }

  private static byte[] secret(String configured) {
    if (configured != null && configured.length() >= 32) {
      return configured.getBytes(StandardCharsets.UTF_8);
    }
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    return random;
  }
}
