package com.chromascape.web.replay.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Entry point for one isolated refusal-matrix process. The stability test starts this twice with
 * independent output directories and compares the error payload bytes, so a refusal that varies by
 * locale, timezone, hash iteration order or static cache fails loudly.
 */
public final class FreshJvmRefusalMain {

  private FreshJvmRefusalMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("usage: FreshJvmRefusalMain <output-directory>");
      System.exit(2);
    }
    Path output = Path.of(args[0]);
    Files.createDirectories(output);

    byte[] payload = FreshJvmRefusalFixture.refusalPayload();
    Path refusals = output.resolve("refusals.json");
    Files.write(refusals, payload);

    ObjectNode meta = new ObjectMapper().createObjectNode();
    meta.put("pid", ProcessHandle.current().pid());
    meta.put("javaVersion", System.getProperty("java.version"));
    meta.put("compilerVersion", ScenarioManifestV2Compiler.COMPILER_VERSION);
    meta.put("refusalCount", FreshJvmRefusalFixture.refusals().size());
    meta.put("payloadBytes", payload.length);
    meta.put("payloadSha256", sha256Hex(payload));
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("result.json").toFile(), meta);

    System.out.println(meta.path("payloadSha256").asText());
  }

  private static String sha256Hex(byte[] input) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
    StringBuilder builder = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      builder.append(Character.forDigit((value >> 4) & 0xF, 16));
      builder.append(Character.forDigit(value & 0xF, 16));
    }
    return builder.toString();
  }
}
