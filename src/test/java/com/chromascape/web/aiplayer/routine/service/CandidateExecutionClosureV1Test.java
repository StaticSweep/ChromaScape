package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SC-814/SC-2106 execution-closure contract. The committed {@code
 * contracts/sc967/execution-closure-vectors.json} is produced by the real producer code
 * (CandidateSigner, CertifiedCandidateV2Validator, CandidateExecutionClosureV1) with the shared
 * TEST-ONLY key and consumed unchanged by the Micro6 resolver tests. Regenerate with {@code
 * SC967_CLOSURE_VECTORS_WRITE=true}.
 */
class CandidateExecutionClosureV1Test {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CORE_ENTRY =
      "net.runelite.client.plugins.microbot.accountbuilder.sharedcore.fishing.FishingSharedCore";
  private static final String TASK_ENTRY =
      "net.runelite.client.plugins.microbot.accountbuilder.sharedcore.fishing.FishingCore";
  private static final String RUNNER_ENTRY = "com.example.CandidateMain";

  private static JsonNode vectors;
  private static PublicKey publicKey;
  @TempDir static Path temp;

  @BeforeAll
  static void load() throws Exception {
    vectors = MAPPER.readTree(Files.readAllBytes(vectorsPath()));
    publicKey =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(
                new X509EncodedKeySpec(
                    Base64.getDecoder()
                        .decode(
                            vectors
                                .path("public_key_pem")
                                .asText()
                                .replace("-----BEGIN PUBLIC KEY-----", "")
                                .replace("-----END PUBLIC KEY-----", "")
                                .replaceAll("\\s", ""))));
  }

  @Test
  void committedClosureVectorVerifiesAndIsReproducedByTheProducer() throws Exception {
    Path closurePath = vectorsPath().getParent().resolve("execution-closure-vectors.json");
    if ("true".equals(System.getenv("SC967_CLOSURE_VECTORS_WRITE"))) {
      Files.writeString(
          closurePath,
          MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(generate()) + "\n",
          StandardCharsets.UTF_8);
    }
    JsonNode vector = MAPPER.readTree(Files.readAllBytes(closurePath));
    assertEquals("candidate-execution-closure/v1-vector/v1", vector.path("schema").asText());
    Bundle bundle = Bundle.of(vector);

    JsonNode manifest = schemaValidSignedManifest(bundle);
    JsonNode closure =
        CandidateExecutionClosureV1.validate(
            bundle.closure,
            manifest,
            bundle.jar,
            bundle.runner,
            bundle.core,
            List.of(bundle.dependency),
            publicKey);
    assertEquals(
        vector.path("closure_hash").asText(),
        closure.path("closure").path("closure_hash").asText());

    byte[] rebuilt =
        CandidateExecutionClosureV1.build(manifest, bundle.jar, bundle.inputs(), signer());
    assertArrayEquals(bundle.closure, rebuilt, "producer closure bytes must be deterministic");
  }

  @Test
  void dependencyJarWithUnapprovedModuleInfoIsRejected() throws Exception {
    Bundle bundle =
        Bundle.of(
            MAPPER.readTree(
                Files.readAllBytes(
                    vectorsPath().getParent().resolve("execution-closure-vectors.json"))));
    JsonNode manifest = MAPPER.readTree(bundle.manifest);
    // A real Jackson 2.15.2 JAR carries module-info.class, which the signed shadow JAR excludes.
    byte[] withModuleInfo =
        jar(
            "org/example/Dependency.class", "dependency",
            "META-INF/versions/9/module-info.class", "module-info");
    IllegalArgumentException rejected =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CandidateExecutionClosureV1.build(
                    manifest, bundle.jar, bundle.inputs(bundle.core, withModuleInfo), signer()));
    assertEquals(
        "DEPENDENCY_ENTRY_NOT_APPROVED:META-INF/versions/9/module-info.class",
        rejected.getMessage());
  }

  @Test
  void dependencyJarRepackedFromTheSignedFatJarIsAccepted() throws Exception {
    Bundle bundle =
        Bundle.of(
            MAPPER.readTree(
                Files.readAllBytes(
                    vectorsPath().getParent().resolve("execution-closure-vectors.json"))));
    JsonNode manifest = MAPPER.readTree(bundle.manifest);
    byte[] repacked = subsetJar(bundle.jar, "org/example/Dependency.class");
    assertTrue(repacked.length > 0, "repacked dependency must retain the approved entry");

    byte[] closure =
        CandidateExecutionClosureV1.build(
            manifest, bundle.jar, bundle.inputs(bundle.core, repacked), signer());
    JsonNode validated =
        CandidateExecutionClosureV1.validate(
            closure,
            manifest,
            bundle.jar,
            bundle.runner,
            bundle.core,
            List.of(repacked),
            publicKey);
    assertEquals(
        "sha256:" + java.util.HexFormat.of().formatHex(Sc967CanonicalJson.digest(repacked)),
        validated.path("dependencies").get(0).path("sha256").asText());
  }

  @Test
  void unapprovedCoreBytesAreNeverSigned() throws Exception {
    Bundle bundle =
        Bundle.of(
            MAPPER.readTree(
                Files.readAllBytes(
                    vectorsPath().getParent().resolve("execution-closure-vectors.json"))));
    JsonNode manifest = MAPPER.readTree(bundle.manifest);
    byte[] rebuiltCore = jar(classPath(CORE_ENTRY), "rebuilt", classPath(TASK_ENTRY), "rebuilt");
    CandidateExecutionClosureV1.Inputs inputs = bundle.inputs(rebuiltCore);
    IllegalArgumentException rejected =
        assertThrows(
            IllegalArgumentException.class,
            () -> CandidateExecutionClosureV1.build(manifest, bundle.jar, inputs, signer()));
    assertTrue(rejected.getMessage().startsWith("CORE_ENTRY_NOT_APPROVED"), rejected.getMessage());
  }

  @Test
  void closureBuiltForAnotherCandidateIsRejected() throws Exception {
    Bundle bundle =
        Bundle.of(
            MAPPER.readTree(
                Files.readAllBytes(
                    vectorsPath().getParent().resolve("execution-closure-vectors.json"))));
    JsonNode manifest = MAPPER.readTree(bundle.manifest);
    ObjectNode foreign = (ObjectNode) manifest.deepCopy();
    ((ObjectNode) foreign.path("candidate")).put("candidate_id", "cand_000000000000000000000000");
    IllegalArgumentException rejected =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CandidateExecutionClosureV1.validate(
                    bundle.closure,
                    foreign,
                    bundle.jar,
                    bundle.runner,
                    bundle.core,
                    List.of(bundle.dependency),
                    publicKey));
    assertEquals("EXECUTION_CLOSURE_BINDING_INVALID:candidate_id", rejected.getMessage());
  }

  @Test
  void missingOrTamperedClosureJarIsRejected() throws Exception {
    Bundle bundle =
        Bundle.of(
            MAPPER.readTree(
                Files.readAllBytes(
                    vectorsPath().getParent().resolve("execution-closure-vectors.json"))));
    JsonNode manifest = MAPPER.readTree(bundle.manifest);
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CandidateExecutionClosureV1.validate(
                    bundle.closure,
                    manifest,
                    bundle.jar,
                    bundle.runner,
                    bundle.core,
                    List.of(),
                    publicKey));
    assertEquals("EXECUTION_CLOSURE_DEPENDENCIES_INCOMPLETE", missing.getMessage());

    byte[] tampered = jar("org/example/Dependency.class", "tampered");
    IllegalArgumentException changed =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CandidateExecutionClosureV1.validate(
                    bundle.closure,
                    manifest,
                    bundle.jar,
                    bundle.runner,
                    bundle.core,
                    List.of(tampered),
                    publicKey));
    assertEquals("DEPENDENCY_HASH_MISMATCH", changed.getMessage());
  }

  @Test
  void tamperedClosureOrWrongKeyIsRejected() throws Exception {
    Bundle bundle =
        Bundle.of(
            MAPPER.readTree(
                Files.readAllBytes(
                    vectorsPath().getParent().resolve("execution-closure-vectors.json"))));
    JsonNode manifest = MAPPER.readTree(bundle.manifest);
    ObjectNode tampered = (ObjectNode) MAPPER.readTree(bundle.closure);
    tampered.with("package").put("entrypoint", "com.example.OtherMain");
    IllegalArgumentException hash =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CandidateExecutionClosureV1.validate(
                    MAPPER.writeValueAsBytes(tampered),
                    manifest,
                    bundle.jar,
                    bundle.runner,
                    bundle.core,
                    List.of(bundle.dependency),
                    publicKey));
    assertEquals("EXECUTION_CLOSURE_HASH_MISMATCH", hash.getMessage());

    ObjectNode otherKey = (ObjectNode) manifest.deepCopy();
    otherKey.put("key_id", "another-key");
    assertThrows(
        IllegalArgumentException.class,
        () -> CandidateExecutionClosureV1.build(otherKey, bundle.jar, bundle.inputs(), signer()));
  }

  private static JsonNode generate() throws Exception {
    byte[] runner = jar(classPath(RUNNER_ENTRY), "runner main");
    byte[] core = jar(classPath(CORE_ENTRY), "shared core", classPath(TASK_ENTRY), "fishing task");
    byte[] dependency = jar("org/example/Dependency.class", "dependency");
    byte[] signedJar =
        jar(
            classPath(RUNNER_ENTRY),
            "runner main",
            classPath(CORE_ENTRY),
            "shared core",
            classPath(TASK_ENTRY),
            "fishing task",
            "org/example/Dependency.class",
            "dependency",
            "net/runelite/client/RuneLite.class",
            "live client only");
    byte[] sbom =
        Base64.getDecoder().decode(vectors.path("artifacts").path("sbom").path("content").asText());
    ObjectNode certification =
        (ObjectNode)
            MAPPER.readTree(
                Base64.getDecoder()
                    .decode(
                        vectors.path("artifacts").path("certification").path("content").asText()));
    certification.put("jar_sha256", sha(signedJar));
    byte[] certificationBytes =
        Sc967CanonicalJson.canonical(certification).getBytes(StandardCharsets.UTF_8);

    ObjectNode manifest = null;
    for (JsonNode candidate : vectors.path("vectors")) {
      if ("valid-canonical-manifest".equals(candidate.path("name").asText())) {
        manifest = (ObjectNode) candidate.path("manifest").deepCopy();
      }
    }
    manifest.put("jar_sha256", sha(signedJar));
    manifest.put("jar_size", (long) signedJar.length);
    manifest.put("certification_document_hash", sha(certificationBytes));
    ObjectNode candidate = manifest.with("candidate");
    candidate.removeAll();
    candidate.put("certification_hash", sha(certificationBytes));
    CandidateSigner signer = signer();
    String candidateHash = signer.unsignedHash(manifest);
    candidate.put("candidate_hash", candidateHash);
    candidate.put("candidate_id", "cand_" + candidateHash.substring(7, 31));
    candidate.put("signature", signer.signCandidateHash(candidateHash));
    byte[] manifestBytes = Sc967CanonicalJson.canonical(manifest).getBytes(StandardCharsets.UTF_8);
    Bundle bundle = new Bundle();
    bundle.manifest = manifestBytes;
    bundle.certification = certificationBytes;
    bundle.jar = signedJar;
    bundle.sbom = sbom;
    JsonNode validated = schemaValidSignedManifest(bundle);
    bundle.runner = runner;
    bundle.core = core;
    bundle.dependency = dependency;
    byte[] closure =
        CandidateExecutionClosureV1.build(validated, signedJar, bundle.inputs(), signer);

    ObjectNode out = MAPPER.createObjectNode();
    out.put("schema", "candidate-execution-closure/v1-vector/v1");
    out.put(
        "note",
        "Generated by ChromaScape CandidateExecutionClosureV1Test with the TEST-ONLY key of"
            + " ed25519-vectors.json. Not an actual SC-814 output.");
    out.put("key_id", vectors.path("key_id").asText());
    out.put("manifest", b64(manifestBytes));
    out.put("certification", b64(certificationBytes));
    out.put("jar", b64(signedJar));
    out.put("sbom", b64(sbom));
    out.put("runner", b64(runner));
    out.put("core", b64(core));
    out.put("dependency", b64(dependency));
    out.put("closure", b64(closure));
    out.put("candidate_hash", candidateHash);
    out.put("closure_hash", MAPPER.readTree(closure).path("closure").path("closure_hash").asText());
    out.put("runner_entrypoint", RUNNER_ENTRY);
    out.put("core_entry_class", CORE_ENTRY);
    out.put("task_core_entry", TASK_ENTRY);
    return out;
  }

  /**
   * Strict v2 schema, signature and artifact/certification hash checks. {@link
   * CertifiedCandidateV2Validator#validate} is not used: its CurrentSliceIdentity check requires
   * identity.source fields (repository_id, commit_id, tree_id, build_id, artifact_digest) that the
   * v2 schema forbids, so it currently rejects every schema-valid manifest (SC-2106 R1 audit G8).
   */
  private static JsonNode schemaValidSignedManifest(Bundle bundle) throws Exception {
    JsonNode manifest = Sc967CanonicalJson.parse(bundle.manifest);
    com.networknt.schema.SchemaValidatorsConfig config =
        new com.networknt.schema.SchemaValidatorsConfig();
    config.setFailFast(false);
    com.networknt.schema.JsonSchema schema;
    try (java.io.InputStream stream =
        new org.springframework.core.io.ClassPathResource(
                "contracts/certified-candidate-v2.schema.json")
            .getInputStream()) {
      schema =
          com.networknt.schema.JsonSchemaFactory.getInstance(
                  com.networknt.schema.SpecVersion.VersionFlag.V202012)
              .getSchema(stream, config);
    }
    assertEquals(java.util.Set.of(), schema.validate(manifest), "v2 schema");
    new CertifiedCandidateV2Validator().validateSignature(manifest, publicKey);
    assertEquals(sha(bundle.certification), manifest.path("certification_document_hash").asText());
    assertEquals(sha(bundle.jar), manifest.path("jar_sha256").asText());
    assertEquals(sha(bundle.sbom), manifest.path("sbom_sha256").asText());
    assertEquals(
        manifest.path("candidate").path("candidate_hash").asText(),
        new CandidateSigner(vectors.path("key_id").asText(), pem()).unsignedHash(manifest));
    return manifest;
  }

  private static Path pem() throws Exception {
    signer();
    return temp.resolve("sc967-test-only.pem");
  }

  private static CandidateSigner signer() throws Exception {
    Path pem = temp.resolve("sc967-test-only.pem");
    if (!Files.exists(pem)) {
      Files.writeString(
          pem,
          "-----BEGIN PRIVATE KEY-----\n"
              + vectors.path("private_key_pkcs8_base64_test_only").asText()
              + "\n-----END PRIVATE KEY-----\n");
    }
    return new CandidateSigner(vectors.path("key_id").asText(), pem);
  }

  private static byte[] jar(String... namesAndContent) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (JarOutputStream out = new JarOutputStream(bytes)) {
      for (int index = 0; index < namesAndContent.length; index += 2) {
        JarEntry entry = new JarEntry(namesAndContent[index]);
        entry.setTime(0L);
        out.putNextEntry(entry);
        out.write(namesAndContent[index + 1].getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  /** Repacks selected entries from an existing JAR, the way the producer builds closure JARs. */
  private static byte[] subsetJar(byte[] sourceJar, String... names) throws Exception {
    java.util.Set<String> wanted = java.util.Set.of(names);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (java.util.zip.ZipInputStream source =
            new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(sourceJar));
        JarOutputStream out = new JarOutputStream(bytes)) {
      java.util.zip.ZipEntry entry;
      while ((entry = source.getNextEntry()) != null) {
        if (entry.isDirectory() || !wanted.contains(entry.getName())) continue;
        JarEntry copy = new JarEntry(entry.getName());
        copy.setTime(0L);
        out.putNextEntry(copy);
        source.transferTo(out);
        out.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static String classPath(String className) {
    return className.replace('.', '/') + ".class";
  }

  private static String sha(byte[] bytes) {
    return "sha256:" + java.util.HexFormat.of().formatHex(Sc967CanonicalJson.digest(bytes));
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static Path vectorsPath() {
    String resolved = System.getProperty("sc967.vectors.path");
    if (resolved == null || resolved.isBlank()) {
      throw new IllegalStateException("sc967.vectors.path is required");
    }
    return Path.of(resolved);
  }

  private static final class Bundle {
    byte[] manifest;
    byte[] certification;
    byte[] jar;
    byte[] sbom;
    byte[] runner;
    byte[] core;
    byte[] dependency;
    byte[] closure;

    static Bundle of(JsonNode vector) {
      Bundle bundle = new Bundle();
      bundle.manifest = decode(vector, "manifest");
      bundle.certification = decode(vector, "certification");
      bundle.jar = decode(vector, "jar");
      bundle.sbom = decode(vector, "sbom");
      bundle.runner = decode(vector, "runner");
      bundle.core = decode(vector, "core");
      bundle.dependency = decode(vector, "dependency");
      bundle.closure = decode(vector, "closure");
      return bundle;
    }

    CandidateExecutionClosureV1.Inputs inputs() {
      return inputs(core);
    }

    CandidateExecutionClosureV1.Inputs inputs(byte[] coreBytes) {
      return inputs(coreBytes, dependency);
    }

    CandidateExecutionClosureV1.Inputs inputs(byte[] coreBytes, byte[] dependencyBytes) {
      List<CandidateExecutionClosureV1.ArtifactBytes> dependencies = new ArrayList<>();
      dependencies.add(
          new CandidateExecutionClosureV1.ArtifactBytes("dependency-0.jar", dependencyBytes));
      return new CandidateExecutionClosureV1.Inputs(
          TASK_ENTRY,
          "sc2106-runner/1",
          "xrsps-adapter/1",
          "xrsps-candidate-session/v2",
          new CandidateExecutionClosureV1.ArtifactBytes("sc2106-runner.jar", runner),
          RUNNER_ENTRY,
          "micro6-shared-fishing-core",
          "1.0.0",
          "sha256:" + "c".repeat(64),
          new CandidateExecutionClosureV1.ArtifactBytes("sc2106-shared-core.jar", coreBytes),
          CORE_ENTRY,
          dependencies);
    }

    private static byte[] decode(JsonNode vector, String field) {
      return Base64.getDecoder().decode(vector.path(field).asText());
    }
  }
}
