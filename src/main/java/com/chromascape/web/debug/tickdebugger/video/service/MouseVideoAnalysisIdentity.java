package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single authority for the output-affecting identity of a visual analysis.
 *
 * <p>The scheduler records these digests when work is admitted; the worker recomputes them
 * immediately before execution. Every setting that changes analysis output, the cursor template's
 * content address and the content hashes of the resolved executables are included, so work can only
 * be coalesced, reused or executed under an identity that still describes its output.
 */
public class MouseVideoAnalysisIdentity {

  private final MouseVideoProperties properties;
  private final ObjectMapper mapper;

  /** Creates the identity authority; callers must share the scheduler's mapper configuration. */
  public MouseVideoAnalysisIdentity(MouseVideoProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
  }

  /** Digest of the algorithm, template content and every output-affecting setting. */
  public String configDigest(String algorithm, String templatePackId, String mediaType) {
    return configDigest(algorithm, templatePackId, mediaType, cursorTemplateChecksum());
  }

  /** Digest for an explicit template content address, so scheduling hashes what it freezes. */
  public String configDigest(
      String algorithm, String templatePackId, String mediaType, String templateChecksum) {
    Map<String, Object> config = new TreeMap<>();
    config.put("algorithmId", algorithm);
    config.put("templatePackId", orEmpty(templatePackId));
    config.put("sampleFps", Math.max(1.0d, Math.min(60.0d, properties.getVisualSampleFps())));
    config.put("samplingMethod", MouseVideoSampleStride.METHOD);
    config.put("maxVisualFrames", properties.getMaxVisualFrames());
    config.put("pauseMinMillis", properties.getPauseMinMillis());
    config.put("pauseMaxDelta", properties.getPauseMaxDelta());
    config.put("cutMinDelta", properties.getCutMinDelta());
    config.put("activityMinDelta", properties.getActivityMinDelta());
    config.put("cursorTemplateConfigured", templateChecksum != null);
    config.put("cursorTemplateChecksum", orEmpty(templateChecksum));
    config.put("cursorHotspotX", properties.getVisualCursorHotspotX());
    config.put("cursorHotspotY", properties.getVisualCursorHotspotY());
    config.put("cursorMaxDelta", properties.getVisualCursorMaxDelta());
    config.put("proxyMaxHeight", properties.getProxyMaxHeight());
    config.put("proxyEncoder", orEmpty(properties.getProxyEncoder()));
    config.put("originalMediaType", orEmpty(mediaType));
    try {
      return digest(mapper.writeValueAsString(config));
    } catch (IOException failure) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Unable to compute the analysis identity");
    }
  }

  /** Digest of the toolchain pin and the content of every resolved executable. */
  public String toolchainDigest() {
    return digest(
        properties.getVisualToolchainPin()
            + "|ffmpeg="
            + executableIdentity(properties.getFfmpeg())
            + "|ffprobe="
            + executableIdentity(properties.getFfprobe())
            + "|python="
            + executableIdentity(properties.getPython()));
  }

  /** Content address of the configured cursor template, or {@code null} when none is usable. */
  public String cursorTemplateChecksum() {
    String template = properties.getVisualCursorTemplate();
    if (template == null || template.isBlank()) {
      return null;
    }
    Path path = Path.of(template);
    if (!Files.isRegularFile(path)) {
      return null;
    }
    return hashFile(path);
  }

  /**
   * Fails closed when the current configuration, template or toolchain no longer matches the
   * identity the analysis was scheduled under. Queued work never executes a configuration other
   * than the one its identity describes; the caller must schedule again under the new identity.
   */
  public void requireUnchanged(MouseVideoAnalysis analysis, String mediaType) {
    String template = cursorTemplateChecksum();
    String config =
        configDigest(analysis.getAlgorithmId(), analysis.getTemplatePackId(), mediaType, template);
    if (!config.equals(analysis.getConfigDigest())
        || !toolchainDigest().equals(analysis.getToolchainDigest())
        || !java.util.Objects.equals(template, analysis.getTemplateChecksum())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_IDENTITY_CHANGED,
          "Analysis configuration, template or toolchain changed after scheduling");
    }
  }

  /** Content hash of the resolved executable, or its unresolved configured name when absent. */
  private static String executableIdentity(String configured) {
    if (configured == null || configured.isBlank()) {
      return "";
    }
    Path resolved = resolveExecutable(configured.trim());
    if (resolved == null) {
      return "unresolved:" + configured.trim();
    }
    String hash = hashFile(resolved);
    return hash == null ? "unreadable:" + resolved : resolved + ":" + hash;
  }

  private static Path resolveExecutable(String configured) {
    Path direct = Path.of(configured);
    if (Files.isRegularFile(direct)) {
      return direct.toAbsolutePath().normalize();
    }
    if (configured.contains("/") || configured.contains("\\")) {
      return null;
    }
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null) {
      return null;
    }
    for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
      if (entry == null || entry.isBlank()) {
        continue;
      }
      Path candidate = Path.of(entry, configured);
      if (Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    return null;
  }

  /** SHA-256 of a file's contents, or {@code null} when it cannot be read within the cap. */
  static String hashFile(Path path) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long total = 0L;
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
          total += read;
          if (total > 512L * 1024 * 1024) {
            return null;
          }
          digest.update(buffer, 0, read);
        }
      }
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException failure) {
      return null;
    }
  }

  static String digest(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:"
          + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
