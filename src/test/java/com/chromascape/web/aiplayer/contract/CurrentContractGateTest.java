package com.chromascape.web.aiplayer.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentContractGateTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  @TempDir Path root;

  private byte[] fixture(String name) throws Exception {
    try (var stream = getClass().getResourceAsStream("/sc663/" + name)) {
      return stream.readAllBytes();
    }
  }

  private CurrentContractGate gate(ObjectNode manifest) throws Exception {
    Path pin = root.resolve("current.json");
    MAPPER.writeValue(pin.toFile(), manifest);
    return new CurrentContractGate(pin.toString());
  }

  private ObjectNode manifest() throws Exception {
    return (ObjectNode) MAPPER.readTree(fixture("current-manifest.json"));
  }

  private CurrentSliceIdentity identity(String kind) throws Exception {
    return MAPPER.readValue(fixture(kind + "-identity.json"), CurrentSliceIdentity.class);
  }

  @Test
  void bothPinnedClientsRoundTripAndValidate() throws Exception {
    var validator = new CurrentSliceIdentityValidator(gate(manifest()));
    for (String kind : new String[] {"micro6", "runelite"}) {
      var before = identity(kind);
      var after = MAPPER.readValue(MAPPER.writeValueAsBytes(before), CurrentSliceIdentity.class);
      assertThat(after).isEqualTo(before);
      validator.requireOperation(after, "capture");
    }
  }

  @Test
  void optionalMissingFeaturesDoNotBlockRuneLiteCapture() throws Exception {
    var pin = manifest();
    ((ObjectNode) pin.path("clients").path("RUNELITE_CAPTURE").path("operations")).remove("shadow");
    var gate = gate(pin);
    gate.requireOperation(identity("runelite"), "capture");
    assertThatThrownBy(() -> gate.requireOperation(identity("runelite"), "shadow"))
        .isInstanceOf(CurrentSliceContractException.class)
        .hasMessage("CAPABILITY_UNAVAILABLE");
    assertThat(gate.diagnostics())
        .containsEntry("controlAllowed", false)
        .containsEntry("runtimeReadinessVerified", false);
  }

  @Test
  void missingPinBlocksBeforeRunPersistence() throws Exception {
    var repository = mock(CurrentSliceRunRepository.class);
    var stabilization = mock(StabilizationIngestionService.class);
    var service =
        new CurrentSliceRunService(
            new CurrentSliceIdentityValidator(new CurrentContractGate("")),
            repository,
            MAPPER,
            stabilization);
    assertThatThrownBy(() -> service.createRunelite(identity("runelite")))
        .hasMessage("CONTRACT_UNCONFIGURED");
    verifyNoInteractions(repository, stabilization);
  }

  @Test
  void staleOrMissingBuildArtifactAndAdapterBlockBeforeRunPersistence() throws Exception {
    var repository = mock(CurrentSliceRunRepository.class);
    var stabilization = mock(StabilizationIngestionService.class);
    var service =
        new CurrentSliceRunService(
            new CurrentSliceIdentityValidator(gate(manifest())), repository, MAPPER, stabilization);
    for (String field : new String[] {"client_build_id", "artifact_digest", "adapter_contract"}) {
      for (boolean omit : new boolean[] {false, true}) {
        var input = (ObjectNode) MAPPER.readTree(fixture("runelite-identity.json"));
        var client = (ObjectNode) input.path("client");
        if (omit) client.remove(field);
        else
          client.put(field, field.equals("artifact_digest") ? "sha256:" + "c".repeat(64) : "stale");
        var value = MAPPER.treeToValue(input, CurrentSliceIdentity.class);
        assertThatThrownBy(() -> service.createRunelite(value))
            .isInstanceOf(CurrentSliceContractException.class);
      }
    }
    verifyNoInteractions(repository, stabilization);
  }

  @Test
  void controlClaimIsNotErasedByRunEnrichment() throws Exception {
    var value = identity("runelite");
    var forbidden =
        new CurrentSliceIdentity(
            value.schema(),
            value.task(),
            value.run(),
            value.client(),
            null,
            null,
            null,
            null,
            null,
            true);
    var repository = mock(CurrentSliceRunRepository.class);
    var service =
        new CurrentSliceRunService(
            new CurrentSliceIdentityValidator(gate(manifest())),
            repository,
            MAPPER,
            mock(StabilizationIngestionService.class));
    assertThatThrownBy(() -> service.createRunelite(forbidden))
        .hasMessage("control_allowed must be false");
    verifyNoInteractions(repository);
  }

  @Test
  void controlWireFieldMustBePresentAndLiteralFalse() throws Exception {
    for (String value : new String[] {"true", "\"false\"", "0", "null"}) {
      String json =
          new String(fixture("runelite-identity.json"), java.nio.charset.StandardCharsets.UTF_8)
              .replace("\"control_allowed\": false", "\"control_allowed\": " + value);
      assertThatThrownBy(() -> MAPPER.readValue(json, CurrentSliceIdentity.class))
          .isInstanceOf(Exception.class);
    }
    var missing = (ObjectNode) MAPPER.readTree(fixture("runelite-identity.json"));
    missing.remove("control_allowed");
    assertThatThrownBy(() -> MAPPER.treeToValue(missing, CurrentSliceIdentity.class))
        .isInstanceOf(Exception.class);
  }

  @Test
  void malformedManifestCannotInventCapabilitiesOrVersions() throws Exception {
    for (String field : new String[] {"inputDispatch", "taskExecution"}) {
      var value = manifest();
      ((ObjectNode) value.path("clients").path("RUNELITE_CAPTURE")).put(field, true);
      assertThat(gate(value).diagnostics()).containsEntry("reason", "CONTRACT_MANIFEST_INVALID");
    }
    var wrongSchema = manifest();
    ((ObjectNode) wrongSchema.path("schemas")).put("identity", "current-slice-identity/v2");
    assertThat(gate(wrongSchema).diagnostics())
        .containsEntry("reason", "CONTRACT_MANIFEST_INVALID");
  }

  @Test
  void diagnosticsRetainExactPinsAndCannotGrantReadiness() throws Exception {
    var gate = gate(manifest());
    var diagnostics = gate.diagnostics();
    assertThat(diagnostics)
        .containsEntry("backend", manifest().get("backend"))
        .containsEntry("clients", manifest().get("clients"))
        .containsEntry("schemas", CurrentContractGate.SCHEMAS)
        .containsEntry("status", "PINNED_EXPECTATIONS");
  }

  @Test
  void optionalVideoSimulatorAndSemanticProducerDegradeIndependently() throws Exception {
    var pin = manifest();
    var operations = (ObjectNode) pin.path("clients").path("RUNELITE_CAPTURE").path("operations");
    operations.remove(java.util.List.of("video", "simulator", "semanticProducer"));
    var gate = gate(pin);
    gate.requireOperation(identity("runelite"), "capture");
    gate.requireOperation(identity("runelite"), "graph");
    for (String operation :
        new String[] {"video", "simulator", "semanticProducer", "managedExecution"}) {
      assertThatThrownBy(() -> gate.requireOperation(identity("runelite"), operation))
          .hasMessage("CAPABILITY_UNAVAILABLE");
    }
    operations.put("managedExecution", true);
    assertThat(gate(pin).diagnostics()).containsEntry("reason", "CONTRACT_MANIFEST_INVALID");
  }

  @Test
  void missingVideoRefusesBeforeCaptureIdempotencyOrRecordingWrites() throws Exception {
    var pin = manifest();
    ((ObjectNode) pin.path("clients").path("RUNELITE_CAPTURE").path("operations")).remove("video");
    var records =
        mock(com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository.class);
    var operations =
        mock(
            com.chromascape.web.aiplayer.routine.repository.RoutineCaptureOperationRepository
                .class);
    var batches =
        mock(com.chromascape.web.aiplayer.routine.repository.RoutineCaptureBatchRepository.class);
    var runs = mock(CurrentSliceRunRepository.class);
    var dispatcher =
        mock(com.chromascape.web.aiplayer.routine.service.CaptureOperationDispatcher.class);
    var snapshots =
        mock(com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository.class);
    var events = mock(org.springframework.context.ApplicationEventPublisher.class);
    var service =
        new com.chromascape.web.aiplayer.routine.service.RoutineCaptureIngestionService(
            MAPPER,
            new CurrentSliceIdentityValidator(gate(pin)),
            records,
            operations,
            batches,
            runs,
            dispatcher,
            snapshots,
            events);
    var request =
        new com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos.CaptureSessionRequest(
            "routine-capture-session/v1", identity("runelite"), null, 0, "CHROMASCAPE_WINDOW");
    assertThatThrownBy(() -> service.start(request, "key")).hasMessage("CAPABILITY_UNAVAILABLE");
    verifyNoInteractions(records, operations, batches, runs, dispatcher, snapshots, events);
  }
}
