package com.chromascape.web.simulator;

import com.chromascape.web.simulator.SimulatorVerifyJobStore.JobStatus;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifyArgs;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifyJob;
import com.chromascape.web.simulator.dto.StartVerifyRequest;
import com.chromascape.web.simulator.dto.StartVerifyResponse;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for OpenRune simulator verification.
 *
 * <p>Provides endpoints to start verification jobs, poll status, and retrieve reports.
 */
@RestController
@RequestMapping("/api/simulator/verify")
public class SimulatorVerifyController {

  private static final Logger logger = LogManager.getLogger(SimulatorVerifyController.class);

  private final SimulatorVerifyService service;
  private final SimulatorVerifierProperties properties;

  @Autowired
  public SimulatorVerifyController(
      SimulatorVerifyService service, SimulatorVerifierProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  /**
   * Starts a new simulator verification job.
   *
   * @param request Verification request with session and optional parameters
   * @return Response with job ID for polling
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> startVerification(
      @RequestBody StartVerifyRequest request) {

    if (!properties.isEnabled()) {
      Map<String, Object> error = new HashMap<>();
      error.put("error", "Simulator verifier integration is disabled");
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    try {
      // Build args with defaults from properties
      String session = request.getSession();
      String scenario = request.getScenario();
      String bridgeUrl =
          request.getBridgeUrl() != null
              ? request.getBridgeUrl()
              : properties.getDefaultBridgeUrl();
      String apiKey = request.getApiKey() != null ? request.getApiKey() : properties.getApiKey();

      VerifyArgs args = new VerifyArgs(session, scenario, bridgeUrl, apiKey);

      VerifyJob job = service.startVerification(args);

      StartVerifyResponse response = new StartVerifyResponse(job.getJobId(), JobStatus.QUEUED);
      response.setStatusUrl("/api/simulator/verify/" + job.getJobId());

      Map<String, Object> result = new HashMap<>();
      result.put("jobId", job.getJobId());
      result.put("status", job.getStatus().name());
      result.put("statusUrl", response.getStatusUrl());

      logger.info("Started verification job {} for session: {}", job.getJobId(), session);
      return ResponseEntity.ok(result);

    } catch (SimulatorVerifierException e) {
      logger.warn("Failed to start verification: {}", e.getMessage());
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getUserMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);

    } catch (Exception e) {
      logger.error("Unexpected error starting verification", e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", "An unexpected error occurred: " + e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
  }

  /**
   * Gets the current status of a verification job.
   *
   * @param jobId Job identifier
   * @return Current job status
   */
  @GetMapping("/{jobId}")
  public ResponseEntity<Map<String, Object>> getJobStatus(@PathVariable String jobId) {
    VerifyJob job = service.getJob(jobId);

    if (job == null) {
      Map<String, Object> error = new HashMap<>();
      error.put("error", "Job not found: " + jobId);
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    Map<String, Object> response = new HashMap<>();
    response.put("jobId", job.getJobId());
    response.put("status", job.getStatus().name());
    response.put("requestedAt", job.getRequestedAt().toString());

    // Include args for transparency
    Map<String, String> argsMap = new HashMap<>();
    argsMap.put("session", job.getArgs().getSession());
    if (job.getArgs().getScenario() != null) {
      argsMap.put("scenario", job.getArgs().getScenario());
    }
    argsMap.put("bridgeUrl", job.getArgs().getBridgeUrl());
    response.put("args", argsMap);

    // Include exit code if available
    if (job.getExitCode() != null) {
      response.put("exitCode", job.getExitCode());
    }

    // Include error if present
    if (job.getError() != null) {
      response.put("error", job.getError());
    }

    // Include summary if job completed successfully
    if (job.getSummary() != null) {
      Map<String, Object> summaryMap = new HashMap<>();
      summaryMap.put("passed", job.getSummary().isPassed());
      summaryMap.put("totalActions", job.getSummary().getTotalActions());
      summaryMap.put("startTick", job.getSummary().getStartTick());
      summaryMap.put("endTick", job.getSummary().getEndTick());
      summaryMap.put("playerCount", job.getSummary().getPlayerCount());
      summaryMap.put("durationMs", job.getSummary().getDurationMs());

      Map<String, Integer> violationsMap = new HashMap<>();
      violationsMap.put("hard", job.getSummary().getViolations().getHard());
      violationsMap.put("soft", job.getSummary().getViolations().getSoft());
      violationsMap.put("warn", job.getSummary().getViolations().getWarn());
      violationsMap.put("notEvaluated", job.getSummary().getViolations().getNotEvaluated());
      summaryMap.put("violations", violationsMap);

      response.put("summary", summaryMap);
    }

    // Include report availability (always include this field for UI contract)
    response.put("reportAvailable", job.getReportPath() != null);

    // Include report URL if available
    if (job.getReportPath() != null) {
      response.put("reportUrl", "/api/simulator/verify/" + jobId + "/report");
    }

    return ResponseEntity.ok(response);
  }

  /**
   * Gets the full JSON report for a completed verification job.
   *
   * @param jobId Job identifier
   * @return JSON report content
   */
  @GetMapping(value = "/{jobId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> getReport(@PathVariable String jobId) {
    try {
      String report = service.getReport(jobId);
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(report);

    } catch (SimulatorVerifierException e) {
      logger.warn("Failed to get report for job {}: {}", jobId, e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body("{\"error\":\"" + e.getUserMessage() + "\"}");
    }
  }

  /**
   * Cancels a running or queued verification job.
   *
   * <p>Terminates the active verifier process if it's still running and marks the job as cancelled.
   * Once cancelled, a job cannot resume and will remain in FAILED status.
   *
   * @param jobId Job identifier
   * @return Response with cancellation status
   */
  @PostMapping("/{jobId}/cancel")
  public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
    boolean cancelled = service.cancel(jobId);

    if (!cancelled) {
      Map<String, Object> error = new HashMap<>();
      error.put("error", "Job not found or already completed: " + jobId);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    VerifyJob job = service.getJob(jobId);
    Map<String, Object> response = new HashMap<>();
    response.put("jobId", jobId);
    response.put("status", job.getStatus().name());
    response.put("cancelled", true);

    logger.info("Cancelled verification job: {}", jobId);
    return ResponseEntity.ok(response);
  }
}
