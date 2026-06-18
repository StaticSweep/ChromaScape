package com.chromascape.web.accounttask.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
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
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteResponse;
import com.chromascape.web.debug.patchloop.service.PatchLoopOrchestratorService;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class AccountTaskControllerPatchLoopExecutionTest {

  private static final String ITEM_ID = "item_pltest";
  private static final String PLAN_ID = "plan_pltest";
  private static final String ITERATION_ID = "iter_pltest";

  @Mock private TaskPlanRepository planRepository;
  @Mock private TaskPlanItemRepository itemRepository;
  @Mock private TaskEvidenceCitationRepository citationRepository;
  @Mock private HybridTaskSynthesisService synthesisService;
  @Mock private TaskPlanExecutionService executionService;
  @Mock private TaskCitationValidationService validationService;
  @Mock private PatchLoopOrchestratorService patchLoopOrchestratorService;
  @Mock private TaskPlanAccountService taskPlanAccountService;

  private AccountTaskController controller;

  @BeforeEach
  void setUp() {
    controller =
        new AccountTaskController(
            planRepository,
            itemRepository,
            citationRepository,
            synthesisService,
            executionService,
            validationService,
            patchLoopOrchestratorService,
            taskPlanAccountService);
  }

  private TaskPlanItem approvedItem() {
    TaskPlanItem item = new TaskPlanItem();
    item.setItemId(ITEM_ID);
    item.setPlanId(PLAN_ID);
    item.setStatus(TaskPlanItem.ItemStatus.APPROVED);
    item.setScriptName("QuestScript");
    item.setTitle("Complete quest");
    item.setDescription("Finish Recipe for Disaster");
    return item;
  }

  private CitationValidationResult tripletPresent() {
    Map<EvidenceType, Integer> counts = new EnumMap<>(EvidenceType.class);
    counts.put(EvidenceType.LOG, 2);
    counts.put(EvidenceType.TICK_LOG, 3);
    counts.put(EvidenceType.RECORDING_SESSION, 1);
    return new CitationValidationResult(ITEM_ID, 6, 0, counts);
  }

  private CitationValidationResult tripletMissing() {
    Map<EvidenceType, Integer> counts = new EnumMap<>(EvidenceType.class);
    counts.put(EvidenceType.LOG, 1);
    return new CitationValidationResult(ITEM_ID, 1, 0, counts);
  }

  private CitationValidationResult counts(EvidenceType... evidenceTypes) {
    Map<EvidenceType, Integer> counts = new EnumMap<>(EvidenceType.class);
    for (EvidenceType type : evidenceTypes) {
      counts.merge(type, 1, Integer::sum);
    }
    return new CitationValidationResult(ITEM_ID, evidenceTypes.length, 0, counts);
  }

  private PatchLoopExecuteResponse plResponse() {
    PatchLoopExecuteResponse resp = new PatchLoopExecuteResponse();
    resp.setIterationId(ITERATION_ID);
    resp.setStatus("QUEUED");
    return resp;
  }

  @Test
  @DisplayName("executePatchLoop returns 400 when item not found")
  void executePatchLoop_returns400_whenItemNotFound() {
    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.empty());

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("executePatchLoop returns 400 when item not approved")
  void executePatchLoop_returns400_whenItemNotApproved() {
    TaskPlanItem item = approvedItem();
    item.setStatus(TaskPlanItem.ItemStatus.PENDING);

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("executePatchLoop returns 400 when evidence triplet missing")
  void executePatchLoop_returns400_whenEvidenceTripletMissing() {
    TaskPlanItem item = approvedItem();

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(validationService.validateCitations(ITEM_ID)).thenReturn(tripletMissing());

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) result.getBody();
    assertThat(body).containsEntry("itemId", ITEM_ID);
    assertThat(body).containsKey("countsByType");
  }

  @Test
  @DisplayName("executePatchLoop returns 400 when LOG evidence is missing")
  void executePatchLoop_returns400_whenLogEvidenceMissing() {
    TaskPlanItem item = approvedItem();

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(validationService.validateCitations(ITEM_ID))
        .thenReturn(counts(EvidenceType.TICK_LOG, EvidenceType.RECORDING_SESSION));

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("executePatchLoop returns 400 when TICK_LOG evidence is missing")
  void executePatchLoop_returns400_whenTickLogEvidenceMissing() {
    TaskPlanItem item = approvedItem();

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(validationService.validateCitations(ITEM_ID))
        .thenReturn(counts(EvidenceType.LOG, EvidenceType.RECORDING_SESSION));

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("executePatchLoop returns 400 when RECORDING_SESSION evidence is missing")
  void executePatchLoop_returns400_whenRecordingSessionEvidenceMissing() {
    TaskPlanItem item = approvedItem();

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(validationService.validateCitations(ITEM_ID))
        .thenReturn(counts(EvidenceType.LOG, EvidenceType.TICK_LOG));

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("executePatchLoop returns 200 and marks executing when valid request")
  void executePatchLoop_returns200_andMarksExecuting_whenRequestValid() {
    TaskPlanItem item = approvedItem();
    TaskPlanItem updatedItem = approvedItem();
    updatedItem.setStatus(TaskPlanItem.ItemStatus.EXECUTING);
    updatedItem.setPatchLoopIterationId(ITERATION_ID);

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(validationService.validateCitations(ITEM_ID)).thenReturn(tripletPresent());
    when(patchLoopOrchestratorService.executeIteration(any(PatchLoopExecuteRequest.class)))
        .thenReturn(plResponse());
    when(executionService.markItemExecuting(ITEM_ID, ITERATION_ID)).thenReturn(updatedItem);

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    ArgumentCaptor<PatchLoopExecuteRequest> captor =
        ArgumentCaptor.forClass(PatchLoopExecuteRequest.class);
    verify(patchLoopOrchestratorService).executeIteration(captor.capture());
    assertThat(captor.getValue().getExecutionMode()).isEqualTo("propose_then_apply");
    verify(executionService).markItemExecuting(ITEM_ID, ITERATION_ID);
  }

  @Test
  @DisplayName("executePatchLoop uses override scriptName and prompt when provided")
  void executePatchLoop_usesOverrides_whenScriptNameAndPromptProvided() {
    TaskPlanItem item = approvedItem();
    TaskPlanItem updatedItem = approvedItem();
    updatedItem.setStatus(TaskPlanItem.ItemStatus.EXECUTING);
    updatedItem.setPatchLoopIterationId(ITERATION_ID);

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(validationService.validateCitations(ITEM_ID)).thenReturn(tripletPresent());
    when(patchLoopOrchestratorService.executeIteration(any(PatchLoopExecuteRequest.class)))
        .thenReturn(plResponse());
    when(executionService.markItemExecuting(ITEM_ID, ITERATION_ID)).thenReturn(updatedItem);

    Map<String, Object> overrides =
        Map.of(
            "scriptName", "OverrideScript",
            "prompt", "Override prompt text");

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, overrides);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

    ArgumentCaptor<PatchLoopExecuteRequest> captor =
        ArgumentCaptor.forClass(PatchLoopExecuteRequest.class);
    verify(patchLoopOrchestratorService).executeIteration(captor.capture());

    PatchLoopExecuteRequest captured = captor.getValue();
    assertThat(captured.getScriptName()).isEqualTo("OverrideScript");
    assertThat(captured.getPrompt()).isEqualTo("Override prompt text");
    assertThat(captured.getExecutionMode()).isEqualTo("propose_then_apply");

    verify(executionService).markItemExecuting(ITEM_ID, ITERATION_ID);
  }

  @Test
  @DisplayName("executePatchLoop normalizes taskMetadata task_key from legacy task_name")
  void executePatchLoop_normalizesLegacyTaskMetadata() {
    TaskPlanItem item = approvedItem();
    TaskPlanItem updatedItem = approvedItem();
    updatedItem.setStatus(TaskPlanItem.ItemStatus.EXECUTING);
    updatedItem.setPatchLoopIterationId(ITERATION_ID);

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(validationService.validateCitations(ITEM_ID)).thenReturn(tripletPresent());
    when(patchLoopOrchestratorService.executeIteration(any(PatchLoopExecuteRequest.class)))
        .thenReturn(plResponse());
    when(executionService.markItemExecuting(ITEM_ID, ITERATION_ID)).thenReturn(updatedItem);

    Map<String, Object> overrides =
        Map.of(
            "taskMetadata",
            Map.of(
                "task_name", "CONFIGURABLE_COOKING",
                "display_name", "Configurable Cooking"));

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, overrides);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

    ArgumentCaptor<PatchLoopExecuteRequest> captor =
        ArgumentCaptor.forClass(PatchLoopExecuteRequest.class);
    verify(patchLoopOrchestratorService).executeIteration(captor.capture());

    PatchLoopExecuteRequest captured = captor.getValue();
    assertThat(captured.getTaskMetadata()).containsEntry("task_key", "CONFIGURABLE_COOKING");
    assertThat(captured.getTaskMetadata()).containsEntry("task_name", "CONFIGURABLE_COOKING");
    assertThat(captured.getExecutionMode()).isEqualTo("propose_then_apply");
  }

  @Test
  @DisplayName("executePatchLoop returns updated item with EXECUTING status and iterationId")
  void executePatchLoop_returnsUpdatedItemWithExecutingStatus() {
    TaskPlanItem item = approvedItem();

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(validationService.validateCitations(ITEM_ID)).thenReturn(tripletPresent());
    when(patchLoopOrchestratorService.executeIteration(any(PatchLoopExecuteRequest.class)))
        .thenReturn(plResponse());

    // Return an updated item from markItemExecuting (simulating the saved entity)
    TaskPlanItem updatedItem = approvedItem();
    updatedItem.setStatus(TaskPlanItem.ItemStatus.EXECUTING);
    updatedItem.setPatchLoopIterationId(ITERATION_ID);
    updatedItem.setExecutedAt(java.time.LocalDateTime.now());
    when(executionService.markItemExecuting(ITEM_ID, ITERATION_ID)).thenReturn(updatedItem);

    ResponseEntity<Object> result = controller.executePatchLoop(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) result.getBody();
    TaskPlanItem returnedItem = (TaskPlanItem) body.get("item");
    assertThat(returnedItem.getStatus()).isEqualTo(TaskPlanItem.ItemStatus.EXECUTING);
    assertThat(returnedItem.getPatchLoopIterationId()).isEqualTo(ITERATION_ID);
  }

  @Test
  @DisplayName("quickTestItem does not require approval or evidence triplet")
  void quickTestItem_isUngated() {
    TaskPlanItem item = approvedItem();
    item.setStatus(TaskPlanItem.ItemStatus.PENDING);
    TaskPlanItem updatedItem = approvedItem();
    updatedItem.setStatus(TaskPlanItem.ItemStatus.EXECUTING);
    updatedItem.setPatchLoopIterationId(ITERATION_ID);

    when(itemRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(item));
    when(patchLoopOrchestratorService.executeIteration(any(PatchLoopExecuteRequest.class)))
        .thenReturn(plResponse());
    when(executionService.markItemExecuting(ITEM_ID, ITERATION_ID)).thenReturn(updatedItem);

    ResponseEntity<Object> result = controller.quickTestItem(ITEM_ID, null);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(validationService, never()).validateCitations(any());

    ArgumentCaptor<PatchLoopExecuteRequest> captor =
        ArgumentCaptor.forClass(PatchLoopExecuteRequest.class);
    verify(patchLoopOrchestratorService).executeIteration(captor.capture());
    assertThat(captor.getValue().getExecutionMode()).isEqualTo("propose_then_apply");
  }
}
