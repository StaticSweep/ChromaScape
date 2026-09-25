package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingActionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates and persists typed action envelopes from a TICK_BATCH.
 *
 * <p>Writes are idempotent on {@code (session,tick,sequence)}. An exact duplicate is accepted; any
 * content conflict is rejected so a retry cannot silently rewrite replay evidence.
 */
@Service
public class ReplayRecordingActionIngestionService {

  private static final Set<String> ACTION_TYPES =
      Set.of(
          "WAIT_UNTIL_TICK",
          "WALK_TO",
          "INTERACT_NPC",
          "INTERACT_OBJECT",
          "INTERACT_GROUND_ITEM",
          "INTERACT_INVENTORY_ITEM",
          "INTERACT_WIDGET",
          "SELECT_DIALOGUE_OPTION",
          "CAST_SPELL",
          "EQUIP_ITEM");
  private static final Set<String> TARGET_KINDS =
      Set.of("NPC", "OBJECT", "GROUND_ITEM", "INVENTORY_ITEM", "WIDGET", "TILE", "PLAYER");
  private static final Set<String> SOURCES =
      Set.of(
          "MICRO6_SEMANTIC_DISPATCH", "MICRO6_LEGACY_CONVERSION", "OPERATOR_SUPPLIED", "DEFAULTED");
  private static final Set<String> PROVENANCES =
      Set.of(
          RecordingAction.PROVENANCE_CAPTURED,
          RecordingAction.PROVENANCE_INFERRED,
          RecordingAction.PROVENANCE_OPERATOR_SUPPLIED,
          RecordingAction.PROVENANCE_DEFAULTED);
  private static final Pattern CONTENT_CHECKSUM = Pattern.compile("^sha256:[0-9a-f]{64}$");
  private static final int MAX_RECORDING_ID_LENGTH = 100;
  private static final int MAX_ACTION_JSON_BYTES = 64 * 1024;

  private final ReplayRecordingActionRepository repository;
  private final ObjectMapper objectMapper;
  private final TickBatchCanonicalChecksumService checksumService;

  @org.springframework.beans.factory.annotation.Autowired
  public ReplayRecordingActionIngestionService(
      ReplayRecordingActionRepository repository,
      ObjectMapper objectMapper,
      TickBatchCanonicalChecksumService checksumService) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.checksumService = checksumService;
  }

  /** Compatibility constructor for direct unit tests that predate the shared protocol writer. */
  public ReplayRecordingActionIngestionService(
      ReplayRecordingActionRepository repository,
      ObjectMapper objectMapper,
      com.chromascape.web.replay.projection.CanonicalProjectionService ignored) {
    this(repository, objectMapper, new TickBatchCanonicalChecksumService(objectMapper));
  }

  /** Ingests all actions from one batch in their declared same-tick order. */
  @Transactional
  public List<ReplayRecordingAction> ingest(TickBatchMessage batch, String recordingId) {
    if (batch == null || batch.getActions() == null || batch.getActions().isEmpty()) {
      return List.of();
    }
    if (batch.getSessionId() == null || batch.getSessionId().isBlank()) {
      throw new IllegalArgumentException("Typed actions require a nonblank batch sessionId");
    }

    List<ReplayRecordingAction> persisted = new java.util.ArrayList<>();
    long previousTick = -1;
    int previousSequence = -1;
    for (RecordingAction action : batch.getActions()) {
      validateEnvelope(action, batch.getSessionId(), batch.getTick(), recordingId);
      if (action.getTick() < previousTick
          || (action.getTick() == previousTick && action.getSequence() <= previousSequence)) {
        throw new IllegalArgumentException(
            "Typed actions must preserve strict (tick, sequence) order");
      }
      // Sequence ordering restarts naturally when the tick increases. A batch may contain a
      // continuation captured from an earlier source tick, so only the lexicographic tuple is
      // validated here; requiring sequence zero on every new tick would reject (10,2) -> (11,0).
      previousTick = action.getTick();
      previousSequence = action.getSequence();
      persisted.add(upsert(action, recordingId));
    }
    return List.copyOf(persisted);
  }

  /** Validates an envelope without performing a database write. */
  public void validateEnvelope(RecordingAction action, String expectedSessionId, long batchTick) {
    validateEnvelope(action, expectedSessionId, batchTick, null, false);
  }

  private void validateEnvelope(
      RecordingAction action,
      String expectedSessionId,
      long batchTick,
      String expectedRecordingId) {
    validateEnvelope(action, expectedSessionId, batchTick, expectedRecordingId, true);
  }

  private void validateEnvelope(
      RecordingAction action,
      String expectedSessionId,
      long batchTick,
      String expectedRecordingId,
      boolean enforceRecordingBinding) {
    if (action == null) {
      throw new IllegalArgumentException("Typed action must not be null");
    }
    if (!RecordingAction.SCHEMA.equals(action.getSchema())) {
      throw new IllegalArgumentException("Unsupported typed action schema");
    }
    if (action.getSessionId() == null || !action.getSessionId().equals(expectedSessionId)) {
      throw new IllegalArgumentException("Typed action sessionId does not match the batch");
    }
    String actionRecordingId = normalizeOptionalIdentifier(action.getRecordingId());
    String batchRecordingId = normalizeOptionalIdentifier(expectedRecordingId);
    if (action.getRecordingId() != null && actionRecordingId == null) {
      throw new IllegalArgumentException("Typed action recordingId must be nonblank and bounded");
    }
    if (enforceRecordingBinding && !Objects.equals(actionRecordingId, batchRecordingId)) {
      throw new IllegalArgumentException("Typed action recordingId does not match the session");
    }
    if (action.getTick() < 0 || action.getTick() > batchTick) {
      throw new IllegalArgumentException("Typed action tick is outside the enclosing batch");
    }
    if (action.getSequence() < 0) {
      throw new IllegalArgumentException("Typed action sequence must be nonnegative");
    }
    if (action.getActionType() == null || !ACTION_TYPES.contains(action.getActionType())) {
      throw new IllegalArgumentException("Unsupported typed action type");
    }
    if (action.getSource() == null || !SOURCES.contains(action.getSource())) {
      throw new IllegalArgumentException("Unsupported typed action source");
    }
    if (action.getProvenance() == null || !PROVENANCES.contains(action.getProvenance())) {
      throw new IllegalArgumentException("Unsupported typed action provenance");
    }
    BigDecimal confidence = action.getConfidence();
    if (confidence == null
        || confidence.compareTo(BigDecimal.ZERO) < 0
        || confidence.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("Typed action confidence must be between 0 and 1");
    }
    if (action.getCapturedAt() == null || action.getCapturedAt().isBlank()) {
      throw new IllegalArgumentException("Typed action capturedAt is required");
    }
    try {
      Instant.parse(action.getCapturedAt());
    } catch (Exception e) {
      throw new IllegalArgumentException("Typed action capturedAt must be ISO-8601", e);
    }

    Map<String, Object> target = action.getTarget();
    if (!"WAIT_UNTIL_TICK".equals(action.getActionType())) {
      if (target == null || target.isEmpty()) {
        throw new IllegalArgumentException("Non-wait typed actions require a target");
      }
      Object kind = target.get("kind");
      Object stableId = target.get("stableId");
      if (!(kind instanceof String)
          || !TARGET_KINDS.contains(kind)
          || !(stableId instanceof String)
          || ((String) stableId).isBlank()) {
        throw new IllegalArgumentException(
            "Typed action target must contain a closed kind and stableId");
      }
    }
    Map<String, Object> arguments = action.getArguments();
    JsonNode targetJson = target == null ? null : objectMapper.valueToTree(target);
    JsonNode argumentsJson = objectMapper.valueToTree(arguments == null ? Map.of() : arguments);
    if (jsonBytes(targetJson) > MAX_ACTION_JSON_BYTES
        || jsonBytes(argumentsJson) > MAX_ACTION_JSON_BYTES) {
      throw new IllegalArgumentException("Typed action JSON payload is too large");
    }
    if (action.getContentChecksum() == null
        || !CONTENT_CHECKSUM.matcher(action.getContentChecksum()).matches()) {
      throw new IllegalArgumentException(
          "Typed action contentChecksum must be sha256:<64 lowercase hex>");
    }
    String expectedChecksum = computeContentChecksum(action);
    if (!action.getContentChecksum().equals(expectedChecksum)) {
      throw new IllegalArgumentException(
          "Typed action contentChecksum does not match the canonical recording-action/v1 envelope");
    }
  }

  private ReplayRecordingAction upsert(RecordingAction action, String recordingId) {
    ReplayRecordingAction candidate = toEntity(action, recordingId);
    var existing =
        repository.findBySessionIdAndTickAndSequence(
            candidate.getSessionId(), candidate.getTick(), candidate.getSequence());
    if (existing.isPresent()) {
      return sameOrConflict(candidate, existing.get());
    }

    int inserted =
        repository.insertIfAbsent(
            candidate.getSessionId(),
            candidate.getRecordingId(),
            candidate.getTick(),
            candidate.getSequence(),
            candidate.getSchema(),
            candidate.getActionType(),
            candidate.getTargetKind(),
            candidate.getTargetStableId(),
            candidate.getTargetJson() == null ? null : candidate.getTargetJson().toString(),
            candidate.getArgumentsJson().toString(),
            candidate.getSource(),
            candidate.getProvenance(),
            candidate.getConfidence(),
            candidate.getCapturedAt(),
            candidate.getIngestedAt(),
            candidate.getContentChecksum());
    if (inserted == 1) {
      return repository
          .findBySessionIdAndTickAndSequence(
              candidate.getSessionId(), candidate.getTick(), candidate.getSequence())
          .orElseThrow(() -> new IllegalStateException("Inserted action could not be read back"));
    }

    // The native ON CONFLICT statement leaves this transaction valid. Read the winner and compare
    // the complete immutable checksum rather than querying after a failed flush/rollback-only TX.
    return repository
        .findBySessionIdAndTickAndSequence(
            candidate.getSessionId(), candidate.getTick(), candidate.getSequence())
        .map(winner -> sameOrConflict(candidate, winner))
        .orElseThrow(() -> new IllegalStateException("Action conflict could not be resolved"));
  }

  private ReplayRecordingAction sameOrConflict(
      ReplayRecordingAction candidate, ReplayRecordingAction winner) {
    if (candidate.getContentChecksum().equals(winner.getContentChecksum())) {
      return winner;
    }
    throw new IllegalArgumentException(
        "ACTION_CHECKSUM_CONFLICT for session/tick/sequence "
            + candidate.getSessionId()
            + "/"
            + candidate.getTick()
            + "/"
            + candidate.getSequence());
  }

  private ReplayRecordingAction toEntity(RecordingAction action, String recordingId) {
    ReplayRecordingAction entity = new ReplayRecordingAction();
    entity.setSessionId(action.getSessionId());
    entity.setRecordingId(normalizeOptionalIdentifier(recordingId));
    entity.setTick(action.getTick());
    entity.setSequence(action.getSequence());
    entity.setSchema(action.getSchema());
    entity.setActionType(action.getActionType());
    Map<String, Object> target = action.getTarget();
    entity.setTargetKind(target == null ? null : asString(target.get("kind")));
    entity.setTargetStableId(target == null ? null : asString(target.get("stableId")));
    entity.setTargetJson(target == null ? null : objectMapper.valueToTree(target));
    entity.setArgumentsJson(
        objectMapper.valueToTree(action.getArguments() == null ? Map.of() : action.getArguments()));
    entity.setSource(action.getSource());
    entity.setProvenance(action.getProvenance());
    entity.setConfidence(action.getConfidence());
    entity.setCapturedAt(
        LocalDateTime.ofInstant(Instant.parse(action.getCapturedAt()), ZoneOffset.UTC));
    entity.setIngestedAt(LocalDateTime.now(ZoneOffset.UTC));

    // The producer checksum was verified before this method was called. Retaining that exact
    // address makes duplicate/conflict handling compare the same immutable content identity that
    // Micro6 used, rather than silently replacing it with a server-local projection.
    entity.setContentChecksum(action.getContentChecksum());
    return entity;
  }

  /**
   * Computes the recording-action/v1 producer checksum. The field set and null handling mirror
   * Micro6's Gson checksum document exactly; {@code contentChecksum} itself is excluded.
   */
  String computeContentChecksum(RecordingAction action) {
    com.fasterxml.jackson.databind.node.ObjectNode content = objectMapper.createObjectNode();
    content.put("schema", action.getSchema());
    if (action.getRecordingId() == null) content.putNull("recordingId");
    else content.put("recordingId", action.getRecordingId());
    content.put("sessionId", action.getSessionId());
    content.put("tick", action.getTick());
    content.put("sequence", action.getSequence());
    content.put("actionType", action.getActionType());
    content.set(
        "target",
        objectMapper.valueToTree(action.getTarget() == null ? Map.of() : action.getTarget()));
    content.set(
        "arguments",
        objectMapper.valueToTree(action.getArguments() == null ? Map.of() : action.getArguments()));
    content.put("source", action.getSource());
    content.put("provenance", action.getProvenance());
    content.put("confidence", action.getConfidence());
    content.put("capturedAt", action.getCapturedAt());
    return checksumService.checksum(content);
  }

  private static String asString(Object value) {
    return value instanceof String ? (String) value : null;
  }

  private static String normalizeOptionalIdentifier(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    return normalized.isEmpty() || normalized.length() > MAX_RECORDING_ID_LENGTH
        ? null
        : normalized;
  }

  private static int jsonBytes(JsonNode node) {
    return node == null
        ? 0
        : node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }
}
