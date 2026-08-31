package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared wire-contract rules used by the producer-facing verifier and debugger read model. */
public final class SourceManifestContract {
  public static final String SCHEMA = "task-source-manifest/v1";
  public static final String LEGACY_SCHEMA = "micro6-source-manifest/v1";
  public static final String LEGACY_SCHEMA_V2 = "micro6-source-manifest/v2";
  public static final String SNAPSHOT_PREFIX = "srcsnap_";
  public static final String HASH_PREFIX = "sha256:";
  public static final int MAX_FILES = 10_000;
  public static final int MAX_SYMBOLS = 100_000;
  public static final int MAX_CLOSURE_EDGES = 50_000;
  public static final int MAX_MANIFEST_BYTES = 5_000_000;
  public static final long MAX_FILE_BYTES = 10_000_000L;
  public static final long MAX_CLOSURE_BYTES = 100_000_000L;
  public static final Pattern SNAPSHOT_ID = Pattern.compile("srcsnap_[0-9a-f]{24}");
  public static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
  public static final Pattern FILE_ID = Pattern.compile("file_[0-9a-f]{16,64}");
  public static final Pattern SYMBOL_ID = Pattern.compile("sym_[0-9a-f]{16,64}");
  public static final Pattern TASK_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,159}");
  public static final Pattern GIT_OBJECT_ID = Pattern.compile("[0-9a-f]{7,64}");
  public static final Pattern MODULE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}");
  private static final Set<String> HASH_FIELDS =
      Set.of("source_manifest_hash", "manifest_hash", "source_snapshot_id", "signature");

  private SourceManifestContract() {}

  public static boolean supportedSchema(String schema, boolean allowLegacy) {
    return SCHEMA.equals(schema)
        || (allowLegacy && (LEGACY_SCHEMA.equals(schema) || LEGACY_SCHEMA_V2.equals(schema)));
  }

  public static String declaredManifestHash(JsonNode manifest) {
    if (manifest == null) return null;
    JsonNode source = manifest.get("source_manifest_hash");
    if (source != null && source.isTextual()) return source.textValue();
    JsonNode generic = manifest.get("manifest_hash");
    return generic != null && generic.isTextual() ? generic.textValue() : null;
  }

  public static String declaredSnapshotId(JsonNode manifest) {
    if (manifest == null) return null;
    JsonNode value = manifest.get("source_snapshot_id");
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  /** Canonical bytes hashed to create the manifest identity. */
  public static byte[] canonicalHashBytes(JsonNode manifest) {
    ObjectNode copy = requireObject(manifest).deepCopy();
    HASH_FIELDS.forEach(copy::remove);
    return CanonicalJsonHasher.canonical(copy, null).getBytes(StandardCharsets.UTF_8);
  }

  /** Canonical bytes signed by the producer after the checksum and snapshot ID are populated. */
  public static byte[] canonicalSignatureBytes(JsonNode manifest) {
    ObjectNode copy = requireObject(manifest).deepCopy();
    copy.remove("signature");
    return CanonicalJsonHasher.canonical(copy, null).getBytes(StandardCharsets.UTF_8);
  }

  public static String snapshotForHash(String hash) {
    if (!isHash(hash)) return null;
    return SNAPSHOT_PREFIX + hash.substring(HASH_PREFIX.length(), HASH_PREFIX.length() + 24);
  }

  public static boolean isHash(String value) {
    return value != null && HASH.matcher(value).matches();
  }

  public static boolean isTaskKey(String value) {
    return value != null && TASK_KEY.matcher(value).matches();
  }

  /** Module names are logical identifiers, never paths or caller-controlled checkout roots. */
  public static boolean isSafeModule(String value) {
    return value != null && MODULE.matcher(value).matches();
  }

  public static boolean isGitObjectId(String value) {
    return value != null && GIT_OBJECT_ID.matcher(value).matches();
  }

  public static boolean isSafeRelativePath(String path) {
    if (path == null || path.isBlank() || path.indexOf('\u0000') >= 0) return false;
    if (path.startsWith("/") || path.startsWith("\\") || path.indexOf('\\') >= 0) return false;
    if (path.indexOf(':') >= 0 || path.contains("//")) return false;
    if (path.length() > 500 || path.endsWith("/") || path.indexOf('%') >= 0) return false;
    for (String segment : path.split("/", -1)) {
      if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)
          || segment.contains("\u0000")) return false;
    }
    return true;
  }

  public static ObjectNode requireObject(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, "source manifest must be an object");
    }
    return (ObjectNode) node;
  }
}
