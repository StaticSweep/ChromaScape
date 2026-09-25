package com.chromascape.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Mandatory trust boundary for authoritative Micro6 source manifests. */
@Component
@ConfigurationProperties(prefix = "chromascape.source")
public class SourceTrustProperties {
  /** X.509 SubjectPublicKeyInfo, Base64-encoded, for the trusted Ed25519 producer key. */
  private String publicKeyBase64 = "";

  /** Exact producer key identifier accepted by the verifier. */
  private String publicKeyId = "";

  /** Exact environment in which the selected ChromaScape instance is operating. */
  private String environment = "";

  public String getPublicKeyBase64() {
    return publicKeyBase64;
  }

  public void setPublicKeyBase64(String value) {
    publicKeyBase64 = normalize(value);
  }

  public String getPublicKeyId() {
    return publicKeyId;
  }

  public void setPublicKeyId(String value) {
    publicKeyId = normalize(value);
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String value) {
    environment = normalize(value);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
