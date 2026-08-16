package com.chromascape.web.debug.buildergoal.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Wire-level account identity contract for Builder Goal launch and executor readiness.
 *
 * <p>The hash is supplied by the launcher/Micro6 AccountConnector contract. ChromaScape deliberately
 * never derives it from a launcher profile or display name; it only normalizes and compares the
 * canonical value received from the selected account and the actual logged-in executor.
 */
public final class AccountIdentityContract {
  public static final String HASH_SCHEME = "accountconnector/v1";
  /** Canonical identity must come from the AccountConnector resolver; profile hashing is not valid. */
  public static final String ACTUAL_ACCOUNT_HASH_FIELD = "account_hash";
  public static final String EXPECTED_ACCOUNT_HASH_FIELD = "requested_account_hash";
  /** Legacy field name accepted only for compatibility with older producers. */
  public static final String LEGACY_EXPECTED_ACCOUNT_HASH_FIELD = "expected_account_hash";

  /** Canonical hashes are normalized lowercase 16-hex values under the accountconnector/v1 scheme. */
  private static final Pattern CANONICAL_HASH_PATTERN = Pattern.compile("[0-9a-f]{16}");

  private AccountIdentityContract() {}

  public static String normalizeHash(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Returns {@code true} only when the value is a canonical account hash: a trimmed, lowercase,
   * 16-hex-character identity. Arbitrary strings (profiles, placeholders, display names) never
   * qualify as canonical hashes.
   */
  public static boolean isCanonicalHash(String value) {
    String normalized = normalizeHash(value);
    return normalized != null && CANONICAL_HASH_PATTERN.matcher(normalized).matches();
  }

  public static String normalizeName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  public static boolean matchesHash(String actualHash, String expectedHash) {
    String actual = normalizeHash(actualHash);
    String expected = normalizeHash(expectedHash);
    return actual != null && expected != null && actual.equals(expected);
  }

  /**
   * Strict comparison for every launch, health and publish authorization decision.
   *
   * <p>{@link #matchesHash(String, String)} deliberately remains a normalization helper for
   * diagnostics and backward-compatible display code. It must not authorize an executor: two
   * malformed but equal values such as {@code hash-1} would otherwise compare successfully.
   */
  public static boolean matchesCanonicalHash(String actualHash, String expectedHash) {
    return isCanonicalHash(actualHash)
        && isCanonicalHash(expectedHash)
        && matchesHash(actualHash, expectedHash);
  }

  public static boolean hasRequiredScheme(String scheme) {
    return HASH_SCHEME.equals(normalizeScheme(scheme));
  }

  public static String normalizeScheme(String scheme) {
    if (scheme == null || scheme.isBlank()) {
      return null;
    }
    return scheme.trim();
  }
}
