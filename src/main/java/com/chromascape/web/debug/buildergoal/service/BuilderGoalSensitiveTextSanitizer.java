package com.chromascape.web.debug.buildergoal.service;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redacts credential-bearing patterns from externally visible Builder Goal launch text.
 *
 * <p>All strings that can reach logs or HTTP responses (messages, reasons, failure classification,
 * log paths, launch previews, executor URLs, conflict information, target metadata, and unexpected
 * exception details) must pass through {@link #sanitize}. Maps, lists, and nested string metadata
 * can be recursively sanitized via {@link #sanitizeRecursively}, and frozen into deeply immutable
 * structures via {@link #sanitizeAndFreeze}.
 *
 * <p>Operation metadata is defined as JSON-shaped values only: null, string, number, boolean, map,
 * list, collection, or array. Sets and other collections are copied into lists, and object and
 * primitive arrays are copied into lists with their elements recursively sanitized. Map keys are
 * converted to strings and null values are preserved. Unsupported mutable object leaves are
 * rejected with a credential-free {@link IllegalArgumentException} instead of retaining a reference
 * to a caller-owned object.
 *
 * <p>Redacted patterns include inline and split {@code --profile-email} options (case-insensitive,
 * including quoted values), bearer tokens, password/pwd/pass/credential assignments (including
 * double-quoted, single-quoted, and unquoted values), and {@code email:password} credential
 * payloads. Ordinary account display names are never redacted unless they contain a credential
 * pattern.
 */
public final class BuilderGoalSensitiveTextSanitizer {

  private static final String REDACTED = "********";

  private static final Pattern PROFILE_EMAIL_INLINE_PATTERN =
      Pattern.compile("(?i)(--profile-email)\\s*=\\s*(\"[^\"]*\"|'[^']*'|\\S+)");
  private static final Pattern PROFILE_EMAIL_SPLIT_PATTERN =
      Pattern.compile("(?i)(--profile-email)(\\s+)(\"[^\"]*\"|'[^']*'|\\S+)");
  private static final Pattern BEARER_PATTERN =
      Pattern.compile("(?i)((?:Authorization|auth)\\s*:\\s*Bearer\\s+)(\"[^\"]*\"|'[^']*'|\\S+)");
  private static final Pattern PASSWORD_ASSIGN_PATTERN =
      Pattern.compile(
          "(?i)((?:password|passwd|pwd|pass|credential)\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)");
  private static final Pattern EMAIL_COLON_PASSWORD_PATTERN = Pattern.compile("\\S+@\\S+:\\S+");

  private BuilderGoalSensitiveTextSanitizer() {}

  /** Redacts credential-bearing patterns from a single string. */
  public static String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String result =
        PROFILE_EMAIL_INLINE_PATTERN.matcher(value).replaceAll("--profile-email=********");
    result = PROFILE_EMAIL_SPLIT_PATTERN.matcher(result).replaceAll("--profile-email ********");
    result = BEARER_PATTERN.matcher(result).replaceAll("$1********");
    result = PASSWORD_ASSIGN_PATTERN.matcher(result).replaceAll("$1********");
    result = EMAIL_COLON_PASSWORD_PATTERN.matcher(result).replaceAll("email:********");
    return result;
  }

  /**
   * Recursively sanitizes JSON-shaped metadata, returning a new collection so the original
   * structure is never mutated. Maps become {@code LinkedHashMap} copies, lists/sets/collections
   * and object/primitive arrays become {@code ArrayList} copies, and strings are redacted.
   * Unsupported mutable object leaves are rejected with a credential-free {@link
   * IllegalArgumentException}.
   */
  @SuppressWarnings("unchecked")
  public static Object sanitizeRecursively(Object value) {
    if (value instanceof String stringValue) {
      return sanitize(stringValue);
    }
    if (value == null || value instanceof Number || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Map<?, ?> mapValue) {
      Map<String, Object> sanitized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
        sanitized.put(
            entry.getKey() != null ? String.valueOf(entry.getKey()) : null,
            sanitizeRecursively(entry.getValue()));
      }
      return sanitized;
    }
    if (value instanceof List<?> listValue) {
      List<Object> sanitized = new ArrayList<>(listValue.size());
      for (Object item : listValue) {
        sanitized.add(sanitizeRecursively(item));
      }
      return sanitized;
    }
    if (value instanceof Collection<?> collectionValue) {
      List<Object> sanitized = new ArrayList<>(collectionValue.size());
      for (Object item : collectionValue) {
        sanitized.add(sanitizeRecursively(item));
      }
      return sanitized;
    }
    if (value.getClass().isArray()) {
      List<Object> sanitized = new ArrayList<>();
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        sanitized.add(sanitizeRecursively(Array.get(value, i)));
      }
      return sanitized;
    }
    throw new IllegalArgumentException(
        "Unsupported metadata value type: " + value.getClass().getName());
  }

  /**
   * Recursively sanitizes and deep-copies the given JSON-shaped value, returning a deeply immutable
   * structure: maps become {@code LinkedHashMap} copies wrapped with {@link
   * Collections#unmodifiableMap} and lists/collections/arrays become {@code ArrayList} copies
   * wrapped with {@link Collections#unmodifiableList}. Strings are sanitized; null values and
   * numeric/boolean leaves are retained. {@code Map.copyOf} is deliberately avoided because target
   * metadata may contain null values. Unsupported mutable object leaves are rejected with a
   * credential-free {@link IllegalArgumentException} instead of retaining a caller-owned reference.
   */
  @SuppressWarnings("unchecked")
  public static Object sanitizeAndFreeze(Object value) {
    if (value instanceof String stringValue) {
      return sanitize(stringValue);
    }
    if (value == null || value instanceof Number || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Map<?, ?> mapValue) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
        copy.put(
            entry.getKey() != null ? String.valueOf(entry.getKey()) : null,
            sanitizeAndFreeze(entry.getValue()));
      }
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> listValue) {
      List<Object> copy = new ArrayList<>(listValue.size());
      for (Object item : listValue) {
        copy.add(sanitizeAndFreeze(item));
      }
      return Collections.unmodifiableList(copy);
    }
    if (value instanceof Collection<?> collectionValue) {
      List<Object> copy = new ArrayList<>(collectionValue.size());
      for (Object item : collectionValue) {
        copy.add(sanitizeAndFreeze(item));
      }
      return Collections.unmodifiableList(copy);
    }
    if (value.getClass().isArray()) {
      List<Object> copy = new ArrayList<>();
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        copy.add(sanitizeAndFreeze(Array.get(value, i)));
      }
      return Collections.unmodifiableList(copy);
    }
    throw new IllegalArgumentException(
        "Unsupported metadata value type: " + value.getClass().getName());
  }
}
