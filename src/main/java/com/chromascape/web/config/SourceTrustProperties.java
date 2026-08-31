package com.chromascape.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The single canonical SC-810 manifest trust configuration, bound to {@code chromascape.source}.
 *
 * <p>Every manifest verification path (upstream Micro6 reads and debugger read-model checks) must
 * consume this bean; transport settings such as base URLs, bearer tokens and timeouts stay in their
 * own properties and are never mixed with manifest trust. Legacy trust values under
 * {@code micro6.source-manifest.*} are tolerated only as aliases: {@link
 * SourceConfigurationValidator} fails startup when an explicitly configured legacy value disagrees
 * with the canonical value recorded here.
 *
 * <p>Relaxed binding maps the documented environment variables directly, for example
 * {@code CHROMASCAPE_SOURCE_PUBLIC_KEY_BASE64} → {@code public-key-base64}.
 */
@Component
@ConfigurationProperties(prefix = "chromascape.source")
public class SourceTrustProperties {

  /** Require Ed25519-signed {@code task-source-manifest/v1} documents for authoritative reads. */
  private boolean requireSignature = false;

  /** Allow legacy pre-SC-810 manifests to be read as provenance UNKNOWN. */
  private boolean allowLegacy = true;

  /** X.509 SubjectPublicKeyInfo, Base64-encoded, of the trusted Micro6 Ed25519 signing key. */
  private String publicKeyBase64 = "";

  /** Operator-visible identifier of the trusted signing key (safe bounded token). */
  private String publicKeyId = "";

  /** Expected source environment; blank disables the environment identity check. */
  private String environment = "";

  public boolean isRequireSignature() {
    return requireSignature;
  }

  public void setRequireSignature(boolean requireSignature) {
    this.requireSignature = requireSignature;
  }

  public boolean isAllowLegacy() {
    return allowLegacy;
  }

  public void setAllowLegacy(boolean allowLegacy) {
    this.allowLegacy = allowLegacy;
  }

  public String getPublicKeyBase64() {
    return publicKeyBase64;
  }

  public void setPublicKeyBase64(String publicKeyBase64) {
    this.publicKeyBase64 = publicKeyBase64 == null ? "" : publicKeyBase64.trim();
  }

  public String getPublicKeyId() {
    return publicKeyId;
  }

  public void setPublicKeyId(String publicKeyId) {
    this.publicKeyId = publicKeyId == null ? "" : publicKeyId.trim();
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment == null ? "" : environment.trim();
  }

  /** Blank-safe view of the configured environment; empty string means "unset". */
  public String configuredEnvironment() {
    return environment == null ? "" : environment.trim();
  }
}
