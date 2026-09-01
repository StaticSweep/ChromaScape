package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver.ResolvedRecording;
import com.chromascape.web.replay.catalog.dto.ReplayBlockingReason;
import com.chromascape.web.replay.catalog.dto.ReplayEligibility;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightRequest;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightResponse;
import com.chromascape.web.replay.curation.entity.CuratedAction;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces a full {@link ReplayPreflightResponse} by combining recording integrity checks with
 * curation and capability context.
 */
@Service
@Transactional(readOnly = true)
public class ReplayPreflightService {

  private final ReplayRecordingResolver resolver;
  private final RecordingIntegrityService integrityService;
  private final ReplayCapabilityService capabilityService;
  private final RecordingCurationRepository curationRepository;
  private final CuratedActionRepository curatedActionRepository;
  private final TickSnapshotRepository snapshotRepository;

  public ReplayPreflightService(
      ReplayRecordingResolver resolver,
      RecordingIntegrityService integrityService,
      ReplayCapabilityService capabilityService,
      RecordingCurationRepository curationRepository,
      CuratedActionRepository curatedActionRepository,
      TickSnapshotRepository snapshotRepository) {
    this.resolver = resolver;
    this.integrityService = integrityService;
    this.capabilityService = capabilityService;
    this.curationRepository = curationRepository;
    this.curatedActionRepository = curatedActionRepository;
    this.snapshotRepository = snapshotRepository;
  }

  public ReplayPreflightResponse preflight(String recordingId, ReplayPreflightRequest request) {
    ResolvedRecording resolved = resolver.resolve(recordingId);
    ReplayEligibility eligibility = integrityService.evaluate(resolved);

    boolean simulationEligible = eligibility.simulationEligible();
    // Degrade simulation eligibility unless CAUSAL SIMULATION is CERTIFIABLY available right
    // now: generic handshake availability is not enough — the worker must attest a
    // certifiable build with pinned, well-formed runtime identity.
    if (simulationEligible && !capabilityService.isCausalSimulationCertifiable()) {
      simulationEligible = false;
      eligibility =
          new ReplayEligibility(
              eligibility.playbackEligible(), false, false, eligibility.blockingReasons());
      eligibility
          .blockingReasons()
          .add(
              new com.chromascape.web.replay.catalog.dto.ReplayBlockingReason(
                  ReplayMode.CAUSAL_SIMULATION,
                  ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                  "Certifiable xRSPS causal simulation is not available",
                  "Start the certified xRSPS executable and verify the capability handshake"));
    }

    // POLICY_REPLAY is truthfully unavailable in v1: never offered, never eligible.
    List<com.chromascape.web.replay.catalog.dto.ReplayBlockingReason> blockers =
        new java.util.ArrayList<>(eligibility.blockingReasons());
    boolean policyReplayEligible = false;
    if (request != null && request.requestedMode() == ReplayMode.POLICY_REPLAY) {
      policyReplayEligible = false;
      blockers.add(
          new com.chromascape.web.replay.catalog.dto.ReplayBlockingReason(
              ReplayMode.POLICY_REPLAY,
              ReplayErrorCode.RUN_STATE_INVALID,
              "POLICY_REPLAY requests are not available in v1",
              "Use CAUSAL_SIMULATION mode"));
    }

    // POST preflight with CAUSAL_SIMULATION must validate ITS requested curation: the exact
    // version must exist FOR THIS RECORDING, be PUBLISHED, and belong to the recording AND
    // its linked debug session. Anything else is a causal blocker — preflight never lets an
    // unpublishable or foreign curation look compile-ready.
    if (request != null && request.requestedMode() == ReplayMode.CAUSAL_SIMULATION) {
      List<ReplayBlockingReason> curationBlockers =
          validateRequestedCuration(recordingId, resolved, request.curationVersion());
      blockers.addAll(curationBlockers);
      // Version/state/session alone are NOT enough: the SELECTED curation's actual actions
      // must survive the ONE shared status+evidence pipeline used by the compiler, so a
      // curation can never pass preflight yet fail (or diverge at) compilation.
      if (curationBlockers.isEmpty()) {
        blockers.addAll(validateCurationActions(recordingId, resolved, request.curationVersion()));
      }
    }
    // A GET preflight (request == null) intentionally reports recording-level integrity and
    // available curations WITHOUT naming any specific curation compile-ready.

    ReplayEligibility finalEligibility =
        new ReplayEligibility(
            eligibility.playbackEligible(),
            policyReplayEligible,
            simulationEligible && !hasCausalBlocker(blockers),
            blockers);

    List<RecordingCuration> curations =
        curationRepository.findByRecordingIdOrderByVersionDesc(recordingId);

    RecordingSession recording = resolved.recording();
    DebugSession debugSession = resolved.debugSession();

    return new ReplayPreflightResponse(
        recordingId,
        debugSession != null ? debugSession.getSessionId() : null,
        finalEligibility,
        request != null ? request.requestedMode() : null,
        curations,
        capabilityService.getManifest());
  }

  /** Causal blockers for a requested curation version; empty means it may proceed. */
  private List<com.chromascape.web.replay.catalog.dto.ReplayBlockingReason>
      validateRequestedCuration(
          String recordingId, ResolvedRecording resolved, Integer curationVersion) {
    List<com.chromascape.web.replay.catalog.dto.ReplayBlockingReason> blockers =
        new java.util.ArrayList<>();
    if (curationVersion == null) {
      blockers.add(
          new com.chromascape.web.replay.catalog.dto.ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION,
              ReplayErrorCode.VALIDATION_ERROR,
              "CAUSAL_SIMULATION preflight requires curationVersion",
              "Supply the exact published curation version to compile"));
      return blockers;
    }
    RecordingCuration curation =
        curationRepository.findByRecordingIdAndVersion(recordingId, curationVersion).orElse(null);
    if (curation == null) {
      blockers.add(
          new com.chromascape.web.replay.catalog.dto.ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION,
              ReplayErrorCode.CURATION_NOT_PUBLISHED,
              "No curation version " + curationVersion + " exists for recording " + recordingId,
              "Choose an existing version for THIS recording"));
      return blockers;
    }
    if (!curation.isPublished()) {
      blockers.add(
          new com.chromascape.web.replay.catalog.dto.ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION,
              ReplayErrorCode.CURATION_NOT_PUBLISHED,
              "Curation version " + curationVersion + " is not PUBLISHED",
              "Publish the curation before preflight"));
    }
    DebugSession debugSession = resolved.debugSession();
    if (debugSession == null || !debugSession.getSessionId().equals(curation.getDebugSessionId())) {
      blockers.add(
          new com.chromascape.web.replay.catalog.dto.ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION,
              ReplayErrorCode.CURATION_IMMUTABLE,
              "Curation version "
                  + curationVersion
                  + " was authored against a different debug session",
              "Curate against the recording's own debug session"));
    }
    return blockers;
  }

  /**
   * Runs the SELECTED curation's actual curated actions through the ONE shared validator pipeline
   * (status policy + per-tick evidence), converting every violation into a causal blocker. Mirrors
   * the compiler exactly: same rows, same ordering, same snapshots.
   */
  private List<ReplayBlockingReason> validateCurationActions(
      String recordingId, ResolvedRecording resolved, Integer curationVersion) {
    List<ReplayBlockingReason> blockers = new ArrayList<>();
    RecordingCuration curation =
        curationRepository.findByRecordingIdAndVersion(recordingId, curationVersion).orElse(null);
    DebugSession debugSession = resolved.debugSession();
    if (curation == null || debugSession == null) {
      return blockers; // already reported by validateRequestedCuration
    }

    // The COMPLETE snapshot range for the session, indexed by tick — the same boundary set
    // the compiler validates against.
    Long startTick =
        snapshotRepository.findMinTickBySessionId(debugSession.getSessionId()).orElse(null);
    Long endTick =
        snapshotRepository.findMaxTickBySessionId(debugSession.getSessionId()).orElse(null);
    if (startTick == null || endTick == null) {
      blockers.add(
          new ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION,
              ReplayErrorCode.NO_TICK_SNAPSHOTS,
              "No tick snapshots exist for debug session " + debugSession.getSessionId(),
              "Capture tick data before curating"));
      return blockers;
    }
    List<TickSnapshot> snapshots =
        snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
            debugSession.getSessionId(), startTick, endTick);
    Map<Long, TickSnapshot> snapshotsByTick = new HashMap<>();
    for (TickSnapshot snap : snapshots) {
      snapshotsByTick.putIfAbsent(snap.getTick(), snap);
    }

    // All curated actions in (tick, sequence) order with the SAME defensive re-sort as the
    // compiler, so both components interpret identical rows in identical order.
    List<CuratedAction> curatedActions =
        new ArrayList<>(
            curatedActionRepository.findByCurationIdOrderByTickAscSequenceAsc(curation.getId()));
    curatedActions.sort(
        Comparator.comparingLong(CuratedAction::getTick)
            .thenComparingInt(CuratedAction::getSequence));
    List<ReplayEvidenceValidator.CuratedActionRow> rows = new ArrayList<>();
    for (CuratedAction ca : curatedActions) {
      rows.add(
          new ReplayEvidenceValidator.CuratedActionRow(
              ca.getTick(),
              ca.getSequence(),
              ca.getStatus(),
              ca.getSemanticActionType(),
              ca.getTargetStableId(),
              ca.getArgumentsJson()));
    }

    for (ReplayEvidenceValidator.Violation violation :
        ReplayEvidenceValidator.validateRecordingEvidence(
            startTick, endTick, snapshots.size(), snapshots)) {
      blockers.add(
          new ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION,
              violation.code(),
              violation.message(),
              violation.remediation()));
    }
    for (ReplayEvidenceValidator.Violation violation :
        ReplayEvidenceValidator.validateCurationActions(rows, snapshotsByTick)) {
      blockers.add(
          new ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION,
              violation.code(),
              violation.message(),
              violation.remediation()));
    }
    // The curation validator proves that an action is valid for the replay contract; the live
    // worker capability must independently declare that it can execute every surviving action.
    var capability = capabilityService.getManifest();
    List<String> supportedActions =
        capability != null && capability.supportedActions() != null
            ? capability.supportedActions()
            : List.of();
    for (ReplayEvidenceValidator.CuratedActionRow row : rows) {
      if (!ReplayEvidenceValidator.STATUS_ACCEPTED_LABEL.equals(row.status())) {
        continue;
      }
      if (row.semanticActionType() == null
          || !supportedActions.contains(row.semanticActionType())) {
        blockers.add(
            new ReplayBlockingReason(
                ReplayMode.CAUSAL_SIMULATION,
                ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
                "Curation action "
                    + row.semanticActionType()
                    + " is not declared by the live xRSPS worker",
                "Use a worker capability manifest that includes every curated action"));
      }
    }
    return blockers;
  }

  private static boolean hasCausalBlocker(
      List<com.chromascape.web.replay.catalog.dto.ReplayBlockingReason> blockers) {
    return blockers.stream().anyMatch(b -> b.mode() == ReplayMode.CAUSAL_SIMULATION);
  }
}
