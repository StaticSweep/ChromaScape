package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopSessionResolverService;
import com.chromascape.web.debug.patchloop.service.ScriptExecutionService;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.chromascape.web.logs.LogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BuilderGoalRunnerServiceTest {

  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private PatchLoopPersistenceService persistenceService;
  @Mock private PatchLoopEvidenceService evidenceService;
  @Mock private PatchLoopSessionResolverService sessionResolverService;
  @Mock private ScriptExecutionService scriptExecutionService;
  @Mock private AnalysisProvider analysisProvider;
  @Mock private Micro6ExecutorCatalogService micro6ExecutorCatalogService;
  @Mock private LogRepository logRepository;
  @Mock private BuilderGoalLauncherControlService launcherControlService;
  @Mock private BuilderGoalAccountCatalogService accountCatalogService;
  @Mock private Micro6OnlineClientRegistryService clientRegistryService;
  @Mock private Micro6TaskSourcePatchService micro6TaskSourcePatchService;
  @Mock private Micro6TargetRelaunchService micro6TargetRelaunchService;
  @Mock private Micro6TargetPreparationService targetPreparationService;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private BuilderGoalRunnerService service;

  @BeforeEach
  void setUp() {
    Executor directExecutor = Runnable::run;
    service =
        new BuilderGoalRunnerService(
            iterationRepository,
            persistenceService,
            evidenceService,
            sessionResolverService,
            scriptExecutionService,
            analysisProvider,
            micro6ExecutorCatalogService,
            logRepository,
            launcherControlService,
            accountCatalogService,
            clientRegistryService,
            micro6TaskSourcePatchService,
            micro6TargetRelaunchService,
            targetPreparationService,
            objectMapper,
            directExecutor,
            300000L,
            3,
            15000L);

    BuilderGoalLauncherControlService.LauncherMachine machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-1", "Machine 1", "ONLINE", true, true, Map.of());
    lenient()
        .when(iterationRepository.save(any(PatchLoopIteration.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, PatchLoopIteration.class));
    lenient().when(launcherControlService.listMachines()).thenReturn(List.of(machine));
    lenient().when(launcherControlService.findMachineByUuid("machine-1")).thenReturn(Optional.of(machine));
    lenient().when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
  }

  @Test
  void startRun_skipsLiveTargetCatalogFetchAndPublishesWithNoCache() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "hash-1",
            "hash-1",
            "bigGreenMeat",
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "hash-1",
            "http://100.120.100.78:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            java.time.Instant.now());
    when(accountCatalogService.findAccount("hash-1")).thenReturn(Optional.of(account));
    when(micro6ExecutorCatalogService.peekCatalogForBaseUrl(any())).thenReturn(null);
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);
    Map<String, Object> response =
        service.startRun(
            objectMapper.readTree(
                """
                {
                  "task_key": "configurable_woodcutting",
                  "settings": {
                    "treeType": "WILLOW"
                  }
                }
                """),
            "hash-1",
            null,
            null,
            null,
            "machine-1",
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isNull();
    assertThat(response.get("entryId")).isNull();
    assertThat(response.get("catalogValidation")).isEqualTo("SKIPPED_NO_CACHE");
    assertThat(response.get("catalogValidationSkipped")).isEqualTo(true);
    assertThat(((Map<?, ?>) response.get("targetPreparation")).get("state"))
        .isEqualTo("PREPARING_TARGET");
    verify(micro6ExecutorCatalogService, never())
        .getCatalogForBaseUrl("http://100.120.100.78:8098", false);
  }

  @Test
  void listRuns_exposesBlockedReasonsForFailedUnanalyzedRun() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_test1234");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    Map<String, Object> run = service.listRuns(10).get(0);

    assertThat(run.get("canAnalyze")).isEqualTo(true);
    assertThat(run.get("canFixAndRelaunch")).isEqualTo(false);
    assertThat(run.get("analysisAvailable")).isEqualTo(false);
    assertThat(run.get("patchDraftAvailable")).isEqualTo(false);
    assertThat(run.get("analyzeBlockedReason")).isNull();
    assertThat(run.get("approvalAvailable")).isEqualTo(false);
    assertThat(run.get("approvalBlockedReason")).isEqualTo("Generate a patch plan before relaunching");
    assertThat(run.get("fixBlockedReason")).isEqualTo("Generate a patch plan before relaunching");
  }

  @Test
  void listRuns_enablesFixWhenAnalysisProducedPatch() throws Exception {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_test5678");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setPatchId("patch-123");
    iteration.setAnalysisResponseJson(
        objectMapper.readTree(
            """
            {
              "schemaVersion": "2.0.0",
              "analysisId": "analysis-123",
              "status": "SUCCESS",
              "patchDraft": {
                "patchId": "patch-123",
                "description": "Fix willow state handling",
                "changes": [
                  {
                    "filePath": "tasks/configurable_woodcutting.java",
                    "oldText": "old",
                    "newText": "new"
                  }
                ]
              }
            }
            """));
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "micro6_publish", Map.of("status", "FAILED"),
                "recovery", Map.of("phase", "PATCH_PLAN_READY", "message", "Patch plan ready"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    Map<String, Object> run = service.listRuns(10).get(0);

    assertThat(run.get("canAnalyze")).isEqualTo(true);
    assertThat(run.get("canFixAndRelaunch")).isEqualTo(true);
    assertThat(run.get("approvalAvailable")).isEqualTo(true);
    assertThat(run.get("analysisAvailable")).isEqualTo(true);
    assertThat(run.get("patchDraftAvailable")).isEqualTo(true);
    assertThat(run.get("fixBlockedReason")).isNull();
  }

  @Test
  void fixAndRelaunch_requiresAnalysisFirst() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_fix0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(Map.of("micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findByIterationId("buildergoal_fix0001")).thenReturn(Optional.of(iteration));

    assertThatThrownBy(() -> service.fixAndRelaunch("buildergoal_fix0001"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Analyze Failure must be run first");
  }

  @Test
  void buildAnalysisDraft_returnsEditablePromptAndSelectedEvidence() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_draft0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setPatchTarget(BuilderGoalRunnerService.PATCH_TARGET_MICRO6_TASK_SOURCE);
    iteration.setFailureReason("Inventory full");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "task_key", "configurable_woodcutting",
                "micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findByIterationId("buildergoal_draft0001")).thenReturn(Optional.of(iteration));
    when(evidenceService.collectEvidence(eq(iteration), any())).thenReturn(sampleEvidenceBundle());

    Map<String, Object> draft = service.buildAnalysisDraft("buildergoal_draft0001");

    assertThat(draft.get("canAnalyze")).isEqualTo(true);
    assertThat(draft.get("prompt")).isEqualTo("Create a Micro6 patch plan for the failed builder goal task '35 Woodcutting'. Use my selected evidence and observations only.");
    assertThat(draft.get("selectedEvidenceCount")).isEqualTo(3);
    assertThat(draft.get("totalEvidenceCount")).isEqualTo(3);
    assertThat((java.util.List<?>) draft.get("evidenceGroups")).hasSize(3);
  }

  @Test
  void getLatestConfig_returnsNewestMatchingRun() {
    PatchLoopIteration newest = new PatchLoopIteration();
    newest.setIterationId("buildergoal_cfg0002");
    newest.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    newest.setScriptName("configurable_woodcutting");
    newest.setPrompt("35 Woodcutting");
    newest.setCreatedAt(LocalDateTime.now());
    newest.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "settings", Map.of("treeType", "WILLOW", "targetLevel", 35),
                "mule_account",
                    Map.of(
                        "account_id", "hash-1",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "source", "catalog_task")));

    PatchLoopIteration older = new PatchLoopIteration();
    older.setIterationId("buildergoal_cfg0001");
    older.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    older.setScriptName("configurable_woodcutting");
    older.setPrompt("35 Woodcutting");
    older.setCreatedAt(LocalDateTime.now().minusDays(1));
    older.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "settings", Map.of("treeType", "OAK"),
                "mule_account",
                    Map.of(
                        "account_id", "hash-1",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "source", "catalog_task")));

    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(newest, older));

    Map<String, Object> config = service.getLatestConfig("hash-1", "configurable_woodcutting");

    assertThat(config.get("configAvailable")).isEqualTo(true);
    assertThat(config.get("iterationId")).isEqualTo("buildergoal_cfg0002");
    assertThat(config.get("taskKey")).isEqualTo("configurable_woodcutting");
    assertThat(config.get("accountId")).isEqualTo("hash-1");
    assertThat(config.get("launcherMachineUuid")).isEqualTo("machine-1");
    assertThat(config.get("settings")).isEqualTo(Map.of("treeType", "WILLOW", "targetLevel", 35));
  }

  @Test
  void syncPublishedRuns_marksLostTargetRunFailedAfterGraceThreshold() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_lost0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);
    iteration.setCreatedAt(LocalDateTime.now().minusMinutes(2));
    iteration.setRunStartTime(LocalDateTime.now().minusMinutes(2));
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_target",
                    Map.of(
                        "account_name", "bigGreenMeat",
                        "advertised_base_url", "http://100.120.100.78:8098"),
                "micro6_publish",
                    Map.of(
                        "entry_id", 1001,
                        "status", "RUNNING",
                        "published_at", LocalDateTime.now().minusMinutes(2).toString(),
                        "consecutive_poll_failures", 2,
                        "last_poll_at", LocalDateTime.now().minusSeconds(30).toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));
    when(scriptExecutionService.getMicro6RunStatus(any(), eq(1001)))
        .thenReturn(
            new ScriptExecutionService.Micro6RunStatusResult(
                false,
                null,
                "Connection refused",
                null,
                "http://100.120.100.78:8098",
                1001));

    service.syncPublishedRuns();

    verify(persistenceService)
        .markIterationFailed(
            eq(iteration),
            eq(PatchLoopFailureCode.TARGET_UNREACHABLE),
            contains("Connection refused"),
            contains("publishEntryId=1001"));
  }

  @Test
  void analyzeFailure_usesEditedPromptAndSelectedEvidence() throws Exception {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_analyze0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setPatchTarget(BuilderGoalRunnerService.PATCH_TARGET_MICRO6_TASK_SOURCE);
    iteration.setFailureReason("Tree interaction failed");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "task_key", "configurable_woodcutting",
                "micro6_publish", Map.of("status", "FAILED"))));
    AnalysisV1EvidenceBundle evidence = sampleEvidenceBundle();
    when(iterationRepository.findByIterationId("buildergoal_analyze0001"))
        .thenReturn(Optional.of(iteration), Optional.of(iteration));
    when(evidenceService.collectEvidence(eq(iteration), any())).thenReturn(evidence);
    when(evidenceService.validateEvidenceSufficiency(any())).thenReturn(true);
    AnalysisV2Response providerResponse = new AnalysisV2Response();
    providerResponse.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
    when(analysisProvider.analyzeEvidence(any())).thenReturn(providerResponse);

    @SuppressWarnings("unchecked")
    java.util.List<Map<String, Object>> groups =
        (java.util.List<Map<String, Object>>) service.buildAnalysisDraft("buildergoal_analyze0001").get("evidenceGroups");
    @SuppressWarnings("unchecked")
    java.util.List<Map<String, Object>> logItems = (java.util.List<Map<String, Object>>) groups.get(0).get("items");
    String selectedId = logItems.get(0).get("id").toString();

    service.analyzeFailure(
        "buildergoal_analyze0001",
        objectMapper.valueToTree(
            Map.of(
                "prompt", "Focus on willow detection only",
                "context", "Ignore unrelated bank noise",
                "selectedEvidenceIds", java.util.List.of(selectedId))));

    ArgumentCaptor<AnalysisV2Request> requestCaptor = ArgumentCaptor.forClass(AnalysisV2Request.class);
    verify(analysisProvider).analyzeEvidence(requestCaptor.capture());
    AnalysisV2Request request = requestCaptor.getValue();
    assertThat(request.getPrompt()).isEqualTo("Focus on willow detection only");
    assertThat(request.getContext()).isEqualTo("Ignore unrelated bank noise");
    assertThat(request.getEvidenceBundle()).isNotNull();
    assertThat(request.getEvidenceBundle().getLogs()).hasSize(1);
    assertThat(request.getEvidenceBundle().getTickLogs()).isEmpty();
    assertThat(request.getEvidenceBundle().getDecisions()).isEmpty();
    verify(persistenceService, times(1)).saveAnalysisRequest(eq(iteration), any());
  }

  @Test
  void rerun_reusesStoredAccountTaskAndSettings() throws Exception {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_rerun0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setStatus(PatchLoopStatus.COMPLETED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "settings", Map.of("treeType", "WILLOW", "targetLevel", 35),
                "mule_account",
                    Map.of(
                        "account_id", "hash-1",
                        "account_hash", "hash-1",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "source", "catalog_task")));

    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "hash-1",
            "hash-1",
            "bigGreenMeat",
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("runnable", true));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "hash-1",
            "http://100.120.100.78:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now());

    when(iterationRepository.findByIterationId("buildergoal_rerun0001")).thenReturn(Optional.of(iteration));
    when(accountCatalogService.findAccount("hash-1")).thenReturn(Optional.of(account));
    Map<String, Object> response = service.rerun("buildergoal_rerun0001");

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> run = (Map<String, Object>) response.get("run");
    assertThat(run.get("taskKey")).isEqualTo("configurable_woodcutting");
    assertThat(run.get("accountId")).isEqualTo("hash-1");
    assertThat(run.get("launcherMachineUuid")).isEqualTo("machine-1");
  }

  @Test
  void openRecoveryWorkspace_entersRecoveryModeAndPersistsWorkspaceMetadata() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_rec0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setPatchTarget(BuilderGoalRunnerService.PATCH_TARGET_MICRO6_TASK_SOURCE);
    iteration.setFailureReason("Inventory full");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setRunSessionId("debug_orig_session");
    iteration.setRunRecordingId("rec_orig_1");
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "task_key", "configurable_woodcutting",
                "micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findByIterationId("buildergoal_rec0001")).thenReturn(Optional.of(iteration));
    when(evidenceService.collectEvidence(eq(iteration), any())).thenReturn(sampleEvidenceBundle());

    Map<String, Object> workspace = service.openRecoveryWorkspace("buildergoal_rec0001");

    assertThat(workspace.get("recoveryState")).isEqualTo("AWAITING_OPERATOR_PROMPT");
    assertThat(workspace.get("debugSessionId")).isEqualTo("debug_orig_session");
    assertThat(workspace.get("recordingId")).isEqualTo("rec_orig_1");
    assertThat(workspace.get("run")).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> patchLoopSeed = (Map<String, Object>) workspace.get("patchLoopSeed");
    assertThat(patchLoopSeed).isNotNull();
    assertThat(patchLoopSeed.get("taskKey")).isEqualTo("configurable_woodcutting");
    assertThat(patchLoopSeed.get("executionMode")).isEqualTo("analysis_only");
    @SuppressWarnings("unchecked")
    Map<String, Object> sessionContext = (Map<String, Object>) patchLoopSeed.get("sessionContext");
    assertThat(sessionContext.get("recordingId")).isEqualTo("rec_orig_1");
    assertThat(sessionContext.get("debugSessionId")).isEqualTo("debug_orig_session");
    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(persistenceService, atLeastOnce()).saveIteration(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> savedMetadata = objectMapper.convertValue(captor.getValue().getTaskMetadataJson(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> recoveryWorkspace = (Map<String, Object>) savedMetadata.get("recovery_workspace");
    assertThat(recoveryWorkspace).isNotNull();
    assertThat(recoveryWorkspace.get("last_loaded_at")).isNotNull();
  }

  @Test
  void saveRecoveryAnnotations_persistsAnnotationsAndSelectedEvidence() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_ann0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "task_key", "configurable_woodcutting",
                "micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findByIterationId("buildergoal_ann0001")).thenReturn(Optional.of(iteration));

    Map<String, Object> result =
        service.saveRecoveryAnnotations(
            "buildergoal_ann0001",
            objectMapper.valueToTree(
                Map.of(
                    "annotations",
                        java.util.List.of(
                            Map.of(
                                "label", "stuck at bank",
                                "note", "bot loops here",
                                "important", true,
                                "startSeconds", 13,
                                "endSeconds", 20)),
                    "activeRangeStartSeconds", 13,
                    "activeRangeEndSeconds", 20,
                    "selectedEvidenceIds", java.util.List.of("log_1"))));

    assertThat(result.get("saved")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    java.util.List<Map<String, Object>> annotations = (java.util.List<Map<String, Object>>) result.get("annotations");
    assertThat(annotations).hasSize(1);
    assertThat(annotations.get(0).get("label")).isEqualTo("stuck at bank");
    assertThat(annotations.get(0).get("important")).isEqualTo(true);
    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(persistenceService, atLeastOnce()).saveIteration(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> savedMetadata = objectMapper.convertValue(captor.getValue().getTaskMetadataJson(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> workspace = (Map<String, Object>) savedMetadata.get("recovery_workspace");
    assertThat(workspace.get("active_start_seconds")).isEqualTo(13);
    assertThat(workspace.get("active_end_seconds")).isEqualTo(20);
  }

  @Test
  void getRecoveryState_returnsPhaseAndChildLinkage() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_state0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setRunSessionId("debug_bound_1");
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "task_key", "configurable_woodcutting",
                "recovery", Map.of("phase", "WAITING_FOR_DEBUG_BIND", "message", "waiting"),
                "recovery_workspace", Map.of("preferred_session_id", "debug_reserved_7"),
                "micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findByIterationId("buildergoal_state0001")).thenReturn(Optional.of(iteration));
    when(iterationRepository.findByParentIterationIdOrderByCreatedAtDesc("buildergoal_state0001"))
        .thenReturn(java.util.List.of());

    Map<String, Object> state = service.getRecoveryState("buildergoal_state0001");

    assertThat(state.get("recoveryPhase")).isEqualTo("WAITING_FOR_DEBUG_BIND");
    assertThat(state.get("preferredSessionId")).isEqualTo("debug_reserved_7");
    assertThat(state.get("relaunchStatus")).isEqualTo("WAITING_FOR_DEBUG_BIND");
    assertThat(state.get("bindStatus")).isEqualTo("WAITING_FOR_DEBUG_BIND");
  }

  @Test
  void openRecoveryWorkspace_restoresPersistedActiveRangeAndSelectedEvidence() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_restore0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "task_key", "configurable_woodcutting",
                "micro6_publish", Map.of("status", "FAILED"),
                "recovery_workspace",
                    Map.of(
                        "active_start_seconds", 13,
                        "active_end_seconds", 20,
                        "selected_evidence_ids", java.util.List.of("log_restore_1")))));
    when(iterationRepository.findByIterationId("buildergoal_restore0001"))
        .thenReturn(Optional.of(iteration));
    when(evidenceService.collectEvidence(eq(iteration), any())).thenReturn(sampleEvidenceBundle());

    Map<String, Object> workspace = service.openRecoveryWorkspace("buildergoal_restore0001");

    assertThat(workspace.get("activeRangeStartSeconds")).isEqualTo(13);
    assertThat(workspace.get("activeRangeEndSeconds")).isEqualTo(20);
    @SuppressWarnings("unchecked")
    java.util.List<String> selectedIds = (java.util.List<String>) workspace.get("selectedEvidenceIds");
    assertThat(selectedIds).contains("log_restore_1");
    @SuppressWarnings("unchecked")
    Map<String, Object> patchLoopSeed = (Map<String, Object>) workspace.get("patchLoopSeed");
    assertThat(patchLoopSeed).isNotNull();
    assertThat(patchLoopSeed.get("taskKey")).isEqualTo("configurable_woodcutting");
  }

  @Test
  void getRecoveryState_includesChildRunSummary() {
    PatchLoopIteration parent = new PatchLoopIteration();
    parent.setIterationId("buildergoal_parent0001");
    parent.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    parent.setScriptName("configurable_woodcutting");
    parent.setStatus(PatchLoopStatus.FAILED);
    parent.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "task_key", "configurable_woodcutting",
                "recovery", Map.of("phase", "WAITING_FOR_DEBUG_BIND"),
                "recovery_workspace", Map.of("preferred_session_id", "debug_child_42"),
                "micro6_publish", Map.of("status", "FAILED"))));

    PatchLoopIteration child = new PatchLoopIteration();
    child.setIterationId("buildergoal_child0001");
    child.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    child.setScriptName("configurable_woodcutting");
    child.setStatus(PatchLoopStatus.QUEUED);
    child.setRunSessionId("debug_child_42");
    child.setRunRecordingId("rec_child_42");
    child.setParentIterationId("buildergoal_parent0001");

    when(iterationRepository.findByIterationId("buildergoal_parent0001"))
        .thenReturn(Optional.of(parent));
    when(iterationRepository.findByIterationId("buildergoal_child0001"))
        .thenReturn(Optional.of(child));
    when(iterationRepository.findByParentIterationIdOrderByCreatedAtDesc("buildergoal_parent0001"))
        .thenReturn(java.util.List.of(child));

    Map<String, Object> state = service.getRecoveryState("buildergoal_parent0001");

    assertThat(state.get("childIterationId")).isEqualTo("buildergoal_child0001");
    assertThat(state.get("childRun")).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> childRun = (Map<String, Object>) state.get("childRun");
    assertThat(childRun.get("runSessionId")).isEqualTo("debug_child_42");
    assertThat(childRun.get("runRecordingId")).isEqualTo("rec_child_42");
  }

  private AnalysisV1EvidenceBundle sampleEvidenceBundle() {
    AnalysisV1EvidenceBundle bundle = new AnalysisV1EvidenceBundle();
    bundle.setTimeWindow(
        new com.chromascape.web.debug.patchloop.dto.TimeWindow(
            java.time.LocalDateTime.parse("2026-06-24T05:00:00"),
            java.time.LocalDateTime.parse("2026-06-24T05:05:00")));
    bundle.setLogs(
        java.util.List.of(
            new AnalysisV1EvidenceBundle.LogEvidence(
                java.time.LocalDateTime.parse("2026-06-24T05:00:30"),
                "ERROR",
                "Failed to find willow tree",
                "micro6")));
    bundle.setTickLogs(
        java.util.List.of(
            new AnalysisV1EvidenceBundle.TickLogEvidence(
                123L,
                java.time.LocalDateTime.parse("2026-06-24T05:00:31"),
                "WOODCUTTING",
                "WARN",
                "Tree interaction failed",
                "No valid target found")));
    bundle.setDecisions(
        java.util.List.of(
            new AnalysisV1EvidenceBundle.DecisionEvidence(
                java.time.LocalDateTime.parse("2026-06-24T05:00:32"),
                "CHOP_TREE",
                false,
                "NO_TARGET",
                "Willow tree",
                "Camera angle obstructed")));
    return bundle;
  }
}
