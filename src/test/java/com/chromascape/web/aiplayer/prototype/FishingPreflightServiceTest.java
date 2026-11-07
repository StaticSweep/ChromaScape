package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.AlignedAction;
import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.RecordingContext;
import com.chromascape.web.aiplayer.prototype.PreflightReport.PreflightIssue;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Tests the preflight acceptance contract. */
class FishingPreflightServiceTest {

  private DebugSessionRepository sessionRepository;
  private FishingRecordingLoader loader;
  private FishingPreflightService preflightService;
  private DebugSession session;

  @BeforeEach
  void setUp() {
    sessionRepository = mock(DebugSessionRepository.class);
    loader = mock(FishingRecordingLoader.class);
    preflightService = new FishingPreflightService(sessionRepository, loader);

    session = new DebugSession("debug_1", "acct", "script", LocalDateTime.now(), 0L);
    session.setRecordingId("recording_1");
    session.setEndTick(2000L);
    session.transitionTo(DebugSessionLifecycleState.FINALIZED);
    when(sessionRepository.findByRecordingId("recording_1")).thenReturn(Optional.of(session));
  }

  private RecordingContext context(
      int snapshotCount,
      int playerActionCount,
      int fishingActionCount,
      int alignedCount,
      int unsupportedCount,
      boolean overflow,
      int catchDelta,
      boolean xpIncreased,
      boolean playerState,
      boolean inventory,
      boolean npcTelemetry,
      int fallback,
      int unaligned) {
    List<TickSnapshot> snapshots = new ArrayList<>();
    for (int i = 0; i < snapshotCount; i++) {
      snapshots.add(new TickSnapshot());
    }
    List<PlayerAction> actions = new ArrayList<>();
    for (int i = 0; i < playerActionCount; i++) {
      actions.add(
          new PlayerAction(
              1000L + i, 0, "Net", "Fishing spot", "NPC_FIRST_OPTION", 1, 1, 0, 1000L + i));
    }
    List<PlayerAction> supported = actions.subList(0, Math.min(fishingActionCount, actions.size()));
    List<PlayerAction> unsupported = new ArrayList<>();
    for (int i = 0; i < unsupportedCount; i++) {
      unsupported.add(
          new PlayerAction(500L + i, 0, "Walk here", "Somewhere", "WALK", 0, 0, 0, 500L + i));
    }
    List<AlignedAction> aligned = new ArrayList<>();
    for (int i = 0; i < alignedCount; i++) {
      aligned.add(
          new AlignedAction(
              supported.get(i % Math.max(1, supported.size())),
              new TickSnapshot(),
              1000L + i,
              false));
    }
    return new RecordingContext(
        session,
        snapshots,
        actions,
        supported,
        unsupported,
        aligned,
        Map.of(),
        0,
        catchDelta,
        catchDelta,
        catchDelta,
        0,
        0,
        xpIncreased,
        playerState,
        inventory,
        npcTelemetry,
        overflow,
        fallback,
        unaligned);
  }

  @Test
  @DisplayName("recording not found returns a structured 404")
  void recordingNotFound() {
    when(sessionRepository.findByRecordingId("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> preflightService.resolveRecording("missing"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e ->
                assertThat(((PrototypeApiException) e).getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  @DisplayName("an active or non-finalized session is not ready")
  void sessionNotFinalized() {
    DebugSession active = new DebugSession("debug_2", "acct", "script", LocalDateTime.now(), 0L);
    active.setRecordingId("recording_2");
    when(sessionRepository.findByRecordingId("recording_2")).thenReturn(Optional.of(active));

    PreflightReport report = preflightService.preflight("recording_2");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.SESSION_NOT_FINALIZED);
  }

  @Test
  @DisplayName("a clean five-catch recording is ready")
  void readyWhenAllConditionsMet() {
    when(loader.load(session))
        .thenReturn(context(240, 2, 2, 2, 0, false, 5, true, true, true, true, 0, 0));

    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isTrue();
    assertThat(report.observedCatchDelta()).isEqualTo(5);
    assertThat(report.fishingXpIncreased()).isTrue();
    assertThat(report.snapshotCount()).isEqualTo(240);
    assertThat(report.alignedFishingActionCount()).isEqualTo(2);
    assertThat(report.issues()).isEmpty();
  }

  @Test
  @DisplayName("missing snapshots are reported")
  void noSnapshots() {
    when(loader.load(session))
        .thenReturn(context(0, 2, 2, 2, 0, false, 5, true, true, true, true, 0, 0));
    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.NO_TICK_SNAPSHOTS);
  }

  @Test
  @DisplayName("no fishing actions are reported")
  void noFishingActions() {
    when(loader.load(session))
        .thenReturn(context(10, 1, 0, 0, 0, false, 5, true, true, true, true, 0, 0));
    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.NO_FISHING_ACTIONS);
  }

  @Test
  @DisplayName("missing NPC telemetry is reported")
  void missingNpcTelemetry() {
    when(loader.load(session))
        .thenReturn(context(10, 2, 2, 2, 0, false, 5, true, true, true, false, 0, 0));
    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.NPC_TELEMETRY_MISSING);
  }

  @Test
  @DisplayName("action without a snapshot is reported")
  void actionWithoutSnapshot() {
    when(loader.load(session))
        .thenReturn(context(10, 2, 2, 1, 0, false, 5, true, true, true, true, 0, 1));
    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.ACTION_WITHOUT_SNAPSHOT);
  }

  @Test
  @DisplayName("action queue overflow is reported")
  void overflowReported() {
    when(loader.load(session))
        .thenReturn(context(10, 2, 2, 2, 0, true, 5, true, true, true, true, 0, 0));
    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.ACTION_QUEUE_OVERFLOW);
  }

  @Test
  @DisplayName("unsupported gameplay actions are reported")
  void unsupportedActionsReported() {
    when(loader.load(session))
        .thenReturn(context(10, 3, 2, 2, 1, false, 5, true, true, true, true, 0, 0));
    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.UNSUPPORTED_ACTION_PRESENT);
  }

  @Test
  @DisplayName("fewer or more than five catches are reported")
  void catchCountMismatch() {
    when(loader.load(session))
        .thenReturn(context(10, 2, 2, 2, 0, false, 3, true, true, true, true, 0, 0));
    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.CATCH_COUNT_MISMATCH);
    assertThat(report.issues())
        .filteredOn(issue -> PreflightReport.IssueCode.CATCH_COUNT_MISMATCH.equals(issue.code()))
        .singleElement()
        .satisfies(
            issue ->
                assertThat(issue.message())
                    .contains("net inventory total change of exactly 5")
                    .contains("observed 3")
                    .doesNotContain("positive inventory additions"));
  }

  @Test
  @DisplayName("unchanged fishing XP is reported")
  void fishingXpUnchanged() {
    when(loader.load(session))
        .thenReturn(context(10, 2, 2, 2, 0, false, 5, false, true, true, true, 0, 0));
    PreflightReport report = preflightService.preflight("recording_1");
    assertThat(report.isReady()).isFalse();
    assertThat(report.issues())
        .extracting(PreflightIssue::code)
        .contains(PreflightReport.IssueCode.FISHING_XP_UNCHANGED);
  }
}
