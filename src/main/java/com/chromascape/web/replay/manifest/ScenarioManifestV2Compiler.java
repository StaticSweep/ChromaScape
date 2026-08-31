package com.chromascape.web.replay.manifest;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.conversion.ReplayActionConversionService;
import com.chromascape.web.replay.conversion.ReplayApproximation;
import com.chromascape.web.replay.conversion.ReplayGap;
import com.chromascape.web.replay.curation.entity.CuratedAction;
import com.chromascape.web.replay.curation.entity.CurationRole;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compiles the one-click recording path into the immutable ScenarioManifest/v2 contract.
 *
 * <p>This compiler deliberately does not replace the v1 curation compiler. v1 remains available
 * for existing published curations; v2 is the recording-to-xRSPS path and carries conversion
 * evidence, provenance, gaps, approximations, and worker fidelity in the manifest itself.
 */
@Service
@Transactional
public class ScenarioManifestV2Compiler {

  static final String COMPILER_VERSION = "2.0.0";
  static final String FIXED_CLOCK_ORIGIN = "2020-01-01T00:00:00.000Z";
  static final int MANIFEST_VERSION = 2;
  private static final int COMPILER_SCHEMA_VERSION = 2;

  private final ReplayRecordingResolver resolver;
  private final TickSnapshotRepository snapshotRepository;
  private final ReplayActionConversionService conversionService;
  private final RecordingCurationRepository curationRepository;
  private final CuratedActionRepository curatedActionRepository;
  private final ScenarioManifestRepository manifestRepository;
  private final ReplayCapabilityService capabilityService;
  private final CanonicalProjectionService projectionService;
  private final ObjectMapper objectMapper;

  public ScenarioManifestV2Compiler(
      ReplayRecordingResolver resolver,
      TickSnapshotRepository snapshotRepository,
      ReplayActionConversionService conversionService,
      RecordingCurationRepository curationRepository,
      CuratedActionRepository curatedActionRepository,
      ScenarioManifestRepository manifestRepository,
      ReplayCapabilityService capabilityService,
      CanonicalProjectionService projectionService,
      ObjectMapper objectMapper) {
    this.resolver = resolver;
    this.snapshotRepository = snapshotRepository;
    this.conversionService = conversionService;
    this.curationRepository = curationRepository;
    this.curatedActionRepository = curatedActionRepository;
    this.manifestRepository = manifestRepository;
    this.capabilityService = capabilityService;
    this.projectionService = projectionService;
    this.objectMapper = objectMapper;
  }

  /** Compile one recording using the requested deterministic conversion mode. */
  public Compilation compile(String recordingId, ConversionMode requestedMode) {
    if (recordingId == null || recordingId.isBlank()) {
      throw ReplayException.badRequest(ReplayErrorCode.VALIDATION_ERROR, "recordingId is required");
    }

    ReplayRecordingResolver.ResolvedRecording resolved = resolver.resolve(recordingId);
    DebugSession session = resolved.debugSession();
    if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
      throw ReplayException.invalid(
          ReplayErrorCode.DEBUG_SESSION_NOT_FOUND,
          "No debug session is linked to recording " + recordingId);
    }
    if (session.getEndTime() == null) {
      throw ReplayException.invalid(
          ReplayErrorCode.DEBUG_SESSION_NOT_FINALIZED,
          "The debug session must be finalized before xRSPS replay");
    }

    CapabilityManifest capability = capabilityService.getManifest();
    requireSimulationCapability(capability);

    long startTick =
        snapshotRepository
            .findMinTickBySessionId(session.getSessionId())
            .orElseThrow(
                () ->
                    ReplayException.invalid(
                        ReplayErrorCode.NO_TICK_SNAPSHOTS,
                        "No tick snapshots exist for the debug session"));
    long endTick =
        snapshotRepository.findMaxTickBySessionId(session.getSessionId()).orElse(startTick);
    List<TickSnapshot> snapshots =
        snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
            session.getSessionId(), startTick, endTick);
    assertContiguousSnapshots(snapshots, startTick, endTick);

    ConversionMode mode = requestedMode == null ? ConversionMode.AUTO : requestedMode;
    ReplayActionConversionResult conversion =
        conversionService.convert(session.getSessionId(), startTick, endTick, mode);
    if (conversion.quality() == com.chromascape.web.replay.conversion.ConversionQuality.UNSUPPORTED
        || conversion.actions().isEmpty()) {
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED,
          "The recording contains no executable semantic replay action");
    }

    List<String> unsupported = unsupportedActions(conversion, capability);
    if (conversion.quality()
            == com.chromascape.web.replay.conversion.ConversionQuality.CERTIFIED
        && !unsupported.isEmpty()) {
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED,
          "The pinned xRSPS worker does not support captured action " + unsupported.get(0));
    }
    if (conversion.quality()
            == com.chromascape.web.replay.conversion.ConversionQuality.CERTIFIED
        && !capabilityService.isAuthoritativeCertifiable()) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          false,
          "Captured replay requires an authoritative certifiable xRSPS worker",
          "Use a worker advertising fidelity=XRSPS_AUTHORITATIVE and certifiable=true");
    }

    ObjectNode initialState = buildInitialState(snapshots.get(0));
    ObjectNode expectations = buildExpectations(snapshots.get(snapshots.size() - 1));
    String recordingChecksum = recordingChecksum(recordingId, session, snapshots, conversion);
    String sessionChecksum = sessionChecksum(session);

    RecordingCuration conversionCuration = ensureConversionCuration(
        recordingId, session, conversion, unsupported);
    ObjectNode manifestDocument =
        buildManifestDocument(
            recordingId,
            session,
            conversion,
            unsupported,
            capability,
            conversionCuration,
            recordingChecksum,
            sessionChecksum,
            initialState,
            expectations);

    UUID manifestId =
        UUID.nameUUIDFromBytes(
            ("scenario-manifest/v2:"
                    + recordingChecksum
                    + ":"
                    + conversion.conversionChecksum()
                    + ":"
                    + capability.fidelity()
                    + ":"
                    + capability.xrsps().adapterVersion()
                    + ":"
                    + capability.xrsps().buildCommit()
                    + ":"
                    + capability.xrsps().cacheRevision()
                    + ":"
                    + capability.xrsps().contentRevision())
                .getBytes(StandardCharsets.UTF_8));
    manifestDocument.put("manifestId", manifestId.toString());
    String manifestChecksum = manifestChecksum(manifestDocument);
    manifestDocument.put("manifestChecksum", manifestChecksum);
    String canonicalManifest = projectionService.canonicalJson(manifestDocument);

    // The manifest id/checksum are deterministic; repeated button presses return the same row.
    var existing = manifestRepository.findByManifestChecksum(manifestChecksum);
    if (existing.isPresent()) {
      return new Compilation(existing.get(), conversion, capability, unsupported);
    }

    ScenarioManifest entity = new ScenarioManifest();
    entity.setId(manifestId);
    entity.setManifestVersion(MANIFEST_VERSION);
    entity.setCurationId(conversionCuration.getId());
    entity.setRecordingId(recordingId);
    entity.setDebugSessionId(session.getSessionId());
    entity.setRecordingChecksum(recordingChecksum);
    entity.setDebugSessionChecksum(sessionChecksum);
    entity.setCompilerVersion(COMPILER_VERSION);
    entity.setCompilerSchemaVersion(COMPILER_SCHEMA_VERSION);
    entity.setXrspsAdapterVersion(capability.xrsps().adapterVersion());
    entity.setXrspsBuildCommit(capability.xrsps().buildCommit());
    entity.setCacheRevision(capability.xrsps().cacheRevision());
    entity.setContentRevision(capability.xrsps().contentRevision());
    entity.setSeed(manifestDocument.path("determinism").path("seed").asText());
    entity.setFixedClockOrigin(LocalDateTime.parse(FIXED_CLOCK_ORIGIN.substring(0, 23)));
    entity.setStartTick(startTick);
    entity.setEndTick(endTick);
    entity.setManifestJson(parseObject(canonicalManifest));
    entity.setManifestChecksum(manifestChecksum);
    entity.setFidelity(capability.fidelity());
    entity.setConversionQuality(conversion.quality().name());
    entity.setActionCoveragePct(conversion.actionCoveragePct());
    entity.setGapCount(conversion.gaps().size() + unsupported.size());
    entity.setApproximationCount(conversion.approximations().size());
    entity.setCompleteness("COMPLETE");
    entity.setPublishState("PUBLISHED");
    entity.setCreatedAt(LocalDateTime.now());
    try {
      entity = manifestRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException race) {
      entity = manifestRepository.findByManifestChecksum(manifestChecksum).orElseThrow(() -> race);
    }
    return new Compilation(entity, conversion, capability, unsupported);
  }

  private void requireSimulationCapability(CapabilityManifest capability) {
    if (capability == null
        || capability.modes() == null
        || !capability.modes().causalSimulation()) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "Causal xRSPS simulation is unavailable",
          "Start the pinned xRSPS worker and retry preflight");
    }
    if (capability.xrsps() == null
        || isBlank(capability.xrsps().adapterVersion())
        || isBlank(capability.xrsps().buildCommit())
        || isBlank(capability.xrsps().cacheRevision())
        || isBlank(capability.xrsps().contentRevision())) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          false,
          "The xRSPS capability has incomplete runtime pins",
          "Configure adapter, build, cache, and content identities before replay");
    }
  }

  private static List<String> unsupportedActions(
      ReplayActionConversionResult conversion, CapabilityManifest capability) {
    Set<String> seen = new HashSet<>();
    List<String> unsupported = new ArrayList<>();
    for (ConvertedReplayAction converted : conversion.actions()) {
      String type = converted.action().getActionType();
      if (!capability.supportedActions().contains(type) && seen.add(type)) {
        unsupported.add(type);
      }
    }
    return unsupported;
  }

  private ObjectNode buildManifestDocument(
      String recordingId,
      DebugSession session,
      ReplayActionConversionResult conversion,
      List<String> unsupported,
      CapabilityManifest capability,
      RecordingCuration curation,
      String recordingChecksum,
      String sessionChecksum,
      ObjectNode initialState,
      ObjectNode expectations) {
    ObjectNode doc = objectMapper.createObjectNode();
    doc.put("manifestVersion", MANIFEST_VERSION);
    // Filled by the caller after the checksum basis is canonicalized.
    doc.put("manifestId", "pending");

    ObjectNode recording = doc.putObject("recording");
    recording.put("recordingId", recordingId);
    recording.put("recordingChecksum", recordingChecksum);
    recording.put("debugSessionId", session.getSessionId());
    recording.put("debugSessionChecksum", sessionChecksum);
    recording.put("startTick", conversion.startTick());
    recording.put("endTick", conversion.endTick());

    ObjectNode curationNode = doc.putObject("curation");
    curationNode.put("curationId", curation.getId().toString());
    curationNode.put("version", curation.getVersion());
    curationNode.put("checksum", curation.getContentChecksum());

    ObjectNode compiler = doc.putObject("compiler");
    compiler.put("version", COMPILER_VERSION);
    compiler.put("schemaVersion", COMPILER_SCHEMA_VERSION);

    ObjectNode runtime = doc.putObject("runtime");
    runtime.put("xrspsAdapterVersion", capability.xrsps().adapterVersion());
    runtime.put("xrspsBuildCommit", capability.xrsps().buildCommit());
    runtime.put("cacheRevision", capability.xrsps().cacheRevision());
    runtime.put("contentRevision", capability.xrsps().contentRevision());

    ObjectNode determinism = doc.putObject("determinism");
    ObjectNode seedBasis = objectMapper.createObjectNode();
    seedBasis.put("recordingChecksum", recordingChecksum);
    seedBasis.put("conversionChecksum", conversion.conversionChecksum());
    seedBasis.put("compilerVersion", COMPILER_VERSION);
    String seed =
        projectionService
            .sha256Hex(projectionService.canonicalJsonBytes(seedBasis))
            .substring(
                CanonicalProjectionService.HASH_PREFIX.length(),
                CanonicalProjectionService.HASH_PREFIX.length() + 16);
    determinism.put("seed", seed);
    determinism.put("clockOrigin", FIXED_CLOCK_ORIGIN);
    determinism.put("tickDurationMs", 600);
    determinism.put("idNamespace", "replay-v2");

    doc.set("initialState", initialState);
    ArrayNode actions = doc.putArray("actions");
    for (ConvertedReplayAction converted : conversion.actions()) {
      if (unsupported.contains(converted.action().getActionType())) {
        // Best-effort manifests retain this fact in conversion.gaps; the kernel receives only
        // actions it declared, so an unsupported action can never be silently dispatched.
        continue;
      }
      actions.add(toKernelAction(converted));
    }
    if (actions.isEmpty()) {
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED,
          "No converted action is supported by the pinned xRSPS worker");
    }

    doc.set("expectations", expectations);
    ObjectNode tolerances = doc.putObject("tolerances");
    tolerances.putObject("player.position").put("level", "SOFT").put("tolerance", 0);

    ObjectNode conversionNode = doc.putObject("conversion");
    conversionNode.put("mode", conversion.mode().name());
    conversionNode.put("quality", conversion.quality().name());
    conversionNode.put("conversionChecksum", conversion.conversionChecksum());
    conversionNode.put("typedActionCount", conversion.typedActionCount());
    conversionNode.put("legacyLogCount", conversion.legacyLogCount());
    conversionNode.put("actionCoveragePct", conversion.actionCoveragePct());
    ArrayNode gaps = conversionNode.putArray("gaps");
    for (ReplayGap gap : conversion.gaps()) {
      gaps.add(gapNode(gap));
    }
    for (String action : unsupported) {
      ObjectNode gap = gaps.addObject();
      gap.put("tick", conversion.startTick());
      gap.put("code", "WORKER_ACTION_UNSUPPORTED");
      gap.put("message", "Pinned worker does not support converted action " + action);
      gap.put("provenance", ActionProvenance.DEFAULTED.name());
    }
    ArrayNode approximations = conversionNode.putArray("approximations");
    for (ReplayApproximation approximation : conversion.approximations()) {
      ObjectNode entry = approximations.addObject();
      entry.put("tick", approximation.tick());
      entry.put("code", approximation.code());
      entry.put("message", approximation.message());
      entry.put("provenance", approximation.provenance().name());
      if (approximation.sourceSnapshotId() != null) {
        entry.put("sourceSnapshotId", approximation.sourceSnapshotId());
      }
    }
    doc.put("fidelity", capability.fidelity());
    doc.put("fidelityRequirement", conversion.quality().name());
    doc.put("controlAllowed", false);
    return doc;
  }

  private ObjectNode toKernelAction(ConvertedReplayAction converted) {
    RecordingAction source = converted.action();
    ObjectNode action = objectMapper.createObjectNode();
    String type = source.getActionType();
    action.put("type", type);
    if ("WAIT_UNTIL_TICK".equals(type)) {
      action.put("tick", source.getTick());
    }
    MapValue target = mapValue(source.getTarget());
    if (target.stableId() != null) {
      action.put("targetStableId", target.stableId());
    }
    String option = textValue(source.getArguments(), "option");
    if (option != null) {
      action.put("option", option);
    }
    Integer slot = intValue(source.getArguments(), "slot");
    Integer itemId = intValue(source.getArguments(), "itemId");
    if (slot != null) action.put("slot", slot);
    if (itemId != null) action.put("itemId", itemId);
    JsonNode destination = jsonValue(source.getArguments(), "destination");
    if (destination != null && destination.isObject()) {
      action.set("destination", destination.deepCopy());
    }

    action.put("sourceActionTick", source.getTick());
    action.put("sourceActionSequence", source.getSequence());
    action.put("provenance", converted.provenance().name());
    action.put("provenanceReason", converted.provenanceReason());
    ObjectNode evidence = action.putObject("sourceEvidence");
    if (converted.sourceLogId() != null) evidence.put("logId", converted.sourceLogId());
    if (converted.sourceSnapshotId() != null) evidence.put("snapshotId", converted.sourceSnapshotId());
    return action;
  }

  private RecordingCuration ensureConversionCuration(
      String recordingId,
      DebugSession session,
      ReplayActionConversionResult conversion,
      List<String> unsupported) {
    ObjectNode content = objectMapper.createObjectNode();
    content.put("schema", "recording-conversion/v1");
    content.put("conversionChecksum", conversion.conversionChecksum());
    content.put("mode", conversion.mode().name());
    content.put("quality", conversion.quality().name());
    content.put("actionCoveragePct", conversion.actionCoveragePct());
    ArrayNode actionNodes = content.putArray("actions");
    for (ConvertedReplayAction converted : conversion.actions()) {
      ObjectNode action = actionNodes.addObject();
      action.set("envelope", objectMapper.valueToTree(converted.action()));
      action.put("provenance", converted.provenance().name());
      action.put("provenanceReason", converted.provenanceReason());
      if (converted.sourceLogId() != null) action.put("sourceLogId", converted.sourceLogId());
      if (converted.sourceSnapshotId() != null) action.put("sourceSnapshotId", converted.sourceSnapshotId());
    }
    ArrayNode gaps = content.putArray("gaps");
    conversion.gaps().forEach(gap -> gaps.add(gapNode(gap)));
    unsupported.forEach(
        action ->
            gaps.addObject()
                .put("code", "WORKER_ACTION_UNSUPPORTED")
                .put("message", "Pinned worker does not support converted action " + action));
    ArrayNode approximations = content.putArray("approximations");
    conversion.approximations()
        .forEach(
            approximation -> {
              ObjectNode entry = approximations.addObject();
              entry.put("tick", approximation.tick());
              entry.put("code", approximation.code());
              entry.put("message", approximation.message());
              entry.put("provenance", approximation.provenance().name());
              if (approximation.sourceSnapshotId() != null) {
                entry.put("sourceSnapshotId", approximation.sourceSnapshotId());
              }
            });
    String checksum = projectionService.sha256Hex(projectionService.canonicalJsonBytes(content));

    RecordingCuration existingByChecksum =
        curationRepository.findByContentChecksum(checksum).orElse(null);
    if (existingByChecksum != null) {
      // A previous process may have committed the curation before its child rows were flushed.
      // Repair only missing children; the content checksum makes this idempotent and prevents
      // unrelated operator curations from being overwritten.
      persistMissingCuratedActions(existingByChecksum.getId(), conversion);
      return existingByChecksum;
    }

    UUID curationId =
        UUID.nameUUIDFromBytes(
            ("recording-conversion/v1:" + recordingId + ":" + checksum)
                .getBytes(StandardCharsets.UTF_8));
    RecordingCuration existingById = curationRepository.findById(curationId).orElse(null);
    if (existingById != null) {
      persistMissingCuratedActions(existingById.getId(), conversion);
      return existingById;
    }

    RecordingCuration curation = new RecordingCuration();
    curation.setId(curationId);
    curation.setRecordingId(recordingId);
    curation.setDebugSessionId(session.getSessionId());
    curation.setVersion(nextCurationVersion(recordingId));
    curation.setState(CurationState.PUBLISHED);
    curation.setRole(CurationRole.HELD_OUT);
    curation.setSchemaVersion(1);
    curation.setContentJson(content);
    curation.setContentChecksum(checksum);
    curation.setCreatedBy("replay-converter");
    curation.setCreatedAt(LocalDateTime.now());
    curation.setPublishedAt(curation.getCreatedAt());
    try {
      RecordingCuration saved = curationRepository.saveAndFlush(curation);
      persistMissingCuratedActions(saved.getId(), conversion);
      return saved;
    } catch (DataIntegrityViolationException race) {
      return curationRepository
          .findByContentChecksum(checksum)
          .orElseThrow(() -> race);
    }
  }

  private int nextCurationVersion(String recordingId) {
    return curationRepository.findByRecordingIdOrderByVersionDesc(recordingId).stream()
            .findFirst()
            .map(curation -> curation.getVersion() + 1)
            .orElse(1);
  }

  private void persistMissingCuratedActions(
      UUID curationId, ReplayActionConversionResult conversion) {
    for (ConvertedReplayAction converted : conversion.actions()) {
      RecordingAction source = converted.action();
      CuratedAction.CuratedActionId actionId = new CuratedAction.CuratedActionId();
      actionId.setCurationId(curationId);
      actionId.setTick(source.getTick());
      actionId.setSequence(source.getSequence());
      if (curatedActionRepository.existsById(actionId)) {
        continue;
      }
      CuratedAction action = new CuratedAction();
      action.setCurationId(curationId);
      action.setTick(source.getTick());
      action.setSequence(source.getSequence());
      action.setStatus("ACCEPTED_LABEL");
      action.setSemanticActionType(source.getActionType());
      action.setTargetStableId(textValue(source.getTarget(), "stableId"));
      action.setArgumentsJson(objectMapper.valueToTree(source.getArguments()));
      action.setEvidenceRef(
          objectMapper.valueToTree(
              java.util.Map.of(
                  "provenance", converted.provenance().name(),
                  "reason", converted.provenanceReason())));
      action.setConfidence(source.getConfidence());
      curatedActionRepository.save(action);
    }
  }

  /** Computes the manifest address without recursively hashing the checksum field itself. */
  private String manifestChecksum(ObjectNode manifestDocument) {
    ObjectNode basis = manifestDocument.deepCopy();
    basis.remove("manifestChecksum");
    return projectionService.sha256Hex(projectionService.canonicalJsonBytes(basis));
  }

  private ObjectNode buildInitialState(TickSnapshot first) {
    JsonNode playerState = first.getPlayerState();
    if (playerState == null || !playerState.isObject()) {
      throw ReplayException.invalid(
          ReplayErrorCode.PLAYER_STATE_MISSING, "Earliest tick snapshot has no player state");
    }
    ObjectNode state = objectMapper.createObjectNode();
    state.put("worldId", playerState.path("worldId").asInt(0));
    state.put("collisionRevision", "recorded");
    ObjectNode player = state.putObject("player");
    JsonNode position = positionNode(playerState);
    if (position == null) {
      throw ReplayException.invalid(
          ReplayErrorCode.POSITION_MISSING, "Recorded player position is missing at start tick");
    }
    player.set("position", normalizedPosition(position));

    JsonNode skills = playerState.path("skills");
    player.set("skills", skills.isObject() ? skills.deepCopy() : objectMapper.createObjectNode());
    player.set("inventory", normalizeSlots(first.getInventory(), "inventory"));
    player.set("equipment", normalizeSlots(first.getEquipment(), "equipment"));

    JsonNode npcs = first.getNpcs();
    if (npcs == null || !npcs.isArray() || npcs.isEmpty()) {
      throw ReplayException.invalid(
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Initial world must contain a non-empty typed NPC list");
    }
    ArrayNode normalizedNpcs = state.putArray("npcs");
    List<JsonNode> npcEntries = new ArrayList<>();
    for (JsonNode npc : npcs) {
      if (!npc.isObject()
          || isBlank(npc.path("stableId").asText(null))
          || positionNode(npc) == null
          || isBlank(npc.path("spotType").asText(null))) {
        throw ReplayException.invalid(
            ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
            "Initial NPC world state lacks stableId, position, or spotType");
      }
      ObjectNode normalized = npc.deepCopy();
      normalized.set("position", normalizedPosition(positionNode(npc)));
      npcEntries.add(normalized);
    }
    npcEntries.sort(Comparator.comparing(npc -> npc.path("stableId").asText()));
    Set<String> stableIds = new HashSet<>();
    for (JsonNode npc : npcEntries) {
      if (!stableIds.add(npc.path("stableId").asText())) {
        throw ReplayException.invalid(
            ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE, "Initial NPC stable IDs are not unique");
      }
      normalizedNpcs.add(npc);
    }

    if (first.getGameObjects() == null || first.getGroundItems() == null) {
      throw ReplayException.invalid(
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Initial object and ground-item state must be present");
    }
    state.set("objects", first.getGameObjects().deepCopy());
    state.set("groundItems", first.getGroundItems().deepCopy());
    return state;
  }

  private ArrayNode normalizeSlots(JsonNode raw, String name) {
    if (raw == null || !raw.isArray()) {
      throw ReplayException.invalid(
          "inventory".equals(name)
              ? ReplayErrorCode.INVENTORY_SLOT_MISSING
              : ReplayErrorCode.EQUIPMENT_SLOT_MISSING,
          "Initial " + name + " state is missing");
    }
    List<JsonNode> entries = new ArrayList<>();
    for (JsonNode item : raw) {
      if (!item.isObject()
          || !item.has("slot")
          || !item.has("itemId")
          || item.path("slot").asInt(-1) < 0
          || item.path("itemId").asInt(-1) < 0) {
        throw ReplayException.invalid(
            "inventory".equals(name)
                ? ReplayErrorCode.INVENTORY_SLOT_MISSING
                : ReplayErrorCode.EQUIPMENT_SLOT_MISSING,
            "Initial " + name + " contains an invalid slot");
      }
      ObjectNode normalized = item.deepCopy();
      if (!normalized.has("quantity")) normalized.put("quantity", 1);
      entries.add(normalized);
    }
    entries.sort(Comparator.comparingInt(node -> node.path("slot").asInt()));
    ArrayNode array = objectMapper.createArrayNode();
    entries.forEach(array::add);
    return array;
  }

  private ObjectNode buildExpectations(TickSnapshot last) {
    ObjectNode expectations = objectMapper.createObjectNode();
    JsonNode position = positionNode(last.getPlayerState());
    if (position != null) {
      ObjectNode spec = expectations.putObject("player.position");
      spec.set("expected", normalizedPosition(position));
      spec.put("level", "HARD");
    }
    JsonNode skills = last.getPlayerState() == null ? null : last.getPlayerState().path("skills");
    JsonNode fishing = skills == null ? null : skills.path("fishing");
    if (fishing != null && fishing.isObject() && fishing.has("xp")) {
      ObjectNode spec = expectations.putObject("player.skills.fishing.xp");
      spec.set("expected", fishing.get("xp"));
      spec.put("level", "SOFT");
    }
    return expectations;
  }

  private String recordingChecksum(
      String recordingId,
      DebugSession session,
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion) {
    ObjectNode evidence = objectMapper.createObjectNode();
    evidence.put("recordingId", recordingId);
    evidence.put("debugSessionId", session.getSessionId());
    evidence.put("start", String.valueOf(session.getStartTime()));
    evidence.put("end", String.valueOf(session.getEndTime()));
    evidence.put("conversionChecksum", conversion.conversionChecksum());
    ArrayNode snapshotNodes = evidence.putArray("snapshots");
    for (TickSnapshot snapshot : snapshots) {
      ObjectNode node = snapshotNode(snapshot);
      snapshotNodes.add(node);
    }
    return projectionService.sha256Hex(projectionService.canonicalJsonBytes(evidence));
  }

  private ObjectNode snapshotNode(TickSnapshot snapshot) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("tick", snapshot.getTick());
    node.set("playerState", nullable(snapshot.getPlayerState()));
    node.set("inventory", nullable(snapshot.getInventory()));
    node.set("equipment", nullable(snapshot.getEquipment()));
    node.set("npcs", nullable(snapshot.getNpcs()));
    node.set("gameObjects", nullable(snapshot.getGameObjects()));
    node.set("groundItems", nullable(snapshot.getGroundItems()));
    node.set("schemaVersion", nullable(snapshot.getSchemaVersion()));
    return node;
  }

  private String sessionChecksum(DebugSession session) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("sessionId", session.getSessionId());
    node.put("start", String.valueOf(session.getStartTime()));
    node.put("end", String.valueOf(session.getEndTime()));
    return projectionService.sha256Hex(projectionService.canonicalJsonBytes(node));
  }

  private static void assertContiguousSnapshots(List<TickSnapshot> snapshots, long start, long end) {
    if (snapshots == null || snapshots.size() != end - start + 1) {
      throw ReplayException.invalid(
          ReplayErrorCode.TICK_GAP_DETECTED,
          "Tick snapshots do not cover the complete range " + start + ".." + end);
    }
    long expected = start;
    for (TickSnapshot snapshot : snapshots) {
      if (snapshot == null || snapshot.getTick() == null || snapshot.getTick() != expected) {
        throw ReplayException.invalid(
            ReplayErrorCode.TICK_GAP_DETECTED,
            "Tick snapshots are not contiguous in the requested range");
      }
      expected++;
    }
  }

  private ObjectNode gapNode(ReplayGap gap) {
    ObjectNode entry = objectMapper.createObjectNode();
    entry.put("tick", gap.tick());
    entry.put("code", gap.code());
    entry.put("message", gap.message());
    entry.put("provenance", gap.provenance().name());
    if (gap.sourceLogId() != null) entry.put("sourceLogId", gap.sourceLogId());
    return entry;
  }

  private JsonNode nullable(JsonNode node) {
    return node == null ? objectMapper.nullNode() : node.deepCopy();
  }

  private JsonNode nullable(Integer value) {
    return value == null ? objectMapper.nullNode() : objectMapper.getNodeFactory().numberNode(value);
  }

  private static JsonNode positionNode(JsonNode state) {
    if (state == null || !state.isObject()) return null;
    JsonNode nested = state.path("position");
    if (nested.isObject() && hasCoordinate(nested, "x") && (hasCoordinate(nested, "y") || hasCoordinate(nested, "z"))) {
      return nested;
    }
    JsonNode worldPoint = state.path("worldPoint");
    if (worldPoint.isObject() && hasCoordinate(worldPoint, "x") && (hasCoordinate(worldPoint, "y") || hasCoordinate(worldPoint, "z"))) {
      return worldPoint;
    }
    if (hasCoordinate(state, "x") && (hasCoordinate(state, "y") || hasCoordinate(state, "z"))) {
      return state;
    }
    if (hasCoordinate(state, "worldX") && (hasCoordinate(state, "worldY") || hasCoordinate(state, "z"))) {
      return state;
    }
    return null;
  }

  private static ObjectNode normalizedPosition(JsonNode raw) {
    ObjectNode position = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    position.put("x", coordinate(raw, "x", "worldX"));
    position.put("y", coordinate(raw, "y", "z", "worldY"));
    position.put("plane", raw.path("plane").asInt(0));
    return position;
  }

  private static boolean hasCoordinate(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && ((value.isNumber() && value.isIntegralNumber()) || value.isTextual());
  }

  private static int coordinate(JsonNode node, String... fields) {
    for (String field : fields) {
      if (hasCoordinate(node, field)) return node.path(field).asInt();
    }
    return 0;
  }

  private static String textValue(java.util.Map<String, Object> values, String key) {
    if (values == null) return null;
    Object value = values.get(key);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  private static Integer intValue(java.util.Map<String, Object> values, String key) {
    if (values == null) return null;
    Object value = values.get(key);
    if (value instanceof Number number) return number.intValue();
    if (value instanceof String text) {
      try {
        return Integer.valueOf(text.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private JsonNode jsonValue(java.util.Map<String, Object> values, String key) {
    if (values == null || !values.containsKey(key)) return null;
    return objectMapper.valueToTree(values.get(key));
  }

  private static MapValue mapValue(java.util.Map<String, Object> values) {
    String stableId = textValue(values, "stableId");
    return new MapValue(stableId);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private JsonNode parseObject(String canonical) {
    try {
      return objectMapper.readTree(canonical);
    } catch (Exception e) {
      throw new ReplayException(
          ReplayErrorCode.INTERNAL_ERROR,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Failed to parse canonical replay manifest",
          null,
          null,
          e);
    }
  }

  private record MapValue(String stableId) {}

  /** Result used by the v2 orchestration service and API. */
  public record Compilation(
      ScenarioManifest manifest,
      ReplayActionConversionResult conversion,
      CapabilityManifest capability,
      List<String> unsupportedActions) {
    public Compilation {
      unsupportedActions = List.copyOf(unsupportedActions == null ? List.of() : unsupportedActions);
    }
  }
}
