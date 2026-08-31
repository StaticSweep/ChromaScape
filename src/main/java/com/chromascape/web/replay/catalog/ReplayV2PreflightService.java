package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.XrspsPreflightResponse;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver.ResolvedRecording;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.conversion.ReplayActionConversionService;
import com.chromascape.web.replay.catalog.dto.ReplayBlockingReason;
import com.chromascape.web.replay.catalog.dto.ReplayEligibility;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the v2 recording preview without creating a curation, manifest, run, or artifact.
 * A preview can therefore safely be called by the debugger as the capability handshake changes.
 */
@Service
@Transactional(readOnly = true)
public class ReplayV2PreflightService {

  private final ReplayRecordingResolver resolver;
  private final RecordingIntegrityService integrityService;
  private final TickSnapshotRepository snapshotRepository;
  private final ReplayActionConversionService conversionService;
  private final ReplayCapabilityService capabilityService;

  public ReplayV2PreflightService(
      ReplayRecordingResolver resolver,
      RecordingIntegrityService integrityService,
      TickSnapshotRepository snapshotRepository,
      ReplayActionConversionService conversionService,
      ReplayCapabilityService capabilityService) {
    this.resolver = resolver;
    this.integrityService = integrityService;
    this.snapshotRepository = snapshotRepository;
    this.conversionService = conversionService;
    this.capabilityService = capabilityService;
  }

  public XrspsPreflightResponse preflight(String recordingId, ConversionMode requestedMode) {
    ResolvedRecording resolved = resolver.resolve(recordingId);
    ReplayEligibility base = integrityService.evaluate(resolved);
    CapabilityManifest capability = capabilityService.getManifest();
    ConversionMode mode = requestedMode == null ? ConversionMode.AUTO : requestedMode;
    List<ReplayBlockingReason> blockers = new ArrayList<>();
    base.blockingReasons().stream()
        .filter(reason -> reason.mode() != ReplayMode.OBSERVED_PLAYBACK)
        .forEach(blockers::add);

    DebugSession session = resolved.debugSession();
    if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
      return response(
          recordingId,
          null,
          base,
          mode,
          null,
          blockers,
          capability,
          false,
          false);
    }

    var minTick = snapshotRepository.findMinTickBySessionId(session.getSessionId());
    var maxTick = snapshotRepository.findMaxTickBySessionId(session.getSessionId());
    if (minTick.isEmpty() || maxTick.isEmpty()) {
      addBlocker(
          blockers,
          ReplayErrorCode.NO_TICK_SNAPSHOTS,
          "No tick snapshots exist for the debug session",
          "Capture tick snapshots before requesting xRSPS replay");
      return response(
          recordingId,
          session.getSessionId(),
          base,
          mode,
          null,
          blockers,
          capability,
          false,
          false);
    }

    long startTick = minTick.get();
    long endTick = maxTick.get();
    List<TickSnapshot> snapshots =
        snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
            session.getSessionId(), startTick, endTick);
    if (!isContiguous(snapshots, startTick, endTick)) {
      addBlocker(
          blockers,
          ReplayErrorCode.TICK_GAP_DETECTED,
          "Tick snapshots do not cover the complete recording range",
          "Repair or recapture the missing ticks before simulation");
    }
    addInitialStateBlockers(snapshots, blockers);

    ReplayActionConversionResult conversion =
        conversionService.convert(session.getSessionId(), startTick, endTick, mode);
    List<String> unsupported = unsupportedActions(conversion, capability);
    if (!conversion.hasExecutableActions()) {
      addBlocker(
          blockers,
          ReplayErrorCode.ACTION_UNSUPPORTED,
          "The recording contains no executable semantic replay action",
          "Capture typed semantic actions or provide structured legacy action evidence");
    }

    boolean runtimeReady = runtimeReady(capability);
    if (!runtimeReady) {
      addBlocker(
          blockers,
          capabilityService.isSimulationAvailable()
              ? ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE
              : ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          capabilityService.isSimulationAvailable()
              ? "The xRSPS capability has incomplete runtime pins"
              : "The pinned xRSPS worker is not available",
          "Start the pinned worker and verify its capability handshake");
    }
    if (conversion.quality() == ConversionQuality.CERTIFIED && !unsupported.isEmpty()) {
      addBlocker(
          blockers,
          ReplayErrorCode.ACTION_UNSUPPORTED,
          "The pinned xRSPS worker does not support a captured action: " + unsupported.get(0),
          "Use a worker that declares support for every captured semantic action");
    }
    if (conversion.quality() == ConversionQuality.CERTIFIED
        && !capabilityService.isAuthoritativeCertifiable()) {
      addBlocker(
          blockers,
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          "Complete typed capture requires an authoritative certifiable xRSPS worker",
          "Use a worker advertising fidelity=XRSPS_AUTHORITATIVE and certifiable=true");
    }

    boolean initialStateReady = blockers.stream().noneMatch(this::blocksInitialState);
    boolean simulationEligible =
        session.getEndTime() != null
            && conversion.hasExecutableActions()
            && runtimeReady
            && initialStateReady
            && !(conversion.quality() == ConversionQuality.CERTIFIED && !unsupported.isEmpty())
            && !(conversion.quality() == ConversionQuality.CERTIFIED
                && !capabilityService.isAuthoritativeCertifiable());
    boolean certificationEligible =
        simulationEligible
            && conversion.quality() == ConversionQuality.CERTIFIED
            && unsupported.isEmpty()
            && conversion.gaps().isEmpty()
            && conversion.approximations().isEmpty()
            && capabilityService.isAuthoritativeCertifiable();
    return response(
        recordingId,
        session.getSessionId(),
        base,
        mode,
        conversion,
        blockers,
        capability,
        simulationEligible,
        certificationEligible);
  }

  private XrspsPreflightResponse response(
      String recordingId,
      String sessionId,
      ReplayEligibility base,
      ConversionMode mode,
      ReplayActionConversionResult conversion,
      List<ReplayBlockingReason> blockers,
      CapabilityManifest capability,
      boolean simulationEligible,
      boolean certificationEligible) {
    ConversionQuality quality = conversion == null ? ConversionQuality.UNSUPPORTED : conversion.quality();
    int actionCount = conversion == null ? 0 : conversion.actions().size();
    int typedCount = conversion == null ? 0 : conversion.typedActionCount();
    int legacyCount = conversion == null ? 0 : conversion.legacyLogCount();
    double coverage = conversion == null ? 0.0 : conversion.actionCoveragePct();
    var gaps = conversion == null ? List.<com.chromascape.web.replay.conversion.ReplayGap>of() : conversion.gaps();
    var approximations =
        conversion == null
            ? List.<com.chromascape.web.replay.conversion.ReplayApproximation>of()
            : conversion.approximations();
    String state =
        simulationEligible
            ? "READY"
            : quality == ConversionQuality.UNSUPPORTED ? "UNSUPPORTED" : "BLOCKED";
    return new XrspsPreflightResponse(
        XrspsPreflightResponse.SCHEMA,
        recordingId,
        sessionId,
        state,
        base.playbackEligible(),
        base.policyReplayEligible(),
        simulationEligible,
        certificationEligible,
        mode,
        quality,
        coverage,
        actionCount,
        typedCount,
        legacyCount,
        gaps.size(),
        approximations.size(),
        gaps,
        approximations,
        blockers,
        capability);
  }

  private boolean runtimeReady(CapabilityManifest capability) {
    return capability != null
        && capability.modes() != null
        && capability.modes().causalSimulation()
        && capability.xrsps() != null
        && nonblank(capability.xrsps().adapterVersion())
        && nonblank(capability.xrsps().buildCommit())
        && nonblank(capability.xrsps().cacheRevision())
        && nonblank(capability.xrsps().contentRevision());
  }

  private static List<String> unsupportedActions(
      ReplayActionConversionResult conversion, CapabilityManifest capability) {
    Set<String> unsupported = new HashSet<>();
    if (conversion == null || capability == null) return List.of();
    for (ConvertedReplayAction action : conversion.actions()) {
      if (!capability.supportedActions().contains(action.action().getActionType())) {
        unsupported.add(action.action().getActionType());
      }
    }
    return unsupported.stream().sorted().toList();
  }

  private void addInitialStateBlockers(
      List<TickSnapshot> snapshots, List<ReplayBlockingReason> blockers) {
    if (snapshots == null || snapshots.isEmpty()) return;
    TickSnapshot first = snapshots.get(0);
    JsonNode playerState = first.getPlayerState();
    if (playerState == null || !playerState.isObject()) {
      addBlocker(
          blockers,
          ReplayErrorCode.PLAYER_STATE_MISSING,
          "The earliest snapshot has no player state",
          "Capture a complete player state at the recording start");
    } else if (positionNode(playerState) == null) {
      addBlocker(
          blockers,
          ReplayErrorCode.POSITION_MISSING,
          "The earliest snapshot has no player position",
          "Capture a world position at the recording start");
    }
    if (first.getInventory() == null) {
      addBlocker(
          blockers,
          ReplayErrorCode.INVENTORY_SLOT_MISSING,
          "Initial inventory slots are missing",
          "Capture inventory slots at the recording start");
    }
    if (first.getEquipment() == null) {
      addBlocker(
          blockers,
          ReplayErrorCode.EQUIPMENT_SLOT_MISSING,
          "Initial equipment slots are missing",
          "Capture equipment slots at the recording start");
    }
    JsonNode npcs = first.getNpcs();
    if (npcs == null || !npcs.isArray() || npcs.isEmpty()) {
      addBlocker(
          blockers,
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Initial world has no typed NPC list",
          "Capture stable NPC identities and positions at the recording start");
    } else {
      Set<String> ids = new HashSet<>();
      for (JsonNode npc : npcs) {
        String stableId = npc.path("stableId").asText(null);
        if (!npc.isObject()
            || !nonblank(stableId)
            || positionNode(npc) == null
            || !nonblank(npc.path("spotType").asText(null))
            || !ids.add(stableId)) {
          addBlocker(
              blockers,
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "Initial NPC world state lacks a unique stable identity, position, or spot type",
              "Capture complete typed NPC state at the recording start");
          break;
        }
      }
    }
    if (first.getGameObjects() == null || first.getGroundItems() == null) {
      addBlocker(
          blockers,
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Initial object and ground-item state is missing",
          "Capture typed objects and ground items at the recording start");
    }
  }

  private boolean blocksInitialState(ReplayBlockingReason reason) {
    return switch (reason.code()) {
      case PLAYER_STATE_MISSING,
          POSITION_MISSING,
          INVENTORY_SLOT_MISSING,
          EQUIPMENT_SLOT_MISSING,
          SIM_INITIAL_STATE_INCOMPLETE,
          SIM_INITIAL_WORLD_INCOMPLETE,
          TICK_GAP_DETECTED,
          NO_TICK_SNAPSHOTS,
          DEBUG_SESSION_NOT_FINALIZED,
          DEBUG_SESSION_NOT_FOUND -> true;
      default -> false;
    };
  }

  private static void addBlocker(
      List<ReplayBlockingReason> blockers,
      ReplayErrorCode code,
      String message,
      String remediation) {
    boolean present =
        blockers.stream()
            .anyMatch(reason -> reason.mode() == ReplayMode.CAUSAL_SIMULATION && reason.code() == code);
    if (!present) {
      blockers.add(
          new ReplayBlockingReason(
              ReplayMode.CAUSAL_SIMULATION, code, message, remediation));
    }
  }

  private static boolean isContiguous(List<TickSnapshot> snapshots, long startTick, long endTick) {
    if (snapshots == null || endTick < startTick || endTick - startTick >= Integer.MAX_VALUE) {
      return false;
    }
    if (snapshots.size() != endTick - startTick + 1) return false;
    long expected = startTick;
    for (TickSnapshot snapshot : snapshots) {
      if (snapshot == null || snapshot.getTick() == null || snapshot.getTick() != expected++) {
        return false;
      }
    }
    return true;
  }

  private static JsonNode positionNode(JsonNode state) {
    if (state == null || !state.isObject()) return null;
    JsonNode nested = state.path("position");
    if (nested.isObject() && hasCoordinate(nested, "x") && hasCoordinate(nested, "y")) {
      return nested;
    }
    JsonNode worldPoint = state.path("worldPoint");
    if (worldPoint.isObject() && hasCoordinate(worldPoint, "x") && hasCoordinate(worldPoint, "y")) {
      return worldPoint;
    }
    if (hasCoordinate(state, "x") && hasCoordinate(state, "y")) return state;
    if (hasCoordinate(state, "worldX") && hasCoordinate(state, "worldY")) return state;
    return null;
  }

  private static boolean hasCoordinate(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && ((value.isNumber() && value.isIntegralNumber()) || value.isTextual());
  }

  private static boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }
}
