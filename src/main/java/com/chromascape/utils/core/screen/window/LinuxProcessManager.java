package com.chromascape.utils.core.screen.window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for locating and identifying a specific native process (e.g., "RuneLite") on the
 * Linux operating system by scanning {@code /proc/[pid]/cmdline} entries.
 *
 * <p>Assumes a shared PID namespace — {@code /proc} must be visible.
 */
public class LinuxProcessManager implements ProcessManager {

  private static final Logger logger = LogManager.getLogger(LinuxProcessManager.class);
  private static final String RUNELITE_MAIN_CLASS = "net.runelite.client.RuneLite";

  /**
   * Returns the Process ID of RuneLite. Scans {@code /proc/[pid]/cmdline} entries for the RuneLite
   * main class ({@code net.runelite.client.RuneLite}).
   *
   * @return The integer process ID of RuneLite, or {@code -1} if not found
   */
  @Override
  public int getPid() {
    Path proc = Paths.get("/proc");
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(proc, "[0-9]*")) {
      for (Path pidDir : stream) {
        Path cmdlinePath = pidDir.resolve("cmdline");
        try {
          byte[] bytes = Files.readAllBytes(cmdlinePath);
          // /proc/[pid]/cmdline is null-byte delimited — replace for string matching
          String cmdline = new String(bytes, StandardCharsets.UTF_8).replace('\0', ' ').trim();
          if (cmdline.contains(RUNELITE_MAIN_CLASS)) {
            return Integer.parseInt(pidDir.getFileName().toString());
          }
        } catch (NoSuchFileException ignored) {
          // Process exited between directory listing and read — skip silently
        } catch (NumberFormatException ignored) {
          // pidDir name is not a valid integer — skip
        } catch (IOException e) {
          logger.debug("Failed to read cmdline for {}: {}", pidDir, e.getMessage());
        }
      }
    } catch (IOException e) {
      logger.error("Failed to iterate /proc: {}", e.getMessage());
    }
    return -1; // May be -1 if not found
  }
}
