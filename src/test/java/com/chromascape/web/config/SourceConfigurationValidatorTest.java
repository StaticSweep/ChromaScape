package com.chromascape.web.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SourceConfigurationValidatorTest {
  @Test
  void completeAuthoritativeConfigurationPasses() throws Exception {
    assertDoesNotThrow(() -> validator(validTrust()).validate());
  }

  @Test
  void publicKeyEnvironmentEndpointAndCredentialsAreMandatory() throws Exception {
    SourceTrustProperties missingKey = validTrust();
    missingKey.setPublicKeyBase64("");
    IllegalStateException keyFailure = assertThrows(IllegalStateException.class,
        () -> validator(missingKey).validate());
    assertTrue(keyFailure.getMessage().contains("public-key-base64"));

    SourceTrustProperties unsafeEnvironment = validTrust();
    unsafeEnvironment.setEnvironment("../production");
    IllegalStateException environmentFailure = assertThrows(IllegalStateException.class,
        () -> validator(unsafeEnvironment).validate());
    assertTrue(environmentFailure.getMessage().contains("environment"));

    IllegalStateException endpointFailure = assertThrows(IllegalStateException.class,
        () -> new SourceConfigurationValidator(validTrust(), "file:///checkout", "upstream",
            "chromascape-web", "metadata", "content").validate());
    assertTrue(endpointFailure.getMessage().contains("base-url"));

    IllegalStateException tokenFailure = assertThrows(IllegalStateException.class,
        () -> new SourceConfigurationValidator(validTrust(), "https://micro6.example", "",
            "chromascape-web", "metadata", "content").validate());
    assertTrue(tokenFailure.getMessage().contains("auth-token"));
  }

  @Test
  void rejectsMalformedOrNonEd25519Keys() throws Exception {
    SourceTrustProperties malformed = validTrust();
    malformed.setPublicKeyBase64("not-base64!");
    assertThrows(IllegalStateException.class, () -> validator(malformed).validate());

    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    SourceTrustProperties rsaTrust = validTrust();
    rsaTrust.setPublicKeyBase64(Base64.getEncoder().encodeToString(
        rsa.generateKeyPair().getPublic().getEncoded()));
    assertThrows(IllegalStateException.class, () -> validator(rsaTrust).validate());
  }

  @Test
  void rejectsUnsafeKeyAndActorTokens() throws Exception {
    SourceTrustProperties unsafeKeyId = validTrust();
    unsafeKeyId.setPublicKeyId("../key");
    assertThrows(IllegalStateException.class, () -> validator(unsafeKeyId).validate());

    assertThrows(IllegalStateException.class, () ->
        new SourceConfigurationValidator(validTrust(), "https://micro6.example", "upstream",
            "../actor", "metadata", "content").validate());
  }

  private static SourceTrustProperties validTrust() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    SourceTrustProperties trust = new SourceTrustProperties();
    trust.setPublicKeyBase64(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    trust.setPublicKeyId("micro6-source-v1");
    trust.setEnvironment("production-eu-1");
    return trust;
  }

  private static SourceConfigurationValidator validator(SourceTrustProperties trust)
      throws Exception {
    return new SourceConfigurationValidator(trust, "https://micro6.example", "upstream-token",
        "chromascape-web", "metadata-token", "content-token");
  }
}
