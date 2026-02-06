package com.chromascape.web.integration.micro6.service;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tails a log file and reads new lines incrementally.
 *
 * <p>Tracks the byte offset in the file and detects file rotation (when the file size decreases,
 * indicating a new file has replaced the old one).
 *
 * <p>Thread-safe for single-threaded use (should be called from a single scheduled executor
 * thread).
 */
public class LogFileTailer implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(LogFileTailer.class);

  private final Path logFilePath;
  private RandomAccessFile raf;
  private long lastReadPosition = 0;
  private long lastFileSize = 0;
  private boolean notifiedMissing = false;

  public LogFileTailer(String filePath) {
    this.logFilePath = Path.of(filePath);
  }

  /**
   * Read all new lines added to the file since the last read.
   *
   * @return list of new lines (empty if no new content or file doesn't exist)
   * @throws IOException if an I/O error occurs while reading
   */
  public List<String> readNewLines() throws IOException {
    // 1. Check if file exists
    if (!Files.exists(logFilePath)) {
      if (!notifiedMissing) {
        log.warn("Log file not found: {}", logFilePath);
        notifiedMissing = true;
      }
      return Collections.emptyList();
    }
    notifiedMissing = false;

    // 2. Detect file rotation (file size < last position)
    long currentSize = Files.size(logFilePath);
    if (currentSize < lastReadPosition) {
      log.info("Log file rotation detected, resetting position");
      closeRaf();
      lastReadPosition = 0;
    }

    // 3. Open RAF if needed
    if (raf == null) {
      raf = new RandomAccessFile(logFilePath.toFile(), "r");
    }

    // 4. Seek to last position and read new lines
    raf.seek(lastReadPosition);
    List<String> lines = new ArrayList<>();
    String line;
    while ((line = raf.readLine()) != null) {
      if (!line.trim().isEmpty()) {
        lines.add(line);
      }
    }

    // 5. Update position
    lastReadPosition = raf.getFilePointer();
    lastFileSize = currentSize;

    return lines;
  }

  private void closeRaf() {
    if (raf != null) {
      try {
        raf.close();
      } catch (IOException e) {
        log.warn("Error closing RAF: {}", e.getMessage());
      }
      raf = null;
    }
  }

  @Override
  public void close() {
    closeRaf();
  }
}
