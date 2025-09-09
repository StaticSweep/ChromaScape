package com.chromascape.web.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.lang.reflect.Method;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Test class for {@link StartupConfiguration}. */
class StartupConfigurationTest {

  @TempDir File tempDir;

  private StartupConfiguration startupConfiguration;
  private static Logger mockLogger;

  @BeforeEach
  void setUp() {
    startupConfiguration = new StartupConfiguration();
    mockLogger = mock(Logger.class);
    ReflectionTestUtils.setField(startupConfiguration, "logger", mockLogger);
  }

  @Test
  void testStartupConfigurationCanBeInstantiated() {
    assertNotNull(startupConfiguration, "StartupConfiguration should be instantiable");
  }

  @Test
  void testInitializeInfrastructureCreatesChromascapeDirectory() throws Exception {
    final String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    startupConfiguration.initializeInfrastructure();

    File expectedDir = new File(tempDir, ".chromascape");
    assertTrue(expectedDir.exists(), ".chromascape directory should be created");
    assertTrue(expectedDir.isDirectory(), ".chromascape should be a directory");
    assertTrue(expectedDir.canWrite(), ".chromascape directory should be writable");

    verify(mockLogger, atLeastOnce()).info(anyString());

    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testInitializeInfrastructureCreatesSubdirectories() throws Exception {
    final String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    startupConfiguration.initializeInfrastructure();

    File chromascapeDir = new File(tempDir, ".chromascape");
    String[] expectedSubdirs = {"config", "logs", "scripts", "data", "cache"};
    for (String subdir : expectedSubdirs) {
      File subdirFile = new File(chromascapeDir, subdir);
      assertTrue(subdirFile.exists(), "Subdirectory " + subdir + " should exist");
      assertTrue(subdirFile.isDirectory(), "Subdirectory " + subdir + " should be a directory");
    }

    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testInitializeInfrastructureHandlesExistingDirectory() throws Exception {
    final String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    File existingDir = new File(tempDir, ".chromascape");
    existingDir.mkdirs();

    startupConfiguration.initializeInfrastructure();

    assertTrue(existingDir.exists(), "Existing directory should still exist");
    verify(mockLogger, atLeastOnce()).info(anyString());

    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testInitializeInfrastructureLogsAppropriateMessages() throws Exception {
    final String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    startupConfiguration.initializeInfrastructure();

    verify(mockLogger, atLeastOnce()).info(anyString());

    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testInitializeInfrastructureHandlesDirectoryCreationFailure() throws Exception {
    final String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", "/root/restricted");

    try {
      startupConfiguration.initializeInfrastructure();
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("Failed to initialize .chromascape directory"));
    }

    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testCreateSubdirectoriesCreatesAllRequiredSubdirectories() throws Exception {
    Method createSubdirectoriesMethod =
        StartupConfiguration.class.getDeclaredMethod("createSubdirectories", File.class);
    createSubdirectoriesMethod.setAccessible(true);

    File testDir = new File(tempDir, "test-chromascape");
    testDir.mkdirs();

    createSubdirectoriesMethod.invoke(startupConfiguration, testDir);

    String[] expectedSubdirs = {"config", "logs", "scripts", "data", "cache"};
    for (String subdir : expectedSubdirs) {
      File subdirFile = new File(testDir, subdir);
      assertTrue(subdirFile.exists(), "Subdirectory " + subdir + " should be created");
    }
  }

  @Test
  void testCreateSubdirectoriesHandlesExistingSubdirectories() throws Exception {
    Method createSubdirectoriesMethod =
        StartupConfiguration.class.getDeclaredMethod("createSubdirectories", File.class);
    createSubdirectoriesMethod.setAccessible(true);

    File testDir = new File(tempDir, "test-chromascape");
    testDir.mkdirs();

    File existingSubdir = new File(testDir, "config");
    existingSubdir.mkdirs();

    createSubdirectoriesMethod.invoke(startupConfiguration, testDir);

    String[] expectedSubdirs = {"config", "logs", "scripts", "data", "cache"};
    for (String subdir : expectedSubdirs) {
      File subdirFile = new File(testDir, subdir);
      assertTrue(subdirFile.exists(), "Subdirectory " + subdir + " should exist");
    }
  }

  @Test
  void testInitializeChromascapeDirectoryPrivateMethod() throws Exception {
    Method initializeChromascapeDirectoryMethod =
        StartupConfiguration.class.getDeclaredMethod("initializeChromascapeDirectory");
    initializeChromascapeDirectoryMethod.setAccessible(true);

    final String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    initializeChromascapeDirectoryMethod.invoke(startupConfiguration);

    File expectedDir = new File(tempDir, ".chromascape");
    assertTrue(expectedDir.exists(), ".chromascape directory should be created");

    System.setProperty("user.dir", originalUserDir);
  }
}
