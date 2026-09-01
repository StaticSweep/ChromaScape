package com.chromascape.web.replay.compiler;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayEvidenceValidator;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.compiler.action.InteractNpcAction;
import com.chromascape.web.replay.compiler.action.SemanticAction;
import com.chromascape.web.replay.compiler.action.WaitUntilTickAction;
import com.chromascape.web.replay.curation.entity.CuratedAction;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Deterministic scenario compiler.
 *
 * <p>Every authoritative value is derived from immutable evidence: recording + debug session +
 * published curation + tick snapshots. No wall-clock reads, no random UUIDs. Compiling the same
 * inputs twice produces a byte-identical, identically-addressed manifest (idempotent publish).
 *
 * <p>Transaction topology: this class is deliberately NOT {@code @Transactional}. Persistence-race
 * handling lives entirely outside any transaction — insertion goes through {@link
 * ScenarioManifestInsertService} ({@code REQUIRES_NEW}) whose constraint exceptions escape
 * untouched, and the concurrent winner is verified through {@link ScenarioManifestWinnerReader} in
 * a FRESH read-only transaction by comparing CANONICAL bytes (never {@code JsonNode.toString()},
 * which silently differs under JSONB key reordering).
 *
 * <p>Evidence fidelity: missing recorded evidence fails compilation. The compiler NEVER synthesizes
 * fallback stable ids, inventory slots from array positions, or world metadata.
 */
@Service
public class ScenarioCompiler {

  private static final Logger log = LoggerFactory.getLogger(ScenarioCompiler.class);
  private static final String COMPILER_VERSION = "1.1.0";
  private static final int SCHEMA_VERSION = 1;

  /** Fixed epoch: manifests pin simulation time, wall-clock never enters. */
  private static final String FIXED_CLOCK_ORIGIN = "2020-01-01T00:00:00.000Z";

  private final ReplayRecordingResolver resolver;
  private final TickSnapshotRepository snapshotRepository;
  private final RecordingCurationRepository curationRepository;
  private final CuratedActionRepository curatedActionRepository;
  private final ScenarioManifestRepository manifestRepository;
  private final ReplayCapabilityService capabilityService;
  private final CanonicalProjectionService projectionService;
  private final ObjectMapper objectMapper;
  private final ScenarioManifestInsertService insertService;
  private final ScenarioManifestWinnerReader winnerReader;

  public ScenarioCompiler(
      ReplayRecordingResolver resolver,
      TickSnapshotRepository snapshotRepository,
      RecordingCurationRepository curationRepository,
      CuratedActionRepository curatedActionRepository,
      ScenarioManifestRepository manifestRepository,
      ReplayCapabilityService capabilityService,
      CanonicalProjectionService projectionService,
      ObjectMapper objectMapper,
      ScenarioManifestInsertService insertService,
      ScenarioManifestWinnerReader winnerReader) {
    this.resolver = resolver;
    this.snapshotRepository = snapshotRepository;
    this.curationRepository = curationRepository;
    this.curatedActionRepository = curatedActionRepository;
    this.manifestRepository = manifestRepository;
    this.capabilityService = capabilityService;
    this.projectionService = projectionService;
    this.objectMapper = objectMapper;
    this.insertService = insertService;
    this.winnerReader = winnerReader;
  }

  /** Effective-seed bounds: a bounded UTF-8 printable string, 1–128 characters. */
  private static final java.util.regex.Pattern SEED_PATTERN =
      java.util.regex.Pattern.compile("^[ -~]{1,128}$");

  /**
   * Compiles (or idempotently returns) the manifest for a published curation. The optional {@code
   * seedOverride} pins the RNG seed.
   *
   * <p>Identity rules: {@code manifestId} derives from the COMPLETE identity basis INCLUDING the
   * effective seed, so the same curation legally produces different immutable manifests when the
   * seed override or any other identity input differs. There is deliberately no early return on
   * {@code (curationId, PUBLISHED)} alone — idempotency compares complete canonical bytes at the
   * derived checksum, and any collision between identical checksums and different bytes fails
   * closed.
   */
  public ScenarioManifest compile(String recordingId, UUID curationId, String seedOverride) {
    var resolved = resolver.resolve(recordingId);
    DebugSession session = resolved.debugSession();
    if (session == null || session.getSessionId() == null) {
      throw ReplayException.invalid(
          ReplayErrorCode.DEBUG_SESSION_NOT_FOUND, "No debug session for recording " + recordingId);
    }
    if (session.getEndTime() == null) {
      throw ReplayException.invalid(
          ReplayErrorCode.DEBUG_SESSION_NOT_FINALIZED, "Debug session has not been finalized");
    }

    RecordingCuration curation =
        curationRepository
            .findById(curationId)
            .orElseThrow(
                () ->
                    ReplayException.notFound(
                        ReplayErrorCode.CURATION_NOT_PUBLISHED,
                        "Curation " + curationId + " not found"));
    // Ownership: curation must belong to this recording AND its session.
    if (!curation.isPublished()) {
      throw ReplayException.invalid(
          ReplayErrorCode.CURATION_NOT_PUBLISHED, "Curation " + curationId + " is not PUBLISHED");
    }
    if (!recordingId.equals(curation.getRecordingId())) {
      throw ReplayException.invalid(
          ReplayErrorCode.CURATION_IMMUTABLE, "Curation does not belong to this recording");
    }
    if (!session.getSessionId().equals(curation.getDebugSessionId())) {
      throw ReplayException.invalid(
          ReplayErrorCode.CURATION_IMMUTABLE,
          "Curation was authored against a different debug session");
    }
    // ONE authoritative eligibility predicate: causal simulation must be certifiable.
    if (!capabilityService.isCausalSimulationCertifiable()) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "xRSPS runtime versions cannot be pinned because certifiable causal simulation is unavailable",
          "Start the certified xRSPS worker so the capability handshake succeeds");
    }

    // ── immutable evidence ──
    long startTick =
        snapshotRepository
            .findMinTickBySessionId(session.getSessionId())
            .orElseThrow(
                () ->
                    ReplayException.invalid(
                        ReplayErrorCode.NO_TICK_SNAPSHOTS, "No tick snapshots"));
    long endTick =
        snapshotRepository.findMaxTickBySessionId(session.getSessionId()).orElse(startTick);
    long snapshotCount = snapshotRepository.countBySessionId(session.getSessionId());
    if (snapshotCount == 0) {
      throw ReplayException.invalid(ReplayErrorCode.NO_TICK_SNAPSHOTS, "No tick snapshots");
    }

    List<TickSnapshot> boundarySnapshots =
        snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
            session.getSessionId(), startTick, endTick);

    // ONE shared evidence interpretation with preflight: tick continuity, explicit supported
    // schema version on EVERY snapshot (null is never accepted), and initial-tick world
    // evidence. Any violation fails compilation before identity derivation.
    for (ReplayEvidenceValidator.Violation violation :
        ReplayEvidenceValidator.validateRecordingEvidence(
            startTick, endTick, snapshotCount, boundarySnapshots)) {
      throw ReplayException.invalid(violation.code(), violation.message());
    }

    // ── checksums over ACTUAL EVIDENCE CONTENT ──
    String sessionChecksum =
        "sha256:"
            + sha256Hex(
                projectionService.canonicalJsonBytes(
                    buildSessionEvidence(session, boundarySnapshots)));

    ObjectNode evidence = objectMapper.createObjectNode();
    evidence.put("recordingId", recordingId);
    evidence.put("debugSessionId", session.getSessionId());
    evidence.put("sessionStart", String.valueOf(session.getStartTime()));
    evidence.put("sessionEnd", String.valueOf(session.getEndTime()));
    evidence.put("startTick", startTick);
    evidence.put("endTick", endTick);
    evidence.put("snapshotCount", snapshotCount);
    Integer schemaVersion =
        boundarySnapshots.isEmpty() ? null : boundarySnapshots.get(0).getSchemaVersion();
    if (schemaVersion != null) {
      evidence.put("schemaVersion", schemaVersion);
    }
    evidence.put("curationChecksum", curation.getContentChecksum());
    evidence.put("debugSessionChecksum", sessionChecksum);
    String recordingChecksum =
        "sha256:" + sha256Hex(projectionService.canonicalJsonBytes(evidence));

    // ── pinned runtime identity from live capability handshake ──
    var cap = capabilityService.getManifest();
    String adapterVersion = cap.xrsps().adapterVersion();
    String buildCommit = cap.xrsps().buildCommit();
    String cacheRevision = cap.xrsps().cacheRevision();
    String contentRevision = cap.xrsps().contentRevision();
    // Blank runtime identity fails compilation: blank pins are not wildcards.
    requireRuntimeField(
        "xrspsAdapterVersion", adapterVersion, ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE);
    requireRuntimeField(
        "xrspsBuildCommit", buildCommit, ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE);
    requireRuntimeField("cacheRevision", cacheRevision, ReplayErrorCode.CACHE_REVISION_UNAVAILABLE);
    requireRuntimeField(
        "contentRevision", contentRevision, ReplayErrorCode.CONTENT_REVISION_UNAVAILABLE);

    // ── semantic content BEFORE identity: actions/state/expectations participate in it ──
    Map<Long, TickSnapshot> snapshotsByTick = new HashMap<>();
    for (TickSnapshot snap : boundarySnapshots) {
      snapshotsByTick.put(snap.getTick(), snap);
    }
    List<SemanticAction> manifestActions =
        compileActions(curation.getId(), boundarySnapshots, snapshotsByTick);
    verifySupportedActions(manifestActions, cap.supportedActions());
    ObjectNode initialState = buildInitialState(boundarySnapshots.get(0));
    ObjectNode expectations =
        buildExpectations(boundarySnapshots.get(boundarySnapshots.size() - 1));
    ObjectNode tolerances = objectMapper.createObjectNode();
    tolerances.putObject("player.position").put("level", "SOFT").put("tolerance", 0);

    // ── effective seed: blank/null normalizes to "no override" (derived default) ──
    String derivedSeed =
        deriveSeed(
            recordingChecksum,
            sessionChecksum,
            curation,
            adapterVersion,
            buildCommit,
            cacheRevision,
            contentRevision,
            initialState,
            manifestActions,
            expectations,
            tolerances);
    String trimmedOverride = seedOverride == null ? "" : seedOverride.trim();
    String seed;
    if (trimmedOverride.isEmpty()) {
      seed = derivedSeed;
    } else {
      if (!SEED_PATTERN.matcher(trimmedOverride).matches()) {
        throw ReplayException.badRequest(
            ReplayErrorCode.VALIDATION_ERROR,
            "Seed override must be a printable string of 1-128 characters");
      }
      seed = trimmedOverride;
    }

    // ── manifest identity from the COMPLETE basis, including the effective seed ──
    UUID manifestUuid =
        deriveManifestId(
            recordingChecksum,
            sessionChecksum,
            curation,
            adapterVersion,
            buildCommit,
            cacheRevision,
            contentRevision,
            seed,
            initialState,
            manifestActions,
            expectations,
            tolerances);

    // Build document WITHOUT checksum, canonicalize, hash, then insert the checksum field.
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

    String manifestChecksum =
        projectionService.sha256Hex(projectionService.canonicalJsonBytes(doc));
    String manifestChecksumValue =
        "sha256:" + manifestChecksum.substring(CanonicalProjectionService.HASH_PREFIX.length());
    doc.put("manifestChecksum", manifestChecksumValue);

    String canonicalDoc = projectionService.canonicalJson(doc);
    byte[] canonicalBytes = canonicalDoc.getBytes(StandardCharsets.UTF_8);

    // Fast idempotency path: canonical comparison of the STORED JSONB value. PostgreSQL may
    // reorder object keys inside jsonb, so raw toString() comparison would produce false
    // mismatches; canonicalizing both sides makes key order irrelevant.
    var existingByChecksum = manifestRepository.findByManifestChecksum(manifestChecksumValue);
    if (existingByChecksum.isPresent()) {
      return acceptIdenticalWinnerOrThrow(existingByChecksum.get(), canonicalBytes);
    }

    JsonNode storedJson;
    try {
      storedJson = objectMapper.readTree(canonicalDoc);
    } catch (Exception e) {
      throw new ReplayException(
          ReplayErrorCode.INTERNAL_ERROR,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Failed to parse canonicalized manifest",
          null,
          null,
          e);
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
    entity.setManifestChecksum(manifestChecksumValue);
    entity.setCompleteness("COMPLETE");
    entity.setPublishState("PUBLISHED");
    // Publication time comes from the database clock, never LocalDateTime.now().
    entity.setCreatedAt(manifestRepository.currentTimestamp());

    // Insert in its OWN transaction; a constraint violation escapes it untouched and is
    // handled HERE — outside every database transaction — by verifying the committed winner.
    try {
      return insertService.insert(entity);
    } catch (DataIntegrityViolationException race) {
      ScenarioManifest concurrentWinner =
          winnerReader.findByChecksum(manifestChecksumValue).orElseThrow(() -> race);
      return acceptIdenticalWinnerOrThrow(concurrentWinner, canonicalBytes);
    }
  }

  /**
   * Accepts a checksum winner ONLY when its stored JSONB document canonicalizes to EXACTLY the
   * local canonical bytes AND its checksum column matches. Identical checksums with different
   * canonical content are a collision and fail closed.
   */
  private ScenarioManifest acceptIdenticalWinnerOrThrow(
      ScenarioManifest winner, byte[] localCanonicalBytes) {
    JsonNode stored = winner.getManifestJson();
    if (stored == null || !stored.isObject()) {
      throw checksumCollision();
    }
    // Canonicalize the stored JSONB value through the shared projection: never compare
    // JsonNode.toString(), which depends on insertion order.
    byte[] storedCanonical = projectionService.canonicalJsonBytes(stored);
    if (java.util.Arrays.equals(storedCanonical, localCanonicalBytes)) {
      return winner;
    }
    throw checksumCollision();
  }

  private ReplayException checksumCollision() {
    return new ReplayException(
        ReplayErrorCode.CHECKSUM_MISMATCH,
        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
        false,
        "Manifest checksum maps to different canonical bytes",
        null,
        null,
        null);
  }

  private void requireRuntimeField(String name, String value, ReplayErrorCode code) {
    if (value == null || value.isBlank()) {
      throw new ReplayException(
          code,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "Runtime identity field '" + name + "' is blank; refusing to pin an incomplete runtime",
          "Ensure the xRSPS worker exposes real build identity before compiling",
          null,
          null);
    }
  }

  /** Canonical complete identity basis; every input that can change replay output participates. */
  private ObjectNode identityBasis(
      String recordingChecksum,
      String sessionChecksum,
      RecordingCuration curation,
      String adapterVersion,
      String buildCommit,
      String cacheRevision,
      String contentRevision,
      String effectiveSeed,
      ObjectNode initialState,
      List<SemanticAction> actions,
      ObjectNode expectations,
      ObjectNode tolerances) {
    ObjectNode basis = objectMapper.createObjectNode();
    basis.put("recordingChecksum", recordingChecksum);
    basis.put("debugSessionChecksum", sessionChecksum);
    basis.put("curationId", curation.getId().toString());
    basis.put("curationVersion", curation.getVersion());
    basis.put("curationChecksum", curation.getContentChecksum());
    basis.put("compilerVersion", COMPILER_VERSION);
    basis.put("compilerSchemaVersion", SCHEMA_VERSION);
    basis.put("xrspsAdapterVersion", adapterVersion);
    basis.put("xrspsBuildCommit", buildCommit);
    basis.put("cacheRevision", cacheRevision);
    basis.put("contentRevision", contentRevision);
    basis.put("effectiveSeed", effectiveSeed);
    basis.put("clockOrigin", FIXED_CLOCK_ORIGIN);
    basis.put("tickDurationMs", 600);
    basis.put("projectionVersion", CanonicalProjectionService.PROJECTION_VERSION);
    basis.put("hashVersion", CanonicalProjectionService.HASH_VERSION);
    basis.set("initialState", initialState);
    ArrayNode actionNodes = basis.putArray("actions");
    for (SemanticAction action : actions) {
      actionNodes.add(objectMapper.valueToTree(action));
    }
    basis.set("expectations", expectations);
    basis.set("tolerances", tolerances);
    basis.put("controlAllowed", false);
    return basis;
  }

  private String deriveSeed(
      String recordingChecksum,
      String sessionChecksum,
      RecordingCuration curation,
      String adapterVersion,
      String buildCommit,
      String cacheRevision,
      String contentRevision,
      ObjectNode initialState,
      List<SemanticAction> actions,
      ObjectNode expectations,
      ObjectNode tolerances) {
    ObjectNode seedBasis =
        identityBasis(
            recordingChecksum,
            sessionChecksum,
            curation,
            adapterVersion,
            buildCommit,
            cacheRevision,
            contentRevision,
            "DERIVED_DEFAULT_SEED",
            initialState,
            actions,
            expectations,
            tolerances);
    return sha256Hex(projectionService.canonicalJsonBytes(seedBasis)).substring(0, 16);
  }

  private UUID deriveManifestId(
      String recordingChecksum,
      String sessionChecksum,
      RecordingCuration curation,
      String adapterVersion,
      String buildCommit,
      String cacheRevision,
      String contentRevision,
      String effectiveSeed,
      ObjectNode initialState,
      List<SemanticAction> actions,
      ObjectNode expectations,
      ObjectNode tolerances) {
    byte[] digest =
        sha256Hex(
                projectionService.canonicalJsonBytes(
                    identityBasis(
                        recordingChecksum,
                        sessionChecksum,
                        curation,
                        adapterVersion,
                        buildCommit,
                        cacheRevision,
                        contentRevision,
                        effectiveSeed,
                        initialState,
                        actions,
                        expectations,
                        tolerances)))
            .getBytes(StandardCharsets.UTF_8);
    // The hex-digest string (not raw bytes) feeds the name-based UUID so the derivation is
    // stable across JDKs and independent of digest-array aliasing.
    return UUID.nameUUIDFromBytes(digest);
  }

  private List<SemanticAction> compileActions(
      UUID curationId,
      List<TickSnapshot> orderedSnapshots,
      Map<Long, TickSnapshot> snapshotsByTick) {
    // Defensive copy: repositories may legally return immutable lists and
    // the deterministic order guarantee requires sorting here.
    List<CuratedAction> curatedActions =
        new ArrayList<>(
            curatedActionRepository.findByCurationIdOrderByTickAscSequenceAsc(curationId));
    // Defensive re-sort: guarantee (tick, sequence) order even if repository changes.
    curatedActions.sort(
        Comparator.comparingLong(CuratedAction::getTick)
            .thenComparingInt(CuratedAction::getSequence));

    // ── ONE shared action pipeline with preflight ──
    // Status policy (only explicitly IGNORED rows are dropped; UNSUPPORTED and UNKNOWN
    // statuses fail loudly), semantic-action-type support, and every accepted action's
    // per-tick evidence all come from the ONE shared validator, so a curation can never
    // pass preflight yet fail compilation or silently diverge.
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
        ReplayEvidenceValidator.validateCurationActions(rows, snapshotsByTick)) {
      throw ReplayException.invalid(violation.code(), violation.message());
    }

    // ── deterministic construction pass (evidence already proven) ──
    List<SemanticAction> result = new ArrayList<>();
    long lastActionTick = Long.MIN_VALUE;
    for (CuratedAction ca : curatedActions) {
      if (ReplayEvidenceValidator.STATUS_IGNORED.equals(ca.getStatus())) {
        continue;
      }
      String type = ca.getSemanticActionType();
      if ("WAIT_UNTIL_TICK".equals(type)) {
        result.add(new WaitUntilTickAction(ca.getTick()));
        lastActionTick = ca.getTick();
        continue;
      }
      String option = ReplayEvidenceValidator.extractOption(ca.getArgumentsJson());
      if (lastActionTick != Long.MIN_VALUE && ca.getTick() > lastActionTick + 1) {
        result.add(new WaitUntilTickAction(ca.getTick()));
      }
      result.add(new InteractNpcAction(ca.getTargetStableId(), option));
      lastActionTick = ca.getTick();
    }
    return result;
  }

  /** Ensures the compiled manifest never contains an action the live worker failed to declare. */
  private void verifySupportedActions(List<SemanticAction> actions, List<String> supportedActions) {
    if (supportedActions == null) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          false,
          "xRSPS capability supportedActions is missing",
          "Refresh the capability handshake before compiling a manifest");
    }
    for (SemanticAction action : actions) {
      if (!supportedActions.contains(action.type())) {
        throw new ReplayException(
            ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
            false,
            "Compiled action " + action.type() + " is not declared by the live xRSPS worker",
            "Use a worker that advertises every action required by the curation");
      }
    }
  }

  /**
   * Builds initial world state STRICTLY from recorded evidence. Every referenced NPC carries an
   * explicit stable id, a positive numeric npc id, numeric position coordinates, an explicit plane,
   * and a supported typed spot type; inventory items carry explicit slots. Missing or
   * fabricated-looking evidence FAILS compilation — the compiler never invents fallback ids, slots,
   * world ids, or collision revisions.
   */
  private ObjectNode buildInitialState(TickSnapshot first) {
    ObjectNode state = objectMapper.createObjectNode();

    JsonNode ps = first.getPlayerState();
    if (ps == null || ps.isNull()) {
      throw ReplayException.invalid(
          ReplayErrorCode.PLAYER_STATE_MISSING, "Earliest tick snapshot has no player state");
    }

    // World identity comes from the RECORDING (playerState.world / collisionRevision). A
    // snapshot without world evidence cannot back a causal simulation: guessing world 0 would
    // fabricate provenance.
    JsonNode worldNode = ps.has("world") && ps.get("world").isObject() ? ps.get("world") : ps;
    JsonNode worldIdNode = worldNode.has("worldId") ? worldNode.get("worldId") : null;
    if (worldIdNode == null || !worldIdNode.canConvertToInt() || worldIdNode.asInt() <= 0) {
      throw ReplayException.invalid(
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Recorded world id missing or invalid at start tick");
    }
    state.put("worldId", worldIdNode.asInt());
    JsonNode collisionNode = ps.get("collisionRevision");
    if (collisionNode == null || !collisionNode.isTextual() || collisionNode.asText().isBlank()) {
      throw ReplayException.invalid(
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Recorded collision revision missing at start tick");
    }
    state.put("collisionRevision", collisionNode.asText());

    ObjectNode player = state.putObject("player");
    JsonNode pos =
        ps.has("position")
            ? ps.get("position")
            : ps.has("worldPoint") ? ps.get("worldPoint") : null;
    if (pos == null
        || !pos.isObject()
        || !pos.has("x")
        || !pos.has("y")
        || !pos.has("plane")
        || !pos.get("x").isNumber()
        || !pos.get("y").isNumber()
        || !pos.get("plane").isNumber()) {
      throw ReplayException.invalid(
          ReplayErrorCode.POSITION_MISSING,
          "Recorded player position with numeric x/y/plane missing at start tick");
    }
    player
        .putObject("position")
        .put("x", pos.path("x").asInt())
        .put("y", pos.path("y").asInt())
        .put("plane", pos.path("plane").asInt());
    ObjectNode skills = player.putObject("skills");
    JsonNode skillsNode = ps.path("skills");
    if (skillsNode.isObject()) {
      var fields = skillsNode.fields();
      while (fields.hasNext()) {
        var e = fields.next();
        JsonNode s = e.getValue();
        skills
            .putObject(e.getKey())
            .put("level", s.path("level").asInt(1))
            .put("xp", s.path("xp").asInt(0));
      }
    }
    ArrayNode inventory = player.putArray("inventory");
    JsonNode inv = first.getInventory();
    if (inv != null && inv.isArray()) {
      for (JsonNode item : inv) {
        int itemId =
            item.has("itemId")
                ? item.get("itemId").asInt()
                : item.has("id") ? item.get("id").asInt() : item.asInt(-1);
        if (itemId <= 0) {
          continue;
        }
        // Explicit slot REQUIRED: array position is not slot evidence.
        JsonNode slotNode = item.get("slot");
        if (slotNode == null || !slotNode.isInt() || slotNode.asInt() < 0) {
          throw ReplayException.invalid(
              ReplayErrorCode.INVENTORY_SLOT_MISSING,
              "Recorded inventory item lacks an explicit slot");
        }
        inventory
            .addObject()
            .put("slot", slotNode.asInt())
            .put("itemId", itemId)
            .put("quantity", item.path("quantity").asInt(1));
      }
    } else {
      throw ReplayException.invalid(
          ReplayErrorCode.INVENTORY_SLOT_MISSING,
          "Earliest tick snapshot carries no inventory array");
    }
    player.set("equipment", buildEquipment(first));

    ArrayNode npcsNode = state.putArray("npcs");
    JsonNode npcs = first.getNpcs();
    if (npcs != null && npcs.isArray()) {
      for (JsonNode npc : npcs) {
        ObjectNode n = npcsNode.addObject();
        JsonNode stable = npc.get("stableId");
        // Compiler-generated fallback stable ids are FORBIDDEN: an unresolved id means the
        // recording pipeline failed and compilation must block, not synthesize identity.
        if (stable == null || !stable.isTextual() || stable.asText().isBlank()) {
          throw ReplayException.invalid(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED, "Recorded NPC has no explicit stable id");
        }
        n.put("stableId", stable.asText());
        int npcId = npc.has("npcId") ? npc.path("npcId").asInt(-1) : npc.path("id").asInt(-1);
        if (npcId <= 0) {
          throw ReplayException.invalid(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "Recorded NPC " + stable.asText() + " has no positive numeric npc id");
        }
        n.put("npcId", npcId);
        JsonNode p = npc.path("position");
        if (!p.isObject()) p = npc.path("worldPoint");
        if (!p.isObject()
            || !p.has("x")
            || !p.has("y")
            || !p.has("plane")
            || !p.get("x").isNumber()
            || !p.get("y").isNumber()
            || !p.get("plane").isNumber()) {
          throw ReplayException.invalid(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "Recorded NPC " + stable.asText() + " lacks numeric position/plane");
        }
        n.putObject("position")
            .put("x", p.path("x").asInt())
            .put("y", p.path("y").asInt())
            .put("plane", p.path("plane").asInt());
        // spotType defaults are forbidden: fishing spots must be typed AND within the
        // ONE supported v1 set shared with preflight evidence validation.
        String spotType = npc.path("spotType").asText(null);
        if (spotType == null || !ReplayEvidenceValidator.SUPPORTED_SPOT_TYPES.contains(spotType)) {
          throw ReplayException.invalid(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "NPC " + n.get("stableId").asText() + " lacks a supported spotType");
        }
        n.put("spotType", spotType);
      }
    } else {
      throw ReplayException.invalid(
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Initial world contains no recorded NPC array");
    }
    state.putArray("objects");
    state.putArray("groundItems");
    return state;
  }

  /**
   * Equipment capture with EXPLICIT slots: every entry needs an integer slot and a positive item
   * id. A missing equipment array means the recorder did not capture equipment and compilation
   * blocks instead of assuming nothing was worn.
   */
  private ArrayNode buildEquipment(TickSnapshot first) {
    JsonNode equipment = first.getEquipment();
    if (equipment == null || !equipment.isArray()) {
      throw ReplayException.invalid(
          ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
          "Earliest tick snapshot carries no equipment capture");
    }
    ArrayNode out = objectMapper.createArrayNode();
    for (JsonNode piece : equipment) {
      int itemId =
          piece.has("itemId")
              ? piece.get("itemId").asInt()
              : piece.has("id") ? piece.get("id").asInt() : -1;
      if (itemId <= 0) {
        continue;
      }
      JsonNode slotNode = piece.get("slot");
      if (slotNode == null || !slotNode.isInt() || slotNode.asInt() < 0) {
        throw ReplayException.invalid(
            ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
            "Recorded equipment item lacks an explicit slot");
      }
      out.addObject()
          .put("slot", slotNode.asInt())
          .put("itemId", itemId)
          .put("quantity", piece.path("quantity").asInt(1));
    }
    return out;
  }

  /** Expectation map: {"pointer": {"expected": value, "level": "HARD"}}. */
  private ObjectNode buildExpectations(TickSnapshot last) {
    ObjectNode expectations = objectMapper.createObjectNode();
    JsonNode ps = last.getPlayerState();
    if (ps != null && ps.isObject()) {
      JsonNode pos =
          ps.has("position")
              ? ps.get("position")
              : ps.has("worldPoint") ? ps.get("worldPoint") : null;
      if (pos != null && pos.isObject()) {
        ObjectNode spec = expectations.putObject("player.position");
        spec.putObject("expected")
            .put("x", pos.path("x").asInt())
            .put("y", pos.path("y").asInt())
            .put("plane", pos.path("plane").asInt());
        spec.put("level", "HARD");
      }
      JsonNode skillsNode = ps.path("skills");
      if (skillsNode.isObject() && skillsNode.has("fishing")) {
        ObjectNode fishing =
            skillsNode.get("fishing").isObject() ? (ObjectNode) skillsNode.get("fishing") : null;
        if (fishing != null && fishing.has("xp")) {
          ObjectNode spec = expectations.putObject("player.skills.fishing.xp");
          spec.put("expected", fishing.path("xp").asInt());
          spec.put("level", "SOFT");
        }
      }
    }
    return expectations;
  }

  /**
   * Canonical session evidence: EVERY snapshot in tick order with all replay-relevant content —
   * player state, skills/XP, inventory, NPCs, objects, ground items, schema version and tick. This
   * document is what {@code debugSessionChecksum} hashes; changing any recorded evidence byte
   * changes manifest identity.
   */
  private ObjectNode buildSessionEvidence(DebugSession session, List<TickSnapshot> snapshots) {
    ObjectNode doc = objectMapper.createObjectNode();
    doc.put("sessionId", session.getSessionId());
    doc.put("start", String.valueOf(session.getStartTime()));
    doc.put("end", String.valueOf(session.getEndTime()));
    ArrayNode ticks = doc.putArray("snapshots");
    for (TickSnapshot snap : snapshots) {
      ObjectNode n = ticks.addObject();
      n.put("tick", snap.getTick());
      if (snap.getSchemaVersion() != null) {
        n.put("schemaVersion", snap.getSchemaVersion());
      }
      n.set("playerState", nullSafe(snap.getPlayerState()));
      n.set("inventory", nullSafe(snap.getInventory()));
      n.set("equipment", nullSafe(snap.getEquipment()));
      n.set("npcs", nullSafe(snap.getNpcs()));
      n.set("gameObjects", nullSafe(snap.getGameObjects()));
      n.set("groundItems", nullSafe(snap.getGroundItems()));
      n.set("cooldowns", nullSafe(snap.getCooldowns()));
      n.set("quest", nullSafe(snap.getQuest()));
    }
    return doc;
  }

  private static JsonNode nullSafe(JsonNode node) {
    return node != null ? node : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
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
