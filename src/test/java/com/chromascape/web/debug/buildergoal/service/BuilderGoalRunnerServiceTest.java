package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopSessionResolverService;
import com.chromascape.web.debug.patchloop.service.ScriptExecutionService;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  @Mock private Micro6OnlineClientRegistryService clientRegistryService;
  @Mock private Micro6TaskSourcePatchService micro6TaskSourcePatchService;

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
            clientRegistryService,
            micro6TaskSourcePatchService,
            objectMapper,
            directExecutor,
            300000L);

    lenient()
        .when(iterationRepository.save(any(PatchLoopIteration.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, PatchLoopIteration.class));
  }

  @Test
  void startRun_skipsLiveTargetCatalogFetchAndPublishesWithNoCache() throws Exception {
    when(clientRegistryService.getRunnableClient("client-1"))
        .thenReturn(
            Optional.of(
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
                    java.time.Instant.now())));
    when(micro6ExecutorCatalogService.peekCatalogForBaseUrl("http://100.120.100.78:8098"))
        .thenReturn(null);
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);
    when(scriptExecutionService.publishMicro6Task(any(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                1337, false, "http://100.120.100.78:8098", "PUBLISHED"));

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
            "client-1");

    assertThat(response.get("published")).isEqualTo(true);
    assertThat(response.get("entryId")).isEqualTo(1337);
    assertThat(response.get("catalogValidation")).isEqualTo("SKIPPED_NO_CACHE");
    assertThat(response.get("catalogValidationSkipped")).isEqualTo(true);
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
    assertThat(run.get("fixBlockedReason")).isEqualTo("Analyze Failure must be run first");
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
                "micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    Map<String, Object> run = service.listRuns(10).get(0);

    assertThat(run.get("canAnalyze")).isEqualTo(true);
    assertThat(run.get("canFixAndRelaunch")).isEqualTo(true);
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
    assertThat(draft.get("prompt")).isEqualTo("Analyze the failed builder goal task '35 Woodcutting'");
    assertThat(draft.get("selectedEvidenceCount")).isEqualTo(3);
    assertThat(draft.get("totalEvidenceCount")).isEqualTo(3);
    assertThat((java.util.List<?>) draft.get("evidenceGroups")).hasSize(3);
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
