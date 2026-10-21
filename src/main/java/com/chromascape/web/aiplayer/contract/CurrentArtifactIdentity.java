package com.chromascape.web.aiplayer.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Current packaged artifact metadata bound to independently hashed bytes, never configured pins.
 */
final class CurrentArtifactIdentity {
  private CurrentArtifactIdentity() {}

  static Map<String, Object> observe(Path artifact) {
    try {
      if (artifact == null || !Files.isRegularFile(artifact)) return Map.of();
      try (JarFile jar = new JarFile(artifact.toFile());
          var input = Files.newInputStream(artifact)) {
        if (jar.getManifest() == null) return Map.of();
        var attributes = jar.getManifest().getMainAttributes();
        String build = attributes.getValue("SC663-Build-Id");
        String commit = attributes.getValue("SC663-Commit-Id");
        if (build == null || build.isBlank() || commit == null || !commit.matches("[0-9a-f]{40}"))
          return Map.of();
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
        return Map.of(
            "build_id",
            build,
            "commit_id",
            commit,
            "artifact_digest",
            "sha256:" + HexFormat.of().formatHex(digest.digest()));
      }
    } catch (Exception ignored) {
      return Map.of();
    }
  }
}
