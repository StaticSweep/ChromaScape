package com.chromascape.web.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.chromascape.web.logs.LogService;
import java.io.File;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Test class for {@link StartupConfiguration}. */
class StartupConfigurationTest {

  @TempDir File tempDir;

  private StartupConfiguration startupConfiguration;
  private LogService mockLogService;

  @BeforeEach
  void setUp() {
    startupConfiguration = new StartupConfiguration();
    mockLogService = mock(LogService.class);

    // Inject the mock LogService using reflection
    ReflectionTestUtils.setField(startupConfiguration, "logService", mockLogService);
  }

  @Test
  void testStartupConfiguration_CanBeInstantiated() {
    // When & Then
    assertNotNull(startupConfiguration, "StartupConfiguration should be instantiable");
  }

  @Test
  void testInitializeInfrastructure_CreatesChromascapeDirectory() throws Exception {
    // Given
    String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    // When
    startupConfiguration.initializeInfrastructure();

    // Then
    File expectedDir = new File(tempDir, ".chromascape");
    assertTrue(expectedDir.exists(), ".chromascape directory should be created");
    assertTrue(expectedDir.isDirectory(), ".chromascape should be a directory");
    assertTrue(expectedDir.canWrite(), ".chromascape directory should be writable");

    // Verify logging - should be called at least once
    verify(mockLogService, atLeastOnce()).addLog(anyString());

    // Cleanup
    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testInitializeInfrastructure_CreatesSubdirectories() throws Exception {
    // Given
    String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    // When
    startupConfiguration.initializeInfrastructure();

    // Then
    File chromascapeDir = new File(tempDir, ".chromascape");
    String[] expectedSubdirs = {"config", "logs", "scripts", "data", "cache"};

    for (String subdir : expectedSubdirs) {
      File subdirFile = new File(chromascapeDir, subdir);
      assertTrue(subdirFile.exists(), "Subdirectory " + subdir + " should exist");
      assertTrue(subdirFile.isDirectory(), "Subdirectory " + subdir + " should be a directory");
    }

    // Cleanup
    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testInitializeInfrastructure_HandlesExistingDirectory() throws Exception {
    // Given
    String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    // Create the directory beforehand
    File existingDir = new File(tempDir, ".chromascape");
    existingDir.mkdirs();

    // When
    startupConfiguration.initializeInfrastructure();

    // Then
    assertTrue(existingDir.exists(), "Existing directory should still exist");
    verify(mockLogService, times(1)).addLog(anyString()); // Only logs that directory was found

    // Cleanup
    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testInitializeInfrastructure_LogsAppropriateMessages() throws Exception {
    // Given
    String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    // When
    startupConfiguration.initializeInfrastructure();

    // Then
    verify(mockLogService, atLeastOnce()).addLog(anyString());

    // Cleanup
    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testInitializeInfrastructure_HandlesDirectoryCreationFailure() throws Exception {
    // Given
    String originalUserDir = System.getProperty("user.dir");
    // Set user.dir to a path that should cause permission issues
    System.setProperty("user.dir", "/root/restricted");

    // When & Then
    try {
      startupConfiguration.initializeInfrastructure();
    } catch (RuntimeException e) {
      // Expected behavior - should throw RuntimeException on failure
      assertTrue(e.getMessage().contains("Failed to initialize .chromascape directory"));
    }

    // Cleanup
    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  void testCreateSubdirectories_CreatesAllRequiredSubdirectories() throws Exception {
    // Given
    Method createSubdirectoriesMethod =
        StartupConfiguration.class.getDeclaredMethod("createSubdirectories", File.class);
    createSubdirectoriesMethod.setAccessible(true);

    File testDir = new File(tempDir, "test-chromascape");
    testDir.mkdirs();

    // When
    createSubdirectoriesMethod.invoke(startupConfiguration, testDir);

    // Then
    String[] expectedSubdirs = {"config", "logs", "scripts", "data", "cache"};
    for (String subdir : expectedSubdirs) {
      File subdirFile = new File(testDir, subdir);
      assertTrue(subdirFile.exists(), "Subdirectory " + subdir + " should be created");
    }
  }

  @Test
  void testCreateSubdirectories_HandlesExistingSubdirectories() throws Exception {
    // Given
    Method createSubdirectoriesMethod =
        StartupConfiguration.class.getDeclaredMethod("createSubdirectories", File.class);
    createSubdirectoriesMethod.setAccessible(true);

    File testDir = new File(tempDir, "test-chromascape");
    testDir.mkdirs();

    // Create one subdirectory beforehand
    File existingSubdir = new File(testDir, "config");
    existingSubdir.mkdirs();

    // When
    createSubdirectoriesMethod.invoke(startupConfiguration, testDir);

    // Then
    String[] expectedSubdirs = {"config", "logs", "scripts", "data", "cache"};
    for (String subdir : expectedSubdirs) {
      File subdirFile = new File(testDir, subdir);
      assertTrue(subdirFile.exists(), "Subdirectory " + subdir + " should exist");
    }
  }

  @Test
  void testInitializeChromascapeDirectory_PrivateMethod() throws Exception {
    // Given
    Method initializeChromascapeDirectoryMethod =
        StartupConfiguration.class.getDeclaredMethod("initializeChromascapeDirectory");
    initializeChromascapeDirectoryMethod.setAccessible(true);

    String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.getAbsolutePath());

    // When
    initializeChromascapeDirectoryMethod.invoke(startupConfiguration);

    // Then
    File expectedDir = new File(tempDir, ".chromascape");
    assertTrue(expectedDir.exists(), ".chromascape directory should be created");

    // Cleanup
    System.setProperty("user.dir", originalUserDir);
  }
}
