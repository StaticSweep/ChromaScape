package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Provisioning state of the one reviewed cursor template the server is configured with.
 *
 * <p>The template is private media and is never committed. The operator provisions its bytes and
 * pins their SHA-256; when a pin is set, a missing file or changed content is a provisioning fault
 * that fails analysis scheduling instead of silently running without cursor suggestions or with
 * different bytes. A blank template configuration is not a fault: cursor tracking is then reported
 * as unavailable and analyses run without suggestions.
 */
public final class MouseVideoCursorTemplate {

  /** Effective provisioning state of the configured template. */
  public enum State {
    /** No template path is configured; cursor tracking is explicitly unavailable. */
    NOT_CONFIGURED,
    /** The configured bytes match the optional pinned digest and can be used. */
    AVAILABLE,
    /** A template path is configured but no regular file exists there. */
    MISSING,
    /** The configured file exists but its content no longer matches the pinned digest. */
    DIGEST_MISMATCH
  }

  /**
   * Observed template state. {@code checksum} is the content address of the bytes actually present,
   * so an operator can compare it with the pinned digest without reading the file again.
   */
  public record Status(
      State state, String path, String checksum, String expectedSha256, String problem) {

    /** True only when the configured bytes are present and match the optional pin. */
    public boolean usable() {
      return state == State.AVAILABLE;
    }
  }

  private final MouseVideoProperties properties;

  public MouseVideoCursorTemplate(MouseVideoProperties properties) {
    this.properties = properties;
  }

  /** Inspects the configured template without throwing; capabilities and diagnostics use this. */
  public Status inspect() {
    String configured = trim(properties.getVisualCursorTemplate());
    String expected = normalizeDigest(properties.getVisualCursorTemplateSha256());
    if (configured.isEmpty()) {
      return new Status(State.NOT_CONFIGURED, null, null, expected, null);
    }
    Path path = Path.of(configured);
    if (!Files.isRegularFile(path)) {
      return new Status(
          State.MISSING,
          path.toString(),
          null,
          expected,
          "Configured cursor template file does not exist");
    }
    String checksum;
    try {
      checksum = hashFile(path);
    } catch (IOException unreadable) {
      return new Status(
          State.MISSING,
          path.toString(),
          null,
          expected,
          "Configured cursor template file could not be read");
    }
    if (expected != null && !expected.equalsIgnoreCase(checksum)) {
      return new Status(
          State.DIGEST_MISMATCH,
          path.toString(),
          checksum,
          expected,
          "Configured cursor template does not match its pinned SHA-256");
    }
    return new Status(State.AVAILABLE, path.toString(), checksum, expected, null);
  }

  /**
   * Returns the content address scheduling must freeze, or {@code null} when no template is
   * configured. A configured but unusable template is a provisioning fault and fails closed.
   */
  public String requireChecksum() {
    Status status = inspect();
    return switch (status.state()) {
      case NOT_CONFIGURED -> null;
      case AVAILABLE -> "sha256:" + status.checksum();
      case MISSING -> throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "Reviewed cursor template is missing; provision the configured template pack first");
      case DIGEST_MISMATCH -> throw new MouseVideoException(
          MouseVideoException.CODE_CHECKSUM_MISMATCH,
          "Reviewed cursor template does not match its pinned SHA-256; re-provision the"
              + " reviewed template pack");
    };
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String normalizeDigest(String value) {
    String trimmed = trim(value);
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.regionMatches(true, 0, "sha256:", 0, 7) ? trimmed.substring(7) : trimmed;
  }

  private static String hashFile(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 is unavailable", missing);
    }
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read > 0) {
          digest.update(buffer, 0, read);
        }
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
