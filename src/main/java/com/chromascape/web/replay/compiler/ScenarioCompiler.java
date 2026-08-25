package com.chromascape.web.replay.compiler;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.compiler.action.InteractNpcAction;
import com.chromascape.web.replay.compiler.action.SemanticAction;
import com.chromascape.web.replay.compiler.action.WaitUntilTickAction;
import com.chromascape.web.replay.curation.entity.CuratedAction;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic scenario compiler.
 *
 * Every authoritative value is derived from immutable evidence: recording +
 * debug session + published curation + tick snapshots. No wall-clock reads,
 * no random UUIDs. Compiling the same inputs twice produces a byte-identical,
 * identically-addressed manifest (idempotent publish).
 */
@Service
@Transactional
public class ScenarioCompiler {

    private static final Logger log = LoggerFactory.getLogger(ScenarioCompiler.class);
    private static final String COMPILER_VERSION = "1.1.0";
    private static final int SCHEMA_VERSION = 1;
    /** Fixed epoch: manifests pin simulation time, wall-clock never enters. */
    private static final String FIXED_CLOCK_ORIGIN = "2020-01-01T00:00:00.000Z";
    private static final Set<String> SUPPORTED_ACTION_TYPES = Set.of("INTERACT_NPC", "WAIT_UNTIL_TICK");
    private static final Set<String> SUPPORTED_FISHING_OPTIONS =
            Set.of("net", "bait", "lure", "cage", "harpoon");

    private final ReplayRecordingResolver resolver;
    private final TickSnapshotRepository snapshotRepository;
    private final RecordingCurationRepository curationRepository;
    private final CuratedActionRepository curatedActionRepository;
    private final ScenarioManifestRepository manifestRepository;
    private final ReplayCapabilityService capabilityService;
    private final CanonicalProjectionService projectionService;
    private final ObjectMapper objectMapper;

    public ScenarioCompiler(ReplayRecordingResolver resolver,
                            TickSnapshotRepository snapshotRepository,
                            RecordingCurationRepository curationRepository,
                            CuratedActionRepository curatedActionRepository,
                            ScenarioManifestRepository manifestRepository,
                            ReplayCapabilityService capabilityService,
                            CanonicalProjectionService projectionService,
                            ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.snapshotRepository = snapshotRepository;
        this.curationRepository = curationRepository;
        this.curatedActionRepository = curatedActionRepository;
        this.manifestRepository = manifestRepository;
        this.capabilityService = capabilityService;
        this.projectionService = projectionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Compiles (or idempotently returns) the manifest for a published
     * curation. The optional {@code seedOverride} pins the RNG seed.
     */
    public ScenarioManifest compile(String recordingId, UUID curationId, String seedOverride) {
        // Idempotent publish: an already-compiled published curation returns
        // its existing manifest regardless of how often compilation repeats.
        var existing = manifestRepository.findByCurationIdAndPublishState(curationId, "PUBLISHED");
        if (existing.isPresent()) {
            return existing.get();
        }

        var resolved = resolver.resolve(recordingId);
        DebugSession session = resolved.debugSession();
        if (session == null || session.getSessionId() == null) {
            throw ReplayException.invalid(ReplayErrorCode.DEBUG_SESSION_NOT_FOUND,
                    "No debug session for recording " + recordingId);
        }
        if (session.getEndTime() == null) {
            throw ReplayException.invalid(ReplayErrorCode.DEBUG_SESSION_NOT_FINALIZED,
                    "Debug session has not been finalized");
        }

        RecordingCuration curation = curationRepository.findById(curationId)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.CURATION_NOT_PUBLISHED,
                        "Curation " + curationId + " not found"));
        // Ownership: curation must belong to this recording AND its session.
        if (!curation.isPublished()) {
            throw ReplayException.invalid(ReplayErrorCode.CURATION_NOT_PUBLISHED,
                    "Curation " + curationId + " is not PUBLISHED");
        }
        if (!recordingId.equals(curation.getRecordingId())) {
            throw ReplayException.invalid(ReplayErrorCode.CURATION_IMMUTABLE,
                    "Curation does not belong to this recording");
        }
        if (!session.getSessionId().equals(curation.getDebugSessionId())) {
            throw ReplayException.invalid(ReplayErrorCode.CURATION_IMMUTABLE,
                    "Curation was authored against a different debug session");
        }
        if (!capabilityService.isSimulationAvailable()) {
            throw new ReplayException(ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, true,
                    "xRSPS runtime versions cannot be pinned because the simulator is unavailable",
                    "Start the xRSPS worker so the capability handshake succeeds");
        }

        // ── immutable evidence ──
        long startTick = snapshotRepository.findMinTickBySessionId(session.getSessionId()).orElseThrow(
                () -> ReplayException.invalid(ReplayErrorCode.NO_TICK_SNAPSHOTS, "No tick snapshots"));
        long endTick = snapshotRepository.findMaxTickBySessionId(session.getSessionId()).orElse(startTick);
        long snapshotCount = snapshotRepository.countBySessionId(session.getSessionId());
        if (snapshotCount == 0) {
            throw ReplayException.invalid(ReplayErrorCode.NO_TICK_SNAPSHOTS, "No tick snapshots");
        }
        if (endTick - startTick + 1 != snapshotCount) {
            throw ReplayException.invalid(ReplayErrorCode.TICK_GAP_DETECTED,
                    "Tick snapshots contain gaps: expected " + (endTick - startTick + 1)
                            + " snapshots for range [" + startTick + "," + endTick + "] but found " + snapshotCount);
        }

        List<TickSnapshot> boundarySnapshots = snapshotRepository
                .findBySessionIdAndTickBetweenOrderByTick(session.getSessionId(), startTick, endTick);

        // Checksums over actual evidence content, never bare identifiers.
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("recordingId", recordingId);
        evidence.put("debugSessionId", session.getSessionId());
        evidence.put("sessionStart", String.valueOf(session.getStartTime()));
        evidence.put("sessionEnd", String.valueOf(session.getEndTime()));
        evidence.put("startTick", startTick);
        evidence.put("endTick", endTick);
        evidence.put("snapshotCount", snapshotCount);
        Integer schemaVersion = boundarySnapshots.isEmpty() ? null
                : boundarySnapshots.get(0).getSchemaVersion();
        if (schemaVersion != null) {
            evidence.put("schemaVersion", schemaVersion);
        }
        evidence.put("curationChecksum", curation.getContentChecksum());
        String evidenceHex = sha256Hex(projectionService.canonicalJsonBytes(evidence));
        String recordingChecksum = "sha256:" + evidenceHex;

        ObjectNode sessionEvidence = objectMapper.createObjectNode();
        sessionEvidence.put("sessionId", session.getSessionId());
        sessionEvidence.put("start", String.valueOf(session.getStartTime()));
        sessionEvidence.put("end", String.valueOf(session.getEndTime()));
        String sessionChecksum = "sha256:" + sha256Hex(
                projectionService.canonicalJsonBytes(sessionEvidence));

        // ── pinned runtime identity from live capability handshake ──
        var cap = capabilityService.getManifest();
        String adapterVersion = cap.xrsps().adapterVersion();
        String buildCommit = cap.xrsps().buildCommit();
        String cacheRevision = cap.xrsps().cacheRevision();
        String contentRevision = cap.xrsps().contentRevision();

        // ── determinism: derived, reproducible values only ──
        ObjectNode seedBasis = objectMapper.createObjectNode();
        seedBasis.put("recordingChecksum", recordingChecksum);
        seedBasis.put("curationChecksum", curation.getContentChecksum());
        seedBasis.put("compilerVersion", COMPILER_VERSION);
        String derivedSeed = sha256Hex(projectionService.canonicalJsonBytes(seedBasis)).substring(0, 16);
        String seed = seedOverride != null && !seedOverride.isBlank() ? seedOverride : derivedSeed;
        String manifestIdSeed = "sha256:" + derivedSeed;
        UUID manifestUuid = UUID.nameUUIDFromBytes(manifestIdSeed.getBytes(StandardCharsets.UTF_8));

        // ── actions: preserve every same-tick sequence entry ──
        List<SemanticAction> manifestActions =
                compileActions(curation.getId(), boundarySnapshots);

        // ── initial state from the earliest snapshot ──
        ObjectNode initialState = buildInitialState(boundarySnapshots.get(0));

        // ── expectations from the final snapshot ──
        ObjectNode expectations = buildExpectations(boundarySnapshots.get(boundarySnapshots.size() - 1));
        ObjectNode tolerances = objectMapper.createObjectNode();
        tolerances.putObject("player.position").put("level", "SOFT").put("tolerance", 0);

        // Build document WITHOUT checksum, canonicalize, hash, then insert.
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("manifestVersion", 1);
        doc.put("manifestId", manifestUuid.toString());
        ObjectNode rec = doc.putObject("recording");
        rec.put("recordingId", recordingId);
        rec.put("recordingChecksum", recordingChecksum);
        rec.put("debugSessionId", session.getSessionId());
        rec.put("debugSessionChecksum", sessionChecksum);
        rec.put("startTick", startTick);
        rec.put("endTick", endTick);
        ObjectNode cur = doc.putObject("curation");
        cur.put("curationId", curation.getId().toString());
        cur.put("version", curation.getVersion());
        cur.put("checksum", curation.getContentChecksum());
        ObjectNode comp = doc.putObject("compiler");
        comp.put("version", COMPILER_VERSION);
        comp.put("schemaVersion", SCHEMA_VERSION);
        ObjectNode rt = doc.putObject("runtime");
        rt.put("xrspsAdapterVersion", adapterVersion);
        rt.put("xrspsBuildCommit", buildCommit);
        rt.put("cacheRevision", cacheRevision);
        rt.put("contentRevision", contentRevision);
        ObjectNode det = doc.putObject("determinism");
        det.put("seed", seed);
        det.put("clockOrigin", FIXED_CLOCK_ORIGIN);
        det.put("tickDurationMs", 600);
        det.put("idNamespace", "replay");
        doc.set("initialState", initialState);
        ArrayNode actionsNode = doc.putArray("actions");
        for (SemanticAction action : manifestActions) {
            actionsNode.add(objectMapper.valueToTree(action));
        }
        doc.set("expectations", expectations);
        doc.set("tolerances", tolerances);
        doc.put("controlAllowed", false);

        String manifestChecksum = projectionService.sha256Hex(
                projectionService.canonicalJsonBytes(doc));
        doc.put("manifestChecksum", "sha256:" + manifestChecksum.substring(CanonicalProjectionService.HASH_PREFIX.length()));

        String canonicalDoc = projectionService.canonicalJson(doc);
        JsonNode storedJson;
        try {
            storedJson = objectMapper.readTree(canonicalDoc);
        } catch (Exception e) {
            throw new ReplayException(ReplayErrorCode.INTERNAL_ERROR,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, false,
                    "Failed to parse canonicalized manifest", null, null, e);
        }

        ScenarioManifest entity = new ScenarioManifest();
        entity.setCurationId(curation.getId());
        entity.setRecordingId(recordingId);
        entity.setDebugSessionId(session.getSessionId());
        entity.setRecordingChecksum(recordingChecksum);
        entity.setDebugSessionChecksum(sessionChecksum);
        entity.setCompilerVersion(COMPILER_VERSION);
        entity.setCompilerSchemaVersion(SCHEMA_VERSION);
        entity.setXrspsAdapterVersion(adapterVersion);
        entity.setXrspsBuildCommit(buildCommit);
        entity.setCacheRevision(cacheRevision);
        entity.setContentRevision(contentRevision);
        entity.setSeed(seed);
        entity.setFixedClockOrigin(LocalDateTime.parse(FIXED_CLOCK_ORIGIN.substring(0, 23)));
        entity.setStartTick(startTick);
        entity.setEndTick(endTick);
        entity.setManifestJson(storedJson);
        entity.setManifestChecksum("sha256:" + manifestChecksum.substring(CanonicalProjectionService.HASH_PREFIX.length()));
        entity.setCompleteness("COMPLETE");
        entity.setPublishState("PUBLISHED");
        entity.setCreatedAt(LocalDateTime.now());
        try {
            return manifestRepository.saveAndFlush(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Concurrent compile: return the winner's identical manifest.
            return manifestRepository.findByManifestChecksum(entity.getManifestChecksum())
                    .orElseThrow(() -> race);
        }
    }

    private List<SemanticAction> compileActions(UUID curationId, List<TickSnapshot> snapshots) {
        List<CuratedAction> curatedActions = curatedActionRepository
                .findByCurationIdOrderByTickAscSequenceAsc(curationId);
        // Defensive re-sort: guarantee (tick, sequence) order even if repository changes.
        curatedActions.sort(Comparator.comparingLong(CuratedAction::getTick)
                .thenComparingInt(CuratedAction::getSequence));

        Set<String> knownStableIds = collectNpcStableIds(snapshots.get(0));

        List<SemanticAction> result = new ArrayList<>();
        long lastActionTick = Long.MIN_VALUE;
        for (CuratedAction ca : curatedActions) {
            switch (ca.getStatus()) {
                case "IGNORED" -> {
                    continue;
                }
                case "UNSUPPORTED" -> {
                    // Fail loudly: silently dropping curated evidence would
                    // produce a manifest that does not reproduce the recording.
                    throw ReplayException.invalid(ReplayErrorCode.ACTION_UNSUPPORTED,
                            "Curation marks tick " + ca.getTick()
                                    + " sequence " + ca.getSequence() + " UNSUPPORTED");
                }
                default -> {
                    // ACCEPTED_LABEL and any legacy statuses fall through.
                }
            }
            String type = ca.getSemanticActionType();
            if (type == null || !SUPPORTED_ACTION_TYPES.contains(type)) {
                throw ReplayException.invalid(ReplayErrorCode.ACTION_UNSUPPORTED,
                        "Curated action type " + type + " at tick " + ca.getTick() + " is unsupported");
            }
            if ("WAIT_UNTIL_TICK".equals(type)) {
                result.add(new WaitUntilTickAction(ca.getTick()));
                lastActionTick = ca.getTick();
                continue;
            }

            if (ca.getTargetStableId() == null || ca.getTargetStableId().isBlank()) {
                throw ReplayException.invalid(ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                        "INTERACT_NPC at tick " + ca.getTick() + " has no stable target id");
            }
            if (!knownStableIds.contains(ca.getTargetStableId())) {
                throw ReplayException.invalid(ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                        "Stable target " + ca.getTargetStableId() + " not present in initial world");
            }
            String option = extractOption(ca.getArgumentsJson());
            if (option == null || !SUPPORTED_FISHING_OPTIONS.contains(option)) {
                throw ReplayException.invalid(ReplayErrorCode.ACTION_UNSUPPORTED,
                        "INTERACT_NPC at tick " + ca.getTick() + " has missing or unsupported option");
            }
            if (lastActionTick != Long.MIN_VALUE && ca.getTick() > lastActionTick + 1) {
                result.add(new WaitUntilTickAction(ca.getTick()));
            }
            result.add(new InteractNpcAction(ca.getTargetStableId(), option));
            lastActionTick = ca.getTick();
        }
        return result;
    }

    private String extractOption(JsonNode argumentsJson) {
        if (argumentsJson == null) {
            return null;
        }
        JsonNode option = argumentsJson.get("option");
        return option != null && option.isTextual() ? option.asText() : null;
    }

    private Set<String> collectNpcStableIds(TickSnapshot snapshot) {
        Set<String> ids = new java.util.HashSet<>();
        JsonNode npcs = snapshot.getNpcs();
        if (npcs != null && npcs.isArray()) {
            int idx = 0;
            for (JsonNode npc : npcs) {
                JsonNode stable = npc.get("stableId");
                ids.add(stable != null && stable.isTextual()
                        ? stable.asText() : "npc:" + snapshot.getSessionId() + ":" + idx);
                idx++;
            }
        }
        return ids;
    }

    private ObjectNode buildInitialState(TickSnapshot first) {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("worldId", 0);
        state.put("collisionRevision", "recorded");

        ObjectNode player = state.putObject("player");
        JsonNode ps = first.getPlayerState();
        if (ps == null || ps.isNull()) {
            throw ReplayException.invalid(ReplayErrorCode.PLAYER_STATE_MISSING,
                    "Earliest tick snapshot has no player state");
        }
        JsonNode pos = ps.has("position") ? ps.get("position")
                : ps.has("worldPoint") ? ps.get("worldPoint") : null;
        if (pos != null && pos.isObject()) {
            player.putObject("position")
                    .put("x", pos.path("x").asInt())
                    .put("y", pos.path("y").asInt())
                    .put("plane", pos.path("plane").asInt(0));
        } else {
            throw ReplayException.invalid(ReplayErrorCode.POSITION_MISSING,
                    "Recorded player position missing at start tick");
        }
        ObjectNode skills = player.putObject("skills");
        JsonNode skillsNode = ps.path("skills");
        if (skillsNode.isObject()) {
            var fields = skillsNode.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                JsonNode s = e.getValue();
                skills.putObject(e.getKey())
                        .put("level", s.path("level").asInt(1))
                        .put("xp", s.path("xp").asInt(0));
            }
        }
        ArrayNode inventory = player.putArray("inventory");
        JsonNode inv = first.getInventory();
        if (inv != null && inv.isArray()) {
            int slot = 0;
            for (JsonNode item : inv) {
                int itemId = item.has("itemId") ? item.get("itemId").asInt()
                        : item.has("id") ? item.get("id").asInt() : item.asInt(-1);
                if (itemId > 0) {
                    ObjectNode slotNode = inventory.addObject();
                    slotNode.put("slot", item.has("slot") ? item.get("slot").asInt() : slot);
                    slotNode.put("itemId", itemId);
                    slotNode.put("quantity", item.path("quantity").asInt(1));
                }
                slot++;
            }
        }
        player.putArray("equipment");

        ArrayNode npcsNode = state.putArray("npcs");
        JsonNode npcs = first.getNpcs();
        if (npcs != null && npcs.isArray()) {
            int idx = 0;
            for (JsonNode npc : npcs) {
                ObjectNode n = npcsNode.addObject();
                JsonNode stable = npc.get("stableId");
                n.put("stableId", stable != null && stable.isTextual()
                        ? stable.asText() : "npc:" + first.getSessionId() + ":" + idx);
                n.put("npcId", npc.path("id").asInt(npc.path("npcId").asInt(0)));
                JsonNode p = npc.path("position");
                if (!p.isObject()) p = npc.path("worldPoint");
                n.putObject("position")
                        .put("x", p.path("x").asInt())
                        .put("y", p.path("y").asInt())
                        .put("plane", p.path("plane").asInt(0));
                // spotType defaults are forbidden: fishing spots must be typed.
                String spotType = npc.path("spotType").asText(null);
                if (spotType == null || !SUPPORTED_FISHING_OPTIONS.contains(spotType)) {
                    throw ReplayException.invalid(ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                            "NPC " + n.get("stableId").asText() + " lacks a supported spotType");
                }
                n.put("spotType", spotType);
                idx++;
            }
        }
        state.putArray("objects");
        state.putArray("groundItems");
        return state;
    }

    /** Expectation map: {"pointer": {"expected": value, "level": "HARD"}}. */
    private ObjectNode buildExpectations(TickSnapshot last) {
        ObjectNode expectations = objectMapper.createObjectNode();
        JsonNode ps = last.getPlayerState();
        if (ps != null && ps.isObject()) {
            JsonNode pos = ps.has("position") ? ps.get("position")
                    : ps.has("worldPoint") ? ps.get("worldPoint") : null;
            if (pos != null && pos.isObject()) {
                ObjectNode spec = expectations.putObject("player.position");
                spec.putObject("expected")
                        .put("x", pos.path("x").asInt())
                        .put("y", pos.path("y").asInt())
                        .put("plane", pos.path("plane").asInt(0));
                spec.put("level", "HARD");
            }
            JsonNode skillsNode = ps.path("skills");
            if (skillsNode.isObject() && skillsNode.has("fishing")) {
                ObjectNode fishing = skillsNode.get("fishing").isObject()
                        ? (ObjectNode) skillsNode.get("fishing") : null;
                if (fishing != null && fishing.has("xp")) {
                    ObjectNode spec = expectations.putObject("player.skills.fishing.xp");
                    spec.put("expected", fishing.path("xp").asInt());
                    spec.put("level", "SOFT");
                }
            }
        }
        return expectations;
    }

    private static String sha256Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
