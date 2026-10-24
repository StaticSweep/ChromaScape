package com.chromascape.web.replay.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Entry point for one isolated compiler process. The fresh-JVM determinism test starts this twice
 * with independent output directories and compares the canonical manifest bytes.
 */
public final class FreshJvmCompileMain {

  private FreshJvmCompileMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("usage: FreshJvmCompileMain <output-directory>");
      System.exit(2);
    }
    Path output = Path.of(args[0]);
    Files.createDirectories(output);

    long started = System.nanoTime();
    FreshJvmCompileFixture.Result result = FreshJvmCompileFixture.compile();
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    Path manifest = output.resolve("manifest.json");
    Files.write(manifest, result.manifestBytes());

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode meta = mapper.createObjectNode();
    meta.put("pid", ProcessHandle.current().pid());
    meta.put("javaVersion", System.getProperty("java.version"));
    meta.put("compilerVersion", result.compilerVersion());
    meta.put("manifestChecksum", result.manifestChecksum());
    meta.put("publishedCurationChecksum", result.publishedCurationChecksum());
    meta.put("actionCount", result.actionCount());
    meta.put("unsupportedActionCount", result.unsupportedActionCount());
    meta.put("manifestBytes", result.manifestBytes().length);
    meta.put("manifestSha256", sha256Hex(result.manifestBytes()));
    meta.put("elapsedMs", elapsedMillis);
    mapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("result.json").toFile(), meta);

    System.out.println(result.manifestChecksum());
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
