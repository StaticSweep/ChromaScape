package com.chromascape.web.aiplayer.routine.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Ed25519 verifier for the producer-signed canonical manifest bytes. */
public final class SourceManifestSignatureVerifier {
  private final PublicKey publicKey;
  private final String expectedKeyId;

  public SourceManifestSignatureVerifier(PublicKey publicKey, String expectedKeyId) {
    if (publicKey == null || expectedKeyId == null || expectedKeyId.isBlank()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source manifest trust key is not configured");
    }
    this.publicKey = publicKey;
    this.expectedKeyId = expectedKeyId.trim();
  }

  public static SourceManifestSignatureVerifier fromX509Base64(
      String publicKeyBase64, String expectedKeyId) {
    if (publicKeyBase64 == null
        || publicKeyBase64.isBlank()
        || expectedKeyId == null
        || expectedKeyId.isBlank()) {
      return null;
    }
    try {
      PublicKey key =
          KeyFactory.getInstance("Ed25519")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
      return new SourceManifestSignatureVerifier(key, expectedKeyId);
    } catch (Exception exception) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "configured source manifest verification key is invalid",
          exception);
    }
  }

  public boolean verify(JsonNode manifest) {
    if (publicKey == null) return false;
    JsonNode signature = manifest == null ? null : manifest.get("signature");
    if (signature == null || !signature.isObject()) return false;
    String algorithm = text(signature, "algorithm");
    String keyId = text(signature, "key_id");
    String encoded = text(signature, "value");
    if (!"Ed25519".equals(algorithm) || encoded == null || encoded.isBlank()) return false;
    if (!expectedKeyId.equals(keyId)) return false;
    try {
      byte[] value = Base64.getDecoder().decode(encoded);
      String signedHash = text(signature, "signed_bytes_hash");
      byte[] canonical = SourceManifestContract.canonicalSignatureBytes(manifest);
      if (signedHash == null || !signedHash.equals(hash(canonical))) return false;
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(canonical);
      return verifier.verify(value);
    } catch (Exception exception) {
      return false;
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static String hash(byte[] bytes) {
    byte[] digest = SourceManifestCanonicalJson.digest(bytes);
    StringBuilder result = new StringBuilder("sha256:");
    for (byte value : digest) result.append(String.format("%02x", value));
    return result.toString();
  }
}
