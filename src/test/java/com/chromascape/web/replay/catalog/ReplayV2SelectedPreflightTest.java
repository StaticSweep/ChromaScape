package com.chromascape.web.replay.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.api.ReplayV2Controller;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.dto.ReplayBlockingReason;
import com.chromascape.web.replay.catalog.dto.ReplayEligibility;
import com.chromascape.web.replay.conversion.*;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler.Compilation;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.run.ReplayV2RunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplayV2SelectedPreflightTest {
  private final ReplayRecordingResolver resolver = mock(ReplayRecordingResolver.class);
  private final RecordingIntegrityService integrity = mock(RecordingIntegrityService.class);
  private final TickSnapshotRepository snapshots = mock(TickSnapshotRepository.class);
  private final ReplayActionConversionService converter = mock(ReplayActionConversionService.class);
  private final ReplayCapabilityService capabilities = mock(ReplayCapabilityService.class);
  private final ScenarioManifestV2Compiler compiler = mock(ScenarioManifestV2Compiler.class);
  private final ReplayV2PreflightService service =
      new ReplayV2PreflightService(
          resolver, integrity, snapshots, converter, capabilities, compiler);
  private final UUID selection = UUID.fromString("2458e418-890e-456d-ad3e-6249367c1710");
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setUp() {
    var session = new DebugSession();
    session.setSessionId("session");
    when(resolver.resolve("recording"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    // The unselected raw range can be ineligible without vetoing a valid published trim.
    when(integrity.evaluate(any()))
        .thenReturn(
            new ReplayEligibility(
                true,
                false,
                false,
                List.of(
                    new ReplayBlockingReason(
                        ReplayMode.CAUSAL_SIMULATION,
                        ReplayErrorCode.PLAYER_STATE_MISSING,
                        "raw initial tick missing",
                        "select a valid range"))));
  }

  private Compilation compiled(boolean sourceGap) {
    var result = mock(Compilation.class);
    var manifest = new ScenarioManifest();
    manifest.setDebugSessionId("session");
    manifest.setId(UUID.fromString("2458e418-890e-456d-ad3e-6249367c1711"));
    manifest.setManifestChecksum("sha256:" + "a".repeat(64));
    manifest.setStartTick(1);
    var document = json.createObjectNode();
    document.putObject("curation").putObject("sourceCuration").put("id", selection.toString());
    var gaps = document.putObject("conversion").putArray("gaps");
    if (sourceGap)
      gaps.addObject()
          .put("tick", 1)
          .put("code", "SOURCE_IDENTITY_INCOMPLETE")
          .put("message", "source context absent")
          .put("provenance", "OBSERVED")
          .put("sourceRef", "recording:recording/runContext");
    manifest.setManifestJson(document);
    when(result.manifest()).thenReturn(manifest);
    when(result.conversion())
        .thenReturn(
            new ReplayActionConversionResult(
                "session",
                1,
                1,
                ConversionMode.AUTO,
                ConversionQuality.BEST_EFFORT,
                List.of(),
                List.of(),
                List.of(),
                7,
                0,
                100.0,
                "sha256:authored-projection-test"));
    when(result.capability())
        .thenReturn(
            CapabilityManifest.current(
                new CapabilityManifest.CapabilityModes(false, false, true),
                new CapabilityManifest.XrspsIdentity("adapter", "build", "cache", "content"),
                false));
    if (sourceGap)
      doThrow(
              ReplayException.invalid(
                  ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE, "compiled source audit blocked"))
          .when(result)
          .requireSimulationAllowed();
    return result;
  }

  @Test
  void selectedEligibilityAndCountsComeFromCompilerNotRawSourcePreflight() {
    var compilation = compiled(false);
    when(compiler.preview("recording", ConversionMode.AUTO, selection, "owner"))
        .thenReturn(compilation);
    var response = service.preflight("recording", null, selection, "owner");
    assertThat(response.simulationEligible()).isTrue();
    assertThat(response.certificationEligible()).isFalse();
    assertThat(response.preflightState()).isEqualTo("READY");
    assertThat(response.typedActionCount()).isEqualTo(7);
    assertThat(response.blockingReasons()).isEmpty();
    assertThat(response.compiledInput().curationId()).isEqualTo(selection);
    assertThat(response.compiledInput().manifestChecksum()).isEqualTo("sha256:" + "a".repeat(64));
    verify(compiler).preview("recording", ConversionMode.AUTO, selection, "owner");
    verifyNoInteractions(converter, snapshots);
  }

  @Test
  void auditGapsRemainVisibleAtFullCapturedRowCoverage() {
    var compilation = compiled(true);
    when(compiler.preview("recording", ConversionMode.AUTO, selection, "owner"))
        .thenReturn(compilation);
    var response = service.preflight("recording", ConversionMode.AUTO, selection, "owner");
    assertThat(response.simulationEligible()).isFalse();
    assertThat(response.actionCoveragePct()).isEqualTo(100.0);
    assertThat(response.gapCount()).isEqualTo(1);
    assertThat(response.gaps().get(0).message()).contains("recording:recording/runContext");
    assertThat(response.gaps().get(0).provenance()).isEqualTo(ActionProvenance.CAPTURED);
  }

  @Test
  void compilerRefusalIsABlockedReadOnlyPreview() {
    when(compiler.preview("recording", ConversionMode.AUTO, selection, "owner"))
        .thenThrow(
            ReplayException.invalid(
                ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED, "missing schema at tick 1"));
    var response = service.preflight("recording", ConversionMode.AUTO, selection, "owner");
    assertThat(response.preflightState()).isEqualTo("BLOCKED");
    assertThat(response.simulationEligible()).isFalse();
    assertThat(response.playbackEligible()).isTrue();
    assertThat(response.blockingReasons().get(0).code())
        .isEqualTo(ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED);
    verify(compiler, never()).compile(anyString(), any(), any(), anyString());
  }

  @Test
  void ownershipRefusalIsNotConvertedToAnInformativePreview() {
    when(compiler.preview("recording", ConversionMode.AUTO, selection, "owner"))
        .thenThrow(ReplayException.notFound(ReplayErrorCode.CURATION_NOT_PUBLISHED, "unavailable"));
    assertThat(
            assertThrows(
                    ReplayException.class,
                    () -> service.preflight("recording", null, selection, "owner"))
                .getHttpStatus()
                .value())
        .isEqualTo(404);
    verifyNoInteractions(converter, snapshots, resolver, integrity);
  }

  @Test
  void unauthenticatedPreviewIsRefusedBeforeLookingUpTheSelection() {
    assertThat(
            assertThrows(
                    ReplayException.class,
                    () -> service.preflight("recording", null, selection, null))
                .getHttpStatus()
                .value())
        .isEqualTo(401);
    verifyNoInteractions(compiler);
  }

  @Test
  void controllerForwardsExactCurationAndAuthenticatedOwner() {
    var runs = mock(ReplayV2RunService.class);
    var controller = new ReplayV2Controller(runs);
    controller.preflight("recording", ConversionMode.AUTO, selection, () -> "owner");
    verify(runs).preflight("recording", ConversionMode.AUTO, selection, "owner");
    verify(runs, never()).preflight(anyString(), any());
  }
}
