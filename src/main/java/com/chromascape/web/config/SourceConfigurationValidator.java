package com.chromascape.web.config;

import com.chromascape.web.aiplayer.routine.source.SourceManifestSignatureVerifier;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fail-fast validation of the SC-810 source trust, transport, and debugger credentials. */
@Component
public class SourceConfigurationValidator {
  private static final Logger log = LoggerFactory.getLogger(SourceConfigurationValidator.class);
  private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private final SourceTrustProperties trust;
  private final String upstreamBaseUrl;
  private final String upstreamToken;
  private final String upstreamActor;
  private final String metadataToken;
  private final String contentToken;

  public SourceConfigurationValidator(
      SourceTrustProperties trust,
      @Value("${micro6.source.transport.base-url:}") String upstreamBaseUrl,
      @Value("${micro6.source.transport.auth-token:}") String upstreamToken,
      @Value("${micro6.source.transport.actor:chromascape-web}") String upstreamActor,
      @Value("${chromascape.source.access.metadata-token:}") String metadataToken,
      @Value("${chromascape.source.access.content-token:}") String contentToken) {
    this.trust = trust;
    this.upstreamBaseUrl = normalize(upstreamBaseUrl);
    this.upstreamToken = normalize(upstreamToken);
    this.upstreamActor = normalize(upstreamActor);
    this.metadataToken = normalize(metadataToken);
    this.contentToken = normalize(contentToken);
  }

  @PostConstruct
  public void validate() {
    if (trust == null) fail("chromascape.source trust configuration is missing");
    String publicKey = normalize(trust.getPublicKeyBase64());
    String keyId = normalize(trust.getPublicKeyId());
    String environment = normalize(trust.getEnvironment());
    requireNonBlank(publicKey, "chromascape.source.public-key-base64");
    requireNonBlank(keyId, "chromascape.source.public-key-id");
    requireNonBlank(environment, "chromascape.source.environment");
    if (!SAFE_TOKEN.matcher(keyId).matches()) {
      fail("chromascape.source.public-key-id must be a safe bounded token");
    }
    if (!SAFE_TOKEN.matcher(environment).matches()) {
      fail("chromascape.source.environment must be a safe bounded token");
    }
    try {
      if (SourceManifestSignatureVerifier.fromX509Base64(publicKey, keyId) == null) {
        fail("trusted source verification key is missing");
      }
    } catch (RuntimeException exception) {
      fail("chromascape.source.public-key-base64 must be a valid X.509 Ed25519 public key");
    }
    requireTrustedEndpoint(upstreamBaseUrl);
    requireNonBlank(upstreamToken, "micro6.source.transport.auth-token");
    if (!SAFE_TOKEN.matcher(upstreamActor).matches()) {
      fail("micro6.source.transport.actor must be a safe bounded token");
    }
    requireNonBlank(metadataToken, "chromascape.source.access.metadata-token");
    requireNonBlank(contentToken, "chromascape.source.access.content-token");
    log.info(
        "SC-810 source trust and transport configuration validated for environment {}",
        environment);
  }

  private static void requireTrustedEndpoint(String value) {
    requireNonBlank(value, "micro6.source.transport.base-url");
    try {
      URI uri = URI.create(value);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        fail("micro6.source.transport.base-url is not a trusted HTTP(S) endpoint");
      }
    } catch (RuntimeException exception) {
      fail("micro6.source.transport.base-url is not a trusted HTTP(S) endpoint");
    }
  }

  private static void requireNonBlank(String value, String property) {
    if (value == null || value.isBlank()) fail(property + " is required");
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static void fail(String message) {
    throw new IllegalStateException(message);
  }
}
