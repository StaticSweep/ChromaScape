package com.chromascape.web.debug.patchloop.controller;

import com.chromascape.web.config.McpBridgeLifecycleManager;
import com.chromascape.web.debug.patchloop.dto.PatchLoopEventResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopIterationResponse;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopAnalysisClient;
import com.chromascape.web.debug.patchloop.service.PatchLoopGraphOrchestratorService;
import com.chromascape.web.debug.patchloop.service.PatchLoopOrchestratorService;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpHealthResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
  private final PatchLoopAnalysisClient analysisClient;
  private final McpTransportClient mcpTransportClient;
  private final Micro6ExecutorCatalogService micro6ExecutorCatalogService;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient catalogHttpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${claude.api.model:claude-opus-4-6}")
  private String claudeApiModel;

  @Value("${analysis.openai.model:gpt-5-mini}")
  private String openAiCodexModel;

  @Value("${chromascape.patchloop.supervisor-base-url:http://127.0.0.1:8096}")
  private String supervisorBaseUrl;

  @Autowired(required = false)
  private McpBridgeLifecycleManager bridgeLifecycleManager;

  @Autowired
  public PatchLoopController(
      PatchLoopOrchestratorService orchestratorService,
      PatchLoopGraphOrchestratorService graphOrchestratorService,
      PatchLoopAnalysisClient analysisClient,
      McpTransportClient mcpTransportClient,
      Micro6ExecutorCatalogService micro6ExecutorCatalogService) {
    this.orchestratorService = orchestratorService;
    this.graphOrchestratorService = graphOrchestratorService;
    this.analysisClient = analysisClient;
    this.mcpTransportClient = mcpTransportClient;
    this.micro6ExecutorCatalogService = micro6ExecutorCatalogService;
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

  /**
   * Gets the current analysis provider status.
   *
   * @return provider status with name and availability
   */
  @GetMapping("/provider/status")
  public ResponseEntity<Object> getProviderStatus() {
    logger.debug("GET /api/debug/patch-loop/provider/status");

    try {
      java.util.Map<String, Object> status = new java.util.HashMap<>();
      String frameworkProvider = analysisClient.getProviderName();
      if (frameworkProvider == null || frameworkProvider.isBlank()) {
        frameworkProvider = "unknown";
      }
      String backend =
          frameworkProvider != null && frameworkProvider.endsWith("-mcp") ? "mcp" : "api";
      boolean available;
      String availabilityReason = "unknown";

      // Keep providerName for backward compatibility with older clients.
      status.put("providerName", frameworkProvider);
      status.put("frameworkProvider", frameworkProvider);
      status.put("backend", backend);
      status.put("runtimeProvider", inferRuntimeProvider(frameworkProvider, backend));
      status.put("bridgeMode", resolveBridgeMode(backend));
      status.put("defaultMode", "analysis_only"); // Current default

      if ("mcp".equals(backend)) {
        available = false;
        try {
          McpHealthResponse health = mcpTransportClient.healthCheck();
          if (health != null) {
            status.put("bridgeHealthy", health.isHealthy());
            available = health.isHealthy();
            Map<String, Object> metadata = health.getMetadata();
            status.put(
                "runtimeProvider",
                normalizeRuntimeProvider(
                    readStringMetadata(
                        metadata, "provider", String.valueOf(status.get("runtimeProvider")))));
            status.put("runtimeModel", readStringMetadata(metadata, "model", "unknown"));
            status.put("backend", readStringMetadata(metadata, "backend", backend));
            availabilityReason =
                readStringMetadata(
                    metadata,
                    "readiness",
                    health.isHealthy() ? "provider_ready" : "provider_unavailable");
          }
        } catch (Exception e) {
          logger.debug("MCP runtime metadata unavailable while building provider status", e);
          availabilityReason = "mcp_health_check_failed";
        }
      } else if ("claude-api".equals(frameworkProvider)) {
        available = analysisClient.isProviderAvailable();
        status.put("runtimeProvider", "claude-api");
        status.put("runtimeModel", claudeApiModel);
        availabilityReason = available ? "provider_ready" : "claude_api_unavailable";
      } else if ("openai-codex".equals(frameworkProvider)) {
        available = analysisClient.isProviderAvailable();
        status.put("runtimeProvider", "openai-codex");
        status.put("runtimeModel", openAiCodexModel);
        availabilityReason = available ? "provider_ready" : "openai_codex_unavailable";
      } else {
        available = analysisClient.isProviderAvailable();
        availabilityReason = available ? "provider_ready" : "provider_unavailable";
      }
      status.put("available", available);
      status.put("availabilityReason", availabilityReason);

      return ResponseEntity.ok(status);

    } catch (Exception e) {
      logger.error("Failed to get provider status", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to get provider status: " + e.getMessage());
    }
  }

  /**
   * Gets a normalized patch-loop script catalog via supervisor.
   *
   * <p>Always returns 200 with a payload that may include errors, so UI can render degraded state.
   *
   * @param refresh whether to request a fresh catalog from upstream
   * @return normalized catalog payload
   */
  @GetMapping("/catalog")
  public ResponseEntity<Object> getCatalog(
      @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
    logger.debug("GET /api/debug/patch-loop/catalog?refresh={}", refresh);

    Map<String, Object> payload = new HashMap<>();
    List<String> errors = new ArrayList<>();
    payload.put("tasks", List.of());
    payload.put("sources", Map.of());
    payload.put("availableClientTypes", List.of());
    payload.put("errors", errors);

    String base =
        supervisorBaseUrl == null || supervisorBaseUrl.isBlank()
            ? "http://127.0.0.1:8096"
            : supervisorBaseUrl;
    String catalogUrl = base + "/api/dev/workers/catalog" + (refresh ? "?refresh=true" : "");
    payload.put("catalogUrl", catalogUrl);

    List<Map<String, Object>> supervisorTasks = new ArrayList<>();
    Map<String, Object> sourceMap = new HashMap<>();

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(catalogUrl))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();
      HttpResponse<String> response =
          catalogHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 400) {
        errors.add("Supervisor catalog request failed: HTTP " + response.statusCode());
      }

      JsonNode root = objectMapper.readTree(response.body());
      if (root.path("tasks").isArray()) {
        for (JsonNode task : root.path("tasks")) {
          if (task.isObject()) {
            supervisorTasks.add(objectMapper.convertValue(task, Map.class));
          }
        }
      }
      if (root.path("sources").isObject()) {
        sourceMap.putAll(objectMapper.convertValue(root.path("sources"), Map.class));
        for (Map.Entry<String, Object> entry : sourceMap.entrySet()) {
          if (entry.getValue() instanceof Map<?, ?> sourceMeta) {
            Object ok = sourceMeta.get("ok");
            if (Boolean.FALSE.equals(ok)) {
              String reason =
                  sourceMeta.get("error") == null
                      ? "source unavailable"
                      : String.valueOf(sourceMeta.get("error"));
              errors.add(entry.getKey() + ": " + reason);
            }
          }
        }
      }
      if (root.path("enabled_client_types").isArray()) {
        List<String> clientTypes = new ArrayList<>();
        for (JsonNode value : root.path("enabled_client_types")) {
          if (value.isTextual()) {
            clientTypes.add(value.asText());
          }
        }
        payload.put("availableClientTypes", clientTypes);
      }
      if (root.path("error").isTextual() && !root.path("error").asText().isBlank()) {
        errors.add(root.path("error").asText());
      }
    } catch (Exception e) {
      errors.add("Supervisor catalog unavailable: " + e.getMessage());
    }

    List<Map<String, Object>> mergedTasks = new ArrayList<>();
    Map<String, Map<String, Object>> byTaskIdentity = new LinkedHashMap<>();
    for (Map<String, Object> task : supervisorTasks) {
      if (task == null) {
        continue;
      }
      String clientType = stringField(task, "client_type");
      if ("micro6".equalsIgnoreCase(clientType)) {
        continue;
      }
      String key = normalizedTaskIdentity(task);
      if (key != null) {
        byTaskIdentity.putIfAbsent(key, task);
      }
    }

    List<Map<String, Object>> normalizedMicro6Tasks = new ArrayList<>();
    try {
      JsonNode micro6Catalog = micro6ExecutorCatalogService.getCatalog(refresh);
      String executorError =
          micro6Catalog.path("error").isTextual() ? micro6Catalog.path("error").asText() : null;
      if (micro6Catalog.path("tasks").isArray()) {
        for (JsonNode task : micro6Catalog.path("tasks")) {
          Map<String, Object> normalized = normalizeMicro6Task(task);
          if (normalized != null) {
            normalizedMicro6Tasks.add(normalized);
          }
        }
      }
      if (executorError != null && !executorError.isBlank()) {
        sourceMap.put(
            "micro6_executor",
            Map.of(
                "ok", false,
                "error", executorError,
                "fetched_at", java.time.Instant.now().toString(),
                "source", "micro6_executor_catalog"));
        errors.add("micro6_executor: " + executorError);
      } else {
        sourceMap.put(
            "micro6_executor",
            Map.of(
                "ok", true,
                "fetched_at", java.time.Instant.now().toString(),
                "source", "micro6_executor_catalog"));
      }
    } catch (Exception e) {
      sourceMap.put(
          "micro6_executor",
          Map.of(
              "ok", false,
              "error", e.getMessage() == null ? "executor catalog unavailable" : e.getMessage(),
              "source", "micro6_executor_catalog"));
      errors.add(
          "micro6_executor: "
              + (e.getMessage() == null ? "executor catalog unavailable" : e.getMessage()));
    }

    if (normalizedMicro6Tasks.isEmpty()) {
      for (Map<String, Object> task : supervisorTasks) {
        if (task == null) {
          continue;
        }
        if (!"micro6".equalsIgnoreCase(stringField(task, "client_type"))) {
          continue;
        }
        String key = normalizedTaskIdentity(task);
        if (key != null) {
          byTaskIdentity.putIfAbsent(key, task);
        }
      }
    } else {
      for (Map<String, Object> task : normalizedMicro6Tasks) {
        String key = normalizedTaskIdentity(task);
        if (key != null) {
          byTaskIdentity.put(key, task);
        }
      }
    }

    mergedTasks.addAll(byTaskIdentity.values());
    payload.put("tasks", mergedTasks);
    payload.put("sources", sourceMap);

    return ResponseEntity.ok(payload);
  }

  private String stringField(Map<String, Object> row, String key) {
    if (row == null) {
      return null;
    }
    Object value = row.get(key);
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private String normalizedTaskIdentity(Map<String, Object> row) {
    String clientType = stringField(row, "client_type");
    String taskKey = stringField(row, "task_key");
    if (taskKey == null) {
      return null;
    }
    String normalizedClient =
        clientType == null ? "unknown" : clientType.toLowerCase(Locale.ROOT).trim();
    return normalizedClient + ":" + taskKey.toLowerCase(Locale.ROOT).trim();
  }

  private Map<String, Object> normalizeMicro6Task(JsonNode row) {
    if (row == null || !row.isObject()) {
      return null;
    }
    if (row.path("enabled").isBoolean() && !row.path("enabled").asBoolean()) {
      return null;
    }
    String taskKey = row.path("task_key").asText(null);
    if (taskKey == null || taskKey.isBlank()) {
      return null;
    }
    Map<String, Object> normalized = new LinkedHashMap<>();
    normalized.put("client_type", "micro6");
    normalized.put("execution_mode", "queue");
    normalized.put("queueable", true);
    normalized.put("source", "micro6");
    normalized.put("task_key", taskKey);
    normalized.put("display_name", row.path("display_name").asText(taskKey));

    if (row.path("description").isTextual()) {
      normalized.put("description", row.path("description").asText());
    }
    if (row.path("category").isTextual()) {
      normalized.put("category", row.path("category").asText());
    }
    if (row.path("tags").isArray()) {
      normalized.put("tags", objectMapper.convertValue(row.path("tags"), List.class));
    }
    if (row.path("args_schema").isObject()) {
      normalized.put("args_schema", objectMapper.convertValue(row.path("args_schema"), Map.class));
    }
    return normalized;
  }

  private String readStringMetadata(Map<String, Object> metadata, String key, String fallback) {
    if (metadata == null) {
      return fallback;
    }
    Object value = metadata.get(key);
    if (value == null) {
      return fallback;
    }
    String stringValue = value.toString().trim();
    return stringValue.isEmpty() ? fallback : stringValue;
  }

  private String inferRuntimeProvider(String frameworkProvider, String backend) {
    if ("api".equals(backend)) {
      return frameworkProvider;
    }
    if ("claude-mcp".equals(frameworkProvider)) {
      return "claude-cli";
    }
    if ("codex-mcp".equals(frameworkProvider)) {
      return "codex-cli";
    }
    return "mcp-wrapper";
  }

  private String normalizeRuntimeProvider(String runtimeProvider) {
    if (runtimeProvider == null || runtimeProvider.isBlank()) {
      return runtimeProvider;
    }

    return switch (runtimeProvider) {
      case "codex" -> "codex-cli";
      case "claude" -> "claude-cli";
      case "opencode" -> "opencode-cli";
      default -> runtimeProvider;
    };
  }

  private String resolveBridgeMode(String backend) {
    if (!"mcp".equals(backend)) {
      return "managed";
    }
    if (bridgeLifecycleManager == null) {
      return "external";
    }
    if (bridgeLifecycleManager.isBridgeProcessRunning()) {
      return "managed";
    }
    String mode = bridgeLifecycleManager.getBridgeMode();
    return (mode == null || mode.isBlank()) ? "external" : mode;
  }

  /**
   * Executes a patch loop for a task plan item.
   *
   * <p>This endpoint is also exposed through /api/account-tasks/items/{itemId}/execute-patch-loop,
   * which delegates here.
   *
   * @param request execution request with optional taskPlanId/taskItemId
   * @return execution response
   */
  @PostMapping("/execute-for-task")
  public ResponseEntity<Object> executeForTask(@RequestBody PatchLoopExecuteRequest request) {
    logger.info(
        "POST /api/debug/patch-loop/execute-for-task - taskItem: {}", request.getTaskItemId());
    return executeIteration(request);
  }
}
