package com.chromascape.web.debug.tickdebugger.video;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stable, bounded public failure text for external-video jobs. Raw probe and process diagnostics
 * are retained only in the restricted job workspace and server logs; they are never copied into a
 * job, source, review or export representation. A job identifier is included as the operator
 * correlation reference.
 */
public final class MouseVideoPublicErrors {

  private static final int MAX_PUBLIC_MESSAGE = 320;

  private static final Map<String, String> MESSAGES =
      Map.ofEntries(
          Map.entry(
              MouseVideoException.CODE_DECODER_FAILED, "The media could not be decoded or probed"),
          Map.entry(
              MouseVideoException.CODE_UNSUPPORTED_MEDIA,
              "The media is not a supported inline video format"),
          Map.entry(
              MouseVideoException.CODE_MEDIA_LIMIT, "The media exceeds a configured import limit"),
          Map.entry(
              MouseVideoException.CODE_QUOTA_EXCEEDED,
              "The job exceeded its configured storage limit; no output was published"),
          Map.entry(
              MouseVideoException.CODE_CHECKSUM_MISMATCH,
              "The media failed integrity verification"),
          Map.entry(
              MouseVideoException.CODE_NO_VISIBLE_CURSOR, "No visible cursor could be tracked"),
          Map.entry(
              MouseVideoException.CODE_CURSOR_UNSUPPORTED,
              "Cursor tracking is not supported for this media"),
          Map.entry(
              MouseVideoException.CODE_SOURCE_AUTH_REQUIRED,
              "The source requires authentication and cannot be imported"),
          Map.entry(
              MouseVideoException.CODE_SOURCE_RATE_LIMITED,
              "The source is rate limiting acquisition"),
          Map.entry(
              MouseVideoException.CODE_TOOL_UNAVAILABLE,
              "A required media tool is unavailable on this server"),
          Map.entry(
              MouseVideoException.CODE_WORKER_TIMEOUT,
              "The job exceeded its processing time limit"),
          Map.entry(
              MouseVideoException.CODE_INTERRUPTED, "The job was interrupted before completion"),
          Map.entry(
              MouseVideoException.CODE_CLEANUP_UNCONFIRMED,
              "Cleanup could not be confirmed; operator reconciliation is required"),
          Map.entry(
              MouseVideoException.CODE_IDENTITY_CHANGED,
              "The analysis configuration changed after scheduling; schedule it again"),
          Map.entry(
              MouseVideoException.CODE_DISABLED, "Video processing is not enabled on this server"));

  private static final Set<String> ALLOWED_PROBE_CODES =
      Set.of(
          MouseVideoException.CODE_DECODER_FAILED,
          MouseVideoException.CODE_UNSUPPORTED_MEDIA,
          MouseVideoException.CODE_MEDIA_LIMIT,
          MouseVideoException.CODE_CHECKSUM_MISMATCH,
          MouseVideoException.CODE_NO_VISIBLE_CURSOR,
          MouseVideoException.CODE_CURSOR_UNSUPPORTED,
          MouseVideoException.CODE_SOURCE_AUTH_REQUIRED,
          MouseVideoException.CODE_SOURCE_RATE_LIMITED,
          MouseVideoException.CODE_TOOL_UNAVAILABLE);

  private MouseVideoPublicErrors() {}

  /** Maps an arbitrary probe code to the bounded public allowlist. */
  public static String probeCode(String candidate) {
    return candidate != null && ALLOWED_PROBE_CODES.contains(candidate)
        ? candidate
        : MouseVideoException.CODE_DECODER_FAILED;
  }

  /** Bounded public message for a persisted job failure with an operator correlation reference. */
  public static String jobFailure(String code, UUID jobId) {
    // Legacy rows may have no code; immutable maps reject null before applying the fallback.
    String publicCode = code == null ? MouseVideoException.CODE_DECODER_FAILED : code;
    String message =
        MESSAGES.getOrDefault(publicCode, MESSAGES.get(MouseVideoException.CODE_DECODER_FAILED));
    return bounded(message + " (reference " + jobId + ")");
  }

  /** Bounded public reason retained when cleanup cannot be confirmed. */
  public static String reconciliation(UUID jobId) {
    return bounded(
        MESSAGES.get(MouseVideoException.CODE_CLEANUP_UNCONFIRMED) + " (reference " + jobId + ")");
  }

  private static String bounded(String message) {
    return message.length() <= MAX_PUBLIC_MESSAGE
        ? message
        : message.substring(0, MAX_PUBLIC_MESSAGE);
  }
}
