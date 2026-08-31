package com.chromascape.web.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Verifies SC-810 source configuration startup validation across the full matrix required by the
 * plan: development defaults, real Ed25519 keys, malformed Base64, non-Ed25519 keys, missing key
 * IDs, unsafe environments, legacy alias disagreements and authentication/token consistency.
 */
class SourceConfigurationValidatorTest {

  /** Defaults (no signature required, blank keys/environments, auth disabled) always pass. */
  @Test
  void developmentDefaultsPass() {
    assertDoesNotThrow(() -> validator(TrustBuilder.defaults()).validate());
  }

  /** A real, correctly encoded Ed25519 X.509 key with a safe key ID passes. */
  @Test
  void validEd25519KeyPasses() throws Exception {
    SourceConfigurationValidator validator =
        validator(TrustBuilder.defaults()
            .requireSignature(true)
            .publicKeyBase64(ed25519PublicKeyBase64())
            .publicKeyId("micro6-source-v1")
            .environment("production-eu-1"));
    assertDoesNotThrow(validator::validate);
  }

  /** A configured key that is not valid Base64 must fail startup even without require-signature. */
  @Test
  void malformedBase64KeyFails() {
    SourceConfigurationValidator validator =
        validator(TrustBuilder.defaults().publicKeyBase64("not!valid@base64"));
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, validator::validate);
    assertTrue(exception.getMessage().contains("Ed25519"));
  }

  /** RSA and other non-Ed25519 keys must be rejected, not silently accepted. */
  @Test
  void nonEd25519KeyFails() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    String rsaPublicKey = Base64.getEncoder().encodeToString(rsa.generateKeyPair()
        .getPublic().getEncoded());
    SourceConfigurationValidator validator =
        validator(TrustBuilder.defaults().publicKeyBase64(rsaPublicKey));
    assertThrows(IllegalStateException.class, validator::validate);
  }

  /** require-signature=true without a public key and key ID must fail closed. */
  @Test
  void requireSignatureNeedsKeyAndKeyId() {
    IllegalStateException missingBoth =
        assertThrows(IllegalStateException.class,
            () -> validator(TrustBuilder.defaults().requireSignature(true)).validate());
    assertTrue(missingBoth.getMessage().contains("public-key-base64"));

    IllegalStateException missingKeyId =
        assertThrows(IllegalStateException.class,
            () -> validator(TrustBuilder.defaults().requireSignature(true)
                .publicKeyBase64(ed25519PublicKeyBase64())).validate());
    assertTrue(missingKeyId.getMessage().contains("public-key-id"));
  }

  /** A configured key ID must be a safe bounded token. */
  @Test
  void unsafeKeyIdFails() throws Exception {
    SourceConfigurationValidator validator =
        validator(TrustBuilder.defaults()
            .publicKeyBase64(ed25519PublicKeyBase64())
            .publicKeyId("../bad key id"));
    assertThrows(IllegalStateException.class, validator::validate);
  }

  /** Configured source environments must be safe tokens; blank stays "unset". */
  @Test
  void environmentMustBeValid() {
    assertDoesNotThrow(() ->
        validator(TrustBuilder.defaults().environment("  ")).validate());

    IllegalStateException invalid =
        assertThrows(IllegalStateException.class,
            () -> validator(TrustBuilder.defaults().environment("../prod/env")).validate());
    assertTrue(invalid.getMessage().contains("environment"));

    assertDoesNotThrow(() ->
        validator(TrustBuilder.defaults().environment("production-eu-1")).validate());
  }

  /** A legacy trust value that is explicitly configured must agree with the canonical value. */
  @Test
  void legacyAliasesMustAgreeWithCanonicalTrust() throws Exception {
    String key = ed25519PublicKeyBase64();

    // Direction 2: canonical signature false + explicit legacy true.
    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults(), true, true, null, null, null).validate()
            , "legacy require-signature=true must disagree with canonical false");

    // Direction 3: canonical legacy allowed + explicit legacy false.
    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults(), false, false, null, null, null).validate(),
        "legacy allow-legacy=false must disagree with canonical true");

    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults(), false, true, key, "micro6-source-v1", null)
            .validate(),
        "a legacy key with no canonical key must disagree");

    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults()
                .publicKeyBase64(key).publicKeyId("canonical-key"),
            false, true, key, "legacy-key", null).validate(),
        "a different legacy key ID must disagree");

    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults().environment("canonical-env"),
            false, true, null, null, "legacy-env").validate(),
        "a different legacy environment must disagree");

    // Fully matching aliases (including blanks) are accepted.
    assertDoesNotThrow(() ->
        validatorLegacy(TrustBuilder.defaults()
                .requireSignature(true)
                .publicKeyBase64(key)
                .publicKeyId("micro6-source-v1")
                .environment("production"),
            true, true, key, "micro6-source-v1", "production").validate());
  }

  /**
   * All four explicit alias disagreement directions must fail startup; the legacy values used in
   * the test constructor are by definition explicitly configured, so the validator cannot treat
   * a default as "absent" and must reject every contradiction.
   */
  @Test
  void allFourExplicitAliasDisagreementDirectionsFail() throws Exception {
    String key = ed25519PublicKeyBase64();

    // Direction 1: canonical signature required + explicit legacy false.
    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults().requireSignature(true)
                .publicKeyBase64(key).publicKeyId("micro6-source-v1"),
            false, true, key, "micro6-source-v1", null).validate(),
        "explicit legacy require-signature=false must disagree with canonical true");

    // Direction 2: canonical signature false + explicit legacy true.
    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults(), true, true, null, null, null).validate(),
        "explicit legacy require-signature=true must disagree with canonical false");

    // Direction 3: canonical legacy allowed + explicit legacy false.
    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults(), false, false, null, null, null).validate(),
        "explicit legacy allow-legacy=false must disagree with canonical true");

    // Direction 4: canonical legacy forbidden + explicit legacy true.
    assertThrows(IllegalStateException.class,
        () -> validatorLegacy(TrustBuilder.defaults().allowLegacy(false),
            false, true, null, null, null).validate(),
        "explicit legacy allow-legacy=true must disagree with canonical false");
  }

  /**
   * With no legacy property present in the Spring environment, the canonical configuration alone
   * controls behavior: a canonical-only tightening passes even though the legacy defaults would
   * have disagreed if they had been explicitly configured.
   */
  @Test
  void absentAliasesLetCanonicalConfigurationControl() throws Exception {
    org.springframework.mock.env.MockEnvironment environment =
        new org.springframework.mock.env.MockEnvironment();
    SourceConfigurationValidator validator = new SourceConfigurationValidator(
        TrustBuilder.defaults().requireSignature(false).allowLegacy(false).build(),
        false, true, null, null, null,
        false, null,
        false, null, null,
        environment);
    assertDoesNotThrow(validator::validate,
        "absent legacy properties must not be treated as explicit disagreements");

    // The same values expressed as explicit properties fail closed in all four directions.
    org.springframework.mock.env.MockEnvironment explicit =
        new org.springframework.mock.env.MockEnvironment();
    explicit.setProperty("micro6.source-manifest.require-signature", "false");
    explicit.setProperty("micro6.source-manifest.allow-legacy", "true");
    SourceConfigurationValidator direction1 = new SourceConfigurationValidator(
        TrustBuilder.defaults().requireSignature(true)
            .publicKeyBase64(ed25519PublicKeyBase64()).publicKeyId("micro6-source-v1").build(),
        false, true, null, null, null,
        false, null,
        false, null, null,
        explicit);
    assertThrows(IllegalStateException.class, direction1::validate,
        "explicit legacy require-signature=false must disagree with canonical true");
  }

  /** Upstream Micro6 authentication required without its token must fail startup. */
  @Test
  void upstreamAuthRequiresToken() {
    SourceConfigurationValidator missingToken =
        new SourceConfigurationValidator(
            TrustBuilder.defaults().build(),
            false, true, null, null, null,
            true, "  ",
            false, null, null);
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, missingToken::validate);
    assertTrue(exception.getMessage().contains("auth-token"));

    assertDoesNotThrow(() -> new SourceConfigurationValidator(
        TrustBuilder.defaults().build(),
        false, true, null, null, null,
        true, "bearer-token",
        false, null, null).validate());
  }

  /** Debugger read authentication requires both metadata and content tokens. */
  @Test
  void debuggerAuthRequiresBothTokens() {
    assertThrows(IllegalStateException.class,
        () -> new SourceConfigurationValidator(
            TrustBuilder.defaults().build(),
            false, true, null, null, null,
            false, null,
            true, "metadata-token", "  ").validate(),
        "content token missing");

    assertThrows(IllegalStateException.class,
        () -> new SourceConfigurationValidator(
            TrustBuilder.defaults().build(),
            false, true, null, null, null,
            false, null,
            true, "  ", "content-token").validate(),
        "metadata token missing");

    assertDoesNotThrow(() -> new SourceConfigurationValidator(
        TrustBuilder.defaults().build(),
        false, true, null, null, null,
        false, null,
        true, "metadata-token", "content-token").validate());
  }

  /** The full production posture passes together. */
  @Test
  void completeProductionConfigurationPasses() throws Exception {
    String key = ed25519PublicKeyBase64();
    assertDoesNotThrow(() -> new SourceConfigurationValidator(
        TrustBuilder.defaults()
            .requireSignature(true)
            .allowLegacy(false)
            .publicKeyBase64(key)
            .publicKeyId("micro6-source-v1")
            .environment("production-eu-1")
            .build(),
        true, false, key, "micro6-source-v1", "production-eu-1",
        true, "upstream-bearer-token",
        true, "metadata-token", "content-token").validate());
  }

  /** Canonical-only tests: legacy aliases are absent, so defaults can never contradict the bean. */
  private static SourceConfigurationValidator validator(TrustBuilder builder) {
    return new SourceConfigurationValidator(
        builder.build(),
        false, true, null, null, null,
        builder.upstreamAuthRequired,
        builder.upstreamAuthToken,
        builder.debuggerAuthRequired,
        builder.debuggerMetadataToken,
        builder.debuggerContentToken,
        false, false);
  }

  /** Alias tests: every passed legacy value is explicitly configured (presence=true). */
  private static SourceConfigurationValidator validatorLegacy(
      TrustBuilder builder,
      boolean legacyRequireSignature,
      boolean legacyAllowLegacy,
      String legacyPublicKeyBase64,
      String legacyPublicKeyId,
      String legacyEnvironment) {
    return new SourceConfigurationValidator(
        builder.build(),
        legacyRequireSignature,
        legacyAllowLegacy,
        legacyPublicKeyBase64,
        legacyPublicKeyId,
        legacyEnvironment,
        builder.upstreamAuthRequired,
        builder.upstreamAuthToken,
        builder.debuggerAuthRequired,
        builder.debuggerMetadataToken,
        builder.debuggerContentToken,
        true, true);
  }

  /** Fluent builder for the canonical trust bean plus auth settings under test. */
  private static final class TrustBuilder {
    private boolean requireSignature = false;
    private boolean allowLegacy = true;
    private String publicKeyBase64 = "";
    private String publicKeyId = "";
    private String environment = "";
    private boolean upstreamAuthRequired = false;
    private String upstreamAuthToken = "";
    private boolean debuggerAuthRequired = false;
    private String debuggerMetadataToken = "";
    private String debuggerContentToken = "";

    private static TrustBuilder defaults() {
      return new TrustBuilder();
    }

    private TrustBuilder requireSignature(boolean value) {
      this.requireSignature = value;
      return this;
    }

    private TrustBuilder allowLegacy(boolean value) {
      this.allowLegacy = value;
      return this;
    }

    private TrustBuilder publicKeyBase64(String value) {
      this.publicKeyBase64 = value;
      return this;
    }

    private TrustBuilder publicKeyId(String value) {
      this.publicKeyId = value;
      return this;
    }

    private TrustBuilder environment(String value) {
      this.environment = value;
      return this;
    }

    private SourceTrustProperties build() {
      SourceTrustProperties properties = new SourceTrustProperties();
      properties.setRequireSignature(requireSignature);
      properties.setAllowLegacy(allowLegacy);
      properties.setPublicKeyBase64(publicKeyBase64);
      properties.setPublicKeyId(publicKeyId);
      properties.setEnvironment(environment);
      return properties;
    }
  }

  private static String ed25519PublicKeyBase64() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair pair = generator.generateKeyPair();
    return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  }
}
