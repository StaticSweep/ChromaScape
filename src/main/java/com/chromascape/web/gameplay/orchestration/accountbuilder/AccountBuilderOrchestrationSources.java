package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import java.util.Set;

/**
 * Closed, locale-independent source vocabulary for SC-1431 orchestrations.
 *
 * <p>The persisted source string is unchanged, but every parse passes through this single strict
 * classifier. Only exact, approved tokens are accepted. In particular a provider source must be the
 * exact ASCII {@code PROVIDER:<PROVIDER_ENUM_NAME>}; case variants such as {@code provider:CODEX},
 * {@code Provider:CODEX} or {@code PROVIDER:codex}, unknown provider names, whitespace-padded
 * values and arbitrary strings are all rejected. The generic orchestration entry point refuses
 * every provider-like source with an ASCII case-independent prefix check, so a caller cannot enter
 * the provider trust path through the generic route even with case tricks.
 */
public final class AccountBuilderOrchestrationSources {
  private static final String PROVIDER_PREFIX = "PROVIDER:";

  /** The complete set of approved non-provider source tokens. */
  private static final Set<String> NON_PROVIDER_TOKENS = Set.of("BOOTSTRAP");

  private AccountBuilderOrchestrationSources() {}

  public enum Kind {
    NON_PROVIDER,
    PROVIDER
  }

  /**
   * Strictly classifies a persisted source string. Returns the exact kind, or fails closed with
   * {@link FailureCode#INVALID_CONTRACT} when the value is not in the closed vocabulary.
   */
  public static Kind classify(String sourceType) {
    if (sourceType == null || sourceType.isEmpty()) {
      throw invalid("Orchestration source type is required");
    }
    if (providerLike(sourceType)) {
      String providerName = sourceType.substring(PROVIDER_PREFIX.length());
      if (providerName.isEmpty() || !sourceType.equals(PROVIDER_PREFIX + providerName)) {
        throw invalid("Provider source type is not an exact approved token");
      }
      try {
        Provider.valueOf(providerName);
      } catch (IllegalArgumentException exception) {
        throw invalid("Provider source type names an unknown provider");
      }
      return Kind.PROVIDER;
    }
    if (!NON_PROVIDER_TOKENS.contains(sourceType)) {
      throw invalid("Orchestration source type is not an approved token");
    }
    return Kind.NON_PROVIDER;
  }

  /**
   * ASCII case-independent prefix test used to categorically reject provider-like sources on the
   * generic route. It never consults the default locale, so {@code provider:CODEX} and {@code
   * PrOvIdEr:...} are all recognized as provider-like.
   */
  public static boolean providerLike(String sourceType) {
    if (sourceType == null || sourceType.length() < PROVIDER_PREFIX.length()) {
      return false;
    }
    for (int index = 0; index < PROVIDER_PREFIX.length(); index++) {
      if (asciiUpper(sourceType.charAt(index)) != PROVIDER_PREFIX.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  private static char asciiUpper(char value) {
    return value >= 'a' && value <= 'z' ? (char) (value - ('a' - 'A')) : value;
  }

  private static AccountBuilderOrchestrationException invalid(String message) {
    return new AccountBuilderOrchestrationException(FailureCode.INVALID_CONTRACT, message);
  }
}
