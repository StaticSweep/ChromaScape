package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    String digest = canonicalJson.digest(bounded);
    try {
      Path root = properties.getTranscriptRoot().toAbsolutePath().normalize();
      Files.createDirectories(root);
      Path target = root.resolve(digest.substring("sha256:".length()) + ".json").normalize();
      if (!target.startsWith(root)) {
        throw new IOException("Transcript path escaped root");
      }
      if (!Files.exists(target)) {
        Files.write(target, content);
      }
      return new Artifact(digest, content.length);
    } catch (IOException exception) {
      throw new ProviderRuntimeException(
          ProviderRuntimeModels.FailureCode.TRANSCRIPT_PERSISTENCE_FAILED.name(),
          "Unable to persist redacted provider transcript");
    }
  }

  public record Artifact(String reference, long byteLength) {}
}
