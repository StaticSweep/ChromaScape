package com.chromascape.web.aiplayer.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.*;
import com.chromascape.web.aiplayer.routine.service.*;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class CurrentContractBoundaryRefusalTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final RoutineCaptureRecordRepository records = mock(RoutineCaptureRecordRepository.class);
  private final RoutineCaptureOperationRepository operations =
      mock(RoutineCaptureOperationRepository.class);
  private final RoutineCaptureBatchRepository batches = mock(RoutineCaptureBatchRepository.class);
  private final CurrentSliceRunRepository runs = mock(CurrentSliceRunRepository.class);
  private final CaptureOperationDispatcher dispatcher = mock(CaptureOperationDispatcher.class);
  private final TickSnapshotRepository snapshots = mock(TickSnapshotRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final RoutineCaptureIngestionService captures =
      new RoutineCaptureIngestionService(
          mapper,
          new CurrentSliceIdentityValidator(new CurrentContractGate("")),
          records,
          operations,
          batches,
          runs,
          dispatcher,
          snapshots,
          events);

  private CurrentSliceIdentity identity() throws Exception {
    try (var stream = getClass().getResourceAsStream("/sc663/runelite-identity.json")) {
      return mapper.readValue(stream, CurrentSliceIdentity.class);
    }
  }

  @Test
  void wrongOrMissingEnvelopeVersionsCannotReachRepositoriesOrRecorder() throws Exception {
    for (String schema :
        new String[] {null, "", "routine-tick-batch/v2", "routine-action-batch/v1"}) {
      var request = new RoutineApiDtos.BatchRequest(schema, "session", 0, List.of(), List.of());
      assertThatThrownBy(() -> captures.ingestTicks("session", request, "key"))
          .isInstanceOf(CurrentSliceContractException.class);
    }
    assertThatThrownBy(
            () ->
                captures.ingestActions(
                    "session",
                    new RoutineApiDtos.BatchRequest(
                        "routine-tick-batch/v1", "session", 0, List.of(), List.of()),
                    "key"))
        .isInstanceOf(CurrentSliceContractException.class);
    assertThatThrownBy(
            () ->
                captures.complete(
                    "session", new RoutineApiDtos.CompleteRequest(null, 0, 0, 0, null), "key"))
        .isInstanceOf(CurrentSliceContractException.class);
    var identity = identity();
    assertThatThrownBy(
            () ->
                captures.start(
                    new RoutineApiDtos.CaptureSessionRequest(
                        "routine-capture-session/v2", identity, null, 0, "NONE"),
                    "key"))
        .isInstanceOf(CurrentSliceContractException.class);
    verifyNoInteractions(records, operations, batches, runs, dispatcher, snapshots, events);
  }

  @Test
  void unconfiguredPinsCannotCreateOrRetryCapture() throws Exception {
    var identity = identity();
    assertThatThrownBy(
            () ->
                captures.start(
                    new RoutineApiDtos.CaptureSessionRequest(
                        "routine-capture-session/v1", identity, null, 0, "NONE"),
                    "key"))
        .hasMessage("CONTRACT_UNCONFIGURED");
    verifyNoInteractions(records, operations, batches, runs, dispatcher, snapshots, events);
  }

  @Test
  void stalePersistedSessionCannotCreateBatchOrCompletionArtifacts() throws Exception {
    var record = new RoutineCaptureRecord();
    record.setIdentityJson(mapper.writeValueAsString(identity()));
    when(records.findWithLockBySessionId("session")).thenReturn(Optional.of(record));
    assertThatThrownBy(
            () ->
                captures.ingestTicks(
                    "session",
                    new RoutineApiDtos.BatchRequest(
                        "routine-tick-batch/v1", "session", 0, List.of(), null),
                    "key"))
        .hasMessage("CONTRACT_UNCONFIGURED");
    assertThatThrownBy(
            () ->
                captures.complete(
                    "session",
                    new RoutineApiDtos.CompleteRequest(
                        "routine-capture-complete/v1", 0, 0, 0, null),
                    "key"))
        .hasMessage("CONTRACT_UNCONFIGURED");
    verify(records, never()).save(any());
    verifyNoInteractions(operations, batches, runs, dispatcher, snapshots, events);
  }

  @Test
  void unavailableSemanticLearningCannotWriteCuration() {
    var ingestion = mock(RoutineCaptureIngestionService.class);
    var curations = mock(RoutineCurationRepository.class);
    doThrow(
            new CurrentSliceContractException(
                CurrentSliceFailureCode.CAPABILITY_UNAVAILABLE, "CAPABILITY_UNAVAILABLE"))
        .when(ingestion)
        .requireRecordingOperation("recording", "semanticLearning");
    var service = new RoutineCurationService(ingestion, curations, mapper);
    assertThatThrownBy(
            () ->
                service.put("recording", new RoutineApiDtos.CurationRequest(List.of(), List.of())))
        .hasMessage("CAPABILITY_UNAVAILABLE");
    verifyNoInteractions(curations);
  }

  private CurrentSliceRunService pinnedRuns() {
    return new CurrentSliceRunService(
        new CurrentSliceIdentityValidator(new CurrentContractGate("")),
        runs,
        mapper,
        mock(com.chromascape.web.accounttask.service.StabilizationIngestionService.class));
  }

  @Test
  void contractMismatchCannotGenerateOrPublishGraph() throws Exception {
    var ingestion = mock(RoutineCaptureIngestionService.class);
    var graphRows = mock(BehaviorGraphDefinitionRepository.class);
    var identity = identity();
    when(ingestion.recording("recording"))
        .thenReturn(
            java.util.Map.of(
                "status",
                "COMPLETED",
                "recording_hash",
                "sha256:" + "a".repeat(64),
                "identity",
                identity));
    var service =
        new BehaviorGraphService(
            mapper,
            ingestion,
            mock(RoutineCurationService.class),
            graphRows,
            pinnedRuns(),
            mock(GraphSchemaValidator.class));
    assertThatThrownBy(() -> service.generate("recording")).hasMessage("CONTRACT_UNCONFIGURED");
    verifyNoInteractions(graphRows);
    var draft = new com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition();
    draft.setState("DRAFT");
    draft.setIdentityJson(mapper.writeValueAsString(identity));
    when(graphRows.findWithLockByGraphId("graph")).thenReturn(Optional.of(draft));
    assertThatThrownBy(() -> service.publish("graph")).hasMessage("CONTRACT_UNCONFIGURED");
    assertThat(draft.getState()).isEqualTo("DRAFT");
    verify(graphRows, never()).save(any());
    verifyNoInteractions(runs);
  }

  @Test
  void contractMismatchCannotCreateModel() throws Exception {
    var graphs = mock(BehaviorGraphService.class);
    var models = mock(RoutineModelRepository.class);
    var traces = mock(RoutineReplayTraceRepository.class);
    var identity = identity();
    when(graphs.get("graph"))
        .thenReturn(
            java.util.Map.of(
                "state", "PUBLISHED", "definition", java.util.Map.of("identity", identity)));
    var service =
        new RoutineReplayShadowService(mapper, captures, graphs, models, traces, pinnedRuns());
    assertThatThrownBy(() -> service.buildModel("graph", identity))
        .hasMessage("CONTRACT_UNCONFIGURED");
    verifyNoInteractions(models, traces, runs);
  }

  @Test
  void contractMismatchCannotStartDurableShadow() throws Exception {
    var ingestion = mock(RoutineCaptureIngestionService.class);
    var models = mock(RoutineModelRepository.class);
    var traces = mock(RoutineReplayTraceRepository.class);
    var shadows = mock(RoutineShadowRunRepository.class);
    var decisions = mock(RoutineShadowDecisionRepository.class);
    var identity = identity();
    when(ingestion.status("session"))
        .thenReturn(java.util.Map.of("status", "RECORDING", "identity", identity));
    var model = new com.chromascape.web.aiplayer.routine.entity.RoutineModel();
    model.setIdentityJson(mapper.writeValueAsString(identity));
    when(models.findByModelId("model")).thenReturn(Optional.of(model));
    var replay = new com.chromascape.web.aiplayer.routine.entity.RoutineReplayTrace();
    replay.setResultJson("{\"status\":\"PASSED\"}");
    when(traces.findFirstByModelIdAndTraceModeOrderByCreatedAtDesc("model", "POLICY_REPLAY"))
        .thenReturn(Optional.of(replay));
    var service =
        new RoutineShadowService(
            mapper,
            models,
            traces,
            shadows,
            decisions,
            ingestion,
            new CurrentSliceIdentityValidator(new CurrentContractGate("")));
    assertThatThrownBy(() -> service.create("model", "session"))
        .hasMessage("CONTRACT_UNCONFIGURED");
    verify(shadows, never()).save(any());
    verifyNoInteractions(decisions);
  }

  @Test
  void staleCandidateCannotBeApprovedQueuedStagedOrRegistered() throws Exception {
    var candidates = mock(PatchCandidateRunRepository.class);
    var worker = mock(IsolatedPatchWorker.class);
    var dispatch = mock(PatchCandidateBuildDispatcher.class);
    var source = mock(Micro6SourceManifestClient.class);
    var registry = mock(CandidateRegistryClient.class);
    var registration = mock(PatchCandidateRegistrationDispatcher.class);
    var properties = new com.chromascape.web.aiplayer.routine.config.Sc967CandidateProperties();
    properties.setBuildsEnabled(true);
    var service =
        new PatchCandidateWorkflowService(
            mapper,
            worker,
            candidates,
            dispatch,
            pinnedRuns(),
            source,
            new CurrentSliceIdentityValidator(new CurrentContractGate("")),
            registry,
            mock(com.chromascape.web.aiplayer.routine.config.AiRoutineOperatorProperties.class),
            properties,
            registration,
            new com.chromascape.web.aiplayer.routine.service.CertifiedCandidateV2Validator());
    for (String state : new String[] {"PATCH_PROPOSED", "APPROVED", "BUILT", "REGISTERING"}) {
      var row = new com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun();
      row.setState(state);
      row.setIdentityJson(mapper.writeValueAsString(identity()));
      when(candidates.findWithLockByProposalId("proposal")).thenReturn(Optional.of(row));
      when(candidates.findByProposalId("proposal")).thenReturn(Optional.of(row));
      assertThatThrownBy(
              () -> {
                switch (state) {
                  case "PATCH_PROPOSED" -> service.approve("proposal", "operator");
                  case "APPROVED" -> service.enqueueBuild("proposal");
                  case "BUILT" -> service.prepareRegistration("proposal", null);
                  case "REGISTERING" -> service.postPreparedRegistration("proposal");
                  default -> throw new AssertionError(state);
                }
              })
          .hasMessage("CONTRACT_UNCONFIGURED");
      assertThat(row.getState()).isEqualTo(state);
      assertThat(row.getApprovalHash()).isNull();
      assertThat(row.getCandidateStagingDirectory()).isNull();
    }
    verify(candidates, never()).save(any());
    verifyNoInteractions(worker, dispatch, source, registry, registration);
  }

  @Test
  void datasetContractRefusalCreatesNoOutputAndDoesNotLoadLegacyEvidence(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    var ingestion = mock(RoutineCaptureIngestionService.class);
    doThrow(
            new CurrentSliceContractException(
                CurrentSliceFailureCode.IDENTITY_MISMATCH, "IDENTITY_MISMATCH"))
        .when(ingestion)
        .requireRecordingOperation("recording", "semanticLearning");
    var preflight = mock(com.chromascape.web.aiplayer.prototype.FishingPreflightService.class);
    var loader = mock(com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.class);
    var service =
        new com.chromascape.web.aiplayer.prototype.FishingDatasetBuilderService(
            preflight, loader, mapper, ingestion);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "aiPlayerEnabled", true);
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "outputDir", root.resolve("dataset").toString());
    assertThatThrownBy(() -> service.train("recording", 5)).hasMessage("IDENTITY_MISMATCH");
    assertThat(root.resolve("dataset")).doesNotExist();
    verifyNoInteractions(preflight, loader);
  }

  @Test
  void staleQueuedCaptureCannotClaimOrReachRecorder() throws Exception {
    var gateway = mock(CaptureRecordingGateway.class);
    var results = mock(CaptureOperationResultService.class);
    var operation = new com.chromascape.web.aiplayer.routine.entity.RoutineCaptureOperation();
    operation.setState("PENDING");
    operation.setNextAttemptAt(java.time.Instant.EPOCH);
    operation.setRequestJson(mapper.writeValueAsString(java.util.Map.of("identity", identity())));
    when(operations.findWithLockById(1L)).thenReturn(Optional.of(operation));
    var service =
        new CaptureOperationDispatcher(
            operations,
            gateway,
            results,
            mock(org.springframework.transaction.PlatformTransactionManager.class),
            new CurrentSliceIdentityValidator(new CurrentContractGate("")));
    assertThatThrownBy(() -> service.claim(1L)).hasMessage("CONTRACT_UNCONFIGURED");
    assertThat(operation.getState()).isEqualTo("PENDING");
    assertThat(operation.getAttemptId()).isNull();
    verify(operations, never()).save(any());
    verifyNoInteractions(gateway, results);
  }

  @Test
  void staleCaptureCannotMutateOrRedispatchReconciliation() throws Exception {
    var record = new RoutineCaptureRecord();
    record.setIdentityJson(mapper.writeValueAsString(identity()));
    when(records.findWithLockBySessionId("session")).thenReturn(Optional.of(record));
    assertThatThrownBy(() -> captures.reconcile("session", "key"))
        .hasMessage("CONTRACT_UNCONFIGURED");
    verify(records, never()).save(any());
    verifyNoInteractions(operations, dispatcher);
  }
}
