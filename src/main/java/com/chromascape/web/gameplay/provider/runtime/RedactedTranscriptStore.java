package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Content-addressed transcript artifacts after deterministic credential redaction. */
@Component
public final class RedactedTranscriptStore {
  private static final Pattern BEARER =
      Pattern.compile("(?i)(authorization[\\s:=\\\"]+bearer\\s+)([^\\s,\\\"}]+)");
  private static final Pattern SECRET =
      Pattern.compile(
          "(?i)(api[_-]?key|authorization|bearer|token|secret|password)([\\s:=\\\"]+)([^\\s,\\\"}]+)");
  private final ProviderRuntimeProperties properties;
  private final CanonicalGameplayJson canonicalJson;

  public RedactedTranscriptStore(
      ProviderRuntimeProperties properties, CanonicalGameplayJson canonicalJson) {
    this.properties = properties;
    this.canonicalJson = canonicalJson;
  }

  public Artifact store(String transcript) {
    String bounded = transcript == null ? "" : transcript;
    bounded = BEARER.matcher(bounded).replaceAll("$1[REDACTED]");
    bounded = SECRET.matcher(bounded).replaceAll("$1$2[REDACTED]");
    byte[] content = bounded.getBytes(StandardCharsets.UTF_8);
    // Hash the exact stored bytes so the recorded digest always matches the artifact.
    String digest = "sha256:" + HexFormat.of().formatHex(sha256(content));
    try {
      Path root = properties.getTranscriptRoot().toAbsolutePath().normalize();
      Files.createDirectories(root);
      Path target = root.resolve(digest.substring("sha256:".length()) + ".json").normalize();
      if (!target.startsWith(root)) {
        throw new IOException("Transcript path escaped root");
      }
      try {
        Files.write(target, content, StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException ignored) {
        byte[] existing = Files.readAllBytes(target);
        if (!MessageDigest.isEqual(existing, content)) {
          throw new IOException("Existing transcript artifact does not match its digest");
        }
      }
      return new Artifact(digest, content.length);
    } catch (IOException exception) {
      throw new ProviderRuntimeException(
          ProviderRuntimeModels.FailureCode.TRANSCRIPT_PERSISTENCE_FAILED.name(),
          "Unable to persist redacted provider transcript");
    }
  }

  /**
   * Verifies a transcript reference against the exact stored bytes.
   *
   * <p>The reference must be a canonical {@code sha256:<64 hex>} value naming {@code <digest>.json}
   * directly below the configured transcript root. Symbolic links and path escapes are rejected,
   * the file must be a regular file within the configured size bound, and the stored bytes are
   * re-hashed and compared with {@link MessageDigest#isEqual}. Any failure is a stable, secret-safe
   * provider-runtime exception; no transcript content ever appears in a message.
   */
  public Verification verify(String reference) {
    if (reference == null || !reference.matches("sha256:[0-9a-f]{64}")) {
      throw new ProviderRuntimeException(
          ProviderRuntimeModels.FailureCode.INVALID_EVIDENCE_REFERENCE.name(),
          "Transcript reference is not a canonical sha256 artifact reference");
    }
    Path root = properties.getTranscriptRoot().toAbsolutePath().normalize();
    Path target = root.resolve(reference.substring("sha256:".length()) + ".json").normalize();
    if (!target.startsWith(root)) {
      throw new ProviderRuntimeException(
          ProviderRuntimeModels.FailureCode.INVALID_EVIDENCE_REFERENCE.name(),
          "Transcript reference escapes the transcript root");
    }
    if (!Files.isRegularFile(target)) {
      throw new ProviderRuntimeException(
          ProviderRuntimeModels.FailureCode.TRANSCRIPT_ARTIFACT_MISSING.name(),
          "Transcript artifact does not exist");
    }
    try {
      // A symlink inside the root may point outside it; the real path must remain below the real
      // root or the artifact is untrusted.
      Path realRoot = root.toRealPath();
      Path realTarget = target.toRealPath();
      if (!realTarget.startsWith(realRoot)) {
        throw new ProviderRuntimeException(
            ProviderRuntimeModels.FailureCode.INVALID_EVIDENCE_REFERENCE.name(),
            "Transcript artifact escapes the transcript root");
      }
      long size = Files.size(realTarget);
      if (size > properties.getMaxTranscriptBytes()) {
        throw new ProviderRuntimeException(
            ProviderRuntimeModels.FailureCode.TRANSCRIPT_ARTIFACT_TOO_LARGE.name(),
            "Transcript artifact exceeds the configured size bound");
      }
      byte[] content = Files.readAllBytes(realTarget);
      String actual = "sha256:" + HexFormat.of().formatHex(sha256(content));
      if (!MessageDigest.isEqual(
          actual.getBytes(StandardCharsets.UTF_8), reference.getBytes(StandardCharsets.UTF_8))) {
        throw new ProviderRuntimeException(
            ProviderRuntimeModels.FailureCode.TRANSCRIPT_DIGEST_MISMATCH.name(),
            "Transcript artifact bytes do not match the recorded digest");
      }
      return new Verification(actual, content.length);
    } catch (ProviderRuntimeException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new ProviderRuntimeException(
          ProviderRuntimeModels.FailureCode.TRANSCRIPT_ARTIFACT_UNREADABLE.name(),
          "Transcript artifact could not be verified");
    }
  }

  public record Artifact(String reference, long byteLength) {}

  /** Verified transcript evidence: the digest of the exact stored bytes and their length. */
  public record Verification(String digest, long byteLength) {}

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for transcript digests", exception);
    }
  }
}
