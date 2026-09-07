package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SC-967 certified-candidate/v2 contract: consumes the committed shared vectors at {@code
 * contracts/sc967/ed25519-vectors.json} (canonical manifest hash, candidate id derivation, Ed25519
 * signature) so Java, Go, and Micro6 agree on exactly the same manifest/signature/hash. The
 * dedicated {@link Sc967CanonicalJson} is the production canonical hasher. Every positive vector
 * must pass; every negative vector must be rejected through the production v2 schema.
 */
class PatchCandidateManifestV2Test {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static JsonSchema v2Schema;

  @BeforeAll
  static void loadSchema() throws Exception {
    // Load the shared strict certified-candidate/v2 schema from the committed contracts checkout.
    Path schemaPath = vectorsPath().getParent().resolve("certified-candidate-v2.schema.json");
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    SchemaValidatorsConfig config = new SchemaValidatorsConfig();
    config.setFailFast(false);
    v2Schema = factory.getSchema(MAPPER.readTree(Files.readAllBytes(schemaPath)), config);
  }

  @Test
  void committedPositiveVectorsAreSelfConsistent() throws Exception {
    JsonNode doc = readVectors();
    assertEquals("certified-candidate/v2-vector/v1", doc.path("schema").asText());
    String publicKeyPem = doc.path("public_key_pem").asText();
    byte[] publicKeyDer =
        Base64.getDecoder()
            .decode(
                publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", ""));
    PublicKey publicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKeyDer));
    boolean sawPositive = false;
    for (JsonNode vector : doc.path("vectors")) {
      String name = vector.path("name").asText();
      if (!name.startsWith("valid-")) {
        continue;
      }
      sawPositive = true;
      JsonNode manifest = vector.path("manifest");
      String candidateHash = vector.path("candidate_hash").asText();
      String candidateId = vector.path("candidate_id").asText();
      String signature = vector.path("signature").asText();
      String recomputed = canonicalHash(manifest);
      assertEquals(
          candidateHash, recomputed, name + ": canonical hash must match the shared vector");
      assertEquals(
          "cand_" + candidateHash.substring(7, 31),
          candidateId,
          name + ": candidate id derivation must match");
      byte[] digest = java.util.HexFormat.of().parseHex(candidateHash.substring(7));
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(digest);
      assertTrue(
          verifier.verify(Base64.getDecoder().decode(signature)), name + ": signature must verify");
    }
    assertTrue(sawPositive, "shared vectors must include at least one positive vector");
  }

  @Test
  void committedPositiveVectorHashMatchesDedicatedHasher() throws Exception {
    JsonNode doc = readVectors();
    for (JsonNode vector : doc.path("vectors")) {
      if (!vector.path("name").asText().startsWith("valid-")) {
        continue;
      }
      String recomputed = Sc967CanonicalJson.hash(removeCandidateFields(vector.path("manifest")));
      assertEquals(
          vector.path("candidate_hash").asText(),
          recomputed,
          "Sc967CanonicalJson must agree with the committed positive vector");
    }
  }

  @Test
  void committedNegativeVectorsAreRejectedBySchemaOrHash() throws Exception {
    JsonNode doc = readVectors();
    String publicKeyPem = doc.path("public_key_pem").asText();
    PublicKey publicKey =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(
                new X509EncodedKeySpec(
                    Base64.getDecoder()
                        .decode(
                            publicKeyPem
                                .replace("-----BEGIN PUBLIC KEY-----", "")
                                .replace("-----END PUBLIC KEY-----", "")
                                .replaceAll("\\s", ""))));
    CertifiedCandidateV2Validator productionValidator =
        new CertifiedCandidateV2Validator(doc.path("key_id").asText());
    for (JsonNode vector : doc.path("vectors")) {
      String name = vector.path("name").asText();
      if (name.startsWith("valid-")) {
        continue;
      }
      ObjectNode manifest = ((ObjectNode) vector.path("manifest").deepCopy());
      ObjectNode candidate = (ObjectNode) manifest.with("candidate");
      candidate.put("candidate_id", vector.path("candidate_id").asText());
      candidate.put("candidate_hash", vector.path("candidate_hash").asText());
      candidate.put("signature", vector.path("signature").asText());
      Set<ValidationMessage> schemaMessages = v2Schema.validate(manifest);
      boolean schemaRejected = !schemaMessages.isEmpty();
      // The production validator also rejects on canonical-hash mismatch for the signed content.
      boolean hashRejected;
      try {
        hashRejected =
            !vector.path("candidate_hash").asText().isEmpty()
                && !vector
                    .path("candidate_hash")
                    .asText()
                    .equals(canonicalHash(vector.path("manifest")));
      } catch (IllegalArgumentException expected) {
        hashRejected = true;
      }
      boolean signatureRejected = false;
      try {
        productionValidator.validateSignature(manifest, publicKey);
      } catch (IllegalArgumentException expected) {
        signatureRejected = true;
      }
      boolean productionRejected = false;
      try {
        productionValidator.validate(
            MAPPER.writeValueAsBytes(manifest),
            Base64.getDecoder()
                .decode(doc.path("artifacts").path("certification").path("content").asText()),
            Base64.getDecoder().decode(doc.path("artifacts").path("jar").path("content").asText()),
            Base64.getDecoder()
                .decode(doc.path("artifacts").path("sbom").path("content").asText()));
      } catch (IllegalArgumentException expected) {
        productionRejected = true;
      }
      assertTrue(
          schemaRejected || hashRejected || signatureRejected || productionRejected,
          "negative vector "
              + name
              + " must be rejected by the production v2 schema or hash check");
    }
  }

  @Test
  void sharedCanonicalizationVectorsUseProductionPath() throws Exception {
    JsonNode doc = readVectors();
    assertTrue(
        doc.path("canonicalization_vectors").isArray(),
        "shared vectors must include canonicalization_vectors");
    for (JsonNode vector : doc.path("canonicalization_vectors")) {
      String raw = vector.path("raw").asText();
      if (!vector.path("valid").asBoolean()) {
        assertThrows(
            IllegalArgumentException.class,
            () -> Sc967CanonicalJson.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            vector.path("name").asText());
        continue;
      }
      JsonNode parsed =
          Sc967CanonicalJson.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(
          vector.path("canonical").asText(),
          Sc967CanonicalJson.canonical(parsed),
          vector.path("name").asText());
    }
  }

  private static JsonNode readVectors() throws Exception {
    Path path = vectorsPath();
    return MAPPER.readTree(Files.readAllBytes(path));
  }

  private static Path vectorsPath() {
    String resolved = System.getProperty("sc967.vectors.path");
    if (resolved == null || resolved.isBlank()) {
      resolved = System.getenv("SC967_VECTORS_PATH");
    }
    if (resolved == null || resolved.isBlank()) {
      throw new IllegalStateException(
          "Gradle did not resolve the canonical SC-967 fixture; configure -Psc967VectorsPath "
              + "or SC967_VECTORS_PATH");
    }
    Path path = Paths.get(resolved).toAbsolutePath().normalize();
    if (!Files.isRegularFile(path)) {
      throw new IllegalStateException("Resolved SC-967 fixture is not a regular file: " + path);
    }
    return path;
  }

  private static String canonicalHash(JsonNode manifest) {
    return Sc967CanonicalJson.hash(removeCandidateFields(manifest));
  }

  private static JsonNode removeCandidateFields(JsonNode tree) {
    ObjectNode copy = tree.deepCopy();
    if (copy.has("candidate") && copy.get("candidate").isObject()) {
      ObjectNode candidate = (ObjectNode) copy.get("candidate");
      candidate.remove("candidate_id");
      candidate.remove("candidate_hash");
      candidate.remove("signature");
    }
    return copy;
  }
}
