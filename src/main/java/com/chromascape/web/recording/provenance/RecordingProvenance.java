package com.chromascape.web.recording.provenance;

import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Exact executed arguments. Null is unknown, an empty object is known empty. */
public record RecordingProvenance(String taskKey, JsonNode taskArgs, String settingsHash) {
  private static final Set<String> SECRET_KEYS =
      Set.of(
          "password",
          "passwd",
          "token",
          "accesstoken",
          "refreshtoken",
          "apikey",
          "authorization",
          "cookie",
          "secret",
          "credentials",
          "sessiontoken");

  public static RecordingProvenance capture(String taskKey, JsonNode args) {
    if (taskKey != null && !taskKey.matches("[A-Z][A-Z0-9_]{0,159}")) fail("INVALID_TASK_KEY");
    if (args == null || args.isNull()) return new RecordingProvenance(taskKey, null, null);
    if (taskKey == null) fail("TASK_KEY_REQUIRED");
    if (!args.isObject()) fail("TASK_ARGS_OBJECT_REQUIRED");
    if (args.toString().getBytes(StandardCharsets.UTF_8).length > 65536)
      fail("TASK_ARGS_TOO_LARGE");
    inspect(args, 0);
    try {
      JsonNode canonical =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(Micro6CanonicalHash.canonicalJson(args));
      return new RecordingProvenance(
          taskKey, canonical, Micro6CanonicalHash.sha256WithPrefix(canonical));
    } catch (java.io.IOException e) {
      throw new ProvenanceException("INVALID_TASK_ARGS");
    }
  }

  private static void inspect(JsonNode node, int depth) {
    if (depth > 16) fail("TASK_ARGS_TOO_DEEP");
    java.util.Set<String> canonicalKeys = new java.util.HashSet<>();
    if (node.isObject())
      node.fieldNames()
          .forEachRemaining(
              key -> {
                if (!canonicalKeys.add(
                    java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFC)))
                  fail("AMBIGUOUS_TASK_ARGS");
                String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (SECRET_KEYS.contains(normalized)
                    || normalized.endsWith("password")
                    || normalized.endsWith("token")
                    || normalized.endsWith("secret")
                    || normalized.endsWith("apikey")) fail("SENSITIVE_TASK_ARGS");
              });
    if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue()))
      fail("INVALID_TASK_NUMBER");
    node.forEach(child -> inspect(child, depth + 1));
  }

  public static void requireNoSecrets(JsonNode node) {
    inspect(node, 0);
  }

  public RecordingProvenance {
    taskArgs = taskArgs == null ? null : taskArgs.deepCopy();
  }

  @Override
  public JsonNode taskArgs() {
    return taskArgs == null ? null : taskArgs.deepCopy();
  }

  private static void fail(String code) {
    throw new ProvenanceException(code);
  }
}
