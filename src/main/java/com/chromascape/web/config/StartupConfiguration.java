package com.chromascape.web.config;

import jakarta.annotation.PostConstruct;
import java.io.File;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

  private static Logger logger = LogManager.getLogger(StartupConfiguration.class);

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
      // - Initialize native libraries (KInput, MinHook)
      // - Validate system requirements
      // - Set up application-wide resources
      // - Load configuration files
      // - Perform health checks

      // Initialize .chromascape directory
      initializeChromascapeDirectory();

      logger.info("CHROMASCAPE STARTUP CONFIGURATION COMPLETED");

    } catch (Exception e) {
      logger.error("Error during infrastructure initialization: {}", e.getMessage());
      // You might want to throw a RuntimeException here to prevent app startup
      // if critical infrastructure fails to initialize
    }
  }

  /**
   * Initializes the .chromascape directory in the project root directory. Creates the directory if
   * it doesn't exist and sets up any required subdirectories.
   */
  private void initializeChromascapeDirectory() {
    try {
      // Get project root directory (where build.gradle.kts is located)
      String projectRoot = System.getProperty("user.dir");
      File chromascapeDir = new File(projectRoot, ".chromascape");

      // Create .chromascape directory if it doesn't exist
      if (!chromascapeDir.exists()) {
        boolean created = chromascapeDir.mkdirs();
        if (created) {
          logger.info("Created .chromascape directory at: {}", chromascapeDir.getAbsolutePath());
          // Only create subdirectories if we just created the main directory
          createSubdirectories(chromascapeDir);
        } else {
          logger.error("Failed to create .chromascape directory");
          throw new RuntimeException("Could not create .chromascape directory");
        }
      } else {
        logger.info(
            "Found existing .chromascape directory at: {}", chromascapeDir.getAbsolutePath());
        // Directory already exists, don't modify it
      }

      // Verify directory is writable
      if (!chromascapeDir.canWrite()) {
        logger.warn("Warning: .chromascape directory is not writable");
        throw new RuntimeException("Cannot write to .chromascape directory");
      }

    } catch (Exception e) {
      logger.error("Error initializing .chromascape directory: {}", e.getMessage());
      throw new RuntimeException("Failed to initialize .chromascape directory", e);
    }
  }

  /**
   * Creates necessary subdirectories within the .chromascape directory.
   *
   * @param chromascapeDir the main .chromascape directory
   */
  private void createSubdirectories(File chromascapeDir) {
    String[] subdirs = {"config", "logs", "scripts", "data", "cache"};

    for (String subdir : subdirs) {
      File subdirFile = new File(chromascapeDir, subdir);
      if (!subdirFile.exists()) {
        boolean created = subdirFile.mkdir();
        if (created) {
          logger.info("Created subdirectory: {}", subdir);
        } else {
          logger.warn("Warning: Failed to create subdirectory: {}", subdir);
        }
      }
    }
  }
}
