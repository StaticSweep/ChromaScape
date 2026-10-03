package com.chromascape.web.debug.buildergoal.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** SHA-256 helpers for compile recovery checksums. Every persisted hash is {@code sha256:<hex>}. */
public final class RecoveryHashes {

  public static String sha256(String text) {
    return sha256(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static boolean isSha256(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  public static String shortId(String prefix, String hash) {
    String hex = hash == null ? "" : hash.replaceFirst("^sha256:", "");
    return prefix + "_" + hex.substring(0, Math.min(24, hex.length()));
  }

  public static String newId(String prefix) {
    return prefix + "_" + java.util.UUID.randomUUID();
  }

  private RecoveryHashes() {}
}
