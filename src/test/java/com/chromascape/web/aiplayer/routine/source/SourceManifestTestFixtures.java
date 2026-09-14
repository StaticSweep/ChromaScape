package com.chromascape.web.aiplayer.routine.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;

/** Small deterministic manifests used by the SC-810 consumer tests. */
public final class SourceManifestTestFixtures {
  public static final String RELATIVE_PATH = "src/main/java/com/example/Entry.java";
  public static final String CONTENT =
      "package com.example;\n" + "public class Entry {\n" + "  public void run() {}\n" + "}\n";
  public static final String GENERATED_RELATIVE_PATH = "build/generated/EntryAdapter.java";
  public static final String GENERATED_CONTENT =
      "package com.example;\npublic final class EntryAdapter {}\n";
  private static final String CONTENT_HASH = hash(CONTENT.getBytes(StandardCharsets.UTF_8));
  private static final String GENERATED_CONTENT_HASH =
      hash(GENERATED_CONTENT.getBytes(StandardCharsets.UTF_8));
  public static final String FILE_ID = SourceManifestContract.fileId(RELATIVE_PATH, CONTENT_HASH);
  public static final String GENERATED_FILE_ID =
      SourceManifestContract.fileId(GENERATED_RELATIVE_PATH, GENERATED_CONTENT_HASH);
  public static final String CLASS_SYMBOL_ID =
      SourceManifestContract.symbolId(
          RELATIVE_PATH,
          "CLASS",
          "com.example.Entry",
          "class Entry",
          0,
          CONTENT.getBytes(StandardCharsets.UTF_8).length);
  private static final int METHOD_START = CONTENT.indexOf("  public void run");
  private static final int METHOD_END =
      CONTENT.indexOf("\n", CONTENT.indexOf("{}", METHOD_START)) + 1;
  public static final String METHOD_SYMBOL_ID =
      SourceManifestContract.symbolId(
          RELATIVE_PATH, "METHOD", "com.example.Entry#run", "run()", METHOD_START, METHOD_END);

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
    root.putArray("generated_mappings");
    root.putObject("entry_point")
        .put("class_name", "com.example.Entry")
        .put("file_id", FILE_ID)
        .put("source_symbol_id", CLASS_SYMBOL_ID);

    ArrayNode files = root.putArray("files");
    files
        .addObject()
        .put("file_id", FILE_ID)
        .put("relative_path", RELATIVE_PATH)
        .put("module", "example-module")
        .put("language", "JAVA")
        .put("content_hash", hash(CONTENT.getBytes(StandardCharsets.UTF_8)))
        .put("size_bytes", CONTENT.getBytes(StandardCharsets.UTF_8).length)
        .put("classification", "SOURCE")
        .put("inclusion_reason", "task entry source");

    root.putArray("closure")
        .addObject()
        .put("from_path", "src/main/java/com/example/Entry.java")
        .put("to_path", "src/main/java/com/example/Entry.java")
        .put("edge_kind", "ENTRY_POINT")
        .put("reason", "entry point");

    ArrayNode symbols = root.putArray("symbols");
    symbols
        .addObject()
        .put("source_symbol_id", CLASS_SYMBOL_ID)
        .put("file_id", FILE_ID)
        .put("kind", "CLASS")
        .put("qualified_name", "com.example.Entry")
        .put("signature", "class Entry")
        .put("start_byte", 0)
        .put("end_byte", CONTENT.getBytes(StandardCharsets.UTF_8).length)
        .put("start_line", 1)
        .put("end_line", 5)
        .put("start_column", 1)
        .put("end_column", 1);
    symbols
        .addObject()
        .put("source_symbol_id", METHOD_SYMBOL_ID)
        .put("file_id", FILE_ID)
        .put("kind", "METHOD")
        .put("qualified_name", "com.example.Entry#run")
        .put("signature", "run()")
        .put("enclosing_symbol_id", CLASS_SYMBOL_ID)
        .put("start_byte", METHOD_START)
        .put("end_byte", METHOD_END)
        .put("start_line", 3)
        .put("end_line", 4)
        .put("start_column", 1)
        .put("end_column", 1);
    root.putArray("generated_mappings");
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

  public static ObjectNode signedManifestWithGeneratedMapping(KeyPair keyPair) {
    ObjectNode manifest = manifest();
    ArrayNode files = (ArrayNode) manifest.get("files");
    files
        .addObject()
        .put("file_id", GENERATED_FILE_ID)
        .put("relative_path", GENERATED_RELATIVE_PATH)
        .put("module", "example-module")
        .put("language", "JAVA")
        .put("content_hash", GENERATED_CONTENT_HASH)
        .put("size_bytes", GENERATED_CONTENT.getBytes(StandardCharsets.UTF_8).length)
        .put("classification", "GENERATED")
        .put("inclusion_reason", "generated adapter for the task entry");
    ObjectNode generatedMapping = manifest.putArray("generated_mappings").addObject();
    generatedMapping.put("generated_file_id", GENERATED_FILE_ID);
    generatedMapping.putArray("authored_file_ids").add(FILE_ID);
    generatedMapping.put("mapping_kind", "GENERATED");
    generatedMapping.put("tool", "example-generator");
    generatedMapping.put("tool_version", "1.0");
    generatedMapping.put("description", "deterministic generated adapter");
    return resealAndSign(manifest, keyPair);
  }

  public static ObjectNode resealAndSign(ObjectNode manifest, KeyPair keyPair) {
    manifest.remove("signature");
    reseal(manifest);
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
      throw new IllegalStateException("unable to reseal signed fixture", exception);
    }
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
    byte[] digest = SourceManifestCanonicalJson.digest(bytes);
    StringBuilder value = new StringBuilder("sha256:");
    for (byte part : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", part));
    return value.toString();
  }
}
