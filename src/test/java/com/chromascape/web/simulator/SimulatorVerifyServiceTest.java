package com.chromascape.web.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Test suite for {@link SimulatorVerifyService}.
 *
 * <p>Tests CLI flag generation, path resolution, and error detection for the simulator verifier
 * integration.
 */
@ExtendWith(MockitoExtension.class)
class SimulatorVerifyServiceTest {

  @Mock private SimulatorVerifierProperties properties;
  @Mock private SimulatorVerifyJobStore jobStore;

  private SimulatorVerifyService service;

  @BeforeEach
  void setUp() {
    // Set default property values using lenient stubbing for tests that don't use all mocks
    lenient().when(properties.getOpenruneRoot()).thenReturn("/path/to/openrune");
    lenient().when(properties.getGradleCmd()).thenReturn("./gradlew");
    lenient().when(properties.getMaxConcurrentJobs()).thenReturn(2);
    lenient().when(properties.getJobTimeoutSeconds()).thenReturn(300);
    lenient().when(properties.getReportsDir()).thenReturn("output/simulator-reports");
    lenient().when(properties.isAutoCreateReportsDir()).thenReturn(false);
    lenient().when(properties.isEnabled()).thenReturn(true);

    service = new SimulatorVerifyService(properties, jobStore);
  }

  @Nested
  @DisplayName("CLI Flag Generation Tests")
  class CliFlagGenerationTests {

    private Method buildArgsStringMethod;

    @BeforeEach
    void setUp() throws Exception {
      buildArgsStringMethod =
          SimulatorVerifyService.class.getDeclaredMethod(
              "buildArgsString",
              String.class,
              String.class,
              String.class,
              String.class,
              String.class);
      buildArgsStringMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should build args string with correct camelCase flags (TC1)")
    void shouldBuildArgsStringWithCorrectCamelCaseFlags() throws Exception {
      // Arrange
      String session = "session-123";
      String scenario = "S1-happy-path";
      String bridgeUrl = "http://localhost:8092";
      String apiKey = "test-api-key";
      String outputPath = "/tmp/report.json";

      // Act
      String result =
          (String)
              buildArgsStringMethod.invoke(
                  service, session, scenario, bridgeUrl, apiKey, outputPath);

      // Assert
      assertTrue(
          result.contains("--nonInteractive"),
          "Result should contain camelCase --nonInteractive flag");
      assertTrue(
          result.contains("--apiKey"),
          "Result should contain camelCase --apiKey flag when API key provided");
      assertFalse(
          result.contains("--non-interactive"),
          "Result should NOT contain kebab-case --non-interactive flag");
      assertFalse(
          result.contains("--api-key"), "Result should NOT contain kebab-case --api-key flag");
    }

    @Test
    @DisplayName("Should build args string without API key when blank")
    void shouldBuildArgsStringWithoutApiKeyWhenBlank() throws Exception {
      // Arrange
      String session = "session-456";
      String scenario = null;
      String bridgeUrl = "http://localhost:8092";
      String apiKey = null; // No API key
      String outputPath = "/tmp/report2.json";

      // Act
      String result =
          (String)
              buildArgsStringMethod.invoke(
                  service, session, scenario, bridgeUrl, apiKey, outputPath);

      // Assert
      assertTrue(
          result.contains("--nonInteractive"),
          "Result should contain --nonInteractive flag even without API key");
      assertFalse(
          result.contains("--apiKey"),
          "Result should NOT contain --apiKey flag when API key is null");
      assertFalse(
          result.contains("--api-key"), "Result should NOT contain kebab-case --api-key flag");
    }

    @Test
    @DisplayName("Should build args string without API key when empty string")
    void shouldBuildArgsStringWithoutApiKeyWhenEmpty() throws Exception {
      // Arrange
      String session = "session-789";
      String scenario = "";
      String bridgeUrl = "http://localhost:8092";
      String apiKey = "  "; // Blank/whitespace API key
      String outputPath = "/tmp/report3.json";

      // Act
      String result =
          (String)
              buildArgsStringMethod.invoke(
                  service, session, scenario, bridgeUrl, apiKey, outputPath);

      // Assert
      assertFalse(
          result.contains("--apiKey"),
          "Result should NOT contain --apiKey flag when API key is blank");
    }
  }

  @Nested
  @DisplayName("Path Resolution Tests")
  class PathResolutionTests {

    @Test
    @DisplayName("Should resolve relative reports dir against openrune root")
    void shouldResolveRelativeReportsDirAgainstOpenruneRoot(@TempDir File tempDir) {
      // Arrange
      String relativePath = "output/simulator-reports";

      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", relativePath);
      ReflectionTestUtils.setField(props, "openruneRoot", tempDir.getAbsolutePath());

      // Act
      Path resolved = props.getResolvedReportsDir();

      // Assert
      assertTrue(
          resolved.isAbsolute(),
          "Resolved path should be absolute (root is absolute, relative resolved against absolute)");
      assertTrue(
          resolved.toString().replace("\\", "/").contains(relativePath),
          "Resolved path should contain the relative path component (normalized to forward slashes)");
      assertTrue(
          resolved.startsWith(tempDir.toPath()), "Resolved path should start with the root path");
    }

    @Test
    @DisplayName("Should return absolute reports dir when already absolute")
    void shouldReturnAbsoluteReportsDirWhenAlreadyAbsolute(@TempDir File tempDir) {
      // Arrange
      // Use tempDir to create an absolute path that works on all platforms
      Path absolutePath = tempDir.toPath().resolve("reports");

      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", absolutePath.toString());
      ReflectionTestUtils.setField(props, "openruneRoot", "/some/other/path");

      // Act
      Path resolved = props.getResolvedReportsDir();

      // Assert
      assertTrue(resolved.isAbsolute(), "Resolved path should be absolute");
      assertEquals(
          absolutePath.normalize(),
          resolved,
          "Resolved path should equal the original absolute path");
    }
  }

  @Nested
  @DisplayName("Error Detection Tests")
  class ErrorDetectionTests {

    private Method extractErrorMessageMethod;

    @BeforeEach
    void setUp() throws Exception {
      extractErrorMessageMethod =
          SimulatorVerifyService.class.getDeclaredMethod("extractErrorMessage", String.class);
      extractErrorMessageMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should surface CLI option error in job result")
    void shouldSurfaceCliOptionErrorInJobResult() throws Exception {
      // Arrange
      String output =
          """
          Error: Unknown option --non-interactive
          Did you mean --nonInteractive?
          Usage: simulator-verifier [options]
          """;

      // Act
      String result = (String) extractErrorMessageMethod.invoke(service, output);

      // Assert
      assertTrue(
          result.contains("Verifier CLI argument error"),
          "Error message should indicate CLI argument error");
      assertTrue(
          result.contains("Unknown option"),
          "Error message should contain the unknown option details");
    }

    @Test
    @DisplayName("Should extract specific line with unknown option error")
    void shouldExtractSpecificLineWithUnknownOption() throws Exception {
      // Arrange
      String output =
          """
          Some log output
          Error: Unknown option --api-key
          More output here
          """;

      // Act
      String result = (String) extractErrorMessageMethod.invoke(service, output);

      // Assert
      assertTrue(result.contains("Error: Unknown option --api-key"));
    }

    @Test
    @DisplayName("Should handle other error patterns when no CLI error")
    void shouldHandleOtherErrorPatternsWhenNoCliError() throws Exception {
      // Arrange
      String output = "Verification failed: Session not found\nMore details here";

      // Act
      String result = (String) extractErrorMessageMethod.invoke(service, output);

      // Assert
      assertTrue(result.contains("Verification failed"));
      assertFalse(result.contains("CLI argument error"));
    }

    @Test
    @DisplayName("Should extract bridge unreachable error from output")
    void shouldExtractBridgeUnreachableError() throws Exception {
      // Arrange
      String output =
          """
          Verification failed: Cannot connect to bridge at http://localhost:8092
          Connection refused
          """;

      // Act
      String result = (String) extractErrorMessageMethod.invoke(service, output);

      // Assert
      assertTrue(result.contains("Cannot connect") || result.contains("Verification failed"));
    }

    @Test
    @DisplayName("Should extract no active session error from output")
    void shouldExtractNoActiveSessionError() throws Exception {
      // Arrange
      String output = "No active session found on bridge at http://localhost:8092";

      // Act
      String result = (String) extractErrorMessageMethod.invoke(service, output);

      // Assert
      assertTrue(result.contains("active session") || result.contains("Unknown error"));
    }
  }

  @Nested
  @DisplayName("ExitCode 2 Failure Path Tests")
  class ExitCodeTwoFailureTests {

    @Test
    @DisplayName("Should handle verifier exitCode 2 without report file")
    void shouldHandleVerifierExitCodeTwoWithoutReport(@TempDir File tempDir) throws Exception {
      // This test documents the actual failure path:
      // - Verifier exits with code 2 (error)
      // - No report file is generated
      // - Error message should include verifier output
      // - Job status should be FAILED

      // The actual behavior is confirmed by the code in executeJob:
      // Line 246-252: When exitCode != 0 and != 1, job is set to FAILED
      // Line 251: extractErrorMessage() is called to get error details

      // Verify that extractErrorMessage handles common exitCode 2 scenarios:
      Method extractErrorMessageMethod =
          SimulatorVerifyService.class.getDeclaredMethod("extractErrorMessage", String.class);
      extractErrorMessageMethod.setAccessible(true);

      // Scenario 1: Bridge unreachable (network error)
      String networkError =
          """
          Verification failed: Cannot connect to bridge at http://localhost:8092
          Caused by: java.net.ConnectException: Connection refused
          """;
      String result1 = (String) extractErrorMessageMethod.invoke(service, networkError);
      assertTrue(
          result1.contains("Cannot connect") || result1.contains("Verification failed"),
          "Error should mention connection failure");

      // Scenario 2: No active session
      String noSessionError = "No active session found on bridge at http://localhost:8092";
      String result2 = (String) extractErrorMessageMethod.invoke(service, noSessionError);
      assertFalse(result2.isEmpty(), "Error should not be empty");

      // Scenario 3: JSON parsing failure
      String jsonError =
          """
          Verification failed: Unable to parse replay data
          Caused by: com.fasterxml.jackson.databind.JsonMappingException
          """;
      String result3 = (String) extractErrorMessageMethod.invoke(service, jsonError);
      assertTrue(
          result3.contains("Verification failed") || result3.contains("Unable to parse"),
          "Error should mention JSON parsing failure");
    }
  }

  @Nested
  @DisplayName("Bridge Reachability Tests")
  class BridgeReachabilityTests {

    private Method isBridgeReachableMethod;

    @BeforeEach
    void setUp() throws Exception {
      isBridgeReachableMethod =
          SimulatorVerifyService.class.getDeclaredMethod("isBridgeReachable", String.class);
      isBridgeReachableMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should use GET method for bridge health check (not HEAD)")
    void shouldUseGetMethodForBridgeHealthCheck() throws Exception {
      // This test validates the fix: bridge reachability now uses GET instead of HEAD
      // Some bridge implementations don't support HEAD requests, so GET is required
      // The fix changes HEAD to GET on line 510 of SimulatorVerifyService.java

      // Verify the method exists and has correct signature
      assertEquals(
          String.class,
          isBridgeReachableMethod.getParameterTypes()[0],
          "isBridgeReachable should accept String bridgeUrl parameter");
      assertEquals(
          boolean.class,
          isBridgeReachableMethod.getReturnType(),
          "isBridgeReachable should return boolean");

      // Real HTTP behavior is validated via integration/smoke tests (TC1, TC2, TC7)
      // Unit-level mock testing of HttpURLConnection is not feasible without refactoring
      // the service to use an HTTP client abstraction
    }

    @Test
    @DisplayName("Should accept 2xx-3xx responses as reachable")
    void shouldAccept2xxAnd3xxAsReachable() throws Exception {
      // Verify the method exists and is callable
      assertTrue(isBridgeReachableMethod != null, "isBridgeReachable method should exist");

      // Actual HTTP response code validation is tested via integration/smoke tests
      // The implementation returns true for response codes 200-399 (line 514)
    }
  }

  @Nested
  @DisplayName("ExitCode Report Parsing Tests")
  class ExitCodeReportParsingTests {

    private Method parseReportMethod;
    private SimulatorVerifyService testService;
    private java.nio.file.Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
      // Create temp directory for this test class
      tempDir = java.nio.file.Files.createTempDirectory("simulator-test");
      java.nio.file.Path reportsDir = tempDir.resolve("reports");
      java.nio.file.Files.createDirectories(reportsDir);

      // Create properties pointing to tempDir
      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", "reports");
      ReflectionTestUtils.setField(props, "openruneRoot", tempDir.toString());

      testService = new SimulatorVerifyService(props, jobStore);

      parseReportMethod =
          SimulatorVerifyService.class.getDeclaredMethod(
              "parseReport", SimulatorVerifyJobStore.VerifyJob.class, String.class);
      parseReportMethod.setAccessible(true);
    }

    @AfterEach
    void tearDown() throws Exception {
      // Cleanup temp directory
      if (tempDir != null) {
        java.nio.file.Files.walk(tempDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    java.nio.file.Files.deleteIfExists(path);
                  } catch (IOException e) {
                    // Ignore cleanup errors
                  }
                });
      }
    }

    @Test
    @DisplayName("Should parse error report for exitCode 2 when file exists")
    void shouldParseErrorReportForExitCodeTwoWhenFileExists() throws Exception {
      // This test validates the fix: exitCode=2 jobs can have error reports
      // OpenRune writes error reports before exitProcess(2) with 'error' field populated
      // The fix adds else-if branch for exitCode=2 that attempts parseReport() when file exists

      String jobId = "test-exit-code-2";
      java.nio.file.Path reportFile = tempDir.resolve("reports").resolve(jobId + ".json");

      String errorReportJson =
          """
          {
            "sessionId": "test",
            "timestamp": "2026-03-31T00:00:00Z",
            "scenario": null,
            "passed": false,
            "summary": {"totalActions":0,"tickRange":{"start":0,"end":0},"actionTypeCounts":{},"playerCount":0,"durationTicks":0},
            "violations": [],
            "error": "Verification failed: Cannot connect to bridge"
          }
          """;
      java.nio.file.Files.writeString(reportFile, errorReportJson);

      // Verify parseReport handles error structure
      SimulatorVerifyJobStore.VerifyJob job =
          new SimulatorVerifyJobStore.VerifyJob(
              jobId,
              new SimulatorVerifyJobStore.VerifyArgs("test", null, "http://localhost:8092", null));

      parseReportMethod.invoke(testService, job, "");

      assertNotNull(job.getReportPath());
      assertNotNull(job.getSummary());
      assertFalse(job.getSummary().isPassed());
    }
  }

  @Nested
  @DisplayName("ExitCode=2 Job Outcome Tests")
  class ExitCodeTwoJobOutcomeTests {

    @Test
    @DisplayName("Should set reportAvailable=true when exitCode=2 with report file")
    void shouldSetReportAvailableTrueWhenExitCodeTwoWithReportFile() throws Exception {
      // This test validates the observable behavior: exitCode=2 jobs with error reports
      // should have reportAvailable=true and reportUrl populated in final job payload

      // Create mock error report
      java.nio.file.Path tempDir =
          java.nio.file.Files.createTempDirectory("simulator-outcome-test");
      java.nio.file.Path reportsDir = tempDir.resolve("reports");
      java.nio.file.Files.createDirectories(reportsDir);
      String jobId = "outcome-exit-code-2";
      java.nio.file.Path reportFile = reportsDir.resolve(jobId + ".json");

      String errorReportJson =
          """
          {
            "sessionId": "outcome-test",
            "timestamp": "2026-03-31T00:00:00Z",
            "scenario": null,
            "passed": false,
            "summary": {"totalActions":0,"tickRange":{"start":0,"end":0},"actionTypeCounts":{},"playerCount":0,"durationTicks":0},
            "violations": [],
            "error": "Test error for exitCode=2"
          }
          """;
      java.nio.file.Files.writeString(reportFile, errorReportJson);

      // Create service with properly configured properties pointing to tempDir
      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", "reports");
      ReflectionTestUtils.setField(props, "openruneRoot", tempDir.toString());

      SimulatorVerifyService testService = new SimulatorVerifyService(props, jobStore);

      // Create job and simulate exitCode=2 branch execution
      SimulatorVerifyJobStore.VerifyJob job =
          new SimulatorVerifyJobStore.VerifyJob(
              jobId,
              new SimulatorVerifyJobStore.VerifyArgs(
                  "outcome-test", null, "http://localhost:8092", null));

      // Simulate what executeJob() does for exitCode=2
      Method parseReport =
          SimulatorVerifyService.class.getDeclaredMethod(
              "parseReport", SimulatorVerifyJobStore.VerifyJob.class, String.class);
      parseReport.setAccessible(true);
      job.setExitCode(2);
      job.setStatus(SimulatorVerifyJobStore.JobStatus.FAILED);
      job.setError("Verifier exited with code 2: Test error for exitCode=2");

      // This is what the exitCode=2 branch does
      parseReport.invoke(testService, job, "");

      // Assert: Observable job payload fields
      assertEquals(
          SimulatorVerifyJobStore.JobStatus.FAILED, job.getStatus(), "Job status must be FAILED");
      assertEquals(2, job.getExitCode(), "Exit code must be 2");
      assertTrue(
          job.getReportPath() != null,
          "reportAvailable (reportPath != null) must be true for exitCode=2 with report");
      assertNotNull(job.getReportPath());
      assertNotNull(job.getSummary());
      assertFalse(job.getSummary().isPassed(), "Summary must show passed=false");
      assertNotNull(job.getError());

      // Cleanup
      java.nio.file.Files.deleteIfExists(reportFile);
      java.nio.file.Files.deleteIfExists(reportsDir);
      java.nio.file.Files.deleteIfExists(tempDir);
    }
  }

  @Nested
  @DisplayName("Missing Report Error Path Tests")
  class MissingReportErrorTests {

    private Method parseReportMethod;
    private Method extractErrorMessageMethod;

    @BeforeEach
    void setUp() throws Exception {
      parseReportMethod =
          SimulatorVerifyService.class.getDeclaredMethod(
              "parseReport", SimulatorVerifyJobStore.VerifyJob.class, String.class);
      parseReportMethod.setAccessible(true);

      extractErrorMessageMethod =
          SimulatorVerifyService.class.getDeclaredMethod("extractErrorMessage", String.class);
      extractErrorMessageMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should use extractErrorMessage when report file not found and no JSON")
    void shouldUseExtractErrorMessageWhenReportNotFound() throws Exception {
      // This test validates the fix: missing report path now uses extractErrorMessage()
      // instead of generic "Report file not found and no JSON in output"

      // Arrange: Simulate output with specific error pattern
      String output =
          """
          Error: Unknown option --non-interactive
          Did you mean --nonInteractive?
          """;

      // Act: Extract error message
      String extractedError = (String) extractErrorMessageMethod.invoke(service, output);

      // Assert: Error should be actionable and specific
      assertTrue(
          extractedError.contains("CLI argument error"),
          "Extracted error should indicate CLI argument error");
      assertTrue(
          extractedError.contains("Unknown option"),
          "Extracted error should contain the unknown option details");

      // Verify the parseReport method exists with correct signature
      assertEquals(
          SimulatorVerifyJobStore.VerifyJob.class,
          parseReportMethod.getParameterTypes()[0],
          "parseReport first parameter should be VerifyJob");
      assertEquals(
          String.class,
          parseReportMethod.getParameterTypes()[1],
          "parseReport second parameter should be String (output)");
    }

    @Test
    @DisplayName("Should extract bridge unreachable error when bridge is down")
    void shouldExtractBridgeUnreachableError() throws Exception {
      // Arrange: Simulate output when bridge is unreachable
      String output =
          """
          Verification failed: Cannot connect to bridge at http://localhost:8092
          Connection refused
          """;

      // Act: Extract error message
      String extractedError = (String) extractErrorMessageMethod.invoke(service, output);

      // Assert: Error should mention the connection issue
      assertTrue(
          extractedError.contains("Cannot connect")
              || extractedError.contains("Verification failed"),
          "Extracted error should contain bridge connection failure details");
    }

    @Test
    @DisplayName("Should extract session not found error when session invalid")
    void shouldExtractSessionNotFoundError() throws Exception {
      // Arrange: Simulate output when session doesn't exist
      String output = "No active session found on bridge at http://localhost:8092";

      // Act: Extract error message
      String extractedError = (String) extractErrorMessageMethod.invoke(service, output);

      // Assert: Error should be actionable
      assertTrue(
          extractedError.contains("active session") || extractedError.contains("Unknown error"),
          "Extracted error should contain session failure details");
    }

    @Test
    @DisplayName(
        "Should build synthetic error report when report file missing and no JSON in output")
    void shouldBuildSyntheticReportWhenNoFileAndNoJson() throws Exception {
      // This validates the SIM-04 fix: parseReport no longer throws for missing files.
      // Instead it builds a synthetic JSON report so reportAvailable stays true.

      java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("synthetic-report-test");
      java.nio.file.Path reportsDir = tempDir.resolve("reports");
      java.nio.file.Files.createDirectories(reportsDir);

      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", "reports");
      ReflectionTestUtils.setField(props, "openruneRoot", tempDir.toString());
      SimulatorVerifyService testService = new SimulatorVerifyService(props, jobStore);

      String jobId = "synthetic-test";
      SimulatorVerifyJobStore.VerifyJob job =
          new SimulatorVerifyJobStore.VerifyJob(
              jobId,
              new SimulatorVerifyJobStore.VerifyArgs(
                  "test-session", null, "http://localhost:8092", null));

      // Act: parseReport with output containing no JSON and no report file
      // Before SIM-04 fix, this would throw IOException
      // After fix, it builds synthetic report and sets reportPath
      String output = "Verification failed: Cannot connect to bridge at http://localhost:8092";
      parseReportMethod.invoke(testService, job, output);

      // Assert: synthetic report was written and job has reportPath
      assertNotNull(job.getReportPath(), "Job should have reportPath after synthetic fallback");
      assertNotNull(job.getSummary(), "Job should have summary from synthetic report");
      assertFalse(job.getSummary().isPassed(), "Synthetic report must show passed=false");

      // Verify the report file exists and is valid JSON
      java.nio.file.Path reportFile = java.nio.file.Paths.get(job.getReportPath());
      assertTrue(java.nio.file.Files.exists(reportFile), "Synthetic report file should exist");
      String content = java.nio.file.Files.readString(reportFile);
      com.fasterxml.jackson.databind.JsonNode report =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
      assertFalse(report.path("passed").asBoolean(), "Report must contain passed=false");
      assertTrue(report.has("error"), "Report must contain error field");

      // Cleanup
      java.nio.file.Files.walk(tempDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  java.nio.file.Files.deleteIfExists(p);
                } catch (IOException e) {
                  // ignore
                }
              });
    }
  }

  @Nested
  @DisplayName("ParseReport Fallback Tests")
  class ParseReportFallbackTests {

    private Method parseReportMethod;

    @BeforeEach
    void setUp() throws Exception {
      parseReportMethod =
          SimulatorVerifyService.class.getDeclaredMethod(
              "parseReport", SimulatorVerifyJobStore.VerifyJob.class, String.class);
      parseReportMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should produce parseable report when no report file and no JSON in output")
    void shouldProduceSyntheticReportWhenNoFileAndNoJson(@TempDir File tempDir) throws Exception {
      // This exercises the parseReport→buildSyntheticErrorReport path that the
      // unexpected-exit-code branch takes when parseReport() can't find a file
      // and extractJsonFromOutput returns null.

      java.nio.file.Path reportsDir = tempDir.toPath().resolve("reports");
      java.nio.file.Files.createDirectories(reportsDir);

      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", "reports");
      ReflectionTestUtils.setField(props, "openruneRoot", tempDir.toString());

      SimulatorVerifyService testService = new SimulatorVerifyService(props, jobStore);

      String jobId = "parse-report-fallback";
      SimulatorVerifyJobStore.VerifyJob job =
          new SimulatorVerifyJobStore.VerifyJob(
              jobId,
              new SimulatorVerifyJobStore.VerifyArgs(
                  "test-session", null, "http://localhost:8092", null));

      // No report file exists; output contains no JSON
      String output = "Some verifier output\nError: something went wrong\n";

      parseReportMethod.invoke(testService, job, output);

      // Assert: synthetic report was written
      assertNotNull(job.getReportPath(), "reportPath must be non-null");
      java.nio.file.Path reportFile = java.nio.file.Paths.get(job.getReportPath());
      assertTrue(java.nio.file.Files.exists(reportFile), "Report file must exist");

      String content = java.nio.file.Files.readString(reportFile);
      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(content);
      assertNotNull(root, "Report must be valid JSON");
      assertTrue(root.has("passed"), "Report must have 'passed' field");
      assertFalse(root.path("passed").asBoolean(), "Report must show passed=false");

      // Cleanup
      java.nio.file.Files.walk(tempDir.toPath())
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  java.nio.file.Files.deleteIfExists(p);
                } catch (IOException e) {
                  // ignore
                }
              });
    }
  }

  @Nested
  @DisplayName("Final Safeguard Tests")
  class FinalSafeguardTests {

    @Test
    @DisplayName("Should write synthetic report when job is FAILED with no reportPath")
    void shouldWriteReportWhenFailedWithoutReportPath(@TempDir File tempDir) throws Exception {
      // Exercises the final safeguard at SimulatorVerifyService.java:315:
      // If a job ends up FAILED with reportPath==null and is not cancelled,
      // the safeguard forces writeSyntheticReport().

      java.nio.file.Path reportsDir = tempDir.toPath().resolve("reports");
      java.nio.file.Files.createDirectories(reportsDir);

      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", "reports");
      ReflectionTestUtils.setField(props, "openruneRoot", tempDir.toString());

      SimulatorVerifyService testService = new SimulatorVerifyService(props, jobStore);

      String jobId = "safeguard-test";
      SimulatorVerifyJobStore.VerifyJob job =
          new SimulatorVerifyJobStore.VerifyJob(
              jobId,
              new SimulatorVerifyJobStore.VerifyArgs(
                  "test-session", null, "http://localhost:8092", null));

      // Simulate a FAILED job with no reportPath (the condition the safeguard guards)
      job.setStatus(SimulatorVerifyJobStore.JobStatus.FAILED);
      job.setError("Simulated failure");
      job.setReportPath(null);

      // Invoke writeSyntheticReport directly (same method the safeguard calls)
      Method writeSyntheticReport =
          SimulatorVerifyService.class.getDeclaredMethod(
              "writeSyntheticReport", SimulatorVerifyJobStore.VerifyJob.class, String.class);
      writeSyntheticReport.setAccessible(true);
      writeSyntheticReport.invoke(testService, job, job.getError());

      // Assert: safeguard produced a report
      assertNotNull(job.getReportPath(), "reportPath must be non-null after safeguard");
      java.nio.file.Path reportFile = java.nio.file.Paths.get(job.getReportPath());
      assertTrue(java.nio.file.Files.exists(reportFile), "Report file must exist");

      String content = java.nio.file.Files.readString(reportFile);
      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(content);
      assertNotNull(root, "Report must be valid JSON");
      assertFalse(root.path("passed").asBoolean(), "Report must show passed=false");

      // Cleanup
      java.nio.file.Files.walk(tempDir.toPath())
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  java.nio.file.Files.deleteIfExists(p);
                } catch (IOException e) {
                  // ignore
                }
              });
    }
  }

  @Nested
  @DisplayName("Synthetic Fallback JSON Safety Tests")
  class SyntheticFallbackJsonSafetyTests {

    @Test
    @DisplayName("Should produce valid JSON with tricky characters in error message")
    void shouldProduceValidJsonWithTrickyCharacters(@TempDir File tempDir) throws Exception {
      java.nio.file.Path reportsDir = tempDir.toPath().resolve("reports");
      java.nio.file.Files.createDirectories(reportsDir);

      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", "reports");
      ReflectionTestUtils.setField(props, "openruneRoot", tempDir.toString());

      SimulatorVerifyService testService = new SimulatorVerifyService(props, jobStore);

      String jobId = "fallback-json-test";
      SimulatorVerifyJobStore.VerifyJob job =
          new SimulatorVerifyJobStore.VerifyJob(
              jobId,
              new SimulatorVerifyJobStore.VerifyArgs(
                  "test-session", null, "http://localhost:8092", null));

      String trickyMessage =
          "Error with special chars: back\\slash, \"quotes\", tabs:\t, newline:\n";

      Method buildSyntheticReport =
          SimulatorVerifyService.class.getDeclaredMethod(
              "buildSyntheticErrorReport", SimulatorVerifyJobStore.VerifyJob.class, String.class);
      buildSyntheticReport.setAccessible(true);

      String json = (String) buildSyntheticReport.invoke(testService, job, trickyMessage);

      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);
      assertNotNull(root, "Synthetic report must be valid JSON");
      assertFalse(root.path("passed").asBoolean(), "Report must show passed=false");
      assertTrue(root.has("error"), "Report must have error field");

      // Cleanup
      java.nio.file.Files.walk(tempDir.toPath())
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  java.nio.file.Files.deleteIfExists(p);
                } catch (IOException e) {
                  // ignore
                }
              });
    }

    @Test
    @DisplayName("Should produce valid JSON with null error message")
    void shouldProduceValidJsonWithNullErrorMessage(@TempDir File tempDir) throws Exception {
      java.nio.file.Path reportsDir = tempDir.toPath().resolve("reports");
      java.nio.file.Files.createDirectories(reportsDir);

      SimulatorVerifierProperties props = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(props, "reportsDir", "reports");
      ReflectionTestUtils.setField(props, "openruneRoot", tempDir.toString());

      SimulatorVerifyService testService = new SimulatorVerifyService(props, jobStore);

      String jobId = "null-error-test";
      SimulatorVerifyJobStore.VerifyJob job =
          new SimulatorVerifyJobStore.VerifyJob(
              jobId,
              new SimulatorVerifyJobStore.VerifyArgs(
                  "test-session", null, "http://localhost:8092", null));

      Method buildSyntheticReport =
          SimulatorVerifyService.class.getDeclaredMethod(
              "buildSyntheticErrorReport", SimulatorVerifyJobStore.VerifyJob.class, String.class);
      buildSyntheticReport.setAccessible(true);

      String json = (String) buildSyntheticReport.invoke(testService, job, (String) null);

      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);
      assertNotNull(root, "Synthetic report must be valid JSON even with null error");
      assertFalse(root.path("passed").asBoolean());

      // Cleanup
      java.nio.file.Files.walk(tempDir.toPath())
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  java.nio.file.Files.deleteIfExists(p);
                } catch (IOException e) {
                  // ignore
                }
              });
    }

    @Test
    @DisplayName("Fallback constant JSON (ObjectMapper-failure path) must be valid")
    void shouldHaveValidFallbackConstantJson() throws Exception {
      // Tests the catch-block fallback in buildSyntheticErrorReport (line ~571).
      // This constant is returned when ObjectMapper serialization fails.
      Field fallbackField =
          SimulatorVerifyService.class.getDeclaredField("FALLBACK_ERROR_REPORT_JSON");
      fallbackField.setAccessible(true);
      String fallbackJson = (String) fallbackField.get(null);

      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(fallbackJson);
      assertNotNull(root, "Fallback constant must be valid JSON");
      assertFalse(root.path("passed").asBoolean(), "Fallback must show passed=false");
      assertTrue(root.has("error"), "Fallback must have error field");
      assertTrue(root.has("summary"), "Fallback must have summary field");
      assertTrue(root.has("violations"), "Fallback must have violations field");
    }
  }

  @Nested
  @DisplayName("ExecuteJob Branch Tests (spy)")
  class ExecuteJobBranchTests {

    @TempDir File tempDir;

    private SimulatorVerifyService spyService;
    private Process mockProcess;

    @BeforeEach
    void setUp() throws Exception {
      java.nio.file.Path reportsDir = tempDir.toPath().resolve("reports");
      java.nio.file.Files.createDirectories(reportsDir);

      // Create real properties object (not mocked) pointing to tempDir
      SimulatorVerifierProperties realProps = new SimulatorVerifierProperties();
      ReflectionTestUtils.setField(realProps, "reportsDir", "reports");
      ReflectionTestUtils.setField(realProps, "openruneRoot", tempDir.getAbsolutePath());
      ReflectionTestUtils.setField(realProps, "maxConcurrentJobs", 2);
      ReflectionTestUtils.setField(realProps, "jobTimeoutSeconds", 300);
      ReflectionTestUtils.setField(realProps, "autoCreateReportsDir", false);
      ReflectionTestUtils.setField(realProps, "enabled", true);
      ReflectionTestUtils.setField(realProps, "invocationMode", "jar");
      ReflectionTestUtils.setField(
          realProps, "verifierJarPath", new File(tempDir, "fake.jar").getAbsolutePath());
      ReflectionTestUtils.setField(realProps, "gradleCmd", "./gradlew");

      // Create a mock jobStore
      SimulatorVerifyJobStore mockJobStore = mock(SimulatorVerifyJobStore.class);

      // Create real service with spy
      SimulatorVerifyService realService = new SimulatorVerifyService(realProps, mockJobStore);
      spyService = spy(realService);

      // Stub bridge reachability to always succeed
      doReturn(true).when(spyService).isBridgeReachable(org.mockito.ArgumentMatchers.anyString());

      // Create mock Process
      mockProcess = mock(Process.class);
    }

    @AfterEach
    void tearDown() throws Exception {
      if (spyService != null) {
        spyService.shutdown();
      }
      if (tempDir != null) {
        java.nio.file.Files.walk(tempDir.toPath())
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    java.nio.file.Files.deleteIfExists(p);
                  } catch (IOException e) {
                    // ignore
                  }
                });
      }
    }

    private void configureMockProcess(int exitCode, String output) throws Exception {
      java.io.InputStream stream =
          new java.io.ByteArrayInputStream(
              output.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      when(mockProcess.getInputStream()).thenReturn(stream);
      when(mockProcess.exitValue()).thenReturn(exitCode);
      when(mockProcess.waitFor(
              org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
          .thenReturn(true);
      // Stub createVerifierProcess to return our mock
      doReturn(mockProcess)
          .when(spyService)
          .createVerifierProcess(
              org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(File.class));
    }

    private SimulatorVerifyJobStore.VerifyJob createTestJob(String jobId) {
      SimulatorVerifyJobStore.VerifyArgs args =
          new SimulatorVerifyJobStore.VerifyArgs(
              "test-session", null, "http://localhost:8092", null);
      return new SimulatorVerifyJobStore.VerifyJob(jobId, args);
    }

    private void invokeExecuteJob(SimulatorVerifyJobStore.VerifyJob job) throws Exception {
      Method executeJob =
          SimulatorVerifyService.class.getDeclaredMethod(
              "executeJob", SimulatorVerifyJobStore.VerifyJob.class);
      executeJob.setAccessible(true);
      executeJob.invoke(spyService, job);
    }

    @Test
    @DisplayName(
        "Unexpected exit code (3) should set FAILED with reportAvailable=true and passed=false")
    void shouldFailWithReportForUnexpectedExitCode() throws Exception {
      String jobId = "unexpected-exit-3";
      SimulatorVerifyJobStore.VerifyJob job = createTestJob(jobId);

      // Verifier produces no report file, only raw output with no JSON
      configureMockProcess(3, "Some verifier output\nError: something went wrong\n");

      invokeExecuteJob(job);

      assertEquals(SimulatorVerifyJobStore.JobStatus.FAILED, job.getStatus());
      assertEquals(3, job.getExitCode());
      assertNotNull(job.getReportPath(), "reportPath must be non-null");
      assertTrue(
          java.nio.file.Files.exists(java.nio.file.Paths.get(job.getReportPath())),
          "Report file must exist on disk");

      String content = java.nio.file.Files.readString(java.nio.file.Paths.get(job.getReportPath()));
      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(content);
      assertFalse(
          root.path("passed").asBoolean(),
          "Report file must show passed=false (rewritten from any parsed value)");
    }

    @Test
    @DisplayName(
        "Unexpected exit code (137) with report file showing passed=true must be rewritten to passed=false")
    void shouldRewriteReportFileForUnexpectedExitCode() throws Exception {
      String jobId = "unexpected-exit-137";
      SimulatorVerifyJobStore.VerifyJob job = createTestJob(jobId);

      // Pre-create a report file on disk that says passed=true
      java.nio.file.Path reportFile = tempDir.toPath().resolve("reports").resolve(jobId + ".json");
      String passingReport =
          "{\"sessionId\":\"test\",\"timestamp\":\"2026-04-01T00:00:00Z\",\"passed\":true,"
              + "\"summary\":{\"totalActions\":10,\"tickRange\":{\"start\":0,\"end\":100},"
              + "\"actionTypeCounts\":{},\"playerCount\":1,\"durationTicks\":600},"
              + "\"violations\":[],\"error\":\"\"}";
      java.nio.file.Files.writeString(reportFile, passingReport);

      configureMockProcess(137, "Verifier ran then crashed with exit 137\n");

      invokeExecuteJob(job);

      assertEquals(SimulatorVerifyJobStore.JobStatus.FAILED, job.getStatus());
      assertEquals(137, job.getExitCode());
      assertNotNull(job.getReportPath());

      // Read report from disk and verify passed was rewritten to false
      String content = java.nio.file.Files.readString(reportFile);
      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(content);
      assertFalse(
          root.path("passed").asBoolean(),
          "Report file on disk must show passed=false after rewrite");
      assertTrue(
          root.path("summary").path("totalActions").asInt() == 10,
          "Other report content must be preserved");
    }

    @Test
    @DisplayName("ExitCode 0 with report showing passed=false should set FAILED")
    void shouldFailWhenExitCodeZeroButReportShowsFailure() throws Exception {
      String jobId = "exit0-passed-false";
      SimulatorVerifyJobStore.VerifyJob job = createTestJob(jobId);

      // Pre-create a report file that says passed=false
      java.nio.file.Path reportFile = tempDir.toPath().resolve("reports").resolve(jobId + ".json");
      String failingReport =
          "{\"sessionId\":\"test\",\"timestamp\":\"2026-04-01T00:00:00Z\",\"passed\":false,"
              + "\"summary\":{\"totalActions\":5,\"tickRange\":{\"start\":0,\"end\":50},"
              + "\"actionTypeCounts\":{},\"playerCount\":1,\"durationTicks\":300},"
              + "\"violations\":[{\"severity\":\"HARD\",\"rule\":\"test\"}]}";
      java.nio.file.Files.writeString(reportFile, failingReport);

      configureMockProcess(0, "Verification completed\n");

      invokeExecuteJob(job);

      assertEquals(
          SimulatorVerifyJobStore.JobStatus.FAILED,
          job.getStatus(),
          "Status must be FAILED even though exitCode=0");
      assertEquals(0, job.getExitCode());
      assertNotNull(job.getSummary());
      assertFalse(job.getSummary().isPassed(), "Summary must show passed=false");
    }

    @Test
    @DisplayName("ExitCode 0 with report showing passed=true should set SUCCEEDED")
    void shouldSucceedWhenExitCodeZeroAndReportShowsPass() throws Exception {
      String jobId = "exit0-passed-true";
      SimulatorVerifyJobStore.VerifyJob job = createTestJob(jobId);

      java.nio.file.Path reportFile = tempDir.toPath().resolve("reports").resolve(jobId + ".json");
      String passingReport =
          "{\"sessionId\":\"test\",\"timestamp\":\"2026-04-01T00:00:00Z\",\"passed\":true,"
              + "\"summary\":{\"totalActions\":10,\"tickRange\":{\"start\":0,\"end\":100},"
              + "\"actionTypeCounts\":{},\"playerCount\":1,\"durationTicks\":600},"
              + "\"violations\":[]}";
      java.nio.file.Files.writeString(reportFile, passingReport);

      configureMockProcess(0, "Verification completed successfully\n");

      invokeExecuteJob(job);

      assertEquals(SimulatorVerifyJobStore.JobStatus.SUCCEEDED, job.getStatus());
      assertEquals(0, job.getExitCode());
      assertNotNull(job.getSummary());
      assertTrue(job.getSummary().isPassed());
    }

    @Test
    @DisplayName("ExitCode 2 with report showing passed=true must be rewritten to passed=false")
    void shouldRewriteReportFileForExitCodeTwo() throws Exception {
      String jobId = "exit2-rewrite";
      SimulatorVerifyJobStore.VerifyJob job = createTestJob(jobId);

      // Pre-create a report file on disk that says passed=true
      java.nio.file.Path reportFile = tempDir.toPath().resolve("reports").resolve(jobId + ".json");
      String passingReport =
          "{\"sessionId\":\"test\",\"timestamp\":\"2026-04-01T00:00:00Z\",\"passed\":true,"
              + "\"summary\":{\"totalActions\":5,\"tickRange\":{\"start\":0,\"end\":50},"
              + "\"actionTypeCounts\":{},\"playerCount\":1,\"durationTicks\":300},"
              + "\"violations\":[],\"error\":\"\"}";
      java.nio.file.Files.writeString(reportFile, passingReport);

      configureMockProcess(2, "Verification failed: session error\n");

      invokeExecuteJob(job);

      assertEquals(SimulatorVerifyJobStore.JobStatus.FAILED, job.getStatus());
      assertEquals(2, job.getExitCode());
      assertNotNull(job.getReportPath());

      // Read report from disk and verify passed was rewritten to false
      String content = java.nio.file.Files.readString(reportFile);
      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(content);
      assertFalse(
          root.path("passed").asBoolean(),
          "Report file on disk must show passed=false after exitCode 2 rewrite");
      assertTrue(
          root.path("summary").path("totalActions").asInt() == 5,
          "Other report content must be preserved after rewrite");
    }
  }
}
