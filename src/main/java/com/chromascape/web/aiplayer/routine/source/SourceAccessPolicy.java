package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.config.SourceTrustProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Separate metadata/content authorization boundary for the debugger source read model. */
@Component
public class SourceAccessPolicy {
  private static final String ACTOR_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}";
  private static final String CORRELATION_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}";
  private final String metadataToken;
  private final String contentToken;
  private final SourceTrustProperties trust;

  public SourceAccessPolicy(
      @Value("${chromascape.source.access.metadata-token:}") String metadataToken,
      @Value("${chromascape.source.access.content-token:}") String contentToken,
      SourceTrustProperties trust) {
    this.metadataToken = normalize(metadataToken);
    this.contentToken = normalize(contentToken);
    this.trust = trust;
  }

  /**
   * Authorizes one debugger request and validates its complete audit context. Header values are
   * never used as selectors; they only establish the identity and environment of the request.
   */
  public String authorize(
      String authorization,
      Purpose purpose,
      String environment,
      String actor,
      String requestPurpose,
      String correlationId) {
    if (purpose == null
        || requestPurpose == null
        || !purpose.wireValue().equals(requestPurpose)
        || environment == null
        || trust == null
        || trust.getEnvironment().isBlank()
        || !trust.getEnvironment().equals(environment.trim())
        || actor == null
        || !actor.matches(ACTOR_PATTERN)
        || correlationId == null
        || !correlationId.matches(CORRELATION_PATTERN)) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_AUDIT_CONTEXT_INVALID,
          HttpStatus.BAD_REQUEST,
          "source audit context is invalid");
    }
    String expected = purpose == Purpose.CONTENT ? contentToken : metadataToken;
    if (expected.isBlank()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_ACCESS_DENIED,
          HttpStatus.SERVICE_UNAVAILABLE,
          "source authorization is not configured");
    }
    if (authorization == null || !authorization.startsWith("Bearer ")) deny();
    String supplied = authorization.substring("Bearer ".length()).trim();
    if (!constantTimeEquals(expected, supplied)) deny();
    return actor;
  }

  private static void deny() {
    throw new SourceManifestException(
        SourceManifestFailureCode.SOURCE_ACCESS_DENIED,
        HttpStatus.UNAUTHORIZED,
        "source access is denied");
  }

  private static boolean constantTimeEquals(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  public enum Purpose {
    METADATA("manifest-read"),
    CONTENT("file-read");

    private final String wireValue;

    Purpose(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }
  }
}
