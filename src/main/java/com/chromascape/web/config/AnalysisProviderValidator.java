package com.chromascape.web.config;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates the analysis.provider configuration property at application startup.
 *
 * <p>This validator ensures that the analysis.provider property (if set) contains a valid value.
 * Invalid values cause the application to fail fast with a clear error message, preventing
 * confusing bean creation failures downstream.
 *
 * <p>Valid values:
 *
 * <ul>
 *   <li>"claude-api" - Uses Claude API provider (production-ready)
 *   <li>"claude-mcp" - Uses Claude MCP provider (scaffold only, not functional)
 *   <li>undefined - Defaults to "claude-api" via Spring's default value mechanism
 * </ul>
 *
 * @see AnalysisProviderConfig
 */
@Component
public class AnalysisProviderValidator {

  private static final Logger logger = LoggerFactory.getLogger(AnalysisProviderValidator.class);
  private static final Set<String> VALID_PROVIDERS = Set.of("claude-api", "claude-mcp");

  @Value("${analysis.provider:claude-api}")
  private String providerValue;

  /**
   * Validates the analysis.provider configuration value at startup.
   *
   * @throws IllegalStateException if the provider value is not in the set of valid providers
   */
  @PostConstruct
  public void validateProvider() {
    if (!VALID_PROVIDERS.contains(providerValue)) {
      String errorMsg =
          String.format(
              "Invalid analysis.provider='%s'. Valid values: %s. "
                  + "Note: If property is undefined, it defaults to 'claude-api'.",
              providerValue, VALID_PROVIDERS);
      logger.error(errorMsg);
      throw new IllegalStateException(errorMsg);
    }

    logger.info("Analysis provider configuration validated: {}", providerValue);
  }
}
