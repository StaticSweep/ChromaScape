package com.chromascape.web.integration.micro6.service;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Tracks recently processed log lines to avoid duplicates during file rotation.
 *
 * <p>Uses a hash-based approach with a fixed-size LRU cache. This is simple and fast, with a
 * negligible collision risk for typical log data.
 *
 * <p>Memory overhead: approximately 8 bytes per hash * maxSize (e.g., ~4KB for 500 lines).
 */
public class LogDeduplicator {
  private final LinkedHashSet<Integer> recentHashes;
  private final int maxSize;

  /**
   * Create a deduplicator with a fixed history size.
   *
   * @param maxSize maximum number of recent line hashes to track
   */
  public LogDeduplicator(int maxSize) {
    this.maxSize = maxSize;
    this.recentHashes = new LinkedHashSet<>(maxSize);
  }

  /**
   * Check if a line has been seen recently.
   *
   * @param line the log line to check
   * @return true if the line is a duplicate, false otherwise
   */
  public boolean isDuplicate(String line) {
    int hash = line.hashCode();
    boolean isDup = recentHashes.contains(hash);

    if (!isDup) {
      recentHashes.add(hash);

      // Evict oldest if size exceeded (LRU)
      if (recentHashes.size() > maxSize) {
        Iterator<Integer> it = recentHashes.iterator();
        it.next();
        it.remove();
      }
    }

    return isDup;
  }

  /** Clear all tracked hashes. */
  public void clear() {
    recentHashes.clear();
  }
}
