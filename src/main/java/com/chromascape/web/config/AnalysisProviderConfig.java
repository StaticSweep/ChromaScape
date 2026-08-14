package com.chromascape.web.config;

import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Configuration class for analysis provider startup logging.
 *
 * <p>Logs the active analysis provider on application startup to help with debugging and
 * configuration verification.
 */
@Configuration
public class AnalysisProviderConfig {

  private static final Logger logger = LoggerFactory.getLogger(AnalysisProviderConfig.class);

  private final AnalysisProvider analysisProvider;

  public AnalysisProviderConfig(AnalysisProvider analysisProvider) {
    this.analysisProvider = analysisProvider;
  }

  /**
   * Logs analysis provider information when application is ready.
   *
   * <p>This runs after all beans are initialized and before the application starts accepting
   * requests.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void logProviderSelection() {
    logger.info("===========================================");
    logger.info("Analysis Provider: {}", analysisProvider.getProviderName());

    boolean available = analysisProvider.isAvailable();
    logger.info("Provider Available: {}", available);
    logger.info("===========================================");

    if (!available) {
      logger.warn(
          "Analysis provider '{}' is NOT available - check configuration (API keys, server URLs,"
              + " etc.)",
          analysisProvider.getProviderName());
      logger.warn(
          "BMAD (Bot Metric Analysis & Diagnostics) features may not work until the provider is"
              + " properly configured");
    }
  }
}
