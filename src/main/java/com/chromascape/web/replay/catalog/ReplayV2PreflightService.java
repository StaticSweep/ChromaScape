package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.XrspsPreflightResponse;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver.ResolvedRecording;
import com.chromascape.web.replay.catalog.dto.ReplayBlockingReason;
import com.chromascape.web.replay.catalog.dto.ReplayEligibility;
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.conversion.ReplayActionConversionService;
import com.chromascape.web.replay.conversion.ReplayGap;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the v2 recording preview without creating a curation, manifest, run, or artifact. A
 * preview can therefore safely be called by the debugger as the capability handshake changes.
 */
@Service
@Transactional(readOnly = true)
public class ReplayV2PreflightService {

  private static final Set<String> SUPPORTED_FISHING_OPTIONS =
      Set.of("net", "bait", "lure", "cage", "harpoon");

  private final ReplayRecordingResolver resolver;
  private final RecordingIntegrityService integrityService;
  private final TickSnapshotRepository snapshotRepository;
  private final ReplayActionConversionService conversionService;
  private final ReplayCapabilityService capabilityService;
  private final ScenarioManifestV2Compiler compiler;

  public ReplayV2PreflightService(
      ReplayRecordingResolver resolver,
      RecordingIntegrityService integrityService,
      TickSnapshotRepository snapshotRepository,
      ReplayActionConversionService conversionService,
      ReplayCapabilityService capabilityService,
      ScenarioManifestV2Compiler compiler) {
    this.resolver = resolver;
    this.integrityService = integrityService;
    this.snapshotRepository = snapshotRepository;
    this.conversionService = conversionService;
    this.capabilityService = capabilityService;
    this.compiler = compiler;
  }

  /** Owner-scoped request preview: the exact same selected-input compiler as execution. */
  public XrspsPreflightResponse preflight(
      String recordingId, ConversionMode requestedMode, UUID curationId, String ownerId) {
    if (ownerId == null || ownerId.isBlank()) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          org.springframework.http.HttpStatus.UNAUTHORIZED,
          false,
          "Authentication is required",
          "Sign in before previewing a selected curation");
    }
    ConversionMode mode = requestedMode == null ? ConversionMode.AUTO : requestedMode;
    List<ReplayBlockingReason> blockers = new ArrayList<>();
    try {
      var compiled = compiler.preview(recordingId, mode, curationId, ownerId);
      ResolvedRecording resolved = resolver.resolve(recordingId);
      ReplayEligibility base = integrityService.evaluate(resolved);
      base.blockingReasons().stream()
          .filter(reason -> reason.mode() != ReplayMode.CAUSAL_SIMULATION)
          .forEach(blockers::add);
      var conversion = compiled.conversion();
      var manifest = compiled.manifest();
      List<ReplayGap> gaps = new ArrayList<>();
      for (JsonNode gap : manifest.getManifestJson().path("conversion").path("gaps")) {
        String message = gap.path("message").asText();
        if (gap.has("sourceRef")) message += "; source=" + gap.path("sourceRef").asText();
        ActionProvenance provenance =
            switch (gap.path("provenance").asText()) {
              case "OBSERVED", "CAPTURED" -> ActionProvenance.CAPTURED;
              case "OPERATOR_SUPPLIED" -> ActionProvenance.OPERATOR_SUPPLIED;
              case "DEFAULTED" -> ActionProvenance.DEFAULTED;
              default -> ActionProvenance.INFERRED;
            };
        gaps.add(
            new ReplayGap(
                gap.path("tick").asLong(manifest.getStartTick()),
                gap.path("code").asText(),
                message,
                provenance,
                gap.has("sourceLogId") ? gap.path("sourceLogId").asLong() : null));
      }
      boolean eligible;
      try {
        compiled.requireSimulationAllowed();
        eligible = true;
      } catch (ReplayException blocked) {
        eligible = false;
        addBlocker(
            blockers,
            blocked.getErrorCode(),
            blocked.getMessage(),
            "Resolve the selected snapshot's source-linked gaps before execution");
      }
      // Report the complete compiled gap list, including source/native audit findings, rather
      // than presenting captured-row coverage as a completeness verdict.
      var audited =
          new ReplayActionConversionResult(
              conversion.sessionId(),
              conversion.startTick(),
              conversion.endTick(),
              conversion.mode(),
              conversion.quality(),
              conversion.actions(),
              gaps,
              conversion.approximations(),
              conversion.typedActionCount(),
              conversion.legacyLogCount(),
              conversion.actionCoveragePct(),
              conversion.conversionChecksum());
      boolean certified =
          eligible
              && conversion.quality() == ConversionQuality.CERTIFIED
              && gaps.isEmpty()
              && conversion.approximations().isEmpty()
              && compiled.capability().certifiable()
              && compiled.capability().fidelity()
                  == CapabilityManifest.Fidelity.XRSPS_AUTHORITATIVE;
      JsonNode sourceId =
          manifest.getManifestJson().path("curation").path("sourceCuration").path("id");
      var input =
          new XrspsPreflightResponse.CompiledInput(
              manifest.getId(),
              manifest.getManifestChecksum(),
              sourceId.isTextual() ? UUID.fromString(sourceId.asText()) : null);
      return new XrspsPreflightResponse(
          response(
              recordingId,
              manifest.getDebugSessionId(),
              base,
              mode,
              audited,
              blockers,
              compiled.capability(),
              eligible,
              certified),
          input);
    } catch (ReplayException rejected) {
      // Keep missing/foreign/private selections indistinguishable at the ownership boundary.
      if (rejected.getHttpStatus().value() == 404 || rejected.getHttpStatus().value() == 401)
        throw rejected;
      ResolvedRecording resolved = resolver.resolve(recordingId);
      ReplayEligibility base = integrityService.evaluate(resolved);
      blockers.clear();
      base.blockingReasons().stream()
          .filter(reason -> reason.mode() != ReplayMode.CAUSAL_SIMULATION)
          .forEach(blockers::add);
      addBlocker(
          blockers,
          rejected.getErrorCode(),
          rejected.getMessage(),
          "Resolve the selected compilation input; preview never publishes partial records");
      return new XrspsPreflightResponse(
          XrspsPreflightResponse.SCHEMA,
          recordingId,
          resolved.debugSession() == null ? null : resolved.debugSession().getSessionId(),
          rejected.getErrorCode() == ReplayErrorCode.ACTION_UNSUPPORTED ? "UNSUPPORTED" : "BLOCKED",
          base.playbackEligible(),
          base.policyReplayEligible(),
          false,
          false,
          mode,
          ConversionQuality.UNSUPPORTED,
          0,
          0,
          0,
          0,
          0,
          0,
          List.of(),
          List.of(),
          blockers,
          capabilityService.getManifest(),
          null);
    }
  }

  /**
   * Raw-source inspection retained for diagnostics; execution requests use the selected overload.
   */
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
      return response(recordingId, null, base, mode, null, blockers, capability, false, false);
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
    ReplayActionConversionResult conversion =
        conversionService.convert(session.getSessionId(), startTick, endTick, mode);
    var missingIdentity =
        com.chromascape.web.replay.manifest.ScenarioCompilationEvidence.missingIdentityFields(
            resolved.recording() == null ? null : resolved.recording().getRunContext(),
            recordingId,
            session.getSessionId());
    if (!missingIdentity.isEmpty()) {
      addBlocker(
          blockers,
          ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE,
          "SOURCE_IDENTITY_INCOMPLETE: "
              + String.join(", ", missingIdentity)
              + "; source=recording:"
              + recordingId
              + "/runContext",
          "Use a recording with the original frozen BehaviorRunContext; do not invent missing"
              + " pins");
    }
    if (!snapshots.isEmpty()
        && conversion != null
        && capability != null
        && capability.xrsps() != null) {
      try {
        var world =
            new com.chromascape.web.replay.manifest.ScenarioCompilationEvidence(
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    new com.chromascape.web.replay.projection.CanonicalProjectionService())
                .world(snapshots, conversion, capability);
        if (!world.path("requiredNativeBindings").isEmpty()) {
          var target = world.path("requiredNativeBindings").get(0);
          addBlocker(
              blockers,
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "NATIVE_WORLD_SUPPORT_REQUIRED: "
                  + target.path("stableId").asText()
                  + "; source="
                  + target.path("sourceRef").asText(),
              "Verify pinned native content and deterministic spawns; do not copy later state into"
                  + " the initial world");
        }
      } catch (com.chromascape.web.replay.error.ReplayException issue) {
        addBlocker(
            blockers,
            issue.getErrorCode(),
            issue.getMessage(),
            "Resolve the target from evidence at its execution tick");
      }
    }
    addInitialStateBlockers(snapshots, conversion, blockers);
    boolean authoritativeNpcPins =
        conversion != null && conversion.quality() == ConversionQuality.CERTIFIED;
    for (ReplayNpcPinValidator.Violation violation :
        ReplayNpcPinValidator.validate(snapshots, conversion, authoritativeNpcPins)) {
      addBlocker(blockers, violation.code(), violation.message(), violation.remediation());
    }
    List<String> unsupported = unsupportedActions(conversion, capability);
    if (!conversion.gaps().isEmpty() || !unsupported.isEmpty()) {
      addBlocker(
          blockers,
          ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE,
          "Captured-action coverage is not completeness: the scenario has capture gaps or"
              + " unsupported actions",
          "Resolve every source-linked gap before causal simulation; playback remains independent");
    }
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
    ConversionQuality quality =
        conversion == null ? ConversionQuality.UNSUPPORTED : conversion.quality();
    int actionCount = conversion == null ? 0 : conversion.actions().size();
    int typedCount = conversion == null ? 0 : conversion.typedActionCount();
    int legacyCount = conversion == null ? 0 : conversion.legacyLogCount();
    double coverage = conversion == null ? 0.0 : conversion.actionCoveragePct();
    var gaps =
        conversion == null
            ? List.<com.chromascape.web.replay.conversion.ReplayGap>of()
            : conversion.gaps();
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
        capability,
        null);
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
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion,
      List<ReplayBlockingReason> blockers) {
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
    if (npcs == null || !npcs.isArray()) {
      addBlocker(
          blockers,
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Initial world has no typed NPC list",
          "Capture stable NPC identities and positions at the recording start");
    } else {
      Set<String> referencedNpcIds = new HashSet<>();
      Map<String, Set<String>> spotTypeCandidates = new HashMap<>();
      Map<String, List<JsonNode>> npcsByStableId = new HashMap<>();
      for (JsonNode npc : npcs) {
        String stableId = npc == null ? null : npc.path("stableId").asText(null);
        if (npc == null || !npc.isObject() || !nonblank(stableId) || positionNode(npc) == null) {
          addBlocker(
              blockers,
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "Initial NPC world state lacks a stable identity or position",
              "Capture stable NPC identities and positions at the recording start");
          continue;
        }
        npcsByStableId.computeIfAbsent(stableId, ignored -> new ArrayList<>()).add(npc);
      }
      if (conversion != null && conversion.actions() != null) {
        for (ConvertedReplayAction converted : conversion.actions()) {
          if (converted == null
              || converted.action() == null
              || (!"INTERACT_NPC".equals(converted.action().getActionType())
                  && !"USE_ITEM_ON_NPC".equals(converted.action().getActionType()))) {
            continue;
          }
          String stableId = textValue(converted.action().getTarget(), "stableId");
          if (!nonblank(stableId)) {
            addBlocker(
                blockers,
                ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                "INTERACT_NPC action has no stable target id",
                "Capture a stable NPC identity at the semantic dispatch boundary");
            continue;
          }
          if (converted.action().getTick() > first.getTick()
              && !npcsByStableId.containsKey(stableId)) {
            // The execution-tick audit resolves later targets. Absence initially is not itself
            // a capture failure; native loading remains a distinct, explicit blocker.
            continue;
          }
          referencedNpcIds.add(stableId);
          List<JsonNode> matches = npcsByStableId.getOrDefault(stableId, List.of());
          if (matches.isEmpty()) {
            addBlocker(
                blockers,
                ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                "Referenced NPC target " + stableId + " is absent from the initial world",
                "Capture the referenced NPC at the recording start");
            continue;
          }
          if (matches.size() > 1) {
            addBlocker(
                blockers,
                ReplayErrorCode.ACTION_AMBIGUOUS,
                "Referenced NPC target " + stableId + " is duplicated in the initial world",
                "Capture each stable NPC identity exactly once");
            continue;
          }
          JsonNode target = matches.get(0);
          String option = normalizedText(converted.action().getArguments(), "option");
          if ("USE_ITEM_ON_NPC".equals(converted.action().getActionType())) {
            // The legacy action has no menu option; its item/tool contract is validated by the
            // conversion and worker boundary. It still requires a uniquely resolved NPC.
            continue;
          }
          if (option == null) {
            addBlocker(
                blockers,
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "INTERACT_NPC action for " + stableId + " has no selected option",
                "Capture the server-facing NPC menu option");
            continue;
          }
          String recordedSpotType = normalizedText(target, "spotType");
          boolean fishingAction =
              isSupportedFishingOption(option)
                  && (recordedSpotType == null || isSupportedFishingOption(recordedSpotType));
          if (fishingAction) {
            spotTypeCandidates.computeIfAbsent(stableId, ignored -> new HashSet<>()).add(option);
            if (isSupportedFishingOption(recordedSpotType) && !recordedSpotType.equals(option)) {
              addBlocker(
                  blockers,
                  ReplayErrorCode.ACTION_UNSUPPORTED,
                  "NPC target "
                      + stableId
                      + " records spotType "
                      + recordedSpotType
                      + " but the action selects "
                      + option,
                  "Align the action option with the recorded NPC spot type");
            }
          } else if (!hasNpcOption(target, option)) {
            addBlocker(
                blockers,
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "Generic NPC " + stableId + " does not expose recorded option '" + option + "'",
                "Capture the NPC's server menu options at the dispatch tick");
          }
        }
      }

      for (String stableId : referencedNpcIds) {
        List<JsonNode> matches = npcsByStableId.getOrDefault(stableId, List.of());
        if (matches.isEmpty()) {
          addBlocker(
              blockers,
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              "Referenced NPC target " + stableId + " is absent from the initial world",
              "Capture the referenced NPC at the recording start");
        } else if (matches.size() > 1) {
          addBlocker(
              blockers,
              ReplayErrorCode.ACTION_AMBIGUOUS,
              "Referenced NPC target " + stableId + " is duplicated in the initial world",
              "Capture each stable NPC identity exactly once");
        }
        if (!spotTypeCandidates.getOrDefault(stableId, Set.of()).isEmpty()
            && spotTypeCandidates.getOrDefault(stableId, Set.of()).size() != 1) {
          addBlocker(
              blockers,
              ReplayErrorCode.ACTION_AMBIGUOUS,
              "Referenced NPC target " + stableId + " has no single supported fishing option",
              "Capture one unambiguous semantic option for the target");
        }
      }

      Set<String> retainedIds = new HashSet<>();
      for (JsonNode npc : npcs) {
        String stableId = npc == null ? null : npc.path("stableId").asText(null);
        if (!nonblank(stableId) || positionNode(npc) == null) continue;
        boolean referenced = referencedNpcIds.contains(stableId);
        String recordedSpotType = npc.path("spotType").asText(null);
        if (!referenced && !isSupportedFishingOption(recordedSpotType)) {
          // Nearby non-fishing NPCs are valid capture context, but the fishing kernel must not
          // be forced to invent a spot type for an NPC no action ever targets.
          continue;
        }
        if (referenced) {
          if (nonblank(recordedSpotType)
              && !isSupportedFishingOption(recordedSpotType)
              && spotTypeCandidates.containsKey(stableId)) {
            addBlocker(
                blockers,
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "Referenced NPC " + stableId + " has unsupported spotType " + recordedSpotType,
                "Capture a supported fishing spot type for the referenced NPC");
          }
          String inferred =
              spotTypeCandidates.getOrDefault(stableId, Set.of()).stream().findFirst().orElse(null);
          if (isSupportedFishingOption(recordedSpotType)
              && inferred != null
              && !recordedSpotType.equals(inferred)) {
            addBlocker(
                blockers,
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "NPC target "
                    + stableId
                    + " records spotType "
                    + recordedSpotType
                    + " but the action selects "
                    + inferred,
                "Align the action option with the recorded NPC spot type");
          }
        }
        if (isSupportedFishingOption(recordedSpotType)
            || (referenced && !spotTypeCandidates.getOrDefault(stableId, Set.of()).isEmpty())
            || (referenced && !spotTypeCandidates.containsKey(stableId))) {
          if (!retainedIds.add(stableId)) {
            addBlocker(
                blockers,
                ReplayErrorCode.ACTION_AMBIGUOUS,
                "Initial NPC target " + stableId + " is not unique",
                "Capture each stable NPC identity exactly once");
          }
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
          ACTION_TARGET_UNRESOLVED,
          ACTION_AMBIGUOUS,
          ACTION_UNSUPPORTED,
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
            .anyMatch(
                reason -> reason.mode() == ReplayMode.CAUSAL_SIMULATION && reason.code() == code);
    if (!present) {
      blockers.add(
          new ReplayBlockingReason(ReplayMode.CAUSAL_SIMULATION, code, message, remediation));
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

  private static boolean isSupportedFishingOption(String value) {
    return value != null && SUPPORTED_FISHING_OPTIONS.contains(value.trim().toLowerCase());
  }

  private static String textValue(Map<String, Object> values, String key) {
    if (values == null) return null;
    Object value = values.get(key);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  private static String normalizedText(Map<String, Object> values, String key) {
    String value = textValue(values, key);
    return value == null ? null : value.trim().toLowerCase();
  }

  private static String normalizedText(JsonNode node, String key) {
    if (node == null || !node.has(key) || !node.get(key).isTextual()) return null;
    String value = node.get(key).asText().trim().toLowerCase();
    return value.isBlank() ? null : value;
  }

  private static boolean hasNpcOption(JsonNode npc, String option) {
    if (npc == null || option == null) return false;
    JsonNode options = npc.has("actions") ? npc.get("actions") : npc.get("options");
    if (options == null || !options.isArray()) return false;
    String expected = option.trim().toLowerCase();
    for (JsonNode candidate : options) {
      if (candidate.isTextual() && expected.equals(candidate.asText().trim().toLowerCase())) {
        return true;
      }
    }
    return false;
  }
}
