package com.chromascape.web.config;

import com.chromascape.web.aiplayer.routine.source.SourceManifestSignatureVerifier;
import jakarta.annotation.PostConstruct;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates SC-810 authoritative source configuration at startup.
 *
 * <p>One canonical trust configuration ({@link SourceTrustProperties}, {@code chromascape.source.*})
 * controls all manifest verification. This validator fails fast instead of letting a request
 * silently fall back to an unverified read:
 *
 * <ul>
 *   <li>requiring a signature without a trusted public key and key ID;
 *   <li>a configured key that is not valid Base64 X.509 Ed25519 (malformed input and RSA or other
 *       non-Ed25519 keys are both rejected through the runtime verifier's own decoder);
 *   <li>a blank or unsafe key ID, or an environment token that is not a safe bounded value;
 *   <li>a legacy trust value under {@code micro6.source-manifest.*} that is explicitly configured
 *       and disagrees with the canonical value (fail-closed alias check);
 *   <li>upstream Micro6 authentication required without its bearer token;
 *   <li>debugger read authentication enabled without both the metadata and content tokens.
 * </ul>
 *
 * <p>Development defaults ({@code require-signature=false}, blank keys and environments,
 * authentication disabled) always pass, so local startup is unaffected.
 */
@Component
public class SourceConfigurationValidator {
  private static final Logger logger =
      LoggerFactory.getLogger(SourceConfigurationValidator.class);

  /** A safe bounded token: letter/digit first, then letters, digits, '.', '_' or '-'. */
  private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private final SourceTrustProperties trust;

  private final boolean legacyRequireSignature;
  private final boolean legacyAllowLegacy;
  /** Explicit property presence: a primitive default can never distinguish absent from false. */
  private final boolean legacyRequireSignaturePresent;
  private final boolean legacyAllowLegacyPresent;
  private final String legacyPublicKeyBase64;
  private final String legacyPublicKeyId;
  private final String legacyEnvironment;

  private final boolean upstreamAuthRequired;
  private final String upstreamAuthToken;

  private final boolean debuggerAuthRequired;
  private final String debuggerMetadataToken;
  private final String debuggerContentToken;

  @org.springframework.beans.factory.annotation.Autowired
  public SourceConfigurationValidator(
      SourceTrustProperties trust,
      @Value("${micro6.source-manifest.require-signature:false}") boolean legacyRequireSignature,
      @Value("${micro6.source-manifest.allow-legacy:true}") boolean legacyAllowLegacy,
      @Value("${micro6.source-manifest.public-key-base64:}") String legacyPublicKeyBase64,
      @Value("${micro6.source-manifest.public-key-id:}") String legacyPublicKeyId,
      @Value("${micro6.source-manifest.environment:}") String legacyEnvironment,
      @Value("${micro6.source-manifest.auth-required:false}") boolean upstreamAuthRequired,
      @Value("${micro6.source-manifest.auth-token:}") String upstreamAuthToken,
      @Value("${chromascape.source.require-auth:false}") boolean debuggerAuthRequired,
      @Value("${chromascape.source.access.metadata-token:}") String debuggerMetadataToken,
      @Value("${chromascape.source.access.content-token:}") String debuggerContentToken,
      org.springframework.core.env.Environment environment) {
    this(trust, legacyRequireSignature, legacyAllowLegacy, legacyPublicKeyBase64,
        legacyPublicKeyId, legacyEnvironment, upstreamAuthRequired, upstreamAuthToken,
        debuggerAuthRequired, debuggerMetadataToken, debuggerContentToken,
        environment != null && environment.containsProperty(
            "micro6.source-manifest.require-signature"),
        environment != null && environment.containsProperty(
            "micro6.source-manifest.allow-legacy"));
  }

  /**
   * Test/embedded constructor without Spring environment presence detection: the boolean alias
   * values are treated as explicitly configured, which is the only way tests can express an
   * explicit disagreement without a property source.
   */
  public SourceConfigurationValidator(
      SourceTrustProperties trust,
      boolean legacyRequireSignature,
      boolean legacyAllowLegacy,
      String legacyPublicKeyBase64,
      String legacyPublicKeyId,
      String legacyEnvironment,
      boolean upstreamAuthRequired,
      String upstreamAuthToken,
      boolean debuggerAuthRequired,
      String debuggerMetadataToken,
      String debuggerContentToken) {
    this(trust, legacyRequireSignature, legacyAllowLegacy, legacyPublicKeyBase64,
        legacyPublicKeyId, legacyEnvironment, upstreamAuthRequired, upstreamAuthToken,
        debuggerAuthRequired, debuggerMetadataToken, debuggerContentToken, true, true);
  }

  /**
   * Canonical constructor with explicit legacy-property presence. Tests that only configure the
   * canonical trust bean pass {@code false} so unconfigured legacy defaults are never treated as
   * explicit disagreements; tests that exercise the four-way alias check pass {@code true}.
   */
  public SourceConfigurationValidator(
      SourceTrustProperties trust,
      boolean legacyRequireSignature,
      boolean legacyAllowLegacy,
      String legacyPublicKeyBase64,
      String legacyPublicKeyId,
      String legacyEnvironment,
      boolean upstreamAuthRequired,
      String upstreamAuthToken,
      boolean debuggerAuthRequired,
      String debuggerMetadataToken,
      String debuggerContentToken,
      boolean legacyRequireSignaturePresent,
      boolean legacyAllowLegacyPresent) {
    this.trust = trust;
    this.legacyRequireSignature = legacyRequireSignature;
    this.legacyAllowLegacy = legacyAllowLegacy;
    this.legacyRequireSignaturePresent = legacyRequireSignaturePresent;
    this.legacyAllowLegacyPresent = legacyAllowLegacyPresent;
    this.legacyPublicKeyBase64 = trimToNull(legacyPublicKeyBase64);
    this.legacyPublicKeyId = trimToNull(legacyPublicKeyId);
    this.legacyEnvironment = trimToNull(legacyEnvironment);
    this.upstreamAuthRequired = upstreamAuthRequired;
    this.upstreamAuthToken = trimToNull(upstreamAuthToken);
    this.debuggerAuthRequired = debuggerAuthRequired;
    this.debuggerMetadataToken = trimToNull(debuggerMetadataToken);
    this.debuggerContentToken = trimToNull(debuggerContentToken);
  }

  @PostConstruct
  public void validate() {
    validateCanonicalTrust();
    validateLegacyAliases();
    validateUpstreamAuthentication();
    validateDebuggerAuthentication();
    logger.info(
        "SC-810 source configuration validated (require-signature={}, allow-legacy={}, "
            + "environment={}, upstream-auth={}, debugger-auth={})",
        trust.isRequireSignature(),
        trust.isAllowLegacy(),
        trust.configuredEnvironment().isBlank() ? "unset" : "configured",
        upstreamAuthRequired,
        debuggerAuthRequired);
  }

  private void validateCanonicalTrust() {
    boolean requireSignature = trust.isRequireSignature();
    String publicKeyBase64 = trust.getPublicKeyBase64();
    String publicKeyId = trust.getPublicKeyId();
    if (requireSignature && (isBlank(publicKeyBase64) || isBlank(publicKeyId))) {
      fail("chromascape.source.require-signature=true requires both "
          + "chromascape.source.public-key-base64 and chromascape.source.public-key-id to be "
          + "configured with the trusted Micro6 signing public key");
    }
    if (!isBlank(publicKeyBase64)) {
      try {
        SourceManifestSignatureVerifier.fromX509Base64(publicKeyBase64, publicKeyId);
      } catch (RuntimeException exception) {
        fail("chromascape.source.public-key-base64 is not a valid Base64 X.509 Ed25519 "
            + "public key: " + rootMessage(exception));
      }
    }
    if (!isBlank(publicKeyId) && !SAFE_TOKEN.matcher(publicKeyId).matches()) {
      fail("chromascape.source.public-key-id='" + publicKeyId + "' is not a safe key ID token; "
          + "use only letters, digits, '.', '_' or '-' (max 64 characters)");
    }
    String environment = trust.configuredEnvironment();
    if (!environment.isEmpty() && !SAFE_TOKEN.matcher(environment).matches()) {
      fail("chromascape.source.environment='" + environment + "' is not a valid source "
          + "environment token; use only letters, digits, '.', '_' or '-'");
    }
  }

  /**
   * Legacy trust values under {@code micro6.source-manifest.*} are aliases only: services no longer
   * read them. Whenever a legacy value is explicitly configured it must agree with the canonical
   * value, otherwise the operator is running two conflicting trust boundaries and startup must
   * fail closed. All four disagreement directions are detected because presence is tracked
   * separately from the boolean value (a primitive default can never reveal an explicit false).
   */
  private void validateLegacyAliases() {
    boolean canonicalRequireSignature = trust.isRequireSignature();
    boolean canonicalAllowLegacy = trust.isAllowLegacy();
    // Direction 1: canonical signature required + explicit legacy false.
    if (legacyRequireSignaturePresent && !legacyRequireSignature && canonicalRequireSignature) {
      fail("micro6.source-manifest.require-signature=false disagrees with canonical "
          + "chromascape.source.require-signature=true; configure the canonical prefix only");
    }
    // Direction 2: canonical signature false + explicit legacy true.
    if (legacyRequireSignaturePresent && legacyRequireSignature && !canonicalRequireSignature) {
      fail("micro6.source-manifest.require-signature=true disagrees with canonical "
          + "chromascape.source.require-signature=false; configure the canonical prefix only");
    }
    // Direction 3: canonical legacy allowed + explicit legacy false.
    if (legacyAllowLegacyPresent && !legacyAllowLegacy && canonicalAllowLegacy) {
      fail("micro6.source-manifest.allow-legacy=false disagrees with canonical "
          + "chromascape.source.allow-legacy=true; configure the canonical prefix only");
    }
    // Direction 4: canonical legacy forbidden + explicit legacy true.
    if (legacyAllowLegacyPresent && legacyAllowLegacy && !canonicalAllowLegacy) {
      fail("micro6.source-manifest.allow-legacy=true disagrees with canonical "
          + "chromascape.source.allow-legacy=false; configure the canonical prefix only");
    }
    requireAliasAgreement(
        "public-key-base64", legacyPublicKeyBase64, trust.getPublicKeyBase64());
    requireAliasAgreement("public-key-id", legacyPublicKeyId, trust.getPublicKeyId());
    requireAliasAgreement(
        "environment", legacyEnvironment, trust.configuredEnvironment());
  }

  private void requireAliasAgreement(String field, String legacyValue, String canonicalValue) {
    if (legacyValue == null) {
      return;
    }
    String canonical = canonicalValue == null ? "" : canonicalValue.trim();
    if (!legacyValue.equals(canonical)) {
      fail("micro6.source-manifest." + field + "='" + legacyValue + "' disagrees with canonical "
          + "chromascape.source." + field + "='" + canonical + "'; configure the canonical "
          + "prefix only");
    }
  }

  private void validateUpstreamAuthentication() {
    if (upstreamAuthRequired && upstreamAuthToken == null) {
      fail("micro6.source-manifest.auth-required=true requires micro6.source-manifest.auth-token "
          + "to be configured");
    }
  }

  private void validateDebuggerAuthentication() {
    if (!debuggerAuthRequired) {
      return;
    }
    if (debuggerMetadataToken == null || debuggerContentToken == null) {
      fail("chromascape.source.require-auth=true requires both "
          + "chromascape.source.access.metadata-token and chromascape.source.access.content-token "
          + "to be configured");
    }
  }

  private static void fail(String error) {
    logger.error("SC-810 source configuration invalid: {}", error);
    throw new IllegalStateException(error);
  }

  private static String rootMessage(Throwable exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
