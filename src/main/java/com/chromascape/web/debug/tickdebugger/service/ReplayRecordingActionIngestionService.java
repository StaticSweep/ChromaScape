package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingActionRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates and persists typed action envelopes from a TICK_BATCH.
 *
 * <p>Writes are idempotent on {@code (session,tick,sequence)}. An exact duplicate is accepted;
 * any content conflict is rejected so a retry cannot silently rewrite replay evidence.
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
          "MICRO6_SEMANTIC_DISPATCH",
          "MICRO6_LEGACY_CONVERSION",
          "OPERATOR_SUPPLIED",
          "DEFAULTED");
  private static final int MAX_ACTION_JSON_BYTES = 64 * 1024;

  private final ReplayRecordingActionRepository repository;
  private final ObjectMapper objectMapper;
  private final CanonicalProjectionService canonicalProjection;

  public ReplayRecordingActionIngestionService(
      ReplayRecordingActionRepository repository,
      ObjectMapper objectMapper,
      CanonicalProjectionService canonicalProjection) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.canonicalProjection = canonicalProjection;
  }

  /** Ingests all actions from one batch in their declared same-tick order. */
  @Transactional
  public List<ReplayRecordingAction> ingest(
      TickBatchMessage batch, String recordingId) {
    if (batch == null || batch.getActions() == null || batch.getActions().isEmpty()) {
      return List.of();
    }
    if (batch.getSessionId() == null || batch.getSessionId().isBlank()) {
      throw new IllegalArgumentException("Typed actions require a nonblank batch sessionId");
    }

    List<ReplayRecordingAction> persisted = new java.util.ArrayList<>();
    int previousSequence = -1;
    for (RecordingAction action : batch.getActions()) {
      validateEnvelope(action, batch.getSessionId(), batch.getTick());
      if (action.getTick() == batch.getTick() && action.getSequence() < previousSequence) {
        throw new IllegalArgumentException("Typed actions must preserve same-tick sequence order");
      }
      previousSequence = action.getSequence();
      persisted.add(upsert(action, recordingId));
    }
    return List.copyOf(persisted);
  }

  /** Validates an envelope without performing a database write. */
  public void validateEnvelope(RecordingAction action, String expectedSessionId, long batchTick) {
    if (action == null) {
      throw new IllegalArgumentException("Typed action must not be null");
    }
    if (!RecordingAction.SCHEMA.equals(action.getSchema())) {
      throw new IllegalArgumentException("Unsupported typed action schema");
    }
    if (action.getSessionId() == null || !action.getSessionId().equals(expectedSessionId)) {
      throw new IllegalArgumentException("Typed action sessionId does not match the batch");
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
    BigDecimal confidence = action.getConfidence();
    if (confidence == null || confidence.compareTo(BigDecimal.ZERO) < 0
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
      if (!(kind instanceof String) || !TARGET_KINDS.contains(kind)
          || !(stableId instanceof String) || ((String) stableId).isBlank()) {
        throw new IllegalArgumentException("Typed action target must contain a closed kind and stableId");
      }
    }
    Map<String, Object> arguments = action.getArguments();
    JsonNode targetJson = target == null ? null : objectMapper.valueToTree(target);
    JsonNode argumentsJson = objectMapper.valueToTree(arguments == null ? Map.of() : arguments);
    if (jsonBytes(targetJson) > MAX_ACTION_JSON_BYTES
        || jsonBytes(argumentsJson) > MAX_ACTION_JSON_BYTES) {
      throw new IllegalArgumentException("Typed action JSON payload is too large");
    }
  }

  private ReplayRecordingAction upsert(RecordingAction action, String recordingId) {
    ReplayRecordingAction candidate = toEntity(action, recordingId);
    var existing = repository.findBySessionIdAndTickAndSequence(
        candidate.getSessionId(), candidate.getTick(), candidate.getSequence());
    if (existing.isPresent()) {
      if (candidate.getContentChecksum().equals(existing.get().getContentChecksum())) {
        return existing.get();
      }
      throw new IllegalArgumentException(
          "Conflicting typed action for session/tick/sequence "
              + candidate.getSessionId() + "/" + candidate.getTick() + "/" + candidate.getSequence());
    }
    try {
      return repository.saveAndFlush(candidate);
    } catch (DataIntegrityViolationException race) {
      ReplayRecordingAction winner = repository
          .findBySessionIdAndTickAndSequence(
              candidate.getSessionId(), candidate.getTick(), candidate.getSequence())
          .orElseThrow(() -> race);
      if (candidate.getContentChecksum().equals(winner.getContentChecksum())) {
        return winner;
      }
      throw new IllegalArgumentException("Conflicting concurrent typed action delivery", race);
    }
  }

  private ReplayRecordingAction toEntity(RecordingAction action, String recordingId) {
    ReplayRecordingAction entity = new ReplayRecordingAction();
    entity.setSessionId(action.getSessionId());
    entity.setRecordingId(recordingId == null || recordingId.isBlank() ? null : recordingId);
    entity.setTick(action.getTick());
    entity.setSequence(action.getSequence());
    entity.setSchema(action.getSchema());
    entity.setActionType(action.getActionType());
    Map<String, Object> target = action.getTarget();
    entity.setTargetKind(target == null ? null : asString(target.get("kind")));
    entity.setTargetStableId(target == null ? null : asString(target.get("stableId")));
    entity.setTargetJson(target == null ? null : objectMapper.valueToTree(target));
    entity.setArgumentsJson(objectMapper.valueToTree(
        action.getArguments() == null ? Map.of() : action.getArguments()));
    entity.setSource(action.getSource());
    entity.setConfidence(action.getConfidence());
    entity.setCapturedAt(LocalDateTime.ofInstant(Instant.parse(action.getCapturedAt()), ZoneOffset.UTC));
    entity.setIngestedAt(LocalDateTime.now(ZoneOffset.UTC));

    Map<String, Object> content = new LinkedHashMap<>();
    content.put("schema", action.getSchema());
    content.put("sessionId", action.getSessionId());
    content.put("tick", action.getTick());
    content.put("sequence", action.getSequence());
    content.put("actionType", action.getActionType());
    content.put("target", target == null ? Map.of() : target);
    content.put("arguments", action.getArguments() == null ? Map.of() : action.getArguments());
    content.put("source", action.getSource());
    content.put("confidence", action.getConfidence());
    content.put("capturedAt", action.getCapturedAt());
    entity.setContentChecksum(canonicalProjection.sha256Hex(
        canonicalProjection.canonicalJsonBytes(objectMapper.valueToTree(content))));
    return entity;
  }

  private static String asString(Object value) {
    return value instanceof String ? (String) value : null;
  }

  private static int jsonBytes(JsonNode node) {
    return node == null ? 0 : node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }
}
