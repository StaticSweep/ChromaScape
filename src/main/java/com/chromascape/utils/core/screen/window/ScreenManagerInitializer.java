package com.chromascape.utils.core.screen.window;

import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Initializes the ScreenManager with the WindowSelectionService on application startup.
 *
 * <p>This component bridges Spring's dependency injection with ScreenManager's static methods by
 * setting the WindowSelectionService reference when the application starts.
 */
@Component
public class ScreenManagerInitializer {

  private static final Logger logger = LogManager.getLogger(ScreenManagerInitializer.class);

  private final WindowSelectionService windowSelectionService;

  @Autowired
  public ScreenManagerInitializer(WindowSelectionService windowSelectionService) {
    this.windowSelectionService = windowSelectionService;
  }

  /** Initializes the ScreenManager with the WindowSelectionService. */
  @PostConstruct
  public void init() {
    ScreenManager.setWindowSelectionService(windowSelectionService);
    logger.info("ScreenManager initialized with WindowSelectionService");
  }
}
