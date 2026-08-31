package com.chromascape.web.aiplayer.routine.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Separate metadata/content authorization boundary for the debugger source read model. */
@Component
public class SourceAccessPolicy {
  private final String metadataToken;
  private final String contentToken;
  private final boolean required;
  private final String actorId;

  @Autowired
  public SourceAccessPolicy(
      @Value("${chromascape.source.access.metadata-token:}") String metadataToken,
      @Value("${chromascape.source.access.content-token:}") String contentToken,
      @Value("${chromascape.source.require-auth:true}") boolean required,
      @Value("${chromascape.source.access.actor-id:debugger}") String actorId) {
    this.metadataToken = normalize(metadataToken);
    this.contentToken = normalize(contentToken);
    this.required = required;
    this.actorId = actorId == null || actorId.isBlank() ? "debugger" : actorId;
  }

  /** Test-only convenience constructor; package-private so Spring selects the @Autowired one. */
  SourceAccessPolicy(String metadataToken, String contentToken, boolean required) {
    this.metadataToken = normalize(metadataToken);
    this.contentToken = normalize(contentToken);
    this.required = required;
    this.actorId = "test";
  }

  public String authorize(String authorization, Purpose purpose) {
    String expected = purpose == Purpose.CONTENT ? contentToken : metadataToken;
    if (!required && expected.isBlank()) return actorId;
    if (expected.isBlank()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_ACCESS_DENIED,
          HttpStatus.SERVICE_UNAVAILABLE,
          "source authorization is not configured");
    }
    if (authorization == null || !authorization.startsWith("Bearer ")) deny();
    String supplied = authorization.substring("Bearer ".length()).trim();
    if (!constantTimeEquals(expected, supplied)) deny();
    return actorId;
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

  private static String normalize(String value) { return value == null ? "" : value.trim(); }

  public enum Purpose { METADATA, CONTENT }
}
