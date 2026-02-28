package com.chromascape.web.debug.patchloop.controller;

import com.chromascape.web.debug.patchloop.dto.PatchLoopEventResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopIterationResponse;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopGraphOrchestratorService;
import com.chromascape.web.debug.patchloop.service.PatchLoopOrchestratorService;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for patch loop iteration management.
 *
 * <p>Provides endpoints to execute, query, and manage BMAD (Bot Metric Analysis & Diagnostics)
 * iterations.
 */
@RestController
@RequestMapping("/api/debug/patch-loop")
public class PatchLoopController {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopController.class);

  private final PatchLoopOrchestratorService orchestratorService;
  private final PatchLoopGraphOrchestratorService graphOrchestratorService;

  @Autowired
  public PatchLoopController(
      PatchLoopOrchestratorService orchestratorService,
      PatchLoopGraphOrchestratorService graphOrchestratorService) {
    this.orchestratorService = orchestratorService;
    this.graphOrchestratorService = graphOrchestratorService;
  }

  /**
   * Executes a new patch loop iteration.
   *
   * <p>Triggers the full pipeline: script run → evidence collection → analysis → recommendations.
   *
   * @param request execution request
   * @return execution response with iteration ID
   */
  @PostMapping("/execute")
  public ResponseEntity<Object> executeIteration(@RequestBody PatchLoopExecuteRequest request) {
    logger.info("POST /api/debug/patch-loop/execute - script: {}", request.getScriptName());

    try {
      PatchLoopExecuteResponse response = orchestratorService.executeIteration(request);
      return ResponseEntity.ok(response);

    } catch (IllegalArgumentException e) {
      logger.warn("Invalid request: {}", e.getMessage());
      return ResponseEntity.badRequest().body(e.getMessage());

    } catch (Exception e) {
      logger.error("Failed to execute iteration", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to execute iteration: " + e.getMessage());
    }
  }

  /**
   * Gets an iteration by ID.
   *
   * @param iterationId iteration ID
   * @return iteration details
   */
  @GetMapping("/{iterationId}")
  public ResponseEntity<Object> getIteration(@PathVariable String iterationId) {
    logger.debug("GET /api/debug/patch-loop/{}", iterationId);

    try {
      Optional<PatchLoopIterationResponse> iteration =
          orchestratorService.getIteration(iterationId);

      if (iteration.isEmpty()) {
        return ResponseEntity.notFound().build();
      }

      return ResponseEntity.ok(iteration.get());

    } catch (Exception e) {
      logger.error("Failed to get iteration {}", iterationId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to get iteration: " + e.getMessage());
    }
  }

  /**
   * Gets events for an iteration.
   *
   * @param iterationId iteration ID
   * @return list of events in chronological order
   */
  @GetMapping("/{iterationId}/events")
  public ResponseEntity<Object> getEvents(@PathVariable String iterationId) {
    logger.debug("GET /api/debug/patch-loop/{}/events", iterationId);

    try {
      List<PatchLoopEventResponse> events = orchestratorService.getEvents(iterationId);
      return ResponseEntity.ok(events);

    } catch (Exception e) {
      logger.error("Failed to get events for iteration {}", iterationId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to get events: " + e.getMessage());
    }
  }

  /**
   * Lists iterations with optional filters.
   *
   * @param scriptName filter by script name (optional)
   * @param status filter by status (optional)
   * @return list of iterations (newest first)
   */
  @GetMapping
  public ResponseEntity<Object> listIterations(
      @RequestParam(required = false) String scriptName,
      @RequestParam(required = false) String status) {
    logger.debug("GET /api/debug/patch-loop - scriptName: {}, status: {}", scriptName, status);

    try {
      PatchLoopStatus statusEnum = null;
      if (status != null && !status.trim().isEmpty()) {
        try {
          statusEnum = PatchLoopStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
          return ResponseEntity.badRequest().body("Invalid status: " + status);
        }
      }

      List<PatchLoopIterationResponse> iterations =
          orchestratorService.listIterations(scriptName, statusEnum);

      return ResponseEntity.ok(iterations);

    } catch (Exception e) {
      logger.error("Failed to list iterations", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to list iterations: " + e.getMessage());
    }
  }

  /**
   * Gets active (non-terminal) iterations.
   *
   * @return list of active iterations
   */
  @GetMapping("/active")
  public ResponseEntity<Object> getActiveIterations() {
    logger.debug("GET /api/debug/patch-loop/active");

    try {
      // Use optimized query instead of loading all and filtering in-memory
      List<PatchLoopIterationResponse> active = orchestratorService.listActiveIterations();
      return ResponseEntity.ok(active);

    } catch (Exception e) {
      logger.error("Failed to get active iterations", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to get active iterations: " + e.getMessage());
    }
  }

  /**
   * Compiles a Mermaid graph without executing it.
   *
   * <p>Validates the Mermaid syntax and returns the compiled graph structure.
   *
   * @param request compile request with mermaid source
   * @return compiled graph info
   */
  @PostMapping("/compile")
  public ResponseEntity<Object> compileGraph(
      @RequestBody com.chromascape.web.debug.patchloop.dto.CompileRequest request) {
    logger.debug("POST /api/debug/patch-loop/compile");

    try {
      com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse.CompiledGraphInfo response =
          graphOrchestratorService.compileGraph(request.getMermaid());

      return ResponseEntity.ok(response);

    } catch (com.chromascape.web.debug.patchloop.graph.GraphCompilationException e) {
      logger.warn("Graph compilation failed: {}", e.getMessage());
      return ResponseEntity.badRequest().body("Graph compilation failed: " + e.getMessage());

    } catch (Exception e) {
      logger.error("Failed to compile graph", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to compile graph: " + e.getMessage());
    }
  }

  // ========== Graph-based Loop Execution Endpoints ==========

  /**
   * Executes a Mermaid-defined patch loop graph.
   *
   * <p>Compiles the Mermaid source and executes the autonomous loop.
   *
   * @param request graph execute request
   * @return graph execute response with execution ID
   */
  @PostMapping("/execute-graph")
  public ResponseEntity<Object> executeGraph(
      @RequestBody com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest request) {
    logger.info("POST /api/debug/patch-loop/execute-graph - script: {}", request.getScriptName());

    try {
      com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse response =
          graphOrchestratorService.executeGraph(request);

      return ResponseEntity.ok(response);

    } catch (IllegalArgumentException e) {
      logger.warn("Invalid graph request: {}", e.getMessage());
      return ResponseEntity.badRequest().body(e.getMessage());

    } catch (com.chromascape.web.debug.patchloop.graph.GraphCompilationException e) {
      logger.warn("Graph compilation failed: {}", e.getMessage());
      return ResponseEntity.badRequest().body("Graph compilation failed: " + e.getMessage());

    } catch (Exception e) {
      logger.error("Failed to execute graph", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to execute graph: " + e.getMessage());
    }
  }

  /**
   * Gets the state of a graph execution.
   *
   * @param iterationId the iteration ID
   * @return graph state response
   */
  @GetMapping("/{iterationId}/graph")
  public ResponseEntity<Object> getGraphState(@PathVariable String iterationId) {
    logger.debug("GET /api/debug/patch-loop/{}/graph", iterationId);

    try {
      com.chromascape.web.debug.patchloop.dto.GraphStateResponse response =
          graphOrchestratorService.getGraphState(iterationId);

      if (response == null) {
        return ResponseEntity.notFound().build();
      }

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to get graph state for iteration {}", iterationId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to get graph state: " + e.getMessage());
    }
  }

  /**
   * Controls a running graph execution (pause/resume/cancel).
   *
   * @param iterationId the iteration ID
   * @param request control request
   * @return empty response on success
   */
  @PostMapping("/{iterationId}/control")
  public ResponseEntity<Object> controlExecution(
      @PathVariable String iterationId,
      @RequestBody com.chromascape.web.debug.patchloop.dto.GraphControlRequest request) {
    logger.info(
        "POST /api/debug/patch-loop/{}/control - action: {}", iterationId, request.getAction());

    try {
      graphOrchestratorService.controlExecution(iterationId, request);
      return ResponseEntity.ok().build();

    } catch (IllegalArgumentException e) {
      logger.warn("Invalid control request: {}", e.getMessage());
      return ResponseEntity.badRequest().body(e.getMessage());

    } catch (com.chromascape.web.debug.patchloop.graph.GraphExecutionException e) {
      logger.warn("Graph control failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());

    } catch (Exception e) {
      logger.error("Failed to control execution for iteration {}", iterationId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to control execution: " + e.getMessage());
    }
  }

  /**
   * Gets artifacts from a graph execution.
   *
   * @param iterationId the iteration ID
   * @return artifacts response
   */
  @GetMapping("/{iterationId}/artifacts")
  public ResponseEntity<Object> getArtifacts(@PathVariable String iterationId) {
    logger.debug("GET /api/debug/patch-loop/{}/artifacts", iterationId);

    try {
      com.chromascape.web.debug.patchloop.dto.ArtifactsResponse response =
          graphOrchestratorService.getArtifacts(iterationId);

      if (response == null) {
        return ResponseEntity.notFound().build();
      }

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to get artifacts for iteration {}", iterationId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to get artifacts: " + e.getMessage());
    }
  }

  /**
   * Gets active graph executions.
   *
   * @return list of active graph executions
   */
  @GetMapping("/graph/active")
  public ResponseEntity<Object> getActiveGraphExecutions() {
    logger.debug("GET /api/debug/patch-loop/graph/active");

    try {
      return ResponseEntity.ok(graphOrchestratorService.getActiveGraphExecutions());

    } catch (Exception e) {
      logger.error("Failed to get active graph executions", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to get active graph executions: " + e.getMessage());
    }
  }
}
