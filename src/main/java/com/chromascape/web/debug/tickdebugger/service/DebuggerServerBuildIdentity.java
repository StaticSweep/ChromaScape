package com.chromascape.web.debug.tickdebugger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Snapshot once from embedded build metadata and actual code bytes, never from a live Git HEAD. */
final class DebuggerServerBuildIdentity {
  private static final ObjectNode IDENTITY = capture();

  private DebuggerServerBuildIdentity() {}

  static ObjectNode snapshot() {
    return IDENTITY.deepCopy();
  }

  private static ObjectNode capture() {
    ObjectNode result = new ObjectMapper().createObjectNode();
    var codes = result.putArray("qualityCodes");
    Properties build = new Properties();
    try (var in =
        DebuggerServerBuildIdentity.class.getResourceAsStream("/debugger-build.properties")) {
      if (in != null) {
        build.load(in);
      }
    } catch (Exception unavailable) {
      codes.add("SERVER_BUILD_METADATA_MISSING");
    }
    result.put("version", build.getProperty("version", "unknown"));
    result.put("gitRevision", build.getProperty("gitRevision", "unknown"));
    result.put("dirty", build.getProperty("dirty", "unknown"));
    if ("unknown".equals(result.path("gitRevision").asText())) {
      codes.add("SERVER_REVISION_MISSING");
    }
    try {
      var location =
          DebuggerServerBuildIdentity.class.getProtectionDomain().getCodeSource().getLocation();
      Path source =
          "file".equals(location.getProtocol())
              ? Path.of(location.toURI())
              : new org.springframework.boot.system.ApplicationHome(
                      DebuggerServerBuildIdentity.class)
                  .getSource()
                  .toPath();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      if (Files.isRegularFile(source)) {
        update(digest, source);
        result.put("artifactKind", "JAR");
      } else if (Files.isDirectory(source)) {
        // A development class directory gets a content digest with an explicit scope, never a
        // path digest masquerading as a packaged JAR identity.
        try (var paths = Files.walk(source)) {
          for (Path file :
              paths
                  .filter(Files::isRegularFile)
                  .sorted(java.util.Comparator.comparing(p -> source.relativize(p).toString()))
                  .toList()) {
            digest.update(
                source
                    .relativize(file)
                    .toString()
                    .replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            update(digest, file);
          }
        }
        result.put("artifactKind", "APPLICATION_CLASS_DIRECTORY");
      } else {
        throw new IllegalStateException("Unknown code source");
      }
      result.put("artifactSha256", HexFormat.of().formatHex(digest.digest()));
    } catch (Exception unavailable) {
      result.putNull("artifactSha256");
      result.put("artifactKind", "UNAVAILABLE");
      codes.add("SERVER_ARTIFACT_MISSING");
    }
    return result;
  }

  private static void update(MessageDigest digest, Path source) throws Exception {
    try (var input = Files.newInputStream(source)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, count);
      }
    }
  }
}
