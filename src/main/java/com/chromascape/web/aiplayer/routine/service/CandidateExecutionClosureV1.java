package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.core.io.ClassPathResource;

/**
 * SC-814 producer side of {@code candidate-execution-closure/v1}: the signed companion of a
 * certified-candidate/v2 manifest that names the headless runner, shared Fishing core and
 * dependency JARs SC-2106 executes.
 *
 * <p>The v2 manifest and its schema are unchanged, so the Go registry contract is unaffected. The
 * closure binds the manifest's candidate hash/id, certification document, JAR digest and key id,
 * and every entry of every closure JAR must be byte-identical to the same entry of the signed
 * candidate JAR. It therefore cannot introduce executable bytes the approval did not cover. The
 * Micro6 consumer ({@code CandidateExecutionClosure}) enforces the same rules.
 */
public final class CandidateExecutionClosureV1 {
  public static final String SCHEMA = "candidate-execution-closure/v1";
  public static final long MAX_CLOSURE_BYTES = 1L << 20;
  public static final long MAX_INFLATED_BYTES = 1L << 30;

  private static final String SCHEMA_RESOURCE = "contracts/candidate-execution-closure-v1.schema.json";
  private static final JsonSchema CLOSURE_SCHEMA = load();

  private CandidateExecutionClosureV1() {}

  /** A closure JAR as built from the same compiled classes as the signed candidate JAR. */
  public record ArtifactBytes(String name, byte[] bytes) {}

  /** Execution identities reported by the Micro6 build that produced the signed JAR. */
  public record Inputs(
      String taskCoreEntry,
      String runnerVersion,
      String adapterVersion,
      String protocolVersion,
      ArtifactBytes runner,
      String entrypoint,
      String coreId,
      String coreVersion,
      String coreSourceDigest,
      ArtifactBytes core,
      String coreEntryClass,
      List<ArtifactBytes> dependencies) {}

  /**
   * Builds and signs the closure for an already validated, signed v2 manifest. Every closure rule
   * is checked before signing, so an unapproved or unbound closure is never signed.
   */
  public static byte[] build(
      JsonNode signedManifest, byte[] signedJarBytes, Inputs inputs, CandidateSigner signer) {
    if (signedManifest == null || inputs == null || signer == null) {
      fail("EXECUTION_CLOSURE_INPUT_MISSING");
    }
    if (!signer.keyId().equals(text(signedManifest, "key_id"))) {
      fail("EXECUTION_CLOSURE_KEY_MISMATCH");
    }
    ObjectNode closure = JsonNodeFactory.instance.objectNode();
    closure.put("schema", SCHEMA);
    ObjectNode candidate = closure.putObject("candidate");
    candidate.put("candidate_id", text(signedManifest.path("candidate"), "candidate_id"));
    candidate.put("candidate_hash", text(signedManifest.path("candidate"), "candidate_hash"));
    closure.put("certification_document_hash", text(signedManifest, "certification_document_hash"));
    closure.put("jar_sha256", text(signedManifest, "jar_sha256"));
    closure.put("key_id", signer.keyId());
    closure.put("signature_algorithm", "Ed25519");
    closure.put("task_key", "FISHING_TRAINING");
    closure.put("task_core_entry", inputs.taskCoreEntry());
    closure.put("runner_version", inputs.runnerVersion());
    closure.put("adapter_version", inputs.adapterVersion());
    closure.put("protocol_version", inputs.protocolVersion());
    ObjectNode pkg = closure.putObject("package");
    putArtifact(pkg, inputs.runner());
    pkg.put("entrypoint", inputs.entrypoint());
    ObjectNode core = closure.putObject("core");
    core.put("id", inputs.coreId());
    core.put("version", inputs.coreVersion());
    core.put("source_digest", inputs.coreSourceDigest());
    putArtifact(core, inputs.core());
    core.put("entry_class", inputs.coreEntryClass());
    ArrayNode dependencies = closure.putArray("dependencies");
    for (ArtifactBytes dependency : inputs.dependencies()) {
      putArtifact(dependencies.addObject(), dependency);
    }
    closure.put("control_allowed", false);

    String closureHash = Sc967CanonicalJson.hash(closure);
    ObjectNode signature = closure.putObject("closure");
    signature.put("closure_hash", closureHash);
    signature.put("signature", signer.signCandidateHash(closureHash));
    byte[] bytes = Sc967CanonicalJson.canonical(closure).getBytes(StandardCharsets.UTF_8);
    // The same validation a consumer runs, with only the signature check deferred to the key below.
    validateUnsigned(bytes, signedManifest, signedJarBytes, inputs);
    return bytes;
  }

  /** Full validation of closure bytes against the signed manifest, artifacts and trusted key. */
  public static JsonNode validate(
      byte[] closureBytes,
      JsonNode signedManifest,
      byte[] signedJarBytes,
      byte[] runnerBytes,
      byte[] coreBytes,
      List<byte[]> dependencyBytes,
      PublicKey publicKey) {
    JsonNode closure = parseAndBind(closureBytes, signedManifest);
    verifySignature(closure, publicKey);
    requireArtifacts(closure, signedJarBytes, runnerBytes, coreBytes, dependencyBytes);
    return closure;
  }

  private static void validateUnsigned(
      byte[] closureBytes, JsonNode signedManifest, byte[] signedJarBytes, Inputs inputs) {
    JsonNode closure = parseAndBind(closureBytes, signedManifest);
    List<byte[]> dependencies =
        inputs.dependencies().stream().map(ArtifactBytes::bytes).toList();
    requireArtifacts(
        closure, signedJarBytes, inputs.runner().bytes(), inputs.core().bytes(), dependencies);
  }

  private static JsonNode parseAndBind(byte[] closureBytes, JsonNode signedManifest) {
    if (closureBytes == null || closureBytes.length > MAX_CLOSURE_BYTES) {
      fail("EXECUTION_CLOSURE_TOO_LARGE");
    }
    JsonNode closure;
    try {
      closure = Sc967CanonicalJson.parse(closureBytes);
    } catch (IllegalArgumentException e) {
      fail("EXECUTION_CLOSURE_INVALID");
      return null;
    }
    Set<ValidationMessage> messages = CLOSURE_SCHEMA.validate(closure);
    if (!messages.isEmpty()) {
      fail("EXECUTION_CLOSURE_SCHEMA_INVALID:" + messages.iterator().next().getMessage());
    }
    bind(closure.path("candidate"), "candidate_id", signedManifest.path("candidate"), "candidate_id");
    bind(
        closure.path("candidate"),
        "candidate_hash",
        signedManifest.path("candidate"),
        "candidate_hash");
    bind(closure, "certification_document_hash", signedManifest, "certification_document_hash");
    bind(closure, "jar_sha256", signedManifest, "jar_sha256");
    bind(closure, "key_id", signedManifest, "key_id");
    ObjectNode unsigned = (ObjectNode) closure.deepCopy();
    unsigned.remove("closure");
    if (!text(closure.path("closure"), "closure_hash").equals(Sc967CanonicalJson.hash(unsigned))) {
      fail("EXECUTION_CLOSURE_HASH_MISMATCH");
    }
    return closure;
  }

  private static void verifySignature(JsonNode closure, PublicKey publicKey) {
    if (publicKey == null) fail("EXECUTION_CLOSURE_KEY_UNAVAILABLE");
    String closureHash = text(closure.path("closure"), "closure_hash");
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(HexFormat.of().parseHex(closureHash.substring("sha256:".length())));
      if (!verifier.verify(
          Base64.getDecoder().decode(text(closure.path("closure"), "signature")))) {
        fail("EXECUTION_CLOSURE_SIGNATURE_MISMATCH");
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      fail("EXECUTION_CLOSURE_SIGNATURE_INVALID");
    }
  }

  private static void requireArtifacts(
      JsonNode closure,
      byte[] signedJarBytes,
      byte[] runnerBytes,
      byte[] coreBytes,
      List<byte[]> dependencyBytes) {
    if (signedJarBytes == null) fail("JAR_MISSING");
    if (!text(closure, "jar_sha256").equals(hash(signedJarBytes))) fail("JAR_HASH_MISMATCH");
    Map<String, String> approved = entryDigests(signedJarBytes);
    requireSubset(closure.path("package"), runnerBytes, approved, "PACKAGE");
    requireSubset(closure.path("core"), coreBytes, approved, "CORE");
    JsonNode dependencies = closure.path("dependencies");
    if (dependencyBytes == null || dependencyBytes.size() != dependencies.size()) {
      fail("EXECUTION_CLOSURE_DEPENDENCIES_INCOMPLETE");
    }
    for (int index = 0; index < dependencies.size(); index++) {
      requireSubset(dependencies.get(index), dependencyBytes.get(index), approved, "DEPENDENCY");
    }
  }

  private static void requireSubset(
      JsonNode declared, byte[] bytes, Map<String, String> approved, String kind) {
    if (bytes == null) fail(kind + "_MISSING");
    if (declared.path("size").asLong(-1) != bytes.length
        || !text(declared, "sha256").equals(hash(bytes))) {
      fail(kind + "_HASH_MISMATCH");
    }
    Map<String, String> entries = entryDigests(bytes);
    if (entries.isEmpty()) fail(kind + "_EMPTY");
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      if (!entry.getValue().equals(approved.get(entry.getKey()))) {
        fail(kind + "_ENTRY_NOT_APPROVED:" + entry.getKey());
      }
    }
  }

  /** Same entry rules as the consumer: skip directories and non-versioned META-INF metadata. */
  private static Map<String, String> entryDigests(byte[] jarBytes) {
    Map<String, String> digests = new HashMap<>();
    byte[] buffer = new byte[8192];
    long inflated = 0L;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (entry.isDirectory()
            || (name.startsWith("META-INF/") && !name.startsWith("META-INF/versions/"))) {
          continue;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int read;
        while ((read = zip.read(buffer)) != -1) {
          inflated += read;
          if (inflated > MAX_INFLATED_BYTES) fail("ARTIFACT_INFLATES_TOO_LARGE");
          digest.update(buffer, 0, read);
        }
        if (digests.put(name, "sha256:" + HexFormat.of().formatHex(digest.digest())) != null) {
          fail("ARTIFACT_DUPLICATE_ENTRY:" + name);
        }
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      fail("ARTIFACT_NOT_A_JAR");
    }
    return digests;
  }

  private static void putArtifact(ObjectNode node, ArtifactBytes artifact) {
    if (artifact == null || artifact.bytes() == null) fail("EXECUTION_CLOSURE_ARTIFACT_MISSING");
    node.put("name", artifact.name());
    node.put("sha256", hash(artifact.bytes()));
    node.put("size", (long) artifact.bytes().length);
  }

  private static void bind(JsonNode left, String leftField, JsonNode right, String rightField) {
    if (!text(left, leftField).equals(text(right, rightField))) {
      fail("EXECUTION_CLOSURE_BINDING_INVALID:" + leftField);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      fail("MISSING_OR_INVALID:" + field);
    }
    return value.textValue();
  }

  private static String hash(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(Sc967CanonicalJson.digest(bytes));
  }

  private static JsonSchema load() {
    try {
      SchemaValidatorsConfig config = new SchemaValidatorsConfig();
      config.setFailFast(false);
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      try (InputStream stream = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
        return factory.getSchema(stream, config);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unable to load " + SCHEMA_RESOURCE, e);
    }
  }

  private static void fail(String code) {
    throw new IllegalArgumentException(code);
  }
}
