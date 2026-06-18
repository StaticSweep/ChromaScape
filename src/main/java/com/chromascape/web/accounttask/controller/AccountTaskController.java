package com.chromascape.web.accounttask.controller;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskPlan;
import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.repository.TaskEvidenceCitationRepository;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.accounttask.repository.TaskPlanRepository;
import com.chromascape.web.accounttask.service.HybridTaskSynthesisService;
import com.chromascape.web.accounttask.service.TaskPlanAccountService;
import com.chromascape.web.accounttask.service.TaskCitationValidationService;
import com.chromascape.web.accounttask.service.TaskCitationValidationService.CitationValidationResult;
import com.chromascape.web.accounttask.service.TaskPlanExecutionService;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteRequest;
import com.chromascape.web.debug.patchloop.service.PatchLoopOrchestratorService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the Account Task Planner.
 *
 * <p>Provides endpoints to generate, review, approve, reject, and execute task plans synthesized
 * from telemetry evidence.
 */
@RestController
@RequestMapping("/api/account-tasks")
public class AccountTaskController {

  private static final Logger logger = LoggerFactory.getLogger(AccountTaskController.class);

  private final TaskPlanRepository planRepository;
  private final TaskPlanItemRepository itemRepository;
  private final TaskEvidenceCitationRepository citationRepository;
  private final HybridTaskSynthesisService synthesisService;
  private final TaskPlanExecutionService executionService;
  private final TaskCitationValidationService validationService;
  private final PatchLoopOrchestratorService patchLoopOrchestratorService;
  private final TaskPlanAccountService taskPlanAccountService;

  public AccountTaskController(
      TaskPlanRepository planRepository,
      TaskPlanItemRepository itemRepository,
      TaskEvidenceCitationRepository citationRepository,
      HybridTaskSynthesisService synthesisService,
      TaskPlanExecutionService executionService,
      TaskCitationValidationService validationService,
      PatchLoopOrchestratorService patchLoopOrchestratorService,
      TaskPlanAccountService taskPlanAccountService) {
    this.planRepository = planRepository;
    this.itemRepository = itemRepository;
    this.citationRepository = citationRepository;
    this.synthesisService = synthesisService;
    this.executionService = executionService;
    this.validationService = validationService;
    this.patchLoopOrchestratorService = patchLoopOrchestratorService;
    this.taskPlanAccountService = taskPlanAccountService;
  }

  /** Generates a new task plan from intent and evidence. */
  @PostMapping("/generate")
  public ResponseEntity<Object> generatePlan(@RequestBody Map<String, Object> request) {
    try {
      String intent = (String) request.get("intent");
      if (intent == null || intent.isBlank()) {
        return ResponseEntity.badRequest().body("Intent is required");
      }

      String sessionId = (String) request.get("sessionId");
      String recordingId = (String) request.get("recordingId");

      // Default evidence window: last 24 hours
      LocalDateTime end = LocalDateTime.now();
      LocalDateTime start = end.minusHours(24);
      if (request.containsKey("evidenceStart")) {
        start = LocalDateTime.parse((String) request.get("evidenceStart"));
      }
      if (request.containsKey("evidenceEnd")) {
        end = LocalDateTime.parse((String) request.get("evidenceEnd"));
      }

      TaskPlan plan = synthesisService.generatePlan(intent, start, end, sessionId, recordingId);
      return ResponseEntity.ok(plan);

    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    } catch (Exception e) {
      logger.error("Failed to generate plan", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to generate plan: " + e.getMessage());
    }
  }

  /** Lists all task plans. */
  @GetMapping
  public ResponseEntity<Object> listPlans(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String accountName) {
    try {
      List<TaskPlan> plans;
      if (TaskPlanAccountService.UNASSIGNED_FILTER.equals(accountName)) {
        plans = planRepository.findUnassignedOrderByCreatedAtDesc();
      } else if (accountName != null) {
        plans = planRepository.findByAccountNameOrderByCreatedAtDesc(accountName);
      } else if (status != null) {
        plans =
            planRepository.findByStatusOrderByCreatedAtDesc(
                com.chromascape.web.accounttask.entity.TaskPlanStatus.valueOf(
                    status.toUpperCase()));
      } else {
        plans = planRepository.findAllByOrderByCreatedAtDesc();
      }
      return ResponseEntity.ok(plans);
    } catch (Exception e) {
      logger.error("Failed to list plans", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to list plans: " + e.getMessage());
    }
  }

  /** Gets a plan with its items and citations. */
  @GetMapping("/{planId}")
  public ResponseEntity<Object> getPlan(@PathVariable String planId) {
    try {
      var plan = planRepository.findByPlanId(planId);
      if (plan.isEmpty()) {
        return ResponseEntity.notFound().build();
      }
      List<TaskPlanItem> items = itemRepository.findByPlanIdOrderBySortOrderAsc(planId);
      List<TaskEvidenceCitation> citations = citationRepository.findByPlanId(planId);
      return ResponseEntity.ok(Map.of("plan", plan.get(), "items", items, "citations", citations));
    } catch (Exception e) {
      logger.error("Failed to get plan {}", planId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to get plan: " + e.getMessage());
    }
  }

  /** Approves an entire plan. */
  @PostMapping("/{planId}/approve")
  public ResponseEntity<Object> approvePlan(@PathVariable String planId) {
    try {
      TaskPlan plan = executionService.approvePlan(planId);
      return ResponseEntity.ok(plan);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (Exception e) {
      logger.error("Failed to approve plan {}", planId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to approve plan: " + e.getMessage());
    }
  }

  /** Rejects an entire plan. */
  @PostMapping("/{planId}/reject")
  public ResponseEntity<Object> rejectPlan(@PathVariable String planId) {
    try {
      TaskPlan plan = executionService.rejectPlan(planId);
      return ResponseEntity.ok(plan);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (Exception e) {
      logger.error("Failed to reject plan {}", planId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to reject plan: " + e.getMessage());
    }
  }

  /** Approves a single item. */
  @PostMapping("/items/{itemId}/approve")
  public ResponseEntity<Object> approveItem(@PathVariable String itemId) {
    try {
      TaskPlanItem item = executionService.approveItem(itemId);
      return ResponseEntity.ok(item);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }

  /** Rejects a single item. */
  @PostMapping("/items/{itemId}/reject")
  public ResponseEntity<Object> rejectItem(@PathVariable String itemId) {
    try {
      TaskPlanItem item = executionService.rejectItem(itemId);
      return ResponseEntity.ok(item);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }

  /** Edits an item's details. */
  @PostMapping("/items/{itemId}/edit")
  public ResponseEntity<Object> editItem(
      @PathVariable String itemId, @RequestBody Map<String, String> updates) {
    try {
      TaskPlanItem item =
          executionService.editItem(
              itemId, updates.get("title"), updates.get("description"), updates.get("scriptName"));
      return ResponseEntity.ok(item);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }

  /** Validates citations for an item. */
  @GetMapping("/items/{itemId}/validate-citations")
  public ResponseEntity<Object> validateCitations(@PathVariable String itemId) {
    CitationValidationResult result = validationService.validateCitations(itemId);
    return ResponseEntity.ok(result);
  }

  /** Executes a patch loop for an approved item. */
  @PostMapping("/items/{itemId}/execute-patch-loop")
  public ResponseEntity<Object> executePatchLoop(
      @PathVariable String itemId, @RequestBody(required = false) Map<String, Object> overrides) {
    try {
      TaskPlanItem item =
          itemRepository
              .findByItemId(itemId)
              .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

      if (item.getStatus() != TaskPlanItem.ItemStatus.APPROVED) {
        return ResponseEntity.badRequest().body("Item must be approved before execution");
      }

      CitationValidationResult citationValidation = validationService.validateCitations(itemId);
      if (!citationValidation.hasRequiredEvidenceTriplet()) {
        return ResponseEntity.badRequest()
            .body(
                Map.of(
                    "error",
                    "Evidence triplet required: LOG + TICK_LOG + RECORDING_SESSION",
                    "itemId",
                    itemId,
                    "countsByType",
                    citationValidation.getCountsByType()));
      }

      // Build patch loop request from item context
      String scriptName =
          overrides != null && overrides.containsKey("scriptName")
              ? (String) overrides.get("scriptName")
              : item.getScriptName();
      if (scriptName == null || scriptName.isBlank()) {
        scriptName = "auto-detected";
      }

      String prompt =
          overrides != null && overrides.containsKey("prompt")
              ? (String) overrides.get("prompt")
              : item.getTitle() + ": " + item.getDescription();

      PatchLoopExecuteRequest plRequest = new PatchLoopExecuteRequest(scriptName, prompt);
      plRequest.setExecutionMode("propose_then_apply");
      plRequest.setTaskPlanId(item.getPlanId());
      plRequest.setTaskItemId(item.getItemId());
      if (overrides != null && overrides.get("taskMetadata") instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> taskMetadata =
            new java.util.HashMap<>((Map<String, Object>) overrides.get("taskMetadata"));
        normalizeTaskMetadata(taskMetadata);
        plRequest.setTaskMetadata(taskMetadata);
      }

      var plResponse = patchLoopOrchestratorService.executeIteration(plRequest);

      // Link item to patch loop iteration and return the updated entity
      TaskPlanItem updatedItem =
          executionService.markItemExecuting(itemId, plResponse.getIterationId());

      return ResponseEntity.ok(Map.of("patchLoop", plResponse, "item", updatedItem));

    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (Exception e) {
      logger.error("Failed to execute patch loop for item {}", itemId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to execute patch loop: " + e.getMessage());
    }
  }

  /** Backfill missing account names for existing plans. */
  @PostMapping("/backfill-account-names")
  public ResponseEntity<Object> backfillAccountNames() {
    try {
      return ResponseEntity.ok(taskPlanAccountService.backfillMissingAccountNames());
    } catch (Exception e) {
      logger.error("Failed to backfill account names", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to backfill account names: " + e.getMessage());
    }
  }

  /** Executes a quick test run for an item without approval/evidence gating. */
  @PostMapping("/items/{itemId}/quick-test")
  public ResponseEntity<Object> quickTestItem(
      @PathVariable String itemId, @RequestBody(required = false) Map<String, Object> overrides) {
    try {
      TaskPlanItem item =
          itemRepository
              .findByItemId(itemId)
              .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

      String scriptName =
          overrides != null && overrides.containsKey("scriptName")
              ? (String) overrides.get("scriptName")
              : item.getScriptName();
      if (scriptName == null || scriptName.isBlank()) {
        scriptName = "auto-detected";
      }

      String prompt =
          overrides != null && overrides.containsKey("prompt")
              ? (String) overrides.get("prompt")
              : item.getTitle() + ": " + item.getDescription();

      PatchLoopExecuteRequest plRequest = new PatchLoopExecuteRequest(scriptName, prompt);
      plRequest.setExecutionMode("propose_then_apply");
      plRequest.setTaskPlanId(item.getPlanId());
      plRequest.setTaskItemId(item.getItemId());

      Map<String, Object> taskMetadata = new java.util.HashMap<>();
      taskMetadata.put("source", "planner_quick_test");
      if (overrides != null && overrides.get("taskMetadata") instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> overrideMetadata = (Map<String, Object>) overrides.get("taskMetadata");
        taskMetadata.putAll(overrideMetadata);
      }
      normalizeTaskMetadata(taskMetadata);
      plRequest.setTaskMetadata(taskMetadata);

      var plResponse = patchLoopOrchestratorService.executeIteration(plRequest);
      TaskPlanItem updatedItem =
          executionService.markItemExecuting(itemId, plResponse.getIterationId());

      return ResponseEntity.ok(Map.of("patchLoop", plResponse, "item", updatedItem));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (Exception e) {
      logger.error("Failed to run quick test for item {}", itemId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to run quick test: " + e.getMessage());
    }
  }

  private void normalizeTaskMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return;
    }
    Object rawTaskKey = taskMetadata.get("task_key");
    if (rawTaskKey != null && !rawTaskKey.toString().isBlank()) {
      return;
    }
    Object rawTaskName = taskMetadata.get("task_name");
    if (rawTaskName != null && !rawTaskName.toString().isBlank()) {
      taskMetadata.put("task_key", rawTaskName.toString());
    }
  }
}
