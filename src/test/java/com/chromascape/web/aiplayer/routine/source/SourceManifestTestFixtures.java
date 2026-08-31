package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;

/** Small deterministic manifests used by the SC-810 consumer tests. */
public final class SourceManifestTestFixtures {
  public static final String FILE_ID = "file_0123456789abcdef";
  public static final String CLASS_SYMBOL_ID = "sym_0123456789abcdef";
  public static final String METHOD_SYMBOL_ID = "sym_fedcba9876543210";
  public static final String CONTENT =
      "package com.example;\n"
          + "public class Entry {\n"
          + "  public void run() {}\n"
          + "}\n";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SourceManifestTestFixtures() {}

  public static ObjectNode manifest() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema", SourceManifestContract.SCHEMA);
    root.put("task_key", "EXAMPLE_TASK");
    root.put("task_family", "example");
    root.put("catalog_version", "catalog-v1");
    root.put("settings_schema_version", "settings-v1");
    root.put("environment", "production");

    ObjectNode repository = root.putObject("repository");
    repository.put("repository_id", "micro6");
    repository.put("commit_id", "0123456789abcdef0123456789abcdef01234567");
    repository.put("tree_id", "fedcba9876543210fedcba9876543210fedcba98");
    repository.put("clean", true);

    ObjectNode build = root.putObject("build");
    build.put("build_id", "micro6-build-1");
    build.put("build_timestamp", "2026-08-28T10:15:30Z");
    build.put("artifact_digest", hash("artifact".getBytes(StandardCharsets.UTF_8)));

    root.put("parser_version", "parser-v1");
    root.put("indexer_version", "indexer-v1");
    root.put("status", "AVAILABLE");
    root.put("retention_class", "DEBUG");
    root.put("confidentiality", "INTERNAL");
    root.putObject("entry_point")
        .put("class_name", "com.example.Entry")
        .put("file_id", FILE_ID)
        .put("source_symbol_id", CLASS_SYMBOL_ID);

    ArrayNode files = root.putArray("files");
    files.addObject()
        .put("file_id", FILE_ID)
        .put("relative_path", "src/main/java/com/example/Entry.java")
        .put("module", "example-module")
        .put("language", "JAVA")
        .put("content_hash", hash(CONTENT.getBytes(StandardCharsets.UTF_8)))
        .put("size_bytes", CONTENT.getBytes(StandardCharsets.UTF_8).length)
        .put("classification", "SOURCE")
        .put("inclusion_reason", "task entry source");

    root.putArray("closure").addObject()
        .put("from_path", "src/main/java/com/example/Entry.java")
        .put("to_path", "src/main/java/com/example/Entry.java")
        .put("edge_kind", "ENTRY_POINT")
        .put("reason", "entry point");

    int methodStart = CONTENT.indexOf("  public void run");
    int methodEnd = CONTENT.indexOf("\n", CONTENT.indexOf("{}", methodStart)) + 1;
    ArrayNode symbols = root.putArray("symbols");
    symbols.addObject()
        .put("source_symbol_id", CLASS_SYMBOL_ID)
        .put("file_id", FILE_ID)
        .put("kind", "CLASS")
        .put("qualified_name", "com.example.Entry")
        .put("signature", "class Entry")
        .put("start_byte", 0)
        .put("end_byte", CONTENT.getBytes(StandardCharsets.UTF_8).length)
        .put("start_line", 1)
        .put("end_line", 4)
        .put("start_column", 1)
        .put("end_column", 2);
    symbols.addObject()
        .put("source_symbol_id", METHOD_SYMBOL_ID)
        .put("file_id", FILE_ID)
        .put("kind", "METHOD")
        .put("qualified_name", "com.example.Entry#run")
        .put("signature", "run()")
        .put("enclosing_symbol_id", CLASS_SYMBOL_ID)
        .put("start_byte", methodStart)
        .put("end_byte", methodEnd)
        .put("start_line", 3)
        .put("end_line", 3)
        .put("start_column", 3)
        .put("end_column", 25);
    return reseal(root);
  }

  public static ObjectNode signedManifest(KeyPair keyPair) {
    ObjectNode manifest = manifest();
    try {
      byte[] signed = SourceManifestContract.canonicalSignatureBytes(manifest);
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(signed);
      ObjectNode signature = manifest.putObject("signature");
      signature.put("algorithm", "Ed25519");
      signature.put("key_id", "test-key");
      signature.put("value", Base64.getEncoder().encodeToString(signer.sign()));
      signature.put("signed_bytes_hash", hash(signed));
      return manifest;
    } catch (Exception exception) {
      throw new IllegalStateException("unable to create signed fixture", exception);
    }
  }

  public static ObjectNode legacyManifest() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema", SourceManifestContract.LEGACY_SCHEMA);
    root.put("task_key", "FISHING_TRAINING");
    root.put("client_build_id", "legacy-build");
    root.put("artifact_digest", "UNVERIFIED");
    ArrayNode files = root.putArray("files");
    files.addObject()
        .put("file_id", FILE_ID)
        .put("relative_path", "src/main/java/Entry.java")
        .put("content_hash", hash(CONTENT.getBytes(StandardCharsets.UTF_8)))
        .put("size_bytes", CONTENT.getBytes(StandardCharsets.UTF_8).length);
    return reseal(root);
  }

  public static ObjectNode unavailableManifest(String status) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema", SourceManifestContract.SCHEMA);
    root.put("task_key", "EXAMPLE_TASK");
    root.put("environment", "production");
    root.put("status", status);
    return reseal(root);
  }

  public static ObjectNode reseal(ObjectNode manifest) {
    manifest.remove("source_manifest_hash");
    manifest.remove("source_snapshot_id");
    String hash = hash(SourceManifestContract.canonicalHashBytes(manifest));
    manifest.put("source_manifest_hash", hash);
    manifest.put("source_snapshot_id", SourceManifestContract.snapshotForHash(hash));
    return manifest;
  }

  public static String hash(byte[] bytes) {
    byte[] digest = CanonicalJsonHasher.digest(bytes);
    StringBuilder value = new StringBuilder("sha256:");
    for (byte part : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", part));
    return value.toString();
  }
}
