package com.chromascape.web.config;

import com.chromascape.utils.core.AccountManager;
import com.chromascape.utils.core.constants.CacheFolderConstants;
import com.chromascape.utils.core.runtime.profile.ProfileManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
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
      // - Initialize native libraries (KInput, MinHook)
      // - Validate system requirements
      // - Set up application-wide resources
      // - Load configuration files
      // - Perform health checks

      // Initialize .chromascape directory
      initializeChromascapeDirectory();

      // Load account configuration
      loadAccountConfiguration();

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

  /**
   * Initializes the .chromascape directory in the project root directory. Creates the directory if
   * it doesn't exist and sets up any required subdirectories.
   */
  private void initializeChromascapeDirectory() {
    try {
      // Get project root directory (where build.gradle.kts is located)
      String projectRoot = System.getProperty("user.dir");
      File chromascapeDir = new File(projectRoot, CacheFolderConstants.CHROMA_CACHE_FOLDER_NAME);

      // Create .chromascape directory if it doesn't exist
      if (!chromascapeDir.exists()) {
        boolean created = chromascapeDir.mkdirs();
        if (created) {
          logger.info("Created .chromascape directory at: {}", chromascapeDir.getAbsolutePath());
          createSubdirectories(chromascapeDir);
        } else {
          String message =
              String.format(
                  "Could not create %s directory", CacheFolderConstants.CHROMA_CACHE_FOLDER_NAME);
          logger.error(message);
          throw new RuntimeException(message);
        }
      } else {
        String message =
            String.format(
                "Found existing %s directory at: %s",
                CacheFolderConstants.CHROMA_CACHE_FOLDER_NAME, chromascapeDir.getAbsolutePath());
        logger.info(message);
      }

      // Verify directory is writable
      if (!chromascapeDir.canWrite()) {
        String message =
            String.format(
                "Warning: %s directory is not writable",
                CacheFolderConstants.CHROMA_CACHE_FOLDER_NAME);
        logger.warn(message);
        throw new RuntimeException(message);
      }

    } catch (Exception e) {
      String message =
          String.format(
              "Error initializing %s directory: %s",
              CacheFolderConstants.CACHE_FOLDER_NAME, e.getMessage());
      logger.error(message);
      throw new RuntimeException(message, e);
    }
  }

  /**
   * Creates necessary subdirectories within the .chromascape directory.
   *
   * @param chromascapeDir the main .chromascape directory
   */
  private void createSubdirectories(File chromascapeDir) {
    for (String subdir : CacheFolderConstants.CHROMA_CACHE_FOLDER_SUBDIRS) {
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

  /**
   * Loads account configuration from the accounts.json file and populates the AccountManager. This
   * method reads the first account from the JSON file and sets it as the selected account.
   */
  private void loadAccountConfiguration() {
    try {
      String projectRoot = System.getProperty("user.dir");
      File accountsFile =
          new File(
              projectRoot,
              CacheFolderConstants.CHROMA_CACHE_FOLDER_NAME
                  + "/"
                  + CacheFolderConstants.CONFIG_FOLDER_NAME
                  + "/"
                  + CacheFolderConstants.ACCOUNTS_FILE_NAME);

      if (accountsFile.exists()) {
        logger.info("Loading account configuration from: {}", accountsFile.getAbsolutePath());

        // Read the JSON file and parse it as a String array
        ObjectMapper objectMapper = new ObjectMapper();
        String[] accounts = objectMapper.readValue(accountsFile, String[].class);

        // Select the first account if any exist
        if (accounts.length > 0) {
          String firstAccount = accounts[0];
          AccountManager.setSelectedAccount(firstAccount);
          logger.info("Account loaded successfully: {}", firstAccount);
        } else {
          logger.warn("No accounts found in accounts.json file");
        }
      } else {
        logger.info("No accounts.json file found, no account will be loaded");
      }
    } catch (IOException e) {
      logger.error("Error reading accounts file: {}", e.getMessage());
    } catch (Exception e) {
      logger.error("Unexpected error during account loading: {}", e.getMessage());
    }
  }
}
