package com.chromascape.web.aiplayer.routine.source;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded content-addressed cache that accepts bytes only after hash verification. */
@Component
public class VerifiedSourceContentCache {
  private final long maxBytes;
  private final Map<String, byte[]> values = new LinkedHashMap<>(16, 0.75f, true);
  private long currentBytes;

  public VerifiedSourceContentCache(
      @Value("${chromascape.source.cache.max-bytes:52428800}") long maxBytes) {
    this.maxBytes = maxBytes <= 0 ? 52_428_800L : maxBytes;
  }

  public synchronized void put(String contentHash, byte[] content) {
    if (!SourceManifestContract.isHash(contentHash)
        || content == null
        || !contentHash.equals(hash(content))) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
          "source cache content does not match its declared identity");
    }
    if (content.length > maxBytes) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_UNAVAILABLE,
          "source cache object exceeds the configured bound");
    }
    byte[] previous = values.remove(contentHash);
    if (previous != null) currentBytes -= previous.length;
    values.put(contentHash, Arrays.copyOf(content, content.length));
    currentBytes += content.length;
    evict();
  }

  public synchronized Optional<byte[]> get(String contentHash) {
    byte[] value = values.get(contentHash);
    return value == null ? Optional.empty() : Optional.of(Arrays.copyOf(value, value.length));
  }

  public synchronized void remove(String contentHash) {
    byte[] removed = values.remove(contentHash);
    if (removed != null) currentBytes -= removed.length;
  }

  public synchronized long sizeBytes() {
    return currentBytes;
  }

  public synchronized int size() {
    return values.size();
  }

  private void evict() {
    var iterator = values.entrySet().iterator();
    while (currentBytes > maxBytes && iterator.hasNext()) {
      byte[] value = iterator.next().getValue();
      currentBytes -= value.length;
      iterator.remove();
    }
  }

  private static String hash(byte[] content) {
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(content);
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
    StringBuilder result = new StringBuilder("sha256:");
    for (byte value : digest) result.append(String.format("%02x", value));
    return result.toString();
  }
}
