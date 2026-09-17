package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Production validator for the signed SC-967 candidate documents.
 *
 * <p>The JSON schemas enforce the complete nested shape. The checks in this class enforce the parts
 * JSON Schema cannot express: the candidate hash/id derivation, certification bindings and
 * byte-level artifact evidence. Callers are expected to invoke this before signing, before a bundle
 * is staged, and again immediately before registry I/O.
 */
@Component
public final class CertifiedCandidateV2Validator {
  public static final long MAX_MANIFEST_BYTES = 1L << 20;
  public static final long MAX_ARTIFACT_BYTES = 128L << 20;
  public static final long MAX_SBOM_BYTES = 8L << 20;

  private static final String MANIFEST_SCHEMA = "contracts/certified-candidate-v2.schema.json";
  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchema manifestSchema;
  private final CandidateCertificationV1Validator certificationValidator;

  @Value("${leeuwbot.signing.public-key-path:}")
  private String configuredPublicKeyPath = "";

  @Value("${leeuwbot.signing.key-id:}")
  private String configuredKeyId = "";

  public CertifiedCandidateV2Validator() {
    this.manifestSchema = load(MANIFEST_SCHEMA);
    this.certificationValidator = new CandidateCertificationV1Validator();
  }

  /** Package-visible constructor for contract tests that provide an explicit trusted key id. */
  CertifiedCandidateV2Validator(String expectedKeyId) {
    this();
    this.configuredKeyId = expectedKeyId == null ? "" : expectedKeyId;
  }

  /** Validates a complete staged candidate and returns its parsed manifest. */
  public JsonNode validate(
      byte[] manifestBytes, byte[] certificationBytes, byte[] jarBytes, byte[] sbomBytes) {
    requireSize(manifestBytes, MAX_MANIFEST_BYTES, "MANIFEST_TOO_LARGE");
    requireSize(
        certificationBytes, CandidateCertificationV1Validator.MAX_BYTES, "CERTIFICATION_TOO_LARGE");
    requireSize(jarBytes, MAX_ARTIFACT_BYTES, "ARTIFACT_TOO_LARGE");
    requireSize(sbomBytes, MAX_SBOM_BYTES, "SBOM_TOO_LARGE");
    JsonNode manifest = parse(manifestBytes, "MANIFEST_INVALID");
    JsonNode certification = certificationValidator.validate(certificationBytes);
    validateSchema(manifestSchema, manifest, "MANIFEST_SCHEMA_INVALID");
    validateBindings(manifest, certification);
    if (!text(manifest, "certification_document_hash").equals(hash(certificationBytes))) {
      fail("CERTIFICATION_DOCUMENT_HASH_MISMATCH");
    }
    validateArtifact(manifest, "jar", jarBytes);
    validateArtifact(manifest, "sbom", sbomBytes);
    verifyConfiguredSignature(manifest);
    return manifest;
  }

  /**
   * Validates every unsigned manifest field and artifact/certification binding before signing. The
   * schema requires the derived candidate fields, so a deterministic syntactic signature
   * placeholder is inserted only in a private copy; the real signature is still verified by the
   * normal byte-oriented validation after signing.
   */
  public void validateBeforeSigning(
      JsonNode unsignedManifest, byte[] certificationBytes, byte[] jarBytes, byte[] sbomBytes) {
    requireSize(
        certificationBytes, CandidateCertificationV1Validator.MAX_BYTES, "CERTIFICATION_TOO_LARGE");
    requireSize(jarBytes, MAX_ARTIFACT_BYTES, "ARTIFACT_TOO_LARGE");
    requireSize(sbomBytes, MAX_SBOM_BYTES, "SBOM_TOO_LARGE");
    JsonNode certification = certificationValidator.validate(certificationBytes);
    if (unsignedManifest == null || !unsignedManifest.isObject()) fail("MANIFEST_INVALID");
    ObjectNode candidateCopy = (ObjectNode) unsignedManifest.deepCopy();
    JsonNode candidateNode = candidateCopy.get("candidate");
    if (candidateNode == null || !candidateNode.isObject()) fail("CANDIDATE_MISSING");
    ObjectNode candidate = (ObjectNode) candidateNode;
    candidate.remove("candidate_id");
    candidate.remove("candidate_hash");
    candidate.remove("signature");
    String candidateHash = Sc967CanonicalJson.hash(candidateCopy);
    candidate.put("candidate_hash", candidateHash);
    candidate.put("candidate_id", "cand_" + candidateHash.substring(7, 31));
    candidate.put("signature", "AA==");
    validateBindings(candidateCopy, certification);
    if (!text(candidateCopy, "certification_document_hash").equals(hash(certificationBytes))) {
      fail("CERTIFICATION_DOCUMENT_HASH_MISMATCH");
    }
    validateArtifact(candidateCopy, "jar", jarBytes);
    validateArtifact(candidateCopy, "sbom", sbomBytes);
  }

  /** Validates a registry response manifest when the binary artifacts are not being downloaded. */
  public JsonNode validateManifest(byte[] manifestBytes) {
    requireSize(manifestBytes, MAX_MANIFEST_BYTES, "MANIFEST_TOO_LARGE");
    JsonNode manifest = parse(manifestBytes, "MANIFEST_INVALID");
    validateSchema(manifestSchema, manifest, "MANIFEST_SCHEMA_INVALID");
    validateCandidateIdentity(manifest);
    validateManifestIdentityBindings(manifest);
    verifyConfiguredSignature(manifest);
    return manifest;
  }

  /** Validates a parsed manifest and certification pair without requiring the artifact bytes. */
  public void validateBindings(JsonNode manifest, JsonNode certification) {
    // Keep this public seam just as strict as the byte-oriented entry point.  Callers used by
    // signing/staging tests sometimes already have parsed trees; accepting a tree that has not
    // passed both authoritative schemas would make nested unknown fields bypass production
    // validation.
    validateSchema(manifestSchema, manifest, "MANIFEST_SCHEMA_INVALID");
    certificationValidator.validate(certification);
    validateCandidateIdentity(manifest);
    String certificationHash = text(manifest, "certification_document_hash");
    String candidateCertificationHash = text(manifest.path("candidate"), "certification_hash");
    if (!certificationHash.equals(candidateCertificationHash)) {
      fail("CERTIFICATION_HASH_BINDING_INVALID");
    }
    // The certification hash is over the exact persisted certification bytes. A caller that only
    // has a parsed response must use validate(...), where the original bytes are available.
    compare(certification, "baseline_fail_manifest_hash", manifest, "baseline_fail_manifest_hash");
    compare(certification, "source_snapshot_id", manifest, "source_snapshot_id");
    compare(certification, "source_manifest_hash", manifest, "source_manifest_hash");
    compare(certification, "proposal_hash", manifest, "proposal_hash");
    compare(certification, "approval_hash", manifest, "approval_hash");
    compare(certification, "patch_hash", manifest, "patch_hash");
    compare(certification, "post_patch_test_result_hash", manifest, "post_patch_test_result_hash");
    compare(certification, "jar_sha256", manifest, "jar_sha256");
    compare(certification, "sbom_sha256", manifest, "sbom_sha256");
    String clientBuildId = text(certification, "client_build_id");
    if (!text(manifest, "base_commit").equals(clientBuildId)) {
      fail("CERTIFICATION_CLIENT_BUILD_BINDING_INVALID");
    }
    JsonNode identity = manifest.path("identity");
    if (identity.has("candidate")) {
      fail("IDENTITY_CANDIDATE_FORBIDDEN");
    }
    // The duplicated root bindings are intentional: they make a registry response self-contained
    // while the frozen current-slice identity remains the authority.  Never accept a manifest
    // whose source or client identity silently differs from those root fields.
    compare(identity.path("source"), "source_snapshot_id", manifest, "source_snapshot_id");
    compare(identity.path("source"), "source_manifest_hash", manifest, "source_manifest_hash");
    compare(identity.path("client"), "client_build_id", manifest, "base_commit");
    String modelHash = text(certification, "model_hash");
    if (!modelHash.equals(text(identity.path("model"), "model_hash"))) {
      fail("CERTIFICATION_MODEL_BINDING_INVALID");
    }
    try {
      CurrentSliceIdentity converted = mapper.treeToValue(identity, CurrentSliceIdentity.class);
      converted.validate();
    } catch (RuntimeException | IOException e) {
      fail("IDENTITY_INVALID");
    }
  }

  /** Strictly validates the literal manifest bytes returned by the registry. */
  public JsonNode validateRegistryManifest(byte[] rawManifest) {
    return validateManifest(rawManifest);
  }

  private void validateCandidateIdentity(JsonNode manifest) {
    if (manifest == null || !manifest.isObject()) fail("MANIFEST_INVALID");
    JsonNode identity = manifest.get("identity");
    if (identity != null && identity.isObject() && identity.has("candidate")) {
      fail("IDENTITY_CANDIDATE_FORBIDDEN");
    }
    validateKeyId(manifest);
    JsonNode candidate = manifest.get("candidate");
    if (candidate == null || !candidate.isObject()) fail("CANDIDATE_MISSING");
    String candidateHash = text(candidate, "candidate_hash");
    String candidateId = text(candidate, "candidate_id");
    if (!candidateHash.matches("sha256:[0-9a-f]{64}")) fail("CANDIDATE_HASH_INVALID");
    if (!candidateId.matches("cand_[0-9a-f]{24}")) fail("CANDIDATE_ID_INVALID");
    ObjectNode unsigned = (ObjectNode) manifest.deepCopy();
    ObjectNode unsignedCandidate = (ObjectNode) unsigned.path("candidate");
    unsignedCandidate.remove("candidate_id");
    unsignedCandidate.remove("candidate_hash");
    unsignedCandidate.remove("signature");
    if (!candidateHash.equals(Sc967CanonicalJson.hash(unsigned))) {
      fail("CANDIDATE_HASH_MISMATCH");
    }
    if (!candidateId.equals("cand_" + candidateHash.substring(7, 31))) {
      fail("CANDIDATE_ID_DERIVATION_INVALID");
    }
    String signature = text(candidate, "signature");
    try {
      if (signature.isBlank()) throw new IllegalArgumentException();
      java.util.Base64.getDecoder().decode(signature);
    } catch (IllegalArgumentException e) {
      fail("CANDIDATE_SIGNATURE_INVALID");
    }
    if (!manifest.path("control_allowed").isBoolean()
        || manifest.path("control_allowed").asBoolean()) {
      fail("CONTROL_MUST_BE_DISABLED");
    }
  }

  /** Checks manifest-to-identity bindings that remain verifiable without certification bytes. */
  private void validateManifestIdentityBindings(JsonNode manifest) {
    String certificationHash = text(manifest, "certification_document_hash");
    if (!certificationHash.equals(text(manifest.path("candidate"), "certification_hash"))) {
      fail("CERTIFICATION_HASH_BINDING_INVALID");
    }
    JsonNode identity = manifest.path("identity");
    if (identity.has("candidate")) {
      fail("IDENTITY_CANDIDATE_FORBIDDEN");
    }
    compare(identity.path("source"), "source_snapshot_id", manifest, "source_snapshot_id");
    compare(identity.path("source"), "source_manifest_hash", manifest, "source_manifest_hash");
    compare(identity.path("client"), "client_build_id", manifest, "base_commit");
    try {
      mapper.treeToValue(identity, CurrentSliceIdentity.class).validate();
    } catch (RuntimeException | IOException e) {
      fail("IDENTITY_INVALID");
    }
  }

  /** Verifies the Ed25519 signature with an explicitly supplied trust key. */
  public void validateSignature(JsonNode manifest, PublicKey publicKey) {
    if (publicKey == null) fail("CANDIDATE_SIGNATURE_KEY_UNAVAILABLE");
    validateKeyId(manifest);
    JsonNode candidate = manifest == null ? null : manifest.path("candidate");
    String candidateHash = text(candidate, "candidate_hash");
    String signature = text(candidate, "signature");
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(HexFormat.of().parseHex(candidateHash.substring("sha256:".length())));
      if (!verifier.verify(Base64.getDecoder().decode(signature))) {
        fail("CANDIDATE_SIGNATURE_MISMATCH");
      }
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      fail("CANDIDATE_SIGNATURE_INVALID");
    }
  }

  private void validateKeyId(JsonNode manifest) {
    if (configuredKeyId != null
        && !configuredKeyId.isBlank()
        && !configuredKeyId.equals(text(manifest, "key_id"))) {
      fail("CANDIDATE_KEY_ID_UNTRUSTED");
    }
  }

  private void verifyConfiguredSignature(JsonNode manifest) {
    if (configuredPublicKeyPath == null || configuredPublicKeyPath.isBlank()) return;
    try {
      String pem = Files.readString(Path.of(configuredPublicKeyPath));
      pem =
          pem.replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("\\s", "");
      PublicKey key =
          KeyFactory.getInstance("Ed25519")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
      validateSignature(manifest, key);
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      fail("CANDIDATE_SIGNATURE_KEY_UNAVAILABLE");
    }
  }

  private void validateArtifact(JsonNode manifest, String kind, byte[] bytes) {
    if (bytes == null) fail(kind.toUpperCase() + "_MISSING");
    String hashField = kind + "_sha256";
    String sizeField = kind + "_size";
    if (!text(manifest, hashField).equals(hash(bytes))) fail(kind.toUpperCase() + "_HASH_MISMATCH");
    if (manifest.path(sizeField).asLong(-1) != bytes.length) {
      fail(kind.toUpperCase() + "_SIZE_MISMATCH");
    }
  }

  private static void compare(JsonNode left, String leftField, JsonNode right, String rightField) {
    if (!text(left, leftField).equals(text(right, rightField))) {
      fail("CERTIFICATION_BINDING_INVALID:" + leftField);
    }
  }

  private static JsonSchema load(String location) {
    try {
      SchemaValidatorsConfig config = new SchemaValidatorsConfig();
      config.setFailFast(false);
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      try (InputStream stream = new ClassPathResource(location).getInputStream()) {
        return factory.getSchema(stream, config);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unable to load SC-967 contract " + location, e);
    }
  }

  private static JsonNode parse(byte[] bytes, String code) {
    try {
      return Sc967CanonicalJson.parse(bytes);
    } catch (RuntimeException e) {
      fail(code);
      return null;
    }
  }

  private static void validateSchema(JsonSchema schema, JsonNode node, String code) {
    Set<ValidationMessage> messages = schema.validate(node);
    if (!messages.isEmpty()) {
      fail(code + ":" + messages.iterator().next().getMessage());
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      fail("MISSING_OR_INVALID:" + field);
    }
    return value.textValue();
  }

  private static void requireSize(byte[] bytes, long limit, String code) {
    if (bytes == null || bytes.length > limit) fail(code);
  }

  private static String hash(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(digest(bytes));
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void fail(String code) {
    throw new IllegalArgumentException(code);
  }
}
