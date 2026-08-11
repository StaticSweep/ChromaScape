package com.chromascape.web.aiplayer.prototype;

import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.RecordingContext;
import com.chromascape.web.aiplayer.prototype.PreflightReport.PreflightIssue;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Validates a recording against the prototype-one acceptance contract.
 *
 * <p>Preflight is the single gate used by the preflight endpoint, training, and replay runs. The
 * prototype contract fixes {@code requiredCatchCount = 5}.
 */
@Service
public class FishingPreflightService {

  public static final int REQUIRED_CATCH_COUNT = 5;

  private final DebugSessionRepository sessionRepository;
  private final FishingRecordingLoader loader;

  public FishingPreflightService(
      DebugSessionRepository sessionRepository, FishingRecordingLoader loader) {
    this.sessionRepository = sessionRepository;
    this.loader = loader;
  }

  /**
   * Runs preflight for a recording.
   *
   * @param recordingId the recording ID
   * @return the preflight report (READY or NOT_READY)
   */
  public PreflightReport preflight(String recordingId) {
    DebugSession session = resolveRecording(recordingId);
    if (!isFinalized(session)) {
      return PreflightReport.notReady(
          recordingId,
          session.getSessionId(),
          List.of(
              new PreflightIssue(
                  PreflightReport.IssueCode.SESSION_NOT_FINALIZED,
                  "Recording must be finalized before use")));
    }

    RecordingContext ctx = loader.load(session);
    List<PreflightIssue> issues = validate(ctx);

    boolean ready = issues.isEmpty();
    return new PreflightReport(
        ready ? "READY" : "NOT_READY",
        recordingId,
        session.getSessionId(),
        true,
        ctx.snapshots().size(),
        ctx.playerActions().size(),
        ctx.supportedActions().size(),
        ctx.alignedActions().size(),
        ctx.unsupportedActions().size(),
        ctx.overflowDetected() ? 1 : 0,
        ctx.catchDelta(),
        ctx.fishingXpIncreased(),
        issues);
  }

  /**
   * Resolves a recording to its debug session, throwing a structured 404 when not found.
   */
  public DebugSession resolveRecording(String recordingId) {
    if (recordingId == null || recordingId.isBlank()) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST, "MISSING_RECORDING_ID", "recordingId is required");
    }
    return sessionRepository
        .findByRecordingId(recordingId)
        .orElseThrow(
            () ->
                new PrototypeApiException(
                    HttpStatus.NOT_FOUND,
                    PreflightReport.IssueCode.RECORDING_NOT_FOUND,
                    "No finalized debug session found for recordingId " + recordingId,
                    Map.of("recordingId", recordingId)));
  }

  private boolean isFinalized(DebugSession session) {
    return session.getEndTick() != null
        && session.getLifecycleStateOrDefault() == DebugSessionLifecycleState.FINALIZED;
  }

  private List<PreflightIssue> validate(RecordingContext ctx) {
    List<PreflightIssue> issues = new ArrayList<>();
    if (ctx.snapshots().isEmpty()) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.NO_TICK_SNAPSHOTS, "Recording has no tick snapshots"));
    }
    if (!ctx.playerStatePresent() || !ctx.inventoryPresent() || !ctx.npcTelemetryPresent()) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.NPC_TELEMETRY_MISSING,
              "Player-state, inventory, or NPC telemetry is missing"));
    }
    if (ctx.playerActions().isEmpty()) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.NO_PLAYER_ACTIONS, "Recording has no captured player actions"));
    }
    if (ctx.supportedActions().isEmpty()) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.NO_FISHING_ACTIONS,
              "Recording has no supported fishing actions"));
    }
    if (!ctx.allActionsAligned()) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.ACTION_WITHOUT_SNAPSHOT,
              "A fishing action could not be aligned to a snapshot (no exact or previous tick)"));
    }
    if (ctx.overflowDetected()) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.ACTION_QUEUE_OVERFLOW,
              "Action queue overflow was recorded; recording is incomplete"));
    }
    if (!ctx.unsupportedActions().isEmpty()) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.UNSUPPORTED_ACTION_PRESENT,
              "Unsupported gameplay actions occurred during the recording"));
    }
    if (ctx.catchDelta() != REQUIRED_CATCH_COUNT) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.CATCH_COUNT_MISMATCH,
              "Recording must show exactly "
                  + REQUIRED_CATCH_COUNT
                  + " positive inventory additions (observed "
                  + ctx.catchDelta()
                  + ")"));
    }
    if (!ctx.fishingXpIncreased()) {
      issues.add(
          new PreflightIssue(
              PreflightReport.IssueCode.FISHING_XP_UNCHANGED,
              "Fishing XP did not increase during the recording"));
    }
    return issues;
  }
}
