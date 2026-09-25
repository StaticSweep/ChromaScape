package com.chromascape.utils.core.screen.window;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Read-only adapter over a procfs root (normally {@code /proc}).
 *
 * <p>The root is injectable so tests can supply a synthetic tree. Every read is non-throwing:
 * missing, unreadable or malformed entries yield an empty result so a single bad process entry can
 * never abort a discovery scan. Command lines are returned as raw bytes for in-memory matching only
 * and must never be logged, written to evidence or returned in diagnostics, because Micro6 client
 * arguments can carry account credentials.
 *
 * <p>Reads are bounded by content, not by file-size metadata: procfs pseudo-files commonly report a
 * size of zero regardless of their readable content, so every read streams at most {@link
 * #MAX_FILE_BYTES} bytes plus one probe byte and rejects oversized content.
 */
public final class ProcFs {

  /** Upper bound on enumerated pid directories so a pathological procfs cannot loop a scan. */
  static final int MAX_PID_ENTRIES = 16384;

  /** Upper bound on a single file read (bytes). */
  static final int MAX_FILE_BYTES = 65536;

  private final Path root;

  /** Creates a procfs adapter over {@code root}. */
  public ProcFs(Path root) {
    this.root = root;
  }

  /** Returns the production procfs adapter rooted at {@code /proc}. */
  public static ProcFs production() {
    return new ProcFs(Path.of("/proc"));
  }

  /**
   * Lists numeric pid directories sorted ascending and bounded at {@link #MAX_PID_ENTRIES}.
   * Non-numeric directory names are skipped; an unreadable root yields an empty list.
   */
  public List<Long> listPidDirs() {
    return listPidDirectories().pids();
  }

  /**
   * Deterministically bounded listing of the numeric pid directories: the smallest {@link
   * #MAX_PID_ENTRIES} numeric pids are retained no matter which order the filesystem enumerates
   * entries in, and the result is returned ascending. Memory stays bounded to the cap while every
   * numeric entry is still considered.
   *
   * @param truncated whether the procfs held more numeric entries than the cap
   */
  public PidListing listPidDirectories() {
    PriorityQueue<Long> retained =
        new PriorityQueue<>(MAX_PID_ENTRIES + 1, Comparator.reverseOrder());
    int numericEntries = 0;
    if (Files.isDirectory(root)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "[0-9]*")) {
        for (Path entry : stream) {
          long pid;
          try {
            pid = Long.parseLong(entry.getFileName().toString());
          } catch (NumberFormatException ignored) {
            // non-numeric directory name that matched the glob: skip
            continue;
          }
          numericEntries++;
          if (retained.size() >= MAX_PID_ENTRIES) {
            if (pid < retained.peek()) {
              retained.poll();
              retained.add(pid);
            }
          } else {
            retained.add(pid);
          }
        }
      } catch (IOException ignored) {
        // procfs became unreadable mid-listing: return what was collected
      }
    }
    List<Long> pids = new ArrayList<>(retained);
    pids.sort(Long::compare);
    return new PidListing(pids, numericEntries > MAX_PID_ENTRIES);
  }

  /** Deterministic outcome of one bounded pid-directory listing. */
  public record PidListing(List<Long> pids, boolean truncated) {}

  /**
   * Reads {@code <pid>/cmdline} as raw null-delimited bytes; empty when the entry is missing,
   * unreadable or oversized.
   */
  public Optional<byte[]> readCmdline(long pid) {
    return readBounded(pidDir(pid).resolve("cmdline"));
  }

  /**
   * Reads the process start time in clock ticks ({@code <pid>/stat} field 22) together with the
   * host uptime in seconds ({@code /proc/uptime}) read at the same moment; empty when either is
   * missing, unreadable or malformed.
   *
   * @return {@code [startTicks, uptimeSeconds]} when both are readable
   */
  public Optional<long[]> readStartTimeTicksAndUptime(long pid) {
    Optional<String> stat = readSmallFile(pidDir(pid).resolve("stat"));
    if (stat.isEmpty()) {
      return Optional.empty();
    }
    long startTicks = parseStartTimeTicks(stat.get());
    if (startTicks < 0) {
      return Optional.empty();
    }
    Optional<String> uptime = readSmallFile(root.resolve("uptime"));
    if (uptime.isEmpty()) {
      return Optional.empty();
    }
    long uptimeSeconds = parseUptimeSeconds(uptime.get());
    if (uptimeSeconds < 0) {
      return Optional.empty();
    }
    return Optional.of(new long[] {startTicks, uptimeSeconds});
  }

  /** Reads the host boot id; empty when unavailable. */
  public Optional<String> readBootId() {
    Optional<String> bootId =
        readSmallFile(root.resolve("sys").resolve("kernel").resolve("random").resolve("boot_id"));
    return bootId.map(String::trim).filter(value -> !value.isEmpty());
  }

  /** Resolves the {@code <pid>/exe} symlink target; empty when unavailable or the pid is gone. */
  public Optional<String> readExeTarget(long pid) {
    try {
      return Optional.ofNullable(Files.readSymbolicLink(pidDir(pid).resolve("exe")).toString());
    } catch (IOException | SecurityException e) {
      return Optional.empty();
    }
  }

  private Optional<byte[]> readBounded(Path file) {
    // Stream with a hard read cap: procfs size metadata (Files.size) is unreliable — pseudo-files
    // report zero while carrying content — so the bound is enforced on the bytes actually read.
    try (InputStream input = Files.newInputStream(file)) {
      byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
      if (bytes.length > MAX_FILE_BYTES) {
        return Optional.empty();
      }
      return Optional.of(bytes);
    } catch (IOException | SecurityException e) {
      return Optional.empty();
    }
  }

  private Optional<String> readSmallFile(Path file) {
    Optional<byte[]> bytes = readBounded(file);
    if (bytes.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new String(bytes.get(), StandardCharsets.UTF_8));
  }

  private Path pidDir(long pid) {
    return root.resolve(Long.toString(pid));
  }

  /**
   * Extracts field 22 (starttime in clock ticks) from a {@code /proc/<pid>/stat} payload.
   *
   * @return the tick value, or {@code -1} when the payload is malformed
   */
  static long parseStartTimeTicks(String stat) {
    int close = stat.lastIndexOf(')');
    if (close < 0 || close + 1 >= stat.length()) {
      return -1;
    }
    String[] rest = stat.substring(close + 1).trim().split("\\s+");
    // rest[0] is field 3 (state); field 22 (starttime) is therefore rest[19]
    if (rest.length <= 19) {
      return -1;
    }
    try {
      return Long.parseLong(rest[19]);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static long parseUptimeSeconds(String uptime) {
    String first = uptime.trim().split("\\s+")[0];
    try {
      return (long) Double.parseDouble(first);
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
