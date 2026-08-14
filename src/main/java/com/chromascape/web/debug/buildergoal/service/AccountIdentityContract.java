package com.chromascape.web.debug.buildergoal.service;

import java.util.Locale;

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

  private AccountIdentityContract() {}

  public static String normalizeHash(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ENGLISH);
  }

  public static String normalizeName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ENGLISH);
  }

  public static boolean matchesHash(String actualHash, String expectedHash) {
    String actual = normalizeHash(actualHash);
    String expected = normalizeHash(expectedHash);
    return actual != null && expected != null && actual.equals(expected);
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
