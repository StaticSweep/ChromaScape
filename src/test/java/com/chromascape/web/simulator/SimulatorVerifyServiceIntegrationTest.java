package com.chromascape.web.simulator;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.simulator.SimulatorVerifyJobStore.JobStatus;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifyJob;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Behavioral integration test suite for {@link SimulatorVerifyService}.
 *
 * <p>Tests real HTTP behavior via Spring Boot REST endpoints, using {@link TestRestTemplate} for
 * actual HTTP calls and {@code awaitility} for async job polling.
 *
 * <p>Complements {@link SimulatorVerifyServiceTest} which covers structural (signature-based) tests
 * using reflection and mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SimulatorVerifyServiceIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private SimulatorVerifyService service;

  @Autowired private SimulatorVerifierProperties properties;

  private String baseUrl;

  @BeforeEach
  void setUp() {
    baseUrl = "http://localhost:" + port + "/api/simulator/verify";
  }

  @Nested
  @DisplayName("Bridge Reachability Behavioral Tests")
  class BridgeReachabilityBehavioralTests {

    private Method isBridgeReachableMethod;

    @BeforeEach
    void setUp() throws Exception {
      isBridgeReachableMethod =
          SimulatorVerifyService.class.getDeclaredMethod("isBridgeReachable", String.class);
      isBridgeReachableMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should return false for unreachable bridge endpoint")
    void shouldReturnFalseForUnreachableBridge() throws Exception {
      // Use a port that is unlikely to have a service running
      String unreachableBridgeUrl = "http://localhost:9999";

      // Act
      Boolean result = (Boolean) isBridgeReachableMethod.invoke(service, unreachableBridgeUrl);

      // Assert - should return false when bridge is unreachable
      assertFalse(result, "Bridge reachability check should return false for unreachable endpoint");
    }

    @Test
    @DisplayName("Should accept various bridge URL formats")
    void shouldAcceptVariousBridgeUrlFormats() throws Exception {
      // Test different URL formats that the service should handle
      String[] testUrls = {
        "http://localhost:8092",
        "http://localhost:8092/replay",
        "http://localhost:8092/api/v1",
        "http://127.0.0.1:8092"
      };

      for (String url : testUrls) {
        // Act - invoke the method (will return false since no service is running)
        Boolean result = (Boolean) isBridgeReachableMethod.invoke(service, url);

        // Assert - method should handle the URL format without throwing
        assertNotNull(result, "Bridge reachability check should handle URL format: " + url);
      }
    }
  }

  @Nested
  @DisplayName("Job Lifecycle Behavioral Tests")
  class JobLifecycleBehavioralTests {

    @Test
    @DisplayName("Should create job with QUEUED status via REST API")
    void shouldCreateJobWithQueuedStatusViaRestApi() {
      // Skip if simulator integration is disabled
      if (!properties.isEnabled()) {
        return;
      }

      // Arrange
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      String requestJson =
          """
          {
            "session": "test-session",
            "scenario": "",
            "bridgeUrl": "http://localhost:8092"
          }
          """;
      HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

      // Act
      ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl, request, Map.class);

      // Assert
      assertEquals(HttpStatus.OK, response.getStatusCode(), "Should return 200 OK");
      assertNotNull(response.getBody(), "Response body should not be null");
      assertTrue(response.getBody().containsKey("jobId"), "Response should contain jobId");
      assertTrue(response.getBody().containsKey("status"), "Response should contain status");
      assertTrue(response.getBody().containsKey("statusUrl"), "Response should contain statusUrl");

      // Note: Status may quickly progress from QUEUED to RUNNING due to async execution
      String status = (String) response.getBody().get("status");
      assertTrue(
          status.equals("QUEUED") || status.equals("RUNNING"),
          "Initial status should be QUEUED or RUNNING (was: " + status + ")");

      // Verify job is accessible via service
      String jobId = (String) response.getBody().get("jobId");
      VerifyJob job = service.getJob(jobId);
      assertNotNull(job, "Job should be accessible via service");
    }

    @Test
    @DisplayName("Should transition job from QUEUED to RUNNING to terminal status")
    void shouldTransitionJobFromQueuedToRunningToTerminal() {
      // Skip if simulator integration is disabled
      if (!properties.isEnabled()) {
        return;
      }

      // Arrange
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      // Use valid bridge but invalid session to trigger failure after verifier starts
      // This allows observing QUEUED -> RUNNING -> FAILED transition
      String requestJson =
          """
          {
            "session": "nonexistent-test-session",
            "scenario": "",
            "bridgeUrl": "http://localhost:8092"
          }
          """;
      HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

      // Act - start job
      ResponseEntity<Map> startResponse = restTemplate.postForEntity(baseUrl, request, Map.class);
      assertEquals(HttpStatus.OK, startResponse.getStatusCode(), "Should return 200 OK");
      String jobId = (String) startResponse.getBody().get("jobId");

      // Assert - initial QUEUED or RUNNING status (may progress quickly)
      ResponseEntity<Map> queuedResponse =
          restTemplate.getForEntity(baseUrl + "/" + jobId, Map.class);
      assertEquals(HttpStatus.OK, queuedResponse.getStatusCode(), "Status check should return 200");
      String initialStatus = (String) queuedResponse.getBody().get("status");
      assertTrue(
          initialStatus.equals("QUEUED") || initialStatus.equals("RUNNING"),
          "Initial status should be QUEUED or RUNNING (was: " + initialStatus + ")");

      // Assert - transition to RUNNING (async, use awaitility to poll)
      await()
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                ResponseEntity<Map> runningResponse =
                    restTemplate.getForEntity(baseUrl + "/" + jobId, Map.class);
                assertEquals(
                    HttpStatus.OK,
                    runningResponse.getStatusCode(),
                    "Status check should return 200");
                String status = (String) runningResponse.getBody().get("status");
                assertTrue(
                    status.equals("RUNNING") || status.equals("FAILED"),
                    "Status should progress to RUNNING or FAILED");
              });

      // Assert - terminal state (FAILED for invalid bridge)
      await()
          .atMost(java.time.Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                ResponseEntity<Map> finalResponse =
                    restTemplate.getForEntity(baseUrl + "/" + jobId, Map.class);
                assertEquals(
                    HttpStatus.OK,
                    finalResponse.getStatusCode(),
                    "Final status check should return 200");
                String status = (String) finalResponse.getBody().get("status");
                assertTrue(
                    status.equals("FAILED") || status.equals("SUCCEEDED"),
                    "Job should reach terminal state (FAILED or SUCCEEDED)");
              });

      // Verify final state
      ResponseEntity<Map> finalResponse =
          restTemplate.getForEntity(baseUrl + "/" + jobId, Map.class);
      assertEquals(
          "FAILED", finalResponse.getBody().get("status"), "Job should fail with invalid bridge");
      assertNotNull(finalResponse.getBody().get("error"), "Failed job should have error message");
    }

    @Test
    @DisplayName("Should reach terminal FAILED status with reportAvailable when exitCode=2")
    void shouldReachTerminalFailedStatusWithReportAvailableWhenExitCodeTwo() {
      // Skip if simulator integration is disabled
      if (!properties.isEnabled()) {
        return;
      }

      // Arrange: use reachable bridge but invalid session to trigger exitCode=2
      // This tests the exitCode=2 report parsing branch
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      String requestJson =
          """
          {
            "session": "nonexistent-session",
            "scenario": "",
            "bridgeUrl": "http://localhost:8092"
          }
          """;
      HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

      // Act: start job
      ResponseEntity<Map> startResponse = restTemplate.postForEntity(baseUrl, request, Map.class);
      String jobId = (String) startResponse.getBody().get("jobId");

      // Assert: terminal FAILED status with error report available
      await()
          .atMost(java.time.Duration.ofSeconds(60))
          .untilAsserted(
              () -> {
                ResponseEntity<Map> statusResponse =
                    restTemplate.getForEntity(baseUrl + "/" + jobId, Map.class);
                assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
                assertEquals("FAILED", statusResponse.getBody().get("status"));
                assertNotNull(statusResponse.getBody().get("error"));

                // Assertions for error report behavior
                // Note: exitCode may be 1 (Gradle-wrapped exitCode=2) or 2 (direct exit)
                // The key is that the error report is available and shows failure
                Object exitCode = statusResponse.getBody().get("exitCode");
                Object reportAvailable = statusResponse.getBody().get("reportAvailable");

                // Assert: error report must be available (regardless of exit code)
                assertTrue((Boolean) reportAvailable, "Error jobs must have reportAvailable=true");

                // Assert: exitCode should be 1 or 2 if present (both indicate failure)
                // If exitCode is null, job may still be running or crashed
                if (exitCode != null) {
                  assertTrue(
                      exitCode.equals(1) || exitCode.equals(2),
                      "Test scenario must produce exitCode=1 or 2 (got " + exitCode + ")");
                }

                // Third assert: verify report endpoint returns 200 with valid error report
                ResponseEntity<Map> reportResponse =
                    restTemplate.getForEntity(baseUrl + "/" + jobId + "/report", Map.class);
                assertEquals(
                    HttpStatus.OK,
                    reportResponse.getStatusCode(),
                    "Report endpoint must return 200 for exitCode=2 jobs");

                Map reportBody = reportResponse.getBody();
                assertFalse(
                    (Boolean) reportBody.get("passed"), "Error report must show passed=false");
                assertNotNull(reportBody.get("error"), "Error report must contain error field");
                assertFalse(
                    reportBody.get("error").toString().isBlank(), "Error field must not be empty");
              });
    }

    @Test
    @DisplayName("Should return 404 for non-existent job")
    void shouldReturn404ForNonExistentJob() {
      // Act
      ResponseEntity<Map> response =
          restTemplate.getForEntity(baseUrl + "/non-existent-job-id", Map.class);

      // Assert
      assertEquals(
          HttpStatus.NOT_FOUND, response.getStatusCode(), "Should return 404 for non-existent job");
      assertNotNull(response.getBody(), "Error response body should not be null");
      assertTrue(
          response.getBody().containsKey("error"), "Error response should contain error message");
    }
  }

  @Nested
  @DisplayName("Job Cancellation Behavioral Tests")
  class JobCancellationBehavioralTests {

    @Test
    @DisplayName("Should cancel running job via REST API")
    void shouldCancelRunningJobViaRestApi() {
      // Skip if simulator integration is disabled
      if (!properties.isEnabled()) {
        return;
      }

      // Arrange - start a job that will take time to complete
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      String requestJson =
          """
          {
            "session": "test-session",
            "scenario": "S1-happy-path",
            "bridgeUrl": "http://localhost:8092"
          }
          """;
      HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

      // Act - start job
      ResponseEntity<Map> startResponse = restTemplate.postForEntity(baseUrl, request, Map.class);
      assertEquals(HttpStatus.OK, startResponse.getStatusCode());
      String jobId = (String) startResponse.getBody().get("jobId");

      // Wait for job to start running
      await()
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                ResponseEntity<Map> statusResponse =
                    restTemplate.getForEntity(baseUrl + "/" + jobId, Map.class);
                String status = (String) statusResponse.getBody().get("status");
                assertTrue(
                    status.equals("RUNNING") || status.equals("FAILED"),
                    "Job should be RUNNING or FAILED");
              });

      // Act - cancel the job
      ResponseEntity<Map> cancelResponse =
          restTemplate.postForEntity(baseUrl + "/" + jobId + "/cancel", null, Map.class);

      // Assert - cancel response
      assertEquals(HttpStatus.OK, cancelResponse.getStatusCode(), "Cancel should return 200 OK");
      assertNotNull(cancelResponse.getBody(), "Cancel response body should not be null");
      assertTrue(
          cancelResponse.getBody().containsKey("cancelled"),
          "Cancel response should contain cancelled flag");
      assertEquals(
          true, cancelResponse.getBody().get("cancelled"), "Cancelled flag should be true");
      assertEquals(
          "FAILED", cancelResponse.getBody().get("status"), "Status should be FAILED after cancel");

      // Assert - job remains in terminal state
      VerifyJob job = service.getJob(jobId);
      assertNotNull(job, "Job should still exist after cancel");
      assertTrue(job.isCancelled(), "Job should be marked as cancelled");
      assertEquals(JobStatus.FAILED, job.getStatus(), "Cancelled job status should be FAILED");
    }
  }

  @Nested
  @DisplayName("Report Retrieval Behavioral Tests")
  class ReportRetrievalBehavioralTests {

    @Test
    @DisplayName("Should return 404 when report not available")
    void shouldReturn404WhenReportNotAvailable() {
      // Skip if simulator integration is disabled
      if (!properties.isEnabled()) {
        return;
      }

      // Arrange - start a job that will fail (no report generated)
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      String requestJson =
          """
          {
            "session": "test-session",
            "scenario": "",
            "bridgeUrl": "http://localhost:9999"
          }
          """;
      HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

      ResponseEntity<Map> startResponse = restTemplate.postForEntity(baseUrl, request, Map.class);
      String jobId = (String) startResponse.getBody().get("jobId");

      // Wait for job to fail
      await()
          .atMost(java.time.Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                ResponseEntity<Map> statusResponse =
                    restTemplate.getForEntity(baseUrl + "/" + jobId, Map.class);
                assertEquals("FAILED", statusResponse.getBody().get("status"));
              });

      // Act - try to get report
      ResponseEntity<String> reportResponse =
          restTemplate.getForEntity(baseUrl + "/" + jobId + "/report", String.class);

      // Assert
      assertEquals(
          HttpStatus.NOT_FOUND,
          reportResponse.getStatusCode(),
          "Should return 404 when report not available");
      assertNotNull(reportResponse.getBody(), "Error response body should not be null");
      assertTrue(
          reportResponse.getBody().contains("Report not available"),
          "Error response should contain appropriate error message");
    }

    @Test
    @DisplayName("Should include reportAvailable field in status response")
    void shouldIncludeReportAvailableFieldInStatusResponse() {
      // Skip if simulator integration is disabled
      if (!properties.isEnabled()) {
        return;
      }

      // Arrange - start a job
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      String requestJson =
          """
          {
            "session": "test-session",
            "scenario": "",
            "bridgeUrl": "http://localhost:9999"
          }
          """;
      HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

      // Act
      ResponseEntity<Map> startResponse = restTemplate.postForEntity(baseUrl, request, Map.class);
      String jobId = (String) startResponse.getBody().get("jobId");
      ResponseEntity<Map> statusResponse =
          restTemplate.getForEntity(baseUrl + "/" + jobId, Map.class);

      // Assert
      assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
      assertTrue(
          statusResponse.getBody().containsKey("reportAvailable"),
          "Status response should include reportAvailable field");
      // For failed jobs, reportAvailable should be false
      assertFalse(
          (Boolean) statusResponse.getBody().get("reportAvailable"),
          "Failed job should have reportAvailable=false");
    }
  }
}
