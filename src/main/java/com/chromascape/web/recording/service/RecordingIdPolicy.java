package com.chromascape.web.recording.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Platform-safe recording identifiers.
 *
 * <p>A recording id is used verbatim as the MP4 filename, so it must be creatable on every
 * supported platform. The whitelist excludes Windows-reserved characters and separators, a trailing
 * dot or space, and reserved device names. The same policy is enforced where a human approves the
 * id and where the recording actually starts, so an approval can never bind an id that the
 * recorder cannot materialise.
 */
public final class RecordingIdPolicy {
  private static final int MAX_LENGTH = 160;
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
  private static final Set<String> RESERVED_DEVICE_NAMES =
      Set.of(
          "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
          "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

  private RecordingIdPolicy() {}

  /** True when the id is a legal, platform-safe recording identifier. */
  public static boolean isPlatformSafe(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
      return false;
    }
    if (!SAFE.matcher(value).matches()) {
      return false;
    }
    if (value.endsWith(".") || value.endsWith(" ")) {
      return false;
    }
    int dot = value.indexOf('.');
    String baseName = (dot < 0 ? value : value.substring(0, dot)).toUpperCase(Locale.ROOT);
    return !RESERVED_DEVICE_NAMES.contains(baseName);
  }

  /** Refuses an id the recorder could not materialise as a filename. */
  public static void require(String value, String context) {
    if (!isPlatformSafe(value)) {
      throw new IllegalArgumentException(
          "A platform-safe " + context + " is required (got " + value + ")");
    }
  }
}
