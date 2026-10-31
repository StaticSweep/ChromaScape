package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver.ResolvedRecording;
import com.chromascape.web.replay.catalog.dto.ReplayBlockingReason;
import com.chromascape.web.replay.catalog.dto.ReplayEligibility;
import com.chromascape.web.replay.error.ReplayErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Evaluates the integrity and eligibility of a recording for each replay mode, returning {@link
 * ReplayEligibility} with stable blocking reason codes.
 */
@Service
public class RecordingIntegrityService {

  private final TickSnapshotRepository tickSnapshotRepository;

  public RecordingIntegrityService(TickSnapshotRepository tickSnapshotRepository) {
    this.tickSnapshotRepository = tickSnapshotRepository;
  }

  public ReplayEligibility evaluate(ResolvedRecording resolved) {
    return evaluate(resolved, false);
  }

  public ReplayEligibility evaluateCatalog(ResolvedRecording resolved) {
    return evaluate(resolved, true);
  }

  private ReplayEligibility evaluate(ResolvedRecording resolved, boolean metadataOnly) {
    boolean playback = checkPlayback(resolved);
    // POLICY_REPLAY is unavailable in v1 — decided HERE, not overwritten later by preflight.
    boolean policyReplay = false;
    // Simulation blockers are computed ONCE and directly gate simulationEligibility so the
    // service's own verdict can never contradict the blockers it reports.
    List<ReplayBlockingReason> simulationBlockers = buildSimulationBlockers(resolved, metadataOnly);

    List<ReplayBlockingReason> blockingReasons = new ArrayList<>();

    if (!playback) {
      blockingReasons.addAll(buildPlaybackBlockers(resolved));
    }
    if (!policyReplay) {
      blockingReasons.add(
          new ReplayBlockingReason(
              ReplayMode.POLICY_REPLAY,
              ReplayErrorCode.RUN_STATE_INVALID,
              "POLICY_REPLAY is not available in v1",
              "Use CAUSAL_SIMULATION mode"));
    }
    boolean simulation = checkSimulation(resolved) && simulationBlockers.isEmpty();
    if (!simulation) {
      blockingReasons.addAll(simulationBlockers);
    }

    return new ReplayEligibility(playback, policyReplay, simulation, blockingReasons);
  }

  private boolean checkPlayback(ResolvedRecording resolved) {
    if (!resolved.hasDebugSession()) return false;
    String sessionId = resolved.debugSession().getSessionId();
    return tickSnapshotRepository.countBySessionId(sessionId) > 0;
  }

  private boolean checkPolicyReplay(ResolvedRecording resolved) {
    if (!resolved.hasDebugSession()) return false;
    DebugSession ds = resolved.debugSession();
    if (ds.getEndTime() == null) return false; // not finalized
    String sessionId = ds.getSessionId();
    long count = tickSnapshotRepository.countBySessionId(sessionId);
    return count > 0;
  }

  private boolean checkSimulation(ResolvedRecording resolved) {
    // Must meet policy-replay requirements first, plus additional checks
    if (!checkPolicyReplay(resolved)) return false;
    // Further simulation checks are handled by the preflight service with curation context
    return true;
  }

  private List<ReplayBlockingReason> buildPlaybackBlockers(ResolvedRecording resolved) {
    List<ReplayBlockingReason> reasons = new ArrayList<>();
    if (!resolved.hasDebugSession()) {
      reasons.add(
          new ReplayBlockingReason(
              ReplayMode.OBSERVED_PLAYBACK,
              ReplayErrorCode.DEBUG_SESSION_NOT_FOUND,
              "No debug session linked to this recording",
              "Record a debug session for this recording"));
    } else {
      String sessionId = resolved.debugSession().getSessionId();
      if (tickSnapshotRepository.countBySessionId(sessionId) == 0) {
        reasons.add(
            new ReplayBlockingReason(
                ReplayMode.OBSERVED_PLAYBACK,
                ReplayErrorCode.NO_TICK_SNAPSHOTS,
                "No tick snapshots found for the debug session",
                "Capture tick data during the recording"));
      }
    }
    return reasons;
  }

  private List<ReplayBlockingReason> buildPolicyReplayBlockers(ResolvedRecording resolved) {
    List<ReplayBlockingReason> reasons = new ArrayList<>();
    if (!resolved.hasDebugSession()) {
      reasons.add(
          new ReplayBlockingReason(
              ReplayMode.POLICY_REPLAY,
              ReplayErrorCode.DEBUG_SESSION_NOT_FOUND,
              "No debug session linked to this recording",
              "Record a debug session for this recording"));
      return reasons;
    }
    DebugSession ds = resolved.debugSession();
    if (ds.getEndTime() == null) {
      reasons.add(
          new ReplayBlockingReason(
              ReplayMode.POLICY_REPLAY,
              ReplayErrorCode.DEBUG_SESSION_NOT_FINALIZED,
              "Debug session has not been finalized",
              "Finalize the debug session first"));
    }
    String sessionId = ds.getSessionId();
    if (tickSnapshotRepository.countBySessionId(sessionId) == 0) {
      reasons.add(
          new ReplayBlockingReason(
              ReplayMode.POLICY_REPLAY,
              ReplayErrorCode.NO_TICK_SNAPSHOTS,
              "No tick snapshots found",
              "Capture tick data during the recording"));
    }
    return reasons;
  }

  private List<ReplayBlockingReason> buildSimulationBlockers(
      ResolvedRecording resolved, boolean metadataOnly) {
    List<ReplayBlockingReason> reasons = new ArrayList<>();
    // Inherits all policy-replay blockers
    reasons.addAll(buildPolicyReplayBlockers(resolved));
    if (!reasons.isEmpty()) return reasons;

    // ── simulation fidelity checks over committed recorded evidence ──
    DebugSession ds = resolved.debugSession();
    String sessionId = ds.getSessionId();
    Long minTick = tickSnapshotRepository.findMinTickBySessionId(sessionId).orElse(null);
    Long maxTick = tickSnapshotRepository.findMaxTickBySessionId(sessionId).orElse(null);
    if (minTick == null || maxTick == null) {
      return reasons;
    }
    List<com.chromascape.web.debug.tickdebugger.entity.TickSnapshot> snapshots;
    if (metadataOnly) {
      snapshots = new ArrayList<>();
      for (var metadata : tickSnapshotRepository.findEvidenceMetadataBySessionId(sessionId)) {
        var snapshot = new com.chromascape.web.debug.tickdebugger.entity.TickSnapshot();
        snapshot.setTick(metadata.getTick());
        snapshot.setSchemaVersion(metadata.getSchemaVersion());
        snapshots.add(snapshot);
      }
      if (!snapshots.isEmpty()) {
        tickSnapshotRepository
            .findFirstBySessionIdOrderByTickAsc(sessionId)
            .ifPresent(first -> snapshots.set(0, first));
      }
    } else {
      snapshots =
          tickSnapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
              sessionId, minTick, maxTick);
    }

    // ONE shared interpretation of evidence validity (preflight AND compiler): tick
    // continuity, explicit supported schema versions on EVERY snapshot (null is never
    // accepted), and complete initial-tick world evidence.
    for (var violation :
        ReplayEvidenceValidator.validateRecordingEvidence(
            minTick, maxTick, tickSnapshotRepository.countBySessionId(sessionId), snapshots)) {
      reasons.add(
          new ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION,
              violation.code(),
              violation.message(),
              violation.remediation()));
    }
    return reasons;
  }
}
