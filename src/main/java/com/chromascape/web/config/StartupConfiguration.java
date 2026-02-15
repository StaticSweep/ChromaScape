package com.chromascape.web.config;

import com.chromascape.utils.core.runtime.profile.ProfileManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class responsible for initializing infrastructure components at application
 * startup.
 *
 * <p>This class runs after all Spring beans are initialized and can be used to set up
 * application-wide infrastructure, validate dependencies, and perform one-time initialization
 * tasks.
 */
@Configuration
public class StartupConfiguration {

  private static Logger logger = LoggerFactory.getLogger(StartupConfiguration.class);

  /**
   * Initializes application infrastructure after Spring context is fully loaded.
   *
   * <p>This method runs after all Spring beans are initialized and is the ideal place to perform
   * startup tasks such as: - Validating system requirements - Initializing native libraries -
   * Setting up application-wide resources - Performing health checks - Loading configuration data
   */
  @PostConstruct
  public void initializeInfrastructure() {
    logger.info("CHROMASCAPE STARTUP CONFIGURATION RUNNING");
    logger.info("Initializing infrastructure...");

    try {
      // Examples:
      // - Initialize native libraries (KInput)
      // - Validate system requirements
      // - Set up application-wide resources
      // - Load configuration files
      // - Perform health checks

      // Load bot profile config file into RuneLite
      ProfileManager profileManager = new ProfileManager();
      profileManager.loadBotProfile();

      logger.info("CHROMASCAPE STARTUP CONFIGURATION COMPLETED");

    } catch (Exception e) {
      logger.error("Error during infrastructure initialization: {}", e.getMessage());
      // You might want to throw a RuntimeException here to prevent app startup
      // if critical infrastructure fails to initialize
    }
  }
}
