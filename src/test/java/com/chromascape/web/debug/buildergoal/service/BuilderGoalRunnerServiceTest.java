package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopSessionResolverService;
import com.chromascape.web.debug.patchloop.service.ScriptExecutionService;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.chromascape.web.logs.LogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
  @Mock private BuilderGoalRunStateMutationService mutationService;
  @Mock private BuilderGoalRunEventService runEventService;
  @Mock private BuilderGoalStartRequestService startRequests;
  private final Map<String, String> requestFingerprints = new ConcurrentHashMap<>();
  private final Map<String, String> requestBindings = new ConcurrentHashMap<>();

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final Map<String, PatchLoopIteration> savedIterations = new ConcurrentHashMap<>();
  private BuilderGoalRunStateMutationService mutationDelegate;
  private BuilderGoalLaunchOperationService launchOperationService;
  private BuilderGoalLocalLaunchCoordinatorService localLaunchCoordinator;
  private BuilderGoalRunnerService service;

  private BuilderGoalRunnerService createService(
      BuilderGoalLaunchOperationService launchOperations,
      BuilderGoalLocalLaunchCoordinatorService coordinator,
      Executor launchExecutor) {
    var created =
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
            mutationService,
            runEventService,
            launchOperations,
            coordinator,
            // Source binding is exercised by BuilderGoalSourceBindingWiringTest; this focused test
            // covers dispatch behaviour and passes null, which the response path renders as no
            // binding.
            null,
            objectMapper,
            Runnable::run,
            launchExecutor,
            "http://127.0.0.1:8098",
            300000L,
            3,
            15000L,
            "http://192.168.1.6:8084",
            "");
    org.springframework.test.util.ReflectionTestUtils.setField(
        created, "startRequestService", startRequests);
    return created;
  }

  @BeforeEach
  void setUp() {
    requestFingerprints.clear();
    requestBindings.clear();
    lenient()
        .when(startRequests.reserve(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String id = inv.getArgument(0), fingerprint = inv.getArgument(1);
              String prior = requestFingerprints.putIfAbsent(id, fingerprint);
              if (prior != null && !prior.equals(fingerprint))
                throw new BuilderGoalStartException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "REQUEST_IDENTITY_CONFLICT",
                    "REQUEST_RESERVATION",
                    "Conflicting request",
                    null,
                    null,
                    null,
                    null);
              return requestBindings.get(id);
            });
    lenient()
        .doAnswer(
            inv -> {
              String id = inv.getArgument(0), iterationId = inv.getArgument(1);
              requestBindings.put(id, iterationId);
              lenient()
                  .when(iterationRepository.findByIterationId(iterationId))
                  .thenAnswer(unused -> Optional.ofNullable(savedIterations.get(iterationId)));
              return null;
            })
        .when(startRequests)
        .bind(anyString(), anyString());
    lenient().when(launcherControlService.isLocalMachine(any())).thenReturn(true);
    savedIterations.clear();
    mutationDelegate =
        new BuilderGoalRunStateMutationService(
            iterationRepository, persistenceService, objectMapper);
    launchOperationService = new BuilderGoalLaunchOperationService();
    localLaunchCoordinator = new BuilderGoalLocalLaunchCoordinatorService();
    service = createService(launchOperationService, localLaunchCoordinator, Runnable::run);

    BuilderGoalLauncherControlService.LauncherMachine machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-1", "Machine 1", "ONLINE", true, true, Map.of());
    lenient()
        .when(iterationRepository.save(any(PatchLoopIteration.class)))
        .thenAnswer(
            invocation -> {
              PatchLoopIteration iteration = invocation.getArgument(0, PatchLoopIteration.class);
              if (iteration.getIterationId() != null) {
                savedIterations.put(iteration.getIterationId(), iteration);
              }
              return iteration;
            });
    // The runner now delegates lifecycle mutations to the transactional mutation service. Keep
    // this unit test's existing mock seam, but route the default mutation calls through a real
    // delegate so status/reconciliation tests exercise the same locked state machine as runtime.
    lenient()
        .doAnswer(
            invocation -> {
              String iterationId = invocation.getArgument(0, String.class);
              Optional<PatchLoopIteration> found =
                  iterationRepository.findByIterationId(iterationId);
              return found.isPresent()
                  ? found
                  : Optional.ofNullable(savedIterations.get(iterationId));
            })
        .when(iterationRepository)
        .findByIterationIdForUpdate(anyString());
    lenient().doReturn(Optional.empty()).when(iterationRepository).findByIterationId(anyString());
    lenient()
        .when(mutationService.reservePublish(anyString(), anyInt(), any(LocalDateTime.class)))
        .thenAnswer(
            invocation -> {
              return mutationDelegate.reservePublish(
                  invocation.getArgument(0, String.class),
                  invocation.getArgument(1, Integer.class),
                  invocation.getArgument(2, LocalDateTime.class));
            });
    lenient()
        .when(
            mutationService.applyPublishOutcome(
                anyString(),
                anyInt(),
                any(ScriptExecutionService.Micro6PublishResult.class),
                any()))
        .thenAnswer(
            invocation -> {
              return mutationDelegate.applyPublishOutcome(
                  invocation.getArgument(0, String.class),
                  invocation.getArgument(1, Integer.class),
                  invocation.getArgument(2, ScriptExecutionService.Micro6PublishResult.class),
                  invocation.getArgument(
                      3, ScriptExecutionService.Micro6ExecutorHealthResult.class));
            });
    lenient()
        .when(
            mutationService.applyExecutorStatus(
                anyString(),
                anyInt(),
                any(ScriptExecutionService.Micro6RunStatusResult.class),
                any(LocalDateTime.class)))
        .thenAnswer(
            invocation ->
                mutationDelegate.applyExecutorStatus(
                    invocation.getArgument(0, String.class),
                    invocation.getArgument(1, Integer.class),
                    invocation.getArgument(2, ScriptExecutionService.Micro6RunStatusResult.class),
                    invocation.getArgument(3, LocalDateTime.class)));
    lenient()
        .when(mutationService.beginLateReconcile(anyString()))
        .thenAnswer(
            invocation ->
                mutationDelegate.beginLateReconcile(invocation.getArgument(0, String.class)));
    lenient()
        .when(mutationService.failStaleLaunchIfActive(anyString()))
        .thenAnswer(
            invocation ->
                mutationDelegate.failStaleLaunchIfActive(invocation.getArgument(0, String.class)));
    lenient()
        .when(
            mutationService.applyLateReconcile(
                anyString(),
                anyInt(),
                any(),
                any(),
                any(ScriptExecutionService.Micro6RunStatusResult.class)))
        .thenAnswer(
            invocation ->
                mutationDelegate.applyLateReconcile(
                    invocation.getArgument(0, String.class),
                    invocation.getArgument(1, Integer.class),
                    invocation.getArgument(2, String.class),
                    invocation.getArgument(3, String.class),
                    invocation.getArgument(4, ScriptExecutionService.Micro6RunStatusResult.class)));
    lenient()
        .when(mutationService.requestStop(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String iterationId = invocation.getArgument(0, String.class);
              PatchLoopIteration found =
                  iterationRepository.findByIterationId(iterationId).orElse(null);
              Map<String, Object> metadata = new LinkedHashMap<>();
              Integer entryId = null;
              if (found != null && found.getTaskMetadataJson() != null) {
                metadata = objectMapper.convertValue(found.getTaskMetadataJson(), Map.class);
                Object rawPublish = metadata.get("micro6_publish");
                if (rawPublish instanceof Map<?, ?> publish) {
                  Object rawEntry = publish.get("entry_id");
                  if (rawEntry instanceof Number number) {
                    entryId = number.intValue();
                  }
                }
              }
              boolean prePublish = entryId == null || entryId <= 0;
              if (prePublish && found != null) {
                persistenceService.updateIterationStatus(found, PatchLoopStatus.CANCELLED);
              }
              return new BuilderGoalRunStateMutationService.StopRequestResult(
                  iterationId, entryId, prePublish, metadata, prePublish ? null : "STOPPING");
            });
    lenient().when(launcherControlService.listMachines()).thenReturn(List.of(machine));
    lenient()
        .when(launcherControlService.findMachineByUuid("machine-1"))
        .thenReturn(Optional.of(machine));
    lenient().when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    // Catalog lookup is deliberately allowed to use either the cached (false) or live (true)
    // path depending on whether the assertion exercises import, launch, or publish validation.
    // Keep the default null so tests only need to stub catalog content when it is relevant.
    lenient().when(micro6ExecutorCatalogService.getCatalog(anyBoolean())).thenReturn(null);
  }

  @Test
  void importPayload_acceptsMicro6CatalogKeyAlias() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false))
        .thenReturn(
            objectMapper.readTree(
                """
                {
                  "tasks": [
                    {
                      "key": "fish_training",
                      "name": "Fish Training",
                      "enabled": true,
                      "category": "fishing",
                      "args_schema": {
                        "fields": [
                          { "key": "targetFish" },
                          { "key": "bankingMode" }
                        ]
                      }
                    }
                  ]
                }
                """));

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "builder_execution": {
                    "task_key": "fish_training",
                    "task_metadata": {
                      "targetFish": "Best Available",
                      "bankingMode": "DROP",
                      "unknownSetting": true
                    }
                  }
                }
                """));

    assertThat(response.get("taskKey")).isEqualTo("fish_training");
    assertThat(response.get("runnableNow")).isEqualTo(true);
    assertThat(response.get("displayName")).isEqualTo("Fish Training");
    assertThat(response.get("matchedSettings")).isEqualTo(List.of("bankingMode", "targetFish"));
    assertThat(response.get("ignoredSettings")).isEqualTo(List.of("unknownSetting"));
  }

  @Test
  void importPayload_emptySettingsEnvelopeRemainsEmpty() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(null);

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "task_key": "configurable_woodcutting",
                  "settings": {}
                }
                """));

    assertThat(response.get("taskKey")).isEqualTo("configurable_woodcutting");
    assertThat(response.get("settings")).isEqualTo(Map.of());
    assertThat(response.get("settingsCount")).isEqualTo(0);
  }

  @Test
  void importPayload_resolvesCatalogAliasToCanonicalTaskKey() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false))
        .thenReturn(
            objectMapper.readTree(
                """
                {
                  "catalog_state": "READY",
                  "tasks": [
                    {
                      "key": "FISHING_TRAINING",
                      "task_key": "FISHING_TRAINING",
                      "display_name": "Fishing Training",
                      "runnable": true,
                      "settings_schema_state": "NOT_REQUESTED"
                    }
                  ],
                  "aliases": [
                    {
                      "alias": "Fish Training",
                      "normalized_alias": "FISH_TRAINING",
                      "task_key": "FISHING_TRAINING"
                    }
                  ]
                }
                """));

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "task_key": "FISH_TRAINING",
                  "settings": {}
                }
                """));

    assertThat(response.get("taskKey")).isEqualTo("FISHING_TRAINING");
    assertThat(response.get("settings")).isEqualTo(Map.of());
  }

  @Test
  void importPayload_canonicalizesCopiedFishingTargetLevelAliases() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(null);

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "task_key": "FISHING_TRAINING",
                  "settings": {
                    "stopCondition": "Level Target",
                    "stopConditionValue": 23,
                    "targetLevel": 23,
                    "target_level": 23,
                    "targetFish": "SHRIMP"
                  }
                }
                """));

    assertThat(response.get("settings"))
        .isEqualTo(
            Map.of(
                "stopCondition", "Level Target",
                "stopConditionValue", 23,
                "targetFish", "SHRIMP"));
  }

  @Test
  void importPayload_rejectsConflictingCopiedFishingTargetLevels() throws Exception {
    assertThatThrownBy(
            () ->
                service.importPayload(
                    objectMapper.readTree(
                        """
                        {
                          "task_key": "FISHING_TRAINING",
                          "settings": {
                            "stopConditionValue": 23,
                            "targetLevel": 24
                          }
                        }
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stopConditionValue disagrees");
  }

  @Test
  void importPayload_settingsEnvelopeIsAuthoritativeEvenWhenEmpty() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(null);

    // A present settings envelope (including {}) wins over top-level transport fields that would
    // otherwise look like legacy raw settings.
    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "task_key": "configurable_woodcutting",
                  "world": 360,
                  "source": "catalog_task",
                  "account_id": "1111111111111111",
                  "settings": {}
                }
                """));

    assertThat(response.get("settings")).isEqualTo(Map.of());
    assertThat(response.get("settingsCount")).isEqualTo(0);
  }

  @Test
  void importPayload_nonEmptySettingsEnvelopeIsPreserved() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(null);

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "task_key": "configurable_woodcutting",
                  "settings": {
                    "treeType": "WILLOW",
                    "targetLevel": 35
                  }
                }
                """));

    assertThat(response.get("settings")).isEqualTo(Map.of("treeType", "WILLOW", "targetLevel", 35));
    assertThat(response.get("settingsCount")).isEqualTo(2);
  }

  @Test
  void importPayload_nestedTaskMetadataSettingsIsUsed() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(null);

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "builder_execution": {
                    "task_key": "configurable_woodcutting",
                    "task_metadata": {
                      "settings": {
                        "treeType": "WILLOW"
                      }
                    }
                  }
                }
                """));

    assertThat(response.get("taskKey")).isEqualTo("configurable_woodcutting");
    assertThat(response.get("settings")).isEqualTo(Map.of("treeType", "WILLOW"));
  }

  @Test
  void importPayload_legacyRawSettingsStripReservedTransportFields() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(null);

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "task_key": "configurable_woodcutting",
                  "taskKey": "configurable_woodcutting",
                  "taskName": "configurable_woodcutting",
                  "title": "Woodcutting",
                  "name": "Woodcutting",
                  "display_name": "Woodcutting",
                  "world": 360,
                  "source": "catalog_task",
                  "account_id": "1111111111111111",
                  "accountId": "1111111111111111",
                  "builder_execution": {},
                  "queue_payload_preview": {},
                  "node_context": {},
                  "task_metadata": {},
                  "treeType": "WILLOW",
                  "targetLevel": 35
                }
                """));

    assertThat(response.get("settings")).isEqualTo(Map.of("treeType", "WILLOW", "targetLevel", 35));
    assertThat(response.get("settingsCount")).isEqualTo(2);
  }

  @Test
  void importPayload_legacyRawSettingsStripControlMetadataFields() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(null);

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "task_key": "configurable_woodcutting",
                  "persist_settings": true,
                  "execution_mode": "queue",
                  "client_type": "micro6",
                  "queueable": true,
                  "enabled": true,
                  "runnable": true,
                  "displayName": "Woodcutting",
                  "allowLaunchFallback": true,
                  "forceRelaunch": false,
                  "builderPayload": {"treeType": "OAK"},
                  "treeType": "WILLOW"
                }
                """));

    assertThat(response.get("settings")).isEqualTo(Map.of("treeType", "WILLOW"));
  }

  @Test
  void importPayload_nestedLegacyTaskMetadataStripReservedFields() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(null);

    // Legacy nested task metadata with no settings envelope: reserved transport fields are removed
    // and the remaining object becomes the settings.
    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "builder_execution": {
                    "task_key": "configurable_woodcutting",
                    "task_metadata": {
                      "task_key": "configurable_woodcutting",
                      "display_name": "Woodcutting",
                      "world": 360,
                      "source": "catalog_task",
                      "treeType": "WILLOW"
                    }
                  }
                }
                """));

    assertThat(response.get("taskKey")).isEqualTo("configurable_woodcutting");
    assertThat(response.get("settings")).isEqualTo(Map.of("treeType", "WILLOW"));
    assertThat(response.get("settingsCount")).isEqualTo(1);
  }

  @Test
  void importPayload_rejectsNonObjectSettingsEnvelope() throws Exception {
    assertThatThrownBy(
            () ->
                service.importPayload(
                    objectMapper.readTree(
                        """
                        {
                          "task_key": "configurable_woodcutting",
                          "settings": "WILLOW"
                        }
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("settings must be a JSON object");
  }

  @Test
  void startRun_rejectsImportedPersistedSettingsMismatch() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
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
            "1111111111111111",
            null,
            null,
            null,
            "machine-1",
            null,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);

    // Simulate a persisted run whose settings drifted from the imported payload: the mismatch must
    // fail closed before publish.
    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository, times(1)).save(captor.capture());
    PatchLoopIteration persisted = captor.getValue();
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata =
        objectMapper.convertValue(persisted.getTaskMetadataJson(), Map.class);
    metadata.put("settings", Map.of("treeType", "OAK"));
    persisted.setTaskMetadataJson(objectMapper.valueToTree(metadata));

    assertThatThrownBy(() -> invokePublishIteration(persisted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("imported task settings that do not match");
  }

  private Object invokePublishIteration(PatchLoopIteration iteration) throws Exception {
    Method publishIteration =
        BuilderGoalRunnerService.class.getDeclaredMethod(
            "publishIteration", PatchLoopIteration.class);
    publishIteration.setAccessible(true);
    try {
      return publishIteration.invoke(service, iteration);
    } catch (java.lang.reflect.InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw e;
    }
  }

  @Test
  void startRun_emptySettingsRoundTripIsConsistent() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);

    Map<String, Object> response =
        service.startRun(
            objectMapper.readTree(
                """
                {
                  "task_key": "configurable_woodcutting",
                  "settings": {}
                }
                """),
            "1111111111111111",
            null,
            null,
            null,
            "machine-1",
            null,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);

    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository, times(1)).save(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata =
        objectMapper.convertValue(captor.getValue().getTaskMetadataJson(), Map.class);
    assertThat(metadata.get("settings")).isEqualTo(Map.of());
  }

  @Test
  void startRun_appliesRequestedWorldWhenImportedPayloadOmitsWorld() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);

    service.startRun(
        objectMapper.readTree(
            """
            {
              "task_key": "FISHING_TRAINING",
              "settings": {"targetLevel": 23}
            }
            """),
        "1111111111111111",
        null,
        null,
        null,
        "machine-1",
        380,
        null);

    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository).save(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata =
        objectMapper.convertValue(captor.getValue().getTaskMetadataJson(), Map.class);
    assertThat(metadata.get("world")).isEqualTo(380);
  }

  @Test
  void launchAccount_acceptsAsynchronouslyAndReachesReadyWithoutPublishingTask() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "1111111111111111",
            "http://127.0.0.1:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenReturn(
            new Micro6TargetPreparationService.PreparationResult(
                targetClient,
                Map.of(
                    "state", Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH,
                    "local_executor_base_url", "http://127.0.0.1:8098",
                    "launcher_log_file", "C:\\micro6\\micro6-recovery-launch.log"),
                null,
                null));

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(result.get("operationId")).isNotNull();
    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
    assertThat(result.get("accountId")).isEqualTo("1111111111111111");
    assertThat(result.get("accountName")).isEqualTo("bigGreenMeat");
    assertThat(result.get("launcherMachineUuid")).isEqualTo("machine-1");
    assertThat(result.get("world")).isEqualTo(360);
    assertThat(result.get("deduplicated")).isEqualTo(false);
    assertThat(result.get("success")).isEqualTo(true);
    assertThat(result.get("terminal")).isEqualTo(true);
    assertThat(result.get("statusUrl"))
        .isEqualTo("/api/debug/builder-goals/launch-operations/" + result.get("operationId"));
    verify(targetPreparationService)
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
    verify(iterationRepository, never()).save(any(PatchLoopIteration.class));
  }

  @Test
  void launchAccount_cancellationEscapingPreparationMarksOperationFailed() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "user@example.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenThrow(new BuilderGoalLaunchCancelledException());

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(result.get("terminal")).isEqualTo(true);
    assertThat(result.get("success")).isEqualTo(false);
    assertThat(result.get("message")).isEqualTo("Launch cancelled (lease lost or stopped)");
  }

  @Test
  void launchAccount_cancellationAfterWatchdogTerminalizationLeavesOperationUnchanged() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "user@example.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenAnswer(
            invocation -> {
              // The watchdog terminalizes the operation while the relaunch worker is mid-flight.
              BuilderGoalLocalLaunchCoordinatorService.LeaseHandle leaseHandle =
                  invocation.getArgument(1);
              launchOperationService.markWatchdogFailed(leaseHandle.ownerId());
              throw new BuilderGoalLaunchCancelledException();
            });

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(result.get("message"))
        .isEqualTo("Launch operation timed out waiting for Micro6 readiness");
  }

  @Test
  void startRun_skipsLiveTargetCatalogFetchAndPublishesWithNoCache() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "1111111111111111",
            "http://100.120.100.78:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            java.time.Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
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
            "1111111111111111",
            null,
            null,
            null,
            "machine-1",
            null,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isNull();
    assertThat(response.get("entryId")).isNull();
    assertThat(response.get("catalogValidation")).isEqualTo("SKIPPED_CATALOG_MISS");
    assertThat(response.get("catalogValidationSkipped")).isEqualTo(true);
    assertThat(((Map<?, ?>) response.get("targetPreparation")).get("state"))
        .isEqualTo("PREPARING_TARGET");
    verify(micro6ExecutorCatalogService, never())
        .getCatalogForBaseUrl("http://100.120.100.78:8098", false);
  }

  @Test
  void startRun_rejectsWhenAnActiveRunAlreadyExistsForSameAccountAndTask() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    PatchLoopIteration activeIteration = new PatchLoopIteration();
    activeIteration.setIterationId("buildergoal_active_1");
    activeIteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    activeIteration.setScriptName("configurable_woodcutting");
    activeIteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);
    activeIteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "settings", Map.of("treeType", "WILLOW"))));
    activeIteration.setImportedPayloadJson(
        objectMapper.readTree(
            """
            {
              "task_key": "configurable_woodcutting",
              "settings": {
                "treeType": "WILLOW"
              }
            }
            """));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(activeIteration));

    assertThatThrownBy(
            () ->
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
                    "1111111111111111",
                    null,
                    null,
                    null,
                    "machine-1",
                    null,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("A Builder Goal run is already active for this account and task");
  }

  @Test
  void startRun_allowsNewRunWhenOlderActiveIterationOnlyHasStaleProcessExitedDisplayState()
      throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    PatchLoopIteration staleIteration = new PatchLoopIteration();
    staleIteration.setIterationId("buildergoal_stale_1");
    staleIteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    staleIteration.setScriptName("configurable_woodcutting");
    staleIteration.setStatus(PatchLoopStatus.QUEUED);
    staleIteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "target_preparation",
                    Map.of(
                        "state", "PROCESS_EXITED",
                        "message", "Gradle task failed before the executor became ready"),
                "settings", Map.of("treeType", "WILLOW"))));
    staleIteration.setImportedPayloadJson(
        objectMapper.readTree(
            """
            {
              "task_key": "configurable_woodcutting",
              "settings": {
                "treeType": "WILLOW"
              }
            }
            """));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(staleIteration));
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
            "1111111111111111",
            null,
            null,
            null,
            "machine-1",
            null,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
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
                "display_name", "35 Woodcutting", "micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    Map<String, Object> run = service.listRuns(10).get(0);

    assertThat(run.get("canAnalyze")).isEqualTo(true);
    assertThat(run.get("canFixAndRelaunch")).isEqualTo(false);
    assertThat(run.get("analysisAvailable")).isEqualTo(false);
    assertThat(run.get("patchDraftAvailable")).isEqualTo(false);
    assertThat(run.get("analyzeBlockedReason")).isNull();
    assertThat(run.get("approvalAvailable")).isEqualTo(false);
    assertThat(run.get("approvalBlockedReason"))
        .isEqualTo("Generate a patch plan before relaunching");
    assertThat(run.get("fixBlockedReason")).isEqualTo("Generate a patch plan before relaunching");
  }

  @Test
  void listRuns_exposesRecoveryWorkspaceAndAnalysisForCancelledRunWithoutReplay() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_cancel001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.CANCELLED);
    // No runRecordingId/runSessionId: the run was cancelled before a replay was bound.
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "Glassblowing", "micro6_publish", Map.of("status", "CANCELLED"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    Map<String, Object> run = service.listRuns(10).get(0);

    assertThat(run.get("cancelled")).isEqualTo(true);
    // A cancelled run can still be reviewed and patch-planned from its evidence.
    assertThat(run.get("canAnalyze")).isEqualTo(true);
    assertThat(run.get("analyzeBlockedReason")).isNull();
    // The operator must still be able to open the workspace and load any replay from the picker.
    assertThat(run.get("recoveryWorkspaceAvailable")).isEqualTo(true);
    // A cancelled run is analysable but is not relaunched from the recovery workspace.
    assertThat(run.get("approvalAvailable")).isEqualTo(false);
    assertThat(run.get("approvalBlockedReason")).isEqualTo("Run was cancelled");
  }

  @Test
  void listRuns_allowsRecoveryForCompletedRun() throws Exception {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_completed001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.COMPLETED);
    iteration.setPatchId("patch-completed-1");
    iteration.setAnalysisResponseJson(
        objectMapper.readTree(
            """
            {
              "schemaVersion": "2.0.0",
              "analysisId": "analysis-completed",
              "status": "SUCCESS",
              "patchDraft": {
                "patchId": "patch-completed-1",
                "description": "Fix",
                "changes": [
                  {"filePath": "tasks/x.java", "oldText": "old", "newText": "new"}
                ]
              }
            }
            """));
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "Glassblowing",
                "micro6_publish", Map.of("status", "COMPLETED"),
                "recovery", Map.of("phase", "PATCH_PLAN_READY", "message", "Patch plan ready"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    Map<String, Object> run = service.listRuns(10).get(0);

    assertThat(run.get("canAnalyze")).isEqualTo(true);
    assertThat(run.get("analyzeBlockedReason")).isNull();
    assertThat(run.get("approvalAvailable")).isEqualTo(true);
    assertThat(run.get("fixBlockedReason")).isNull();
  }

  @Test
  void listRuns_blocksPublishFailedRunFromRecovery() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_pubfail001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name",
                "Glassblowing",
                "micro6_publish",
                Map.of("status", "PUBLISH_FAILED"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    Map<String, Object> run = service.listRuns(10).get(0);

    assertThat(run.get("canAnalyze")).isEqualTo(false);
    assertThat(run.get("analyzeBlockedReason"))
        .isEqualTo("Publish failed; there is no task run to analyze");
    assertThat(run.get("approvalAvailable")).isEqualTo(false);
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
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
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
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(Map.of("micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findByIterationId("buildergoal_fix0001"))
        .thenReturn(Optional.of(iteration));

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
    when(iterationRepository.findByIterationId("buildergoal_draft0001"))
        .thenReturn(Optional.of(iteration));
    when(evidenceService.collectEvidence(eq(iteration), any())).thenReturn(sampleEvidenceBundle());

    Map<String, Object> draft = service.buildAnalysisDraft("buildergoal_draft0001");

    assertThat(draft.get("canAnalyze")).isEqualTo(true);
    assertThat(draft.get("prompt"))
        .isEqualTo(
            "Create a Micro6 patch plan for the failed builder goal task '35 Woodcutting'. Use my"
                + " selected evidence and observations only.");
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
                "display_name",
                "35 Woodcutting",
                "settings",
                Map.of("treeType", "WILLOW", "targetLevel", 35),
                "mule_account",
                Map.of(
                    "account_id", "1111111111111111",
                    "account_name", "bigGreenMeat",
                    "launcher_machine_uuid", "machine-1"),
                "source",
                "catalog_task")));

    PatchLoopIteration older = new PatchLoopIteration();
    older.setIterationId("buildergoal_cfg0001");
    older.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    older.setScriptName("configurable_woodcutting");
    older.setPrompt("35 Woodcutting");
    older.setCreatedAt(LocalDateTime.now().minusDays(1));
    older.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name",
                "35 Woodcutting",
                "settings",
                Map.of("treeType", "OAK"),
                "mule_account",
                Map.of(
                    "account_id", "1111111111111111",
                    "account_name", "bigGreenMeat",
                    "launcher_machine_uuid", "machine-1"),
                "source",
                "catalog_task")));

    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(newest, older));

    Map<String, Object> config =
        service.getLatestConfig("1111111111111111", "configurable_woodcutting");

    assertThat(config.get("configAvailable")).isEqualTo(true);
    assertThat(config.get("iterationId")).isEqualTo("buildergoal_cfg0002");
    assertThat(config.get("taskKey")).isEqualTo("configurable_woodcutting");
    assertThat(config.get("accountId")).isEqualTo("1111111111111111");
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
                        "entry_id",
                        1001,
                        "status",
                        "RUNNING",
                        "published_at",
                        LocalDateTime.now().minusMinutes(2).toString(),
                        "consecutive_poll_failures",
                        2,
                        "last_poll_at",
                        LocalDateTime.now().minusSeconds(30).toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));
    when(iterationRepository.findByIterationId("buildergoal_lost0001"))
        .thenReturn(Optional.of(iteration));
    when(scriptExecutionService.getMicro6RunStatus(any(), eq(1001)))
        .thenReturn(
            new ScriptExecutionService.Micro6RunStatusResult(
                false, null, "Connection refused", null, "http://100.120.100.78:8098", 1001));

    service.syncPublishedRuns();

    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.FAILED);
    assertThat(iteration.getFailureCode()).isEqualTo(PatchLoopFailureCode.TARGET_UNREACHABLE);
    assertThat(iteration.getFailureReason()).contains("Connection refused");
    assertThat(iteration.getFailureContext()).contains("publishEntryId=1001");
  }

  @Test
  void syncPublishedRuns_recoversLateAcceptedPublishFromPublishFailed() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_publishfailed0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setRunSuccess(false);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_target",
                    Map.of(
                        "account_name", "udasuAvuco",
                        "advertised_base_url", "http://192.168.1.6:8098"),
                "micro6_publish",
                    Map.of(
                        "entry_id",
                        1001,
                        "status",
                        "PUBLISH_FAILED",
                        "published_at",
                        LocalDateTime.now().minusMinutes(1).toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration), java.util.List.of(iteration));
    when(iterationRepository.findByIterationId("buildergoal_publishfailed0001"))
        .thenReturn(Optional.of(iteration));
    when(scriptExecutionService.getMicro6RunStatus(any(), eq(1001)))
        .thenReturn(
            new ScriptExecutionService.Micro6RunStatusResult(
                true,
                "QUEUED",
                null,
                objectMapper.valueToTree(
                    Map.of(
                        "status", "QUEUED",
                        "task_started", false,
                        "iteration_id", "buildergoal_publishfailed0001",
                        "task_key", "fish_training")),
                "http://192.168.1.6:8098",
                1001));

    service.syncPublishedRuns();

    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.QUEUED);
    @SuppressWarnings("unchecked")
    Map<String, Object> publish =
        (Map<String, Object>)
            objectMapper
                .convertValue(iteration.getTaskMetadataJson(), Map.class)
                .get("micro6_publish");
    assertThat(publish.get("status")).isEqualTo("QUEUED");
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
        (java.util.List<Map<String, Object>>)
            service.buildAnalysisDraft("buildergoal_analyze0001").get("evidenceGroups");
    @SuppressWarnings("unchecked")
    java.util.List<Map<String, Object>> logItems =
        (java.util.List<Map<String, Object>>) groups.get(0).get("items");
    String selectedId = logItems.get(0).get("id").toString();

    service.analyzeFailure(
        "buildergoal_analyze0001",
        objectMapper.valueToTree(
            Map.of(
                "prompt", "Focus on willow detection only",
                "context", "Ignore unrelated bank noise",
                "selectedEvidenceIds", java.util.List.of(selectedId))));

    ArgumentCaptor<AnalysisV2Request> requestCaptor =
        ArgumentCaptor.forClass(AnalysisV2Request.class);
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
  void analyzeFailure_allowsCancelledRunToProducePatchPlan() throws Exception {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_analyze_cancelled");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("Glassblowing stalled");
    iteration.setPatchTarget(BuilderGoalRunnerService.PATCH_TARGET_MICRO6_TASK_SOURCE);
    iteration.setStatus(PatchLoopStatus.CANCELLED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "Glassblowing",
                "task_key", "configurable_woodcutting",
                "micro6_publish", Map.of("status", "CANCELLED"))));
    when(iterationRepository.findByIterationId("buildergoal_analyze_cancelled"))
        .thenReturn(Optional.of(iteration), Optional.of(iteration));
    when(evidenceService.collectEvidence(eq(iteration), any())).thenReturn(sampleEvidenceBundle());
    when(evidenceService.validateEvidenceSufficiency(any())).thenReturn(true);
    AnalysisV2Response providerResponse = new AnalysisV2Response();
    providerResponse.setStatus(AnalysisStatus.PARTIAL_SUCCESS);
    when(analysisProvider.analyzeEvidence(any())).thenReturn(providerResponse);

    // Must not throw "Run was cancelled": an operator can patch-plan a cancelled run.
    service.analyzeFailure("buildergoal_analyze_cancelled", objectMapper.createObjectNode());

    verify(analysisProvider).analyzeEvidence(any());
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
                "display_name",
                "35 Woodcutting",
                "settings",
                Map.of("treeType", "WILLOW", "targetLevel", 35),
                "mule_account",
                Map.of(
                    "account_id", "1111111111111111",
                    "account_hash", "1111111111111111",
                    "account_name", "bigGreenMeat",
                    "launcher_machine_uuid", "machine-1"),
                "source",
                "catalog_task")));

    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "1111111111111111",
            "http://100.120.100.78:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    when(iterationRepository.findByIterationId("buildergoal_rerun0001"))
        .thenReturn(Optional.of(iteration));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    JsonNode staleCatalog =
        objectMapper.readTree(
            """
            {
              "catalog_source": "micro6_executor_cache",
              "tasks": [
                {
                  "task_key": "some_other_task",
                  "display_name": "Some Other Task",
                  "runnable": true
                }
              ]
            }
            """);
    when(micro6ExecutorCatalogService.getCatalog(false)).thenReturn(staleCatalog);
    Map<String, Object> response = service.rerun("buildergoal_rerun0001");

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isNull();
    assertThat(response.get("catalogValidation")).isEqualTo("SKIPPED_CATALOG_MISS");
    assertThat(response.get("catalogValidationSkipped")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> run = (Map<String, Object>) response.get("run");
    assertThat(run.get("taskKey")).isEqualTo("configurable_woodcutting");
    assertThat(run.get("accountId")).isEqualTo("1111111111111111");
    assertThat(run.get("launcherMachineUuid")).isEqualTo("machine-1");
  }

  private BuilderGoalAccountCatalogService.AccountDefinition sc666Account() {
    return new BuilderGoalAccountCatalogService.AccountDefinition(
        "1111111111111111",
        "1111111111111111",
        "bigGreenMeat",
        "bigGreenMeat",
        "bigGreenMeat",
        AccountIdentityContract.HASH_SCHEME,
        "IlaWalter280@outlook.com",
        "launcher",
        true,
        "ready",
        Map.of("password", "s3cret"));
  }

  private Map<String, Object> sc666Start(
      String taskKey, String requestId, Map<String, Object> settings) throws Exception {
    return service.startRun(
        objectMapper.valueToTree(
            Map.of(
                "task_key",
                taskKey,
                "settings",
                settings,
                "request_id",
                requestId == null ? "" : requestId)),
        "1111111111111111",
        null,
        null,
        null,
        "machine-1",
        null,
        null);
  }

  @Test
  void startRun_deduplicatesRetryWithSameRequestId() throws Exception {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(sc666Account()));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);

    Map<String, Object> first = sc666Start("configurable_woodcutting", "sc666-req-1", Map.of());
    assertThat(first.get("accepted")).isEqualTo(true);
    assertThat(first.get("deduplicated")).isNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> firstRun = (Map<String, Object>) first.get("run");
    String iterationId = (String) firstRun.get("iterationId");
    assertThat(iterationId).isNotBlank();

    // Simulate the persisted state a retry observes after a client timeout or restart.
    PatchLoopIteration saved = savedIterations.get(iterationId);
    assertThat(saved).isNotNull();
    lenient()
        .when(
            iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
                BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(saved));

    Map<String, Object> retry = sc666Start("configurable_woodcutting", "sc666-req-1", Map.of());
    assertThat(retry.get("accepted")).isEqualTo(true);
    assertThat(retry.get("deduplicated")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> retryRun = (Map<String, Object>) retry.get("run");
    assertThat(retryRun.get("iterationId")).isEqualTo(iterationId);
    assertThat(retryRun.get("runRecordingId")).isEqualTo(firstRun.get("runRecordingId"));
    assertThat(retryRun.get("runSessionId")).isEqualTo(firstRun.get("runSessionId"));
    assertThat(savedIterations).hasSize(1);
  }

  @Test
  void startRun_createsNewRunWhenRequestIdIsNew() throws Exception {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(sc666Account()));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);

    Map<String, Object> first = sc666Start("configurable_woodcutting", "sc666-req-1", Map.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> firstRun = (Map<String, Object>) first.get("run");
    String firstIterationId = (String) firstRun.get("iterationId");

    PatchLoopIteration saved = savedIterations.get(firstIterationId);
    assertThat(saved).isNotNull();
    lenient()
        .when(
            iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
                BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(saved));

    Map<String, Object> second = sc666Start("configurable_fishing", "sc666-req-2", Map.of());
    assertThat(second.get("accepted")).isEqualTo(true);
    assertThat(second.get("deduplicated")).isNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> secondRun = (Map<String, Object>) second.get("run");
    assertThat((String) secondRun.get("iterationId")).isNotBlank();
    assertThat(secondRun.get("iterationId")).isNotEqualTo(firstIterationId);
    assertThat(savedIterations).hasSize(2);
  }

  @Test
  void startRun_reusesTerminalRunWithSameRequestId() throws Exception {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(sc666Account()));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);

    Map<String, Object> first = sc666Start("configurable_woodcutting", "sc666-req-1", Map.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> firstRun = (Map<String, Object>) first.get("run");
    String firstIterationId = (String) firstRun.get("iterationId");

    // Terminal outcomes remain attached to the same logical request; explicit rerun is separate.
    PatchLoopIteration saved = savedIterations.get(firstIterationId);
    assertThat(saved).isNotNull();
    saved.setStatus(PatchLoopStatus.FAILED);
    lenient()
        .when(
            iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
                BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(saved));

    Map<String, Object> retry = sc666Start("configurable_woodcutting", "sc666-req-1", Map.of());
    assertThat(retry.get("accepted")).isEqualTo(true);
    assertThat(retry.get("deduplicated")).isEqualTo(true);
    assertThat(retry.get("terminal")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> retryRun = (Map<String, Object>) retry.get("run");
    assertThat(retryRun.get("iterationId")).isEqualTo(firstIterationId);
    assertThat(savedIterations).hasSize(1);
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
    when(iterationRepository.findByIterationId("buildergoal_rec0001"))
        .thenReturn(Optional.of(iteration));
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
    Map<String, Object> savedMetadata =
        objectMapper.convertValue(captor.getValue().getTaskMetadataJson(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> recoveryWorkspace =
        (Map<String, Object>) savedMetadata.get("recovery_workspace");
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
    when(iterationRepository.findByIterationId("buildergoal_ann0001"))
        .thenReturn(Optional.of(iteration));

    Map<String, Object> result =
        service.saveRecoveryAnnotations(
            "buildergoal_ann0001",
            objectMapper.valueToTree(
                Map.of(
                    "annotations",
                    java.util.List.of(
                        Map.of(
                            "label",
                            "stuck at bank",
                            "note",
                            "bot loops here",
                            "important",
                            true,
                            "startSeconds",
                            13,
                            "endSeconds",
                            20)),
                    "activeRangeStartSeconds",
                    13,
                    "activeRangeEndSeconds",
                    20,
                    "selectedEvidenceIds",
                    java.util.List.of("log_1"))));

    assertThat(result.get("saved")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    java.util.List<Map<String, Object>> annotations =
        (java.util.List<Map<String, Object>>) result.get("annotations");
    assertThat(annotations).hasSize(1);
    assertThat(annotations.get(0).get("label")).isEqualTo("stuck at bank");
    assertThat(annotations.get(0).get("important")).isEqualTo(true);
    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(persistenceService, atLeastOnce()).saveIteration(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> savedMetadata =
        objectMapper.convertValue(captor.getValue().getTaskMetadataJson(), Map.class);
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
    when(iterationRepository.findByIterationId("buildergoal_state0001"))
        .thenReturn(Optional.of(iteration));
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
                "display_name",
                "35 Woodcutting",
                "task_key",
                "configurable_woodcutting",
                "micro6_publish",
                Map.of("status", "FAILED"),
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
    java.util.List<String> selectedIds =
        (java.util.List<String>) workspace.get("selectedEvidenceIds");
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

  @Test
  void getRunSummaryByRecordingId_resolvesBuilderGoalRun() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_recording0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setRunRecordingId("recording_123");
    iteration.setRunSessionId("debug_123");
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "display_name", "35 Woodcutting",
                "task_key", "configurable_woodcutting",
                "micro6_publish", Map.of("status", "FAILED"))));
    when(iterationRepository.findFirstBySourceTypeAndRunRecordingIdOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL, "recording_123"))
        .thenReturn(Optional.of(iteration));

    Map<String, Object> run = service.getRunSummaryByRecordingId("recording_123");

    assertThat(run.get("iterationId")).isEqualTo("buildergoal_recording0001");
    assertThat(run.get("runRecordingId")).isEqualTo("recording_123");
    assertThat(run.get("runSessionId")).isEqualTo("debug_123");
  }

  @Test
  void getRunSummaryByRecordingId_rejectsMissingRecording() {
    when(iterationRepository.findFirstBySourceTypeAndRunRecordingIdOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL, "missing_recording"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getRunSummaryByRecordingId("missing_recording"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Builder goal run not found for recordingId");
  }

  @Test
  void publishOnly_publishesWhenMicro6HealthIsPublishReady() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    ScriptExecutionService.Micro6ExecutorHealthResult health = dispatchReadyHealth();
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap())).thenReturn(health);
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                42, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            "machine-1",
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            null,
            false,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isEqualTo(true);
    assertThat(response.get("entryId")).isEqualTo(42);
    assertThat(response.get("launchFallback")).isEqualTo(false);
    verify(scriptExecutionService, times(1)).publishMicro6Task(anyString(), anyMap(), any());
    verify(scriptExecutionService, never()).stopMicro6PublishedRun(anyMap(), any(), anyString());
  }

  @Test
  void publishOnly_acceptsIntegerFieldWithConditionalVisibilityOptions() {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);

    JsonNode catalog = runnableCatalog("FISHING_TRAINING", "Fishing Training", null);
    com.fasterxml.jackson.databind.node.ArrayNode fields =
        (com.fasterxml.jackson.databind.node.ArrayNode)
            catalog.path("tasks").get(0).path("args_schema").path("fields");
    fields
        .addObject()
        .put("key", "stopCondition")
        .put("type", "select")
        .putArray("options")
        .add("Level Target");
    com.fasterxml.jackson.databind.node.ObjectNode stopValue =
        fields
            .addObject()
            .put("key", "stopConditionValue")
            .put("type", "integer")
            .put("min", 1)
            .put("max", 1_000_000)
            .put("depends_on", "stopCondition");
    stopValue.putArray("options").add("visible");
    stopValue.putObject("options_by_dependency").putArray("Level Target").add("visible");
    when(micro6ExecutorCatalogService.getCatalogForBaseUrl("http://127.0.0.1:8098", true))
        .thenReturn(catalog);
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(dispatchReadyHealth());
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                43, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            "machine-1",
            "FISHING_TRAINING",
            Map.of("stopCondition", "Level Target", "stopConditionValue", 23),
            360,
            false,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isEqualTo(true);
  }

  @Test
  void importPayload_reconcilesCraftingTargetLevelAliasesFromSchema() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false))
        .thenReturn(craftingCatalog("PROGRESSIVE_CRAFTING", "Progressive Crafting", false));

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "builder_execution": {
                    "task_key": "PROGRESSIVE_CRAFTING",
                    "task_metadata": {
                      "stopCondition": "Level Target",
                      "stopConditionValue": 5,
                      "target_level": 5,
                      "targetLevel": 5,
                      "budgetLevel": 50,
                      "preferredActivity": "Glass"
                    }
                  }
                }
                """));

    assertThat(response.get("settings"))
        .isEqualTo(
            Map.of(
                "stopCondition",
                "Level Target",
                "stopConditionValue",
                5,
                "budgetLevel",
                50,
                "preferredActivity",
                "Glass"));
  }

  @Test
  void importPayload_keepsTargetLevelWhenSchemaDeclaresIt() throws Exception {
    when(micro6ExecutorCatalogService.getCatalog(false))
        .thenReturn(craftingCatalog("CONSTRUCTION_PHIALS", "Construction Phials", true));

    Map<String, Object> response =
        service.importPayload(
            objectMapper.readTree(
                """
                {
                  "task_key": "CONSTRUCTION_PHIALS",
                  "settings": {
                    "targetLevel": 30,
                    "target_level": 30
                  }
                }
                """));

    assertThat(response.get("settings")).isEqualTo(Map.of("targetLevel", 30));
  }

  @Test
  void publishOnly_reconcilesLegacyCraftingTargetLevelAliases() {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    when(micro6ExecutorCatalogService.getCatalogForBaseUrl("http://127.0.0.1:8098", true))
        .thenReturn(craftingCatalog("PROGRESSIVE_CRAFTING", "Progressive Crafting", false));
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(dispatchReadyHealth());
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                51, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    Map<String, Object> settings = new java.util.LinkedHashMap<>();
    settings.put("stopCondition", "Level Target");
    settings.put("stopConditionValue", 5);
    settings.put("target_level", 5);
    settings.put("targetLevel", 5);
    settings.put("budgetLevel", 50);
    settings.put("preferredActivity", "Glass");

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111", "machine-1", "PROGRESSIVE_CRAFTING", settings, 360, false, null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isEqualTo(true);
    verify(scriptExecutionService)
        .publishMicro6Task(eq("micro6:PROGRESSIVE_CRAFTING"), metadataCaptor.capture(), any());
    assertThat(metadataCaptor.getValue().get("settings"))
        .isEqualTo(
            Map.of(
                "stopCondition",
                "Level Target",
                "stopConditionValue",
                5,
                "budgetLevel",
                50,
                "preferredActivity",
                "Glass"));
  }

  private JsonNode craftingCatalog(
      String taskKey, String displayName, boolean declaresTargetLevel) {
    JsonNode catalog = runnableCatalog(taskKey, displayName, null);
    com.fasterxml.jackson.databind.node.ArrayNode fields =
        (com.fasterxml.jackson.databind.node.ArrayNode)
            catalog.path("tasks").get(0).path("args_schema").path("fields");
    if (declaresTargetLevel) {
      fields
          .addObject()
          .put("key", "targetLevel")
          .put("type", "integer")
          .put("min", 1)
          .put("max", 99);
      return catalog;
    }
    fields
        .addObject()
        .put("key", "stopCondition")
        .put("type", "select")
        .putArray("options")
        .add("Level Target");
    fields
        .addObject()
        .put("key", "stopConditionValue")
        .put("type", "integer")
        .put("min", 1)
        .put("max", 99);
    fields
        .addObject()
        .put("key", "budgetLevel")
        .put("type", "integer")
        .put("min", 0)
        .put("max", 100);
    fields
        .addObject()
        .put("key", "preferredActivity")
        .put("type", "select")
        .putArray("options")
        .add("Glass");
    return catalog;
  }

  @Test
  void publishOnly_neverInvokesPreparationValidationOrLaunch() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(dispatchReadyHealth());
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                99, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            "machine-1",
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            null,
            false,
            null);

    assertThat(response.get("published")).isEqualTo(true);
    // Publish Task must never run shared preparation, validation, Gradle, cleanup, or launch.
    verify(targetPreparationService, never()).ensureReady(any(), any(), any(), any());
    verify(micro6TargetRelaunchService, never())
        .restartTarget(
            anyString(), anyString(), anyString(), anyString(), anyInt(), any(), any(), any());
    verify(micro6TargetRelaunchService, never())
        .restartTarget(anyString(), anyString(), anyString(), anyString(), anyInt(), any(), any());
  }

  @Test
  void publishOnly_defersWhenExecutorIsNotReachable() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    ScriptExecutionService.Micro6ExecutorHealthResult health =
        new ScriptExecutionService.Micro6ExecutorHealthResult(
            false,
            false,
            "WAITING_FOR_HEALTH",
            "executor not reachable",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "http://127.0.0.1:8098",
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap())).thenReturn(health);

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            "machine-1",
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            null,
            false,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isEqualTo(false);
    assertThat(response.get("publishStatus")).isEqualTo("DEFERRED");
    assertThat(response.get("launchFallback")).isEqualTo(false);
    verify(scriptExecutionService, never()).publishMicro6Task(anyString(), anyMap(), any());
  }

  @Test
  void publishOnly_defersWhenExecutorAccountIsMismatched() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    ScriptExecutionService.Micro6ExecutorHealthResult health =
        new ScriptExecutionService.Micro6ExecutorHealthResult(
            true,
            true,
            "WAITING_FOR_ACCOUNT",
            "executor is logged into a different account",
            "someoneElse",
            "other-hash",
            "someoneElse",
            "other-hash",
            false,
            null,
            "READY",
            "LOGGED_IN",
            null,
            null,
            null,
            "http://127.0.0.1:8098",
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap())).thenReturn(health);

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            "machine-1",
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            null,
            false,
            null);

    assertThat(response.get("published")).isEqualTo(false);
    assertThat(response.get("publishStatus")).isEqualTo("DEFERRED");
    verify(scriptExecutionService, never()).publishMicro6Task(anyString(), anyMap(), any());
  }

  @Test
  void syncPendingLaunches_skipsDeferredPublishOnlyRunWhenFallbackIsDisabled() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_publish_only_deferred");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "publish_only", true,
                "launch_fallback_allowed", false,
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "target_preparation",
                    Map.of(
                        "state", "WAITING_FOR_HEALTH",
                        "message", "executor not reachable"),
                "settings", Map.of("treeType", "WILLOW"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(iteration));

    service.syncPendingLaunches();

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void syncPendingLaunches_doesNotQueueProcessExitedRunAfterLockedReconciliation() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_process_exited");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "mule_account",
                Map.of(
                    "account_id", "1111111111111111",
                    "account_name", "bigGreenMeat",
                    "launcher_machine_uuid", "machine-1"),
                "target_preparation",
                Map.of(
                    "state",
                    Micro6TargetRelaunchService.PHASE_PROCESS_EXITED,
                    "message",
                    "Micro6 process exited before readiness"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(iteration));
    when(iterationRepository.findByIterationId("buildergoal_process_exited"))
        .thenReturn(Optional.of(iteration));

    service.syncPendingLaunches();

    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.FAILED);
    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void publishOnly_usesMatchingHeartbeatClientAsTarget() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "3720a9a3b5c9f57b",
            "3720a9a3b5c9f57b",
            "udasuAvuco",
            "udasuAvuco",
            "udasuAvuco",
            AccountIdentityContract.HASH_SCHEME,
            "udasu@example.test",
            "launcher",
            true,
            "ready",
            Map.of());
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-udasu",
            "udasuAvuco",
            "3720a9a3b5c9f57b",
            "http://127.0.0.1:18098",
            "0.0.0.0:18098",
            "micro6",
            "accountbuilder",
            "IDLE",
            null,
            java.time.Instant.now(),
            360,
            360,
            true,
            "RUNNING",
            true,
            false,
            null,
            "accountbuilder_ready",
            "logged_in",
            true,
            "attempt-udasu",
            4321,
            AccountIdentityContract.HASH_SCHEME,
            "3720a9a3b5c9f57b");
    ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);

    when(accountCatalogService.findAccount("3720a9a3b5c9f57b")).thenReturn(Optional.of(account));
    when(clientRegistryService.findDispatchReadyClientsForRecovery(
            eq("3720a9a3b5c9f57b"), eq("udasuAvuco"), isNull(), any(), eq(360)))
        .thenReturn(List.of(targetClient));
    when(micro6ExecutorCatalogService.getCatalogForBaseUrl("http://127.0.0.1:8098", true))
        .thenReturn(runnableCatalog("fish_training", "Fish Training", "targetFish"));
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(
            new ScriptExecutionService.Micro6ExecutorHealthResult(
                true,
                true,
                "READY_TO_PUBLISH",
                null,
                "udasuAvuco",
                "3720a9a3b5c9f57b",
                "udasuAvuco",
                "3720a9a3b5c9f57b",
                false,
                null,
                "accountbuilder_ready",
                "logged_in",
                null,
                null,
                null,
                "http://127.0.0.1:18098",
                objectMapper.valueToTree(
                    Map.ofEntries(
                        Map.entry("account_hash", "3720a9a3b5c9f57b"),
                        Map.entry("requested_account_hash", "3720a9a3b5c9f57b"),
                        Map.entry("account_hash_scheme", AccountIdentityContract.HASH_SCHEME),
                        Map.entry("requested_world", 360),
                        Map.entry("current_world", 360),
                        Map.entry("world_matched", false),
                        Map.entry("dispatch_hold", false),
                        Map.entry("startup_state", "accountbuilder_ready"),
                        Map.entry("login_state", "logged_in"),
                        Map.entry("pathfinder_ready", true),
                        Map.entry("script_loop_alive", true),
                        Map.entry("script_lifecycle_state", "RUNNING"),
                        Map.entry("launch_attempt_id", "attempt-udasu"),
                        Map.entry("process_id", 4321))),
                null,
                null,
                null,
                null,
                null,
                null));
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                77, false, "http://127.0.0.1:18098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "3720a9a3b5c9f57b",
            "machine-1",
            "fish_training",
            Map.of("targetFish", "Best Available"),
            360,
            null,
            false,
            null);

    assertThat(response.get("published")).isEqualTo(true);
    assertThat(response.get("entryId")).isEqualTo(77);
    verify(scriptExecutionService)
        .publishMicro6Task(eq("micro6:fish_training"), metadataCaptor.capture(), any());
    @SuppressWarnings("unchecked")
    Map<String, Object> target =
        (Map<String, Object>) metadataCaptor.getValue().get("micro6_target");
    assertThat(target.get("client_id")).isEqualTo("client-udasu");
    assertThat(target.get("account_hash")).isEqualTo("3720a9a3b5c9f57b");
    assertThat(target.get("account_name")).isEqualTo("udasuAvuco");
    assertThat(target.get("advertised_base_url")).isEqualTo("http://127.0.0.1:18098");
    assertThat(target.get("local_executor_base_url")).isEqualTo("http://127.0.0.1:8098");
  }

  @Test
  void publishOnly_allowsRetryWhenOlderDeferredPublishOnlyRunExists() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    PatchLoopIteration deferred = new PatchLoopIteration();
    deferred.setIterationId("buildergoal_publish_only_deferred");
    deferred.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    deferred.setScriptName("configurable_woodcutting");
    deferred.setStatus(PatchLoopStatus.QUEUED);
    deferred.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "publish_only", true,
                "launch_fallback_allowed", false,
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "target_preparation",
                    Map.of(
                        "state", "WAITING_FOR_HEALTH",
                        "message", "executor not reachable"),
                "settings", Map.of("treeType", "WILLOW"))));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(List.of(deferred));
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(dispatchReadyHealth());
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                43, false, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            "machine-1",
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            null,
            false,
            null);

    assertThat(response.get("published")).isEqualTo(true);
    assertThat(response.get("entryId")).isEqualTo(43);
    verify(scriptExecutionService, times(1)).publishMicro6Task(anyString(), anyMap(), any());
  }

  @Test
  void publishOnly_requiresLauncherMachineWhenLaunchFallbackIsEnabled() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(
            () ->
                service.publishOnly(
                    "1111111111111111",
                    null,
                    "configurable_woodcutting",
                    Map.of("treeType", "WILLOW"),
                    360,
                    null,
                    true,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("launcherMachineUuid is required");
  }

  @Test
  void startRun_rejectsBeforePersistenceWhenLaunchCredentialsAreMissing() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            null,
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(
            () ->
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
                    "1111111111111111",
                    null,
                    null,
                    null,
                    "machine-1",
                    null,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing an email for local Micro6 relaunch");

    verify(iterationRepository, never()).save(any(PatchLoopIteration.class));
    verify(persistenceService, never()).saveIteration(any(PatchLoopIteration.class));
    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void launchAccount_rejectsBeforePreparationWhenLaunchCredentialsAreMissing() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "machine-1", 360))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing a password for local Micro6 relaunch");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void startRun_acceptsCredentialedLaunchAccount() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
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
            "1111111111111111",
            null,
            null,
            null,
            "machine-1",
            null,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("catalogValidation")).isEqualTo("SKIPPED_CATALOG_MISS");
    verify(iterationRepository, times(1)).save(any(PatchLoopIteration.class));
  }

  @Test
  void publishOnly_publishesWithoutLaunchCredentialsToExistingExecutor() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            null,
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    ScriptExecutionService.Micro6ExecutorHealthResult health = dispatchReadyHealth();
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap())).thenReturn(health);
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                42, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            null,
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            false,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isEqualTo(true);
    assertThat(response.get("entryId")).isEqualTo(42);
    assertThat(response.get("launchFallback")).isEqualTo(false);
    verify(scriptExecutionService, times(1)).publishMicro6Task(anyString(), anyMap(), any());
    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void publishOnly_rejectsDisabledAccountEvenWithExistingExecutor() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            false,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(
            () ->
                service.publishOnly(
                    "1111111111111111",
                    null,
                    "configurable_woodcutting",
                    Map.of("treeType", "WILLOW"),
                    360,
                    false,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disabled");

    verify(iterationRepository, never()).save(any(PatchLoopIteration.class));
    verify(scriptExecutionService, never()).publishMicro6Task(anyString(), anyMap(), any());
  }

  @Test
  void publishOnly_rejectsLaunchFallbackWhenLaunchCredentialsAreMissing() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(
            () ->
                service.publishOnly(
                    "1111111111111111",
                    "machine-1",
                    "configurable_woodcutting",
                    Map.of("treeType", "WILLOW"),
                    360,
                    null,
                    true,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing a password for local Micro6 relaunch");

    verify(iterationRepository, never()).save(any(PatchLoopIteration.class));
    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void stopRun_cancelsQueuedRunBeforePublish() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_prepublish_1");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "settings", Map.of("treeType", "WILLOW"))));
    when(iterationRepository.findByIterationId("buildergoal_prepublish_1"))
        .thenReturn(Optional.of(iteration));

    Map<String, Object> response = service.stopRun("buildergoal_prepublish_1", null);

    assertThat(response.get("success")).isEqualTo(true);
    InOrder timelineOrder = inOrder(runEventService);
    timelineOrder
        .verify(runEventService)
        .recordRunEvent(
            eq("buildergoal_prepublish_1"),
            eq(com.chromascape.web.debug.patchloop.entity.PatchLoopEventType.RUN_STOP_REQUESTED),
            anyString(),
            anyString(),
            anyMap());
    timelineOrder
        .verify(runEventService)
        .recordRunEvent(
            eq("buildergoal_prepublish_1"),
            eq(com.chromascape.web.debug.patchloop.entity.PatchLoopEventType.RUN_STOPPED),
            anyString(),
            anyString(),
            isNull());
    verify(scriptExecutionService, never()).stopMicro6PublishedRun(anyMap(), any(), anyString());
    verify(persistenceService, times(1))
        .updateIterationStatus(iteration, PatchLoopStatus.CANCELLED);
  }

  @Test
  void stopRun_sendsStopToMicro6AfterPublish() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_published_1");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "micro6_publish", Map.of("entry_id", 42, "status", "RUNNING"),
                "settings", Map.of("treeType", "WILLOW"))));
    when(iterationRepository.findByIterationId("buildergoal_published_1"))
        .thenReturn(Optional.of(iteration));
    when(scriptExecutionService.requestStopMicro6PublishedRun(anyMap(), eq(42), anyString()))
        .thenReturn(new ScriptExecutionService.Micro6ControlResult(true, null, null));

    Map<String, Object> response = service.stopRun("buildergoal_published_1", null);

    assertThat(response.get("success")).isEqualTo(true);
    verify(scriptExecutionService, times(1))
        .requestStopMicro6PublishedRun(anyMap(), eq(42), anyString());
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.CANCELLED);
  }

  @Test
  void pendingLaunchDoesNotConvertCancellationRaceIntoFailure() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "user@example.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_cancel_race");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "world", 360,
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_hash", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "settings", Map.of("treeType", "WILLOW"))));
    when(iterationRepository.findByIterationId("buildergoal_cancel_race"))
        .thenReturn(Optional.of(iteration));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenAnswer(
            invocation -> {
              // Simulate stopRun committing while the relaunch worker is in its readiness poll.
              iteration.setStatus(PatchLoopStatus.CANCELLED);
              throw new Micro6TargetPreparationService.PreparationException(
                  Micro6TargetPreparationService.PHASE_WAITING_FOR_EXECUTOR,
                  "Micro6 process exited after cancellation");
            });

    Method continuePendingLaunch =
        BuilderGoalRunnerService.class.getDeclaredMethod("continuePendingLaunch", String.class);
    continuePendingLaunch.setAccessible(true);
    continuePendingLaunch.invoke(service, "buildergoal_cancel_race");

    verify(persistenceService, never())
        .markIterationFailed(any(), anyString(), anyString(), anyString());
  }

  @Test
  void pendingLaunchCancellationWithAlreadyTerminalIterationPerformsNoTransition()
      throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "user@example.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_cancel_terminal");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "world", 360,
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_hash", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "settings", Map.of("treeType", "WILLOW"))));
    when(iterationRepository.findByIterationId("buildergoal_cancel_terminal"))
        .thenReturn(Optional.of(iteration));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenAnswer(
            invocation -> {
              // Simulate operator Stop terminalizing the iteration mid-flight.
              iteration.setStatus(PatchLoopStatus.CANCELLED);
              throw new BuilderGoalLaunchCancelledException();
            });

    Method continuePendingLaunch =
        BuilderGoalRunnerService.class.getDeclaredMethod("continuePendingLaunch", String.class);
    continuePendingLaunch.setAccessible(true);
    continuePendingLaunch.invoke(service, "buildergoal_cancel_terminal");

    verify(persistenceService, never())
        .markIterationFailed(any(), anyString(), anyString(), anyString());
  }

  @Test
  void pendingLaunchCancellationLeaseLossMarksNonTerminalIterationFailed() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "user@example.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_cancel_lease_loss");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "world", 360,
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_hash", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "settings", Map.of("treeType", "WILLOW"))));
    when(iterationRepository.findByIterationId("buildergoal_cancel_lease_loss"))
        .thenReturn(Optional.of(iteration));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenThrow(new BuilderGoalLaunchCancelledException());

    Method continuePendingLaunch =
        BuilderGoalRunnerService.class.getDeclaredMethod("continuePendingLaunch", String.class);
    continuePendingLaunch.setAccessible(true);
    continuePendingLaunch.invoke(service, "buildergoal_cancel_lease_loss");

    verify(persistenceService)
        .markIterationFailed(
            eq(iteration),
            eq(PatchLoopFailureCode.SCRIPT_START_FAILED),
            eq("Micro6 launch was cancelled before the executor became ready"),
            eq("lease lost or stop requested"));
  }

  @Test
  void startRun_persistsComputedAccountReadinessNotRawLauncherMetadata() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);

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
        "1111111111111111",
        null,
        null,
        null,
        "machine-1",
        null,
        null);

    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository, times(1)).save(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata =
        objectMapper.convertValue(captor.getValue().getTaskMetadataJson(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> accountMetadata = (Map<String, Object>) metadata.get("mule_account");
    assertThat(accountMetadata.get("runnable")).isEqualTo(true);
    assertThat(accountMetadata.get("hasEmail")).isEqualTo(true);
    assertThat(accountMetadata.get("hasPassword")).isEqualTo(true);
    assertThat(accountMetadata.get("launchReady")).isEqualTo(true);
    assertThat(accountMetadata.get("disabled_reason")).isNull();
    assertThat(accountMetadata.get("launchDisabledReason")).isNull();
    assertThat(accountMetadata.get("executorOnline")).isEqualTo(false);
    assertThat(accountMetadata.get("online")).isEqualTo(false);
    assertThat(accountMetadata.get("online")).isEqualTo(accountMetadata.get("executorOnline"));
    assertThat(metadata.toString()).doesNotContain("s3cret");
  }

  @Test
  void startRun_persistsExecutorOnlineTrueWhenMatchingClientExists() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "1111111111111111",
            "http://127.0.0.1:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(clientRegistryService.findOnlineClientForRecovery(
            eq("1111111111111111"), eq("bigGreenMeat"), isNull(), any()))
        .thenReturn(Optional.of(targetClient));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);

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
        "1111111111111111",
        null,
        null,
        null,
        "machine-1",
        null,
        null);

    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository, times(1)).save(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata =
        objectMapper.convertValue(captor.getValue().getTaskMetadataJson(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> accountMetadata = (Map<String, Object>) metadata.get("mule_account");
    assertThat(accountMetadata.get("executorOnline")).isEqualTo(true);
    assertThat(accountMetadata.get("online")).isEqualTo(true);
    assertThat(accountMetadata.get("online")).isEqualTo(accountMetadata.get("executorOnline"));
  }

  @Test
  void runSummary_exposesNewAccountReadinessFields() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_summary0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    Map<String, Object> accountMetadata = new LinkedHashMap<>();
    accountMetadata.put("account_id", "1111111111111111");
    accountMetadata.put("account_hash", "1111111111111111");
    accountMetadata.put("account_name", "bigGreenMeat");
    accountMetadata.put("online", true);
    accountMetadata.put("executorOnline", true);
    accountMetadata.put("runnable", true);
    accountMetadata.put("disabled_reason", null);
    accountMetadata.put("hasEmail", true);
    accountMetadata.put("hasPassword", true);
    accountMetadata.put("launchReady", true);
    accountMetadata.put("launchDisabledReason", null);

    Map<String, Object> taskMetadata = new LinkedHashMap<>();
    taskMetadata.put("display_name", "35 Woodcutting");
    taskMetadata.put("mule_account", accountMetadata);
    taskMetadata.put("settings", Map.of("treeType", "WILLOW"));

    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    when(iterationRepository.findByIterationId("buildergoal_summary0001"))
        .thenReturn(Optional.of(iteration));

    Map<String, Object> run = service.getRunSummary("buildergoal_summary0001");

    assertThat(run).containsEntry("accountOnline", true);
    assertThat(run).containsEntry("launcherOnline", true);
    assertThat(run).containsEntry("accountRunnable", true);
    assertThat(run).containsEntry("accountDisabledReason", null);
    assertThat(run).containsEntry("accountExecutorOnline", true);
    assertThat(run).containsEntry("accountHasEmail", true);
    assertThat(run).containsEntry("accountHasPassword", true);
    assertThat(run).containsEntry("accountLaunchReady", true);
    assertThat(run).containsEntry("accountLaunchDisabledReason", null);
    assertThat(run.get("accountExecutorOnline")).isEqualTo(run.get("accountOnline"));
  }

  @Test
  void runSummary_pausedStatusSurvivesSummaryGenerationAndRemainsStoppable() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_paused0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setPrompt("35 Woodcutting");
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);
    iteration.setRunSessionId("session-1");

    Map<String, Object> taskMetadata = new LinkedHashMap<>();
    taskMetadata.put("display_name", "35 Woodcutting");
    taskMetadata.put("task_start_confirmed", true);
    taskMetadata.put("micro6_publish", Map.of("status", "PAUSED", "entry_id", 1001));
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    when(iterationRepository.findByIterationId("buildergoal_paused0001"))
        .thenReturn(Optional.of(iteration));

    Map<String, Object> run = service.getRunSummary("buildergoal_paused0001");

    assertThat(run).containsEntry("status", "PAUSED");
    assertThat(run).containsEntry("displayStatus", "PAUSED");
    assertThat(run).containsEntry("canStop", true);
    assertThat(String.valueOf(run.get("status"))).doesNotContain("RUNNING");
  }

  @Test
  void launchAccount_rejectsMissingWorldBeforePreparation() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "machine-1", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("world must be between 300 and 700");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void launchAccount_rejectsWorldBelowRangeBeforePreparation() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "machine-1", 299))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("world must be between 300 and 700");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void launchAccount_rejectsWorldAboveRangeBeforePreparation() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "machine-1", 701))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("world must be between 300 and 700");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void launchAccount_acceptsWorldBounds300And700() {
    BuilderGoalAccountCatalogService.AccountDefinition account = credentialedAccount();
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenReturn(
            new Micro6TargetPreparationService.PreparationResult(
                new Micro6OnlineClientRegistryService.TargetClient(
                    "client-1",
                    "bigGreenMeat",
                    "1111111111111111",
                    "http://127.0.0.1:8098",
                    "0.0.0.0:8098",
                    "micro6",
                    "accountbuilder_builderpro",
                    "IDLE",
                    null,
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null),
                Map.of("state", Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH),
                null,
                null));

    Map<String, Object> lower = service.launchAccount("1111111111111111", "machine-1", 300);
    assertThat(lower.get("operationId")).isNotNull();
    assertThat(lower.get("world")).isEqualTo(300);
    assertThat(lower.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
    Map<String, Object> upper = service.launchAccount("1111111111111111", "machine-1", 700);
    assertThat(upper.get("operationId")).isNotNull();
    assertThat(upper.get("world")).isEqualTo(700);
    assertThat(upper.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
  }

  @Test
  void launchAccount_rejectsMalformedAccountHashBeforePreparation() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "hash-1",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "machine-1", 360))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Selected launcher account is missing the canonical game-account hash");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void publishOnly_rejectsMalformedAccountHashBeforeDispatch() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "abc123",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(
            () ->
                service.publishOnly(
                    "1111111111111111",
                    "machine-1",
                    "FISHING_TRAINING",
                    Map.of(),
                    360,
                    null,
                    false,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Selected launcher account is missing the canonical game-account hash");

    verify(micro6ExecutorCatalogService, never()).getCatalog(anyBoolean());
  }

  @Test
  void launchAccount_rejectsMissingHashSchemeBeforePreparation() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            null,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "machine-1", 360))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Selected launcher account is missing the canonical account hash scheme");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  private BuilderGoalAccountCatalogService.AccountDefinition credentialedAccount() {
    return new BuilderGoalAccountCatalogService.AccountDefinition(
        "1111111111111111",
        "1111111111111111",
        "bigGreenMeat",
        "bigGreenMeat",
        "bigGreenMeat",
        AccountIdentityContract.HASH_SCHEME,
        "IlaWalter280@outlook.com",
        "launcher",
        true,
        "ready",
        Map.of("password", "s3cret"));
  }

  private Micro6OnlineClientRegistryService.TargetClient targetClientAt(String baseUrl) {
    return new Micro6OnlineClientRegistryService.TargetClient(
        "client-1",
        "bigGreenMeat",
        "1111111111111111",
        baseUrl,
        "0.0.0.0:8098",
        "micro6",
        "accountbuilder_builderpro",
        "IDLE",
        null,
        Instant.now(),
        360,
        360,
        true,
        "RUNNING",
        true,
        false,
        null,
        "accountbuilder_ready",
        "logged_in",
        true,
        "attempt-1111111111111111",
        4321,
        AccountIdentityContract.HASH_SCHEME,
        "1111111111111111");
  }

  private ScriptExecutionService.Micro6ExecutorHealthResult dispatchReadyHealth() {
    com.fasterxml.jackson.databind.node.ObjectNode raw = objectMapper.createObjectNode();
    raw.put("account_hash", "1111111111111111");
    raw.put("requested_account_hash", "1111111111111111");
    raw.put("account_hash_scheme", AccountIdentityContract.HASH_SCHEME);
    raw.put("requested_account_name", "bigGreenMeat");
    raw.put("account_name", "bigGreenMeat");
    raw.put("requested_world", 360);
    raw.put("current_world", 360);
    raw.put("world_matched", true);
    raw.put("dispatch_hold", false);
    raw.put("startup_state", "accountbuilder_ready");
    raw.put("login_state", "logged_in");
    raw.put("pathfinder_ready", true);
    raw.put("script_loop_alive", true);
    raw.put("script_lifecycle_state", "RUNNING");
    raw.put("launch_attempt_id", "attempt-1111111111111111");
    raw.put("process_id", 4321);
    return new ScriptExecutionService.Micro6ExecutorHealthResult(
        true,
        true,
        "READY_TO_PUBLISH",
        null,
        "bigGreenMeat",
        "1111111111111111",
        "bigGreenMeat",
        "1111111111111111",
        false,
        null,
        "accountbuilder_ready",
        "logged_in",
        null,
        null,
        null,
        "http://127.0.0.1:8098",
        raw,
        360,
        360,
        true,
        true,
        "RUNNING",
        true);
  }

  private void stubDispatchReadyTarget(String accountHash, String accountName, int world) {
    stubDispatchReadyTarget(accountHash, accountName, world, "http://127.0.0.1:8098");
  }

  private void stubDispatchReadyTarget(
      String accountHash, String accountName, int world, String baseUrl) {
    when(clientRegistryService.findDispatchReadyClientsForRecovery(
            eq(accountHash), eq(accountName), isNull(), any(), eq(world)))
        .thenReturn(List.of(dispatchReadyTarget(accountHash, accountName, world, baseUrl)));
  }

  private Micro6OnlineClientRegistryService.TargetClient dispatchReadyTarget(
      String accountHash, String accountName, int world, String baseUrl) {
    return new Micro6OnlineClientRegistryService.TargetClient(
        "client-" + accountName,
        accountName,
        accountHash,
        baseUrl,
        "0.0.0.0:8098",
        "micro6",
        "accountbuilder_builderpro",
        "IDLE",
        null,
        Instant.now(),
        world,
        world,
        true,
        "RUNNING",
        true,
        false,
        null,
        "accountbuilder_ready",
        "logged_in",
        true,
        "attempt-" + accountHash,
        4321,
        AccountIdentityContract.HASH_SCHEME,
        accountHash);
  }

  private JsonNode runnableCatalog(String taskKey, String displayName, String settingKey) {
    com.fasterxml.jackson.databind.node.ObjectNode catalog = objectMapper.createObjectNode();
    catalog.put("catalog_state", "READY");
    com.fasterxml.jackson.databind.node.ObjectNode task = catalog.putArray("tasks").addObject();
    task.put("key", taskKey);
    task.put("task_key", taskKey);
    task.put("display_name", displayName);
    task.put("runnable", true);
    task.put("enabled", true);
    task.put("settings_schema_state", "AVAILABLE");
    task.put("settings_schema_version", "1");
    task.put("settings_schema_hash", "sha256:" + "a".repeat(64));
    com.fasterxml.jackson.databind.node.ObjectNode schema = task.putObject("args_schema");
    schema.putArray("fields");
    if (settingKey != null) {
      schema.withArray("fields").addObject().put("key", settingKey).put("type", "string");
    }
    return catalog;
  }

  private void stubRunnableCatalog(String taskKey, String displayName, String settingKey) {
    when(micro6ExecutorCatalogService.getCatalogForBaseUrl("http://127.0.0.1:8098", true))
        .thenReturn(runnableCatalog(taskKey, displayName, settingKey));
  }

  @Test
  void launchAccount_reusesReadyExecutorWhenHealthConfirmsDispatch() {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "1111111111111111",
            "http://127.0.0.1:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenReturn(
            new Micro6TargetPreparationService.PreparationResult(
                targetClient,
                Map.of(
                    "state",
                    Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH,
                    "readiness_phase",
                    Micro6TargetRelaunchService.PHASE_READY,
                    "readiness_reason",
                    "dispatch-ready",
                    "local_executor_base_url",
                    "http://127.0.0.1:8098",
                    "reused_executor",
                    true,
                    "launcher_log_file",
                    "C:\\micro6\\micro6-recovery-launch.log"),
                null,
                null));

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
    assertThat(result.get("reusedExecutor")).isEqualTo(true);
    assertThat(result.get("success")).isEqualTo(true);
    assertThat(result.get("terminal")).isEqualTo(true);
    // Reuse happens inside the shared lease-protected preparation, not before it.
    verify(targetPreparationService, times(1)).ensureReady(any(), any(), any(), any());
    @SuppressWarnings("unchecked")
    Map<String, Object> target = (Map<String, Object>) result.get("target");
    assertThat(target).containsEntry("advertised_base_url", "http://127.0.0.1:8098");
  }

  @Test
  void launchAccount_nonReadyExecutorCallsSharedPreparationAndReplaces() {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-2",
            "bigGreenMeat",
            "1111111111111111",
            "http://127.0.0.1:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenReturn(
            new Micro6TargetPreparationService.PreparationResult(
                targetClient,
                Map.of(
                    "state",
                    Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH,
                    "readiness_phase",
                    Micro6TargetRelaunchService.PHASE_READY,
                    "readiness_reason",
                    "dispatch-ready",
                    "local_executor_base_url",
                    "http://127.0.0.1:8098",
                    "reused_executor",
                    false,
                    "launcher_log_file",
                    "C:\\micro6\\micro6-recovery-launch.log"),
                null,
                null));

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    // A non-ready executor is never a CONFLICT: the shared lease-protected preparation replaces it.
    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
    assertThat(result.get("reusedExecutor")).isEqualTo(false);
    assertThat(result.get("success")).isEqualTo(true);
    assertThat(result.get("terminal")).isEqualTo(true);
    verify(targetPreparationService, times(1)).ensureReady(any(), any(), any(), any());
  }

  static class RecordingExecutor implements Executor {
    final java.util.List<Runnable> tasks = new java.util.ArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }
  }

  @Test
  void launchAccount_deduplicatesConcurrentRequestsForSameAccount() {
    RecordingExecutor recordingExecutor = new RecordingExecutor();
    BuilderGoalRunnerService isolated =
        createService(
            new BuilderGoalLaunchOperationService(),
            new BuilderGoalLocalLaunchCoordinatorService(),
            recordingExecutor);
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));

    Map<String, Object> first = isolated.launchAccount("1111111111111111", "machine-1", 360);
    Map<String, Object> second = isolated.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(recordingExecutor.tasks).hasSize(1);
    assertThat(first.get("operationId")).isEqualTo(second.get("operationId"));
    assertThat(first.get("deduplicated")).isEqualTo(false);
    assertThat(second.get("deduplicated")).isEqualTo(true);
    assertThat(first.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_QUEUED);
    assertThat(second.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_QUEUED);
  }

  @Test
  void launchAccount_marksFailedWhenPreparationThrows() {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenThrow(
            new Micro6TargetPreparationService.PreparationException(
                Micro6TargetRelaunchService.PHASE_PROCESS_EXITED,
                "Micro6 relaunch process exited before the local executor became ready"));

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(result.get("success")).isEqualTo(false);
    assertThat(result.get("terminal")).isEqualTo(true);
    assertThat(String.valueOf(result.get("message"))).contains("process exited");
    assertThat(result.get("readinessPhase"))
        .isEqualTo(Micro6TargetRelaunchService.PHASE_PROCESS_EXITED);
  }

  @Test
  void launchAccount_progressIsFedIntoTheOperationEvenOnFailure() {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              java.util.function.Consumer<Micro6TargetRelaunchService.RelaunchProgress> listener =
                  invocation.getArgument(3);
              listener.accept(
                  new Micro6TargetRelaunchService.RelaunchProgress(
                      Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT,
                      "Micro6 local executor came up but did not identify the selected account yet",
                      "http://127.0.0.1:8098",
                      "bigGreenMeat",
                      "1111111111111111",
                      "bigGreenMeat",
                      "1111111111111111",
                      1234L,
                      false,
                      null,
                      "starting_accountbuilder",
                      "waiting_for_login",
                      1,
                      5,
                      null,
                      ":runelite-client:run",
                      "--profile-email=********",
                      false,
                      "C:\\micro6\\micro6-recovery-launch.log",
                      null,
                      null,
                      null));
              listener.accept(
                  new Micro6TargetRelaunchService.RelaunchProgress(
                      Micro6TargetRelaunchService.PHASE_PROCESS_EXITED,
                      "Micro6 relaunch process exited before the local executor became ready",
                      "http://127.0.0.1:8098",
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      ":runelite-client:run",
                      "--profile-email=********",
                      false,
                      "C:\\micro6\\micro6-recovery-launch.log",
                      "PROCESS_EXITED",
                      "process exited detail",
                      null));
              throw new Micro6TargetPreparationService.PreparationException(
                  Micro6TargetRelaunchService.PHASE_PROCESS_EXITED,
                  "Micro6 relaunch process exited before the local executor became ready");
            });

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_FAILED);
    assertThat(result.get("launcherLogFile")).isEqualTo("C:\\micro6\\micro6-recovery-launch.log");
    assertThat(result.get("failureKind")).isEqualTo("PROCESS_EXITED");
    assertThat(result.get("failureDetail")).isEqualTo("process exited detail");
    assertThat(result.get("launchArgsPreview")).isEqualTo("--profile-email=********");
    assertThat(result.get("readinessPhase"))
        .isEqualTo(Micro6TargetRelaunchService.PHASE_PROCESS_EXITED);
  }

  @Test
  void launchOperationService_mapsRelaunchProgressPhases() {
    BuilderGoalLaunchOperationService ops = new BuilderGoalLaunchOperationService();
    BuilderGoalLaunchOperationService.CreateResult created =
        ops.createOrReuse("1111111111111111", "1111111111111111", "bigGreenMeat", "machine-1", 360);
    String operationId = created.operation().getOperationId();
    ops.updateStatus(
        operationId,
        BuilderGoalLaunchOperationService.STATUS_CHECKING_EXISTING,
        "Checking for an existing matching Micro6 executor");
    Map<String, Object> checking = ops.toResponse(operationId, false);
    assertThat(checking.get("status"))
        .isEqualTo(BuilderGoalLaunchOperationService.STATUS_CHECKING_EXISTING);

    ops.updateProgress(
        operationId,
        new Micro6TargetRelaunchService.RelaunchProgress(
            Micro6TargetRelaunchService.PHASE_WAITING_FOR_DISPATCH,
            "Micro6 local executor is waiting for login",
            "http://127.0.0.1:8098",
            null,
            null,
            null,
            null,
            null,
            true,
            "waiting_for_login",
            "starting_accountbuilder",
            "waiting_for_login",
            1,
            5,
            null,
            ":runelite-client:run",
            null,
            false,
            null,
            null,
            null,
            null));
    Map<String, Object> waiting = ops.toResponse(operationId, false);
    assertThat(waiting.get("status"))
        .isEqualTo(BuilderGoalLaunchOperationService.STATUS_WAITING_FOR_DISPATCH);
    assertThat(waiting.get("readinessPhase"))
        .isEqualTo(Micro6TargetRelaunchService.PHASE_WAITING_FOR_DISPATCH);
    assertThat(String.valueOf(waiting.get("message"))).contains("waiting for login");
    assertThat(waiting.get("terminal")).isEqualTo(false);
  }

  @Test
  void publishOnly_succeedsWithoutLauncherMachineWhenExecutorIsAvailable() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            null,
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(dispatchReadyHealth());
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                42, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            null,
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            null,
            false,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isEqualTo(true);
    assertThat(response.get("entryId")).isEqualTo(42);
    assertThat(response.get("launchFallback")).isEqualTo(false);
    verify(scriptExecutionService, times(1)).publishMicro6Task(anyString(), anyMap(), any());
  }

  @Test
  void publishOnly_acceptsCredentiallessAccountWhenFallbackIsFalse() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            null,
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(dispatchReadyHealth());
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                42, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            null,
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            null,
            false,
            null);

    assertThat(response.get("accepted")).isEqualTo(true);
    assertThat(response.get("published")).isEqualTo(true);
    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void startRun_rejectsOfflineMachineBeforePersistence() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    BuilderGoalLauncherControlService.LauncherMachine offline =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-offline", "Machine Offline", "OFFLINE", true, false, Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(launcherControlService.findMachineByUuid("machine-offline"))
        .thenReturn(Optional.of(offline));
    when(launcherControlService.isBuilderGoalRunnableMachine(offline)).thenReturn(false);
    when(launcherControlService.getBuilderGoalMachineDisabledReason(offline))
        .thenReturn("Launcher machine is offline");

    assertThatThrownBy(
            () ->
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
                    "1111111111111111",
                    null,
                    null,
                    null,
                    "machine-offline",
                    null,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Launcher machine is offline");

    verify(iterationRepository, never()).save(any(PatchLoopIteration.class));
    verify(persistenceService, never()).saveIteration(any(PatchLoopIteration.class));
  }

  @Test
  void startRun_rejectsRemoteMachineBeforePersistence() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    BuilderGoalLauncherControlService.LauncherMachine remote =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-remote", "jew (jomar)", "ONLINE", true, true, Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(launcherControlService.findMachineByUuid("machine-remote"))
        .thenReturn(Optional.of(remote));
    when(launcherControlService.isBuilderGoalRunnableMachine(remote)).thenReturn(false);
    when(launcherControlService.getBuilderGoalMachineDisabledReason(remote))
        .thenReturn("Builder Goal local dev launches only run on the local ChromaScape host");

    assertThatThrownBy(
            () ->
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
                    "1111111111111111",
                    null,
                    null,
                    null,
                    "machine-remote",
                    null,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Builder Goal local dev launches only run on the local ChromaScape host");

    verify(iterationRepository, never()).save(any(PatchLoopIteration.class));
    verify(persistenceService, never()).saveIteration(any(PatchLoopIteration.class));
  }

  @Test
  void startRun_rejectsDisabledMachineBeforePersistence() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    BuilderGoalLauncherControlService.LauncherMachine disabled =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-disabled", "Machine Disabled", "ONLINE", false, true, Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(launcherControlService.findMachineByUuid("machine-disabled"))
        .thenReturn(Optional.of(disabled));
    when(launcherControlService.isBuilderGoalRunnableMachine(disabled)).thenReturn(false);
    when(launcherControlService.getBuilderGoalMachineDisabledReason(disabled))
        .thenReturn("Launcher machine is disabled");

    assertThatThrownBy(
            () ->
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
                    "1111111111111111",
                    null,
                    null,
                    null,
                    "machine-disabled",
                    null,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Launcher machine is disabled");

    verify(iterationRepository, never()).save(any(PatchLoopIteration.class));
    verify(persistenceService, never()).saveIteration(any(PatchLoopIteration.class));
  }

  @Test
  void launchAccount_rejectsUnknownMachineUuid() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(launcherControlService.findMachineByUuid("unknown-machine")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "unknown-machine", 360))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Selected launcher machine was not found");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void launchAccount_rejectsOfflineMachineBeforePreparation() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    BuilderGoalLauncherControlService.LauncherMachine offline =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-offline", "Machine Offline", "OFFLINE", true, false, Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(launcherControlService.findMachineByUuid("machine-offline"))
        .thenReturn(Optional.of(offline));
    when(launcherControlService.isBuilderGoalRunnableMachine(offline)).thenReturn(false);
    when(launcherControlService.getBuilderGoalMachineDisabledReason(offline))
        .thenReturn("Launcher machine is offline");

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "machine-offline", 360))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Launcher machine is offline");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void launchAccount_rejectsRemoteMachineBeforePreparation() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    BuilderGoalLauncherControlService.LauncherMachine remote =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-remote", "jew (jomar)", "ONLINE", true, true, Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(launcherControlService.findMachineByUuid("machine-remote"))
        .thenReturn(Optional.of(remote));
    when(launcherControlService.isBuilderGoalRunnableMachine(remote)).thenReturn(false);
    when(launcherControlService.getBuilderGoalMachineDisabledReason(remote))
        .thenReturn("Builder Goal local dev launches only run on the local ChromaScape host");

    assertThatThrownBy(() -> service.launchAccount("1111111111111111", "machine-remote", 360))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Builder Goal local dev launches only run on the local ChromaScape host");

    verify(targetPreparationService, never())
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void launchAccount_acceptsValidLocalMachineAndPrepares() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "1111111111111111",
            "http://127.0.0.1:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenReturn(
            new Micro6TargetPreparationService.PreparationResult(
                targetClient,
                Map.of("state", Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH),
                null,
                null));

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(result.get("operationId")).isNotNull();
    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
    assertThat(result.get("launcherMachineUuid")).isEqualTo("machine-1");
    verify(targetPreparationService)
        .ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any());
  }

  @Test
  void publishOnly_requiresRunnableLocalMachineWhenLaunchFallbackIsEnabled() {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "IlaWalter280@outlook.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    BuilderGoalLauncherControlService.LauncherMachine remote =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-remote", "jew (jomar)", "ONLINE", true, true, Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    when(launcherControlService.findMachineByUuid("machine-remote"))
        .thenReturn(Optional.of(remote));
    when(launcherControlService.isBuilderGoalRunnableMachine(remote)).thenReturn(false);
    when(launcherControlService.getBuilderGoalMachineDisabledReason(remote))
        .thenReturn("Builder Goal local dev launches only run on the local ChromaScape host");

    assertThatThrownBy(
            () ->
                service.publishOnly(
                    "1111111111111111",
                    "machine-remote",
                    "configurable_woodcutting",
                    Map.of("treeType", "WILLOW"),
                    360,
                    null,
                    true,
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Builder Goal local dev launches only run on the local ChromaScape host");

    verify(iterationRepository, never()).save(any(PatchLoopIteration.class));
    verify(persistenceService, never()).saveIteration(any(PatchLoopIteration.class));
    verify(scriptExecutionService, never()).publishMicro6Task(anyString(), anyMap(), any());
  }

  @Test
  void publishOnly_withoutFallbackStillWorksWithoutMachine() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            null,
            "launcher",
            true,
            "ready",
            Map.of());
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));
    stubDispatchReadyTarget("1111111111111111", "bigGreenMeat", 360);
    stubRunnableCatalog("configurable_woodcutting", "Configurable Woodcutting", "treeType");
    when(iterationRepository.findByIterationId(anyString())).thenReturn(Optional.empty());
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(dispatchReadyHealth());
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                42, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response =
        service.publishOnly(
            "1111111111111111",
            null,
            "configurable_woodcutting",
            Map.of("treeType", "WILLOW"),
            360,
            null,
            false,
            null);

    assertThat(response.get("published")).isEqualTo(true);
    assertThat(response.get("entryId")).isEqualTo(42);
    verify(scriptExecutionService, times(1)).publishMicro6Task(anyString(), anyMap(), any());
  }

  // --- Stop/precedence and reconciliation tests ---

  @Test
  void syncPublishedRuns_handlesAssignedStatus() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_assigned0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_publish",
                Map.of(
                    "entry_id",
                    1001,
                    "status",
                    "ASSIGNED",
                    "published_at",
                    LocalDateTime.now().minusMinutes(1).toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));
    when(iterationRepository.findByIterationId("buildergoal_assigned0001"))
        .thenReturn(Optional.of(iteration));
    when(scriptExecutionService.getMicro6RunStatus(any(), eq(1001)))
        .thenReturn(
            new ScriptExecutionService.Micro6RunStatusResult(
                true,
                "ASSIGNED",
                null,
                objectMapper.valueToTree(Map.of("status", "ASSIGNED")),
                "http://127.0.0.1:8098",
                1001));

    service.syncPublishedRuns();

    assertThat(iteration.getRunEndTime()).isNull();
    assertThat(iteration.getRunSuccess()).isNull();
    verify(persistenceService, never()).markIterationFailed(any(), any(), any(), any());
  }

  @Test
  void syncPublishedRuns_handlesCancelledExecutorStatus() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_cancelled_exec0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_publish",
                Map.of(
                    "entry_id",
                    1001,
                    "status",
                    "RUNNING",
                    "published_at",
                    LocalDateTime.now().minusMinutes(2).toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));
    when(iterationRepository.findByIterationId("buildergoal_cancelled_exec0001"))
        .thenReturn(Optional.of(iteration));
    when(scriptExecutionService.getMicro6RunStatus(any(), eq(1001)))
        .thenReturn(
            new ScriptExecutionService.Micro6RunStatusResult(
                true,
                "CANCELLED",
                null,
                objectMapper.valueToTree(Map.of("status", "CANCELLED")),
                "http://127.0.0.1:8098",
                1001));

    service.syncPublishedRuns();

    @SuppressWarnings("unchecked")
    Map<String, Object> publish =
        (Map<String, Object>)
            objectMapper
                .convertValue(iteration.getTaskMetadataJson(), Map.class)
                .get("micro6_publish");
    assertThat(publish.get("status")).isEqualTo("CANCELLED");
  }

  @Test
  void syncPublishedRuns_reconcilePersistsAttemptTimestampBeforePolling() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_fair0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setRunSuccess(false);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_publish",
                Map.of(
                    "entry_id",
                    1001,
                    "status",
                    "PUBLISH_FAILED",
                    "published_at",
                    LocalDateTime.now().minusMinutes(1).toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration), java.util.List.of(iteration));
    when(iterationRepository.findByIterationId("buildergoal_fair0001"))
        .thenReturn(Optional.of(iteration));
    when(scriptExecutionService.getMicro6RunStatus(any(), eq(1001)))
        .thenReturn(
            new ScriptExecutionService.Micro6RunStatusResult(
                true,
                "QUEUED",
                null,
                objectMapper.valueToTree(
                    Map.of(
                        "status", "QUEUED",
                        "iteration_id", "buildergoal_fair0001",
                        "task_key", "fish_training")),
                "http://127.0.0.1:8098",
                1001));

    service.syncPublishedRuns();

    @SuppressWarnings("unchecked")
    Map<String, Object> publish =
        (Map<String, Object>)
            objectMapper
                .convertValue(iteration.getTaskMetadataJson(), Map.class)
                .get("micro6_publish");
    assertThat(publish.get("last_reconcile_attempt_at")).isNotNull();
    assertThat(publish.get("status")).isEqualTo("QUEUED");
  }

  @Test
  void syncPublishedRuns_doesNotRecoverUnreachableExecutorButRotatesAttemptTimestamp() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_unreachable0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setRunSuccess(false);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_publish",
                Map.of(
                    "entry_id",
                    1001,
                    "status",
                    "PUBLISH_FAILED",
                    "published_at",
                    LocalDateTime.now().minusMinutes(1).toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration), java.util.List.of(iteration));
    when(iterationRepository.findByIterationId("buildergoal_unreachable0001"))
        .thenReturn(Optional.of(iteration));
    when(scriptExecutionService.getMicro6RunStatus(any(), eq(1001)))
        .thenReturn(
            new ScriptExecutionService.Micro6RunStatusResult(
                false, null, "Connection refused", null, "http://127.0.0.1:8098", 1001));

    service.syncPublishedRuns();

    @SuppressWarnings("unchecked")
    Map<String, Object> publish =
        (Map<String, Object>)
            objectMapper
                .convertValue(iteration.getTaskMetadataJson(), Map.class)
                .get("micro6_publish");
    assertThat(publish.get("last_reconcile_attempt_at")).isNotNull();
    // Status stays PUBLISH_FAILED since executor was not found.
    assertThat(publish.get("status")).isEqualTo("PUBLISH_FAILED");
  }

  @Test
  void syncPublishedRuns_handlesReceivedStatusInPreStartBranch() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_received0001");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_publish",
                Map.of(
                    "entry_id",
                    1001,
                    "status",
                    "RECEIVED",
                    "published_at",
                    LocalDateTime.now().minusMinutes(1).toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));
    when(iterationRepository.findByIterationId("buildergoal_received0001"))
        .thenReturn(Optional.of(iteration));
    when(scriptExecutionService.getMicro6RunStatus(any(), eq(1001)))
        .thenReturn(
            new ScriptExecutionService.Micro6RunStatusResult(
                true,
                "RECEIVED",
                null,
                objectMapper.valueToTree(Map.of("status", "RECEIVED")),
                "http://127.0.0.1:8098",
                1001));

    service.syncPublishedRuns();

    // RECEIVED is handled in the pre-start branch — no failure applied.
    verify(persistenceService, never()).markIterationFailed(any(), any(), any(), any());
  }

  @Test
  void stopRun_rejectsAlreadyTerminal() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_termstop");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of("micro6_publish", Map.of("entry_id", 1001, "status", "FAILED"))));
    when(iterationRepository.findByIterationId("buildergoal_termstop"))
        .thenReturn(Optional.of(iteration));

    assertThatThrownBy(
            () ->
                service.stopRun(
                    "buildergoal_termstop",
                    objectMapper.createObjectNode().put("reason", "Operator stop")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal");
  }

  // --- Status mapping tests ---

  @Test
  void statusMapping_includesAssigned() {
    // Verify using reflection that ASSIGNED maps to a non-terminal display status.
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_assign_map");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_publish",
                    Map.of(
                        "entry_id",
                        1001,
                        "status",
                        "ASSIGNED",
                        "published_at",
                        LocalDateTime.now().toString()),
                "target_preparation", Map.of("state", "READY_TO_PUBLISH"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    java.util.List<Map<String, Object>> runs = service.listRuns(10);

    String displayStatus = (String) runs.get(0).get("displayStatus");
    assertThat(displayStatus).isIn("ASSIGNED", "AWAITING_EXECUTOR_CONFIRMATION");
  }

  @Test
  void statusMapping_handlesPublishingState() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_publishing_map");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_publish",
                    Map.of(
                        "entry_id",
                        1001,
                        "status",
                        "PUBLISHING",
                        "published_at",
                        LocalDateTime.now().toString()),
                "target_preparation", Map.of("state", "READY_TO_PUBLISH"))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    java.util.List<Map<String, Object>> runs = service.listRuns(10);

    String displayStatus = (String) runs.get(0).get("displayStatus");
    assertThat(displayStatus).isEqualTo("PUBLISHING");
  }

  @Test
  void statusMapping_reconcilingShowsAwaitingExecutorConfirmation() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_reconciling_map");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("fish_training");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "micro6_publish",
                Map.of(
                    "entry_id",
                    1001,
                    "status",
                    "RECONCILING",
                    "published_at",
                    LocalDateTime.now().toString()))));
    when(iterationRepository.findBySourceTypeOrderByCreatedAtDesc(
            BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL))
        .thenReturn(java.util.List.of(iteration));

    java.util.List<Map<String, Object>> runs = service.listRuns(10);

    String displayStatus = (String) runs.get(0).get("displayStatus");
    assertThat(displayStatus).isEqualTo("AWAITING_EXECUTOR_CONFIRMATION");
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

  @Test
  void launchAccount_passesRealCancellationToPreparation() {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));
    AtomicReference<BuilderGoalLaunchCancellation> capturedCancellation = new AtomicReference<>();
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenAnswer(
            invocation -> {
              capturedCancellation.set(invocation.getArgument(2));
              return new Micro6TargetPreparationService.PreparationResult(
                  targetClientAt("http://127.0.0.1:8098"),
                  Map.of("state", Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH),
                  null,
                  null);
            });

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
    BuilderGoalLaunchCancellation cancellation = capturedCancellation.get();
    assertThat(cancellation).isNotNull();
    // The active-work cancellation (not a no-op) must be passed into preparation so the
    // watchdog/stop can abort the worker.
    assertThat(cancellation.isCancelled()).isFalse();
  }

  @Test
  void pendingLaunch_leaseConflictPersistsWaitingForLeaseAndDoesNotFail() throws Exception {
    BuilderGoalAccountCatalogService.AccountDefinition account =
        new BuilderGoalAccountCatalogService.AccountDefinition(
            "1111111111111111",
            "1111111111111111",
            "bigGreenMeat",
            "bigGreenMeat",
            "bigGreenMeat",
            AccountIdentityContract.HASH_SCHEME,
            "user@example.com",
            "launcher",
            true,
            "ready",
            Map.of("password", "s3cret"));
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_lease_wait");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "world", 360,
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_hash", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"),
                "settings", Map.of("treeType", "WILLOW"))));
    when(iterationRepository.findByIterationId("buildergoal_lease_wait"))
        .thenReturn(Optional.of(iteration));
    when(accountCatalogService.findAccount("1111111111111111")).thenReturn(Optional.of(account));

    // Another workflow holds the lease for the same machine+port.
    localLaunchCoordinator.tryAcquire("machine-1", 8098, "other-account", "op-other");

    Method continuePendingLaunch =
        BuilderGoalRunnerService.class.getDeclaredMethod("continuePendingLaunch", String.class);
    continuePendingLaunch.setAccessible(true);
    continuePendingLaunch.invoke(service, "buildergoal_lease_wait");

    @SuppressWarnings("unchecked")
    Map<String, Object> savedMetadata =
        objectMapper.convertValue(iteration.getTaskMetadataJson(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> preparation = (Map<String, Object>) savedMetadata.get("target_preparation");
    assertThat(preparation.get("state")).isEqualTo("WAITING_FOR_LOCAL_LAUNCH_LEASE");
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.QUEUED);
    verify(targetPreparationService, never()).ensureReady(any(), any(), any(), any());
    verify(persistenceService, never()).markIterationFailed(any(), any(), anyString(), anyString());
  }

  @Test
  void recovery_conflictBeforePatchDoesNotApplyOrChangePhase() throws Exception {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_recovery_conflict");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
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
                "description": "fix",
                "changes": [
                  { "filePath": "tasks/x.java", "oldText": "old", "newText": "new" }
                ]
              }
            }
            """));
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "task_key", "configurable_woodcutting",
                "display_name", "Configurable Woodcutting",
                "micro6_publish", Map.of("status", "FAILED"),
                "recovery", Map.of("phase", "PATCH_PLAN_READY"),
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_hash", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "launcher_machine_uuid", "machine-1"))));
    when(iterationRepository.findByIterationId("buildergoal_recovery_conflict"))
        .thenReturn(Optional.of(iteration));

    // Another workflow holds the lease, so recovery must conflict before patching.
    localLaunchCoordinator.tryAcquire("machine-1", 8098, "other-account", "op-other");

    assertThatThrownBy(() -> service.approveRecovery("buildergoal_recovery_conflict"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("holds the local launch lease");
    verify(micro6TaskSourcePatchService, never()).applyPatch(any(), any(), any(), any());

    @SuppressWarnings("unchecked")
    Map<String, Object> savedMetadata =
        objectMapper.convertValue(iteration.getTaskMetadataJson(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> recovery = (Map<String, Object>) savedMetadata.get("recovery");
    assertThat(recovery.get("phase")).isEqualTo("PATCH_PLAN_READY");
    assertThat(iteration.getMicro6RevisionId()).isNull();
  }

  @Test
  void recovery_holdsLeaseAcrossPatchRelaunchAndRepublish() throws Exception {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("buildergoal_recovery_lease");
    iteration.setSourceType(BuilderGoalRunnerService.SOURCE_TYPE_BUILDER_GOAL);
    iteration.setScriptName("configurable_woodcutting");
    iteration.setStatus(PatchLoopStatus.FAILED);
    iteration.setPatchId("patch-123");
    iteration.setImportedPayloadJson(
        objectMapper.readTree(
            """
            {
              "task_key": "configurable_woodcutting",
              "settings": { "treeType": "WILLOW" }
            }
            """));
    iteration.setAnalysisResponseJson(
        objectMapper.readTree(
            """
            {
              "schemaVersion": "2.0.0",
              "analysisId": "analysis-123",
              "status": "SUCCESS",
              "patchDraft": {
                "patchId": "patch-123",
                "description": "fix",
                "changes": [
                  { "filePath": "tasks/x.java", "oldText": "old", "newText": "new" }
                ]
              }
            }
            """));
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            Map.of(
                "task_key", "configurable_woodcutting",
                "display_name", "Configurable Woodcutting",
                "settings", Map.of("treeType", "WILLOW"),
                "world", 360,
                "micro6_publish", Map.of("status", "FAILED"),
                "recovery", Map.of("phase", "PATCH_PLAN_READY"),
                "mule_account",
                    Map.of(
                        "account_id", "1111111111111111",
                        "account_hash", "1111111111111111",
                        "account_name", "bigGreenMeat",
                        "email", "user@example.com",
                        "launcher_machine_uuid", "machine-1"))));
    when(iterationRepository.findByIterationId("buildergoal_recovery_lease"))
        .thenReturn(Optional.of(iteration));

    Micro6TaskSourcePatchService.PatchApplyResult patchResult =
        new Micro6TaskSourcePatchService.PatchApplyResult(
            "configurable_woodcutting", "rev-1", 1, 1, "patched");
    when(micro6TaskSourcePatchService.applyPatch(
            isNull(), eq("configurable_woodcutting"), eq("buildergoal_recovery_lease"), any()))
        .thenReturn(patchResult);
    AtomicBoolean leaseHeldDuringRelaunch = new AtomicBoolean(false);
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenAnswer(
            invocation -> {
              BuilderGoalLocalLaunchCoordinatorService.LeaseHandle lease =
                  invocation.getArgument(1);
              // The recovery workflow must still hold the lease while it relaunches.
              leaseHeldDuringRelaunch.set(localLaunchCoordinator.holdsLease(lease));
              return new Micro6TargetPreparationService.PreparationResult(
                  targetClientAt("http://127.0.0.1:8098"),
                  Map.of(
                      "state",
                      Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH,
                      "launcher_pid",
                      5555L),
                  null,
                  null);
            });
    when(scriptExecutionService.reserveMicro6EntryId()).thenReturn(100);
    when(scriptExecutionService.getMicro6ExecutorHealth(anyMap()))
        .thenReturn(dispatchReadyHealth());
    when(scriptExecutionService.publishMicro6Task(anyString(), anyMap(), any()))
        .thenReturn(
            ScriptExecutionService.Micro6PublishResult.success(
                100, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null));

    Map<String, Object> response = service.approveRecovery("buildergoal_recovery_lease");

    assertThat(response.get("recoveryPhase")).isEqualTo("WAITING_FOR_DEBUG_BIND");
    // The recovery workflow held the lease across the relaunch, and the finally block released it.
    assertThat(leaseHeldDuringRelaunch.get()).isTrue();
    assertThat(
            localLaunchCoordinator
                .tryAcquire("machine-1", 8098, "1111111111111111", "after-recovery")
                .isSuccess())
        .isTrue();
  }

  @Test
  void launchAccount_hashMismatchOverridesMatchingName() {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(credentialedAccount()));
    Micro6OnlineClientRegistryService.TargetClient replacement =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-new",
            "bigGreenMeat",
            "1111111111111111",
            "http://127.0.0.1:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(targetPreparationService.ensureReady(
            any(Micro6TargetPreparationService.PreparationRequest.class),
            any(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle.class),
            any(BuilderGoalLaunchCancellation.class),
            any()))
        .thenReturn(
            new Micro6TargetPreparationService.PreparationResult(
                replacement,
                Map.of(
                    "state",
                    Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH,
                    "readiness_phase",
                    Micro6TargetRelaunchService.PHASE_READY,
                    "readiness_reason",
                    "dispatch-ready",
                    "local_executor_base_url",
                    "http://127.0.0.1:8098",
                    "reused_executor",
                    false,
                    "launcher_log_file",
                    "C:\\micro6\\micro6-recovery-launch.log"),
                null,
                null));

    Map<String, Object> result = service.launchAccount("1111111111111111", "machine-1", 360);

    // A matching display name cannot override a resolved-hash mismatch: a mismatched executor is
    // never reused or reported as CONFLICT; the shared preparation replaces it under the lease.
    assertThat(result.get("status")).isEqualTo(BuilderGoalLaunchOperationService.STATUS_READY);
    assertThat(result.get("reusedExecutor")).isEqualTo(false);
    assertThat(result.get("success")).isEqualTo(true);
    verify(targetPreparationService, times(1)).ensureReady(any(), any(), any(), any());
    assertThat(
            localLaunchCoordinator
                .tryAcquire("machine-1", 8098, "1111111111111111", "after-launch")
                .isSuccess())
        .isTrue();
  }

  @Test
  void startRun_rejectsConflictingIdentityFields() throws Exception {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(sc666Account()));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);
    sc666Start("configurable_woodcutting", "stable-request", Map.of());
    java.util.List<java.util.concurrent.Callable<Map<String, Object>>> conflicts =
        List.of(
            () -> sc666Start("configurable_fishing", "stable-request", Map.of()),
            () -> sc666Start("configurable_woodcutting", "stable-request", Map.of("changed", true)),
            () ->
                service.startCatalogTaskRun(
                    "different-account",
                    "machine-1",
                    "configurable_woodcutting",
                    Map.of(),
                    null,
                    null,
                    null,
                    "stable-request"),
            () ->
                service.startCatalogTaskRun(
                    "1111111111111111",
                    "different-machine",
                    "configurable_woodcutting",
                    Map.of(),
                    null,
                    null,
                    null,
                    "stable-request"),
            () ->
                service.startCatalogTaskRun(
                    "1111111111111111",
                    "machine-1",
                    "configurable_woodcutting",
                    Map.of(),
                    null,
                    301,
                    null,
                    "stable-request"),
            () ->
                service.startCatalogTaskRun(
                    "1111111111111111",
                    "machine-1",
                    "configurable_woodcutting",
                    Map.of(),
                    null,
                    null,
                    Map.of("profile", "changed"),
                    "stable-request"));
    for (var conflict : conflicts) {
      assertThatThrownBy(conflict::call)
          .isInstanceOf(BuilderGoalStartException.class)
          .satisfies(
              e ->
                  assertThat(((BuilderGoalStartException) e).getStatus())
                      .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    }
    assertThat(savedIterations).hasSize(1);
  }

  @Test
  void startRun_afterServiceRestartRetainsActualBoundSessionAndRecording() throws Exception {
    when(accountCatalogService.findAccount("1111111111111111"))
        .thenReturn(Optional.of(sc666Account()));
    when(micro6ExecutorCatalogService.peekCatalog()).thenReturn(null);
    var first = sc666Start("configurable_woodcutting", "stable-request", Map.of());
    var run = (Map<String, Object>) first.get("run");
    var saved = savedIterations.get((String) run.get("iterationId"));
    saved.setRunSessionId("confirmed-session");
    saved.setRunRecordingId("confirmed-recording");
    service = createService(launchOperationService, localLaunchCoordinator, task -> {});
    var retry = sc666Start("configurable_woodcutting", "stable-request", Map.of());
    var retryRun = (Map<String, Object>) retry.get("run");
    assertThat(retryRun.get("runSessionId")).isEqualTo("confirmed-session");
    assertThat(retryRun.get("runRecordingId")).isEqualTo("confirmed-recording");
    assertThat(savedIterations).hasSize(1);
  }
}
