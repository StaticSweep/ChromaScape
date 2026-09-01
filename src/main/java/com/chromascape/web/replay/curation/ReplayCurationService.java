package com.chromascape.web.replay.curation;

import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.curation.dto.CreateCurationRequest;
import com.chromascape.web.replay.curation.dto.CuratedActionDto;
import com.chromascape.web.replay.curation.dto.CurationResponse;
import com.chromascape.web.replay.curation.dto.UpdateCurationRequest;
import com.chromascape.web.replay.curation.entity.CuratedAction;
import com.chromascape.web.replay.curation.entity.CurationRole;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReplayCurationService {

  private static final Set<String> ALLOWED_STATUSES =
      Set.of("ACCEPTED_LABEL", "IGNORED", "UNSUPPORTED");
  private static final int MAX_STABLE_TARGET_LENGTH = 200;

  private final RecordingCurationRepository curationRepository;
  private final CuratedActionRepository actionRepository;
  private final ReplayRecordingResolver resolver;
  private final ObjectMapper objectMapper;

  public ReplayCurationService(
      RecordingCurationRepository curationRepository,
      CuratedActionRepository actionRepository,
      ReplayRecordingResolver resolver,
      ObjectMapper objectMapper) {
    this.curationRepository = curationRepository;
    this.actionRepository = actionRepository;
    this.resolver = resolver;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<CurationResponse> listCurations(String recordingId, String ownerId) {
    // Owner-scoped: curations authored by other operators are invisible.
    List<RecordingCuration> curations =
        ownerId != null
            ? curationRepository.findByRecordingIdAndCreatedByOrderByVersionDesc(
                recordingId, ownerId)
            : curationRepository.findByRecordingIdOrderByVersionDesc(recordingId);
    return curations.stream().map(this::toResponse).collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public CurationResponse getCuration(UUID curationId, String ownerId) {
    RecordingCuration curation = requireOwned(curationId, ownerId);
    return toResponse(curation);
  }

  public CurationResponse create(
      String recordingId, CreateCurationRequest request, String createdBy) {
    validateTickRange(request.tickRangeStart(), request.tickRangeEnd());
    if (request.actionLabels() != null) {
      validateLabels(request.tickRangeStart(), request.tickRangeEnd(), request.actionLabels());
    }
    var resolved = resolver.resolve(recordingId);

    int version = (int) curationRepository.countByRecordingId(recordingId) + 1;

    RecordingCuration curation = new RecordingCuration();
    curation.setId(UUID.randomUUID());
    curation.setRecordingId(recordingId);
    curation.setDebugSessionId(resolved.debugSession().getSessionId());
    curation.setVersion(version);
    curation.setState(CurationState.DRAFT);
    curation.setRole(request.role() != null ? request.role() : CurationRole.HELD_OUT);
    curation.setSchemaVersion(1);
    curation.setContentJson(
        buildContentJson(
            request.tickRangeStart(), request.tickRangeEnd(),
            request.initialStateOverrides(), request.actionLabels()));
    curation.setContentChecksum(computeChecksum(curation.getContentJson()));
    curation.setCreatedBy(createdBy);
    // Durable ordering comes from the PostgreSQL clock — never LocalDateTime.now().
    curation.setCreatedAt(curationRepository.currentTimestamp());

    curation = curationRepository.save(curation);
    replaceActionRows(curation.getId(), request.actionLabels());

    return toResponse(refetch(curation.getId()));
  }

  /**
   * Updates a DRAFT curation. Supplied {@code actionLabels} fully replace the draft's label
   * collection; content JSON and checksum are rebuilt atomically in the same transaction.
   */
  public CurationResponse update(UUID curationId, UpdateCurationRequest request, String updatedBy) {
    RecordingCuration curation = requireOwned(curationId, updatedBy);

    if (curation.getState() != CurationState.DRAFT) {
      throw ReplayException.conflict(
          ReplayErrorCode.CURATION_IMMUTABLE, "Only DRAFT curations can be updated");
    }
    if (request == null
        || (request.role() == null
            && request.initialStateOverrides() == null
            && request.actionLabels() == null)) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR,
          "Update must supply role, initialStateOverrides or actionLabels");
    }

    Long rangeStart = extractRangeStart(curation.getContentJson());
    Long rangeEnd = extractRangeEnd(curation.getContentJson());
    long start = rangeStart != null ? rangeStart : 0L;
    long end = rangeEnd != null ? rangeEnd : start;

    JsonNode overrides = request.initialStateOverrides();
    List<CuratedActionDto> labels = request.actionLabels();
    if (request.role() != null) {
      curation.setRole(request.role());
    }
    boolean contentChanged = overrides != null || labels != null;
    if (contentChanged) {
      validateLabels(start, end, labels);
      curation.setContentJson(
          buildContentJson(
              start,
              end,
              overrides != null ? overrides : extractOverrides(curation.getContentJson()),
              labels));
      curation.setContentChecksum(computeChecksum(curation.getContentJson()));
    }

    curation = curationRepository.save(curation);
    if (labels != null) {
      replaceActionRows(curation.getId(), labels);
    }
    return toResponse(refetch(curation.getId()));
  }

  public CurationResponse publish(UUID curationId, String publishedBy) {
    RecordingCuration curation = requireOwned(curationId, publishedBy);

    if (curation.getState() != CurationState.DRAFT) {
      throw ReplayException.conflict(
          ReplayErrorCode.CURATION_IMMUTABLE, "Only DRAFT curations can be published");
    }
    if (curation.getContentChecksum() == null || curation.getContentChecksum().isBlank()) {
      throw ReplayException.invalid(
          ReplayErrorCode.CURATION_NOT_PUBLISHED, "Curation content is empty");
    }

    curation.setState(CurationState.PUBLISHED);
    curation.setPublishedAt(curationRepository.currentTimestamp());
    curation = curationRepository.save(curation);
    return toResponse(curation);
  }

  // ── helpers ──

  /** Uniform 404 for missing or foreign-owned curations. */
  private RecordingCuration requireOwned(UUID curationId, String ownerId) {
    RecordingCuration curation =
        curationRepository
            .findById(curationId)
            .orElseThrow(
                () ->
                    ReplayException.notFound(
                        ReplayErrorCode.CURATION_NOT_PUBLISHED,
                        "Curation " + curationId + " not found"));
    if (ownerId == null || !ownerId.equals(curation.getCreatedBy())) {
      throw ReplayException.notFound(
          ReplayErrorCode.CURATION_NOT_PUBLISHED, "Curation " + curationId + " not found");
    }
    return curation;
  }

  private void replaceActionRows(UUID curationId, List<CuratedActionDto> labels) {
    actionRepository.deleteByCurationId(curationId);
    actionRepository.flush();
    if (labels == null) {
      return;
    }
    for (CuratedActionDto dto : labels) {
      CuratedAction action = new CuratedAction();
      action.setCurationId(curationId);
      action.setTick(dto.tick());
      action.setSequence(dto.sequence());
      action.setStatus(dto.status() != null ? dto.status() : "ACCEPTED_LABEL");
      action.setSemanticActionType(dto.semanticActionType());
      action.setTargetStableId(dto.targetStableId());
      action.setArgumentsJson(dto.argumentsJson());
      if (dto.evidenceRef() != null) {
        action.setEvidenceRef(dto.evidenceRef());
      }
      if (dto.confidence() != null) {
        action.setConfidence(dto.confidence());
      }
      actionRepository.save(action);
    }
  }

  private void validateTickRange(long start, long end) {
    if (start < 0 || end < start) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR,
          "Invalid tick range: require start >= 0 and end >= start");
    }
  }

  private void validateLabels(long rangeStart, long rangeEnd, List<CuratedActionDto> labels) {
    if (labels == null) {
      return;
    }
    Set<String> seenTickSequence = new HashSet<>();
    for (CuratedActionDto dto : labels) {
      if (dto.tick() < 0) {
        throw ReplayException.badRequest(
            ReplayErrorCode.VALIDATION_ERROR, "Action tick must be >= 0");
      }
      if (dto.tick() < rangeStart || dto.tick() > rangeEnd) {
        throw ReplayException.badRequest(
            ReplayErrorCode.VALIDATION_ERROR,
            "Action tick "
                + dto.tick()
                + " is outside the curation range ["
                + rangeStart
                + ","
                + rangeEnd
                + "]");
      }
      if (dto.sequence() < 0) {
        throw ReplayException.badRequest(
            ReplayErrorCode.VALIDATION_ERROR, "Action sequence must be >= 0");
      }
      if (!seenTickSequence.add(dto.tick() + ":" + dto.sequence())) {
        throw ReplayException.badRequest(
            ReplayErrorCode.VALIDATION_ERROR,
            "Duplicate (tick, sequence): " + dto.tick() + ":" + dto.sequence());
      }
      if (dto.status() != null && !ALLOWED_STATUSES.contains(dto.status())) {
        throw ReplayException.badRequest(
            ReplayErrorCode.VALIDATION_ERROR, "Status must be one of " + ALLOWED_STATUSES);
      }
      if (dto.targetStableId() != null
          && dto.targetStableId().length() > MAX_STABLE_TARGET_LENGTH) {
        throw ReplayException.badRequest(
            ReplayErrorCode.VALIDATION_ERROR,
            "targetStableId exceeds " + MAX_STABLE_TARGET_LENGTH + " characters");
      }
      if (dto.confidence() != null
          && (dto.confidence().compareTo(BigDecimal.ZERO) < 0
              || dto.confidence().compareTo(BigDecimal.ONE) > 0)) {
        throw ReplayException.badRequest(
            ReplayErrorCode.VALIDATION_ERROR, "Confidence must be within [0,1]");
      }
    }
  }

  /**
   * Canonical content document: tick range, role-independent initial-state overrides and every
   * action label with its full payload. The checksum over this document is the curation identity
   * used by the compiler.
   */
  private JsonNode buildContentJson(
      long rangeStart,
      long rangeEnd,
      JsonNode initialStateOverrides,
      List<CuratedActionDto> labels) {
    ObjectNode content = objectMapper.createObjectNode();
    ObjectNode range = content.putObject("tickRange");
    range.put("start", rangeStart);
    range.put("end", rangeEnd);
    content.set(
        "initialStateOverrides",
        initialStateOverrides != null ? initialStateOverrides : objectMapper.createObjectNode());
    ArrayNode arr = content.putArray("actionLabels");
    if (labels != null) {
      for (CuratedActionDto dto : labels) {
        ObjectNode n = arr.addObject();
        n.put("tick", dto.tick());
        n.put("sequence", dto.sequence());
        n.put("status", dto.status() != null ? dto.status() : "ACCEPTED_LABEL");
        if (dto.semanticActionType() != null) n.put("semanticActionType", dto.semanticActionType());
        if (dto.targetStableId() != null) n.put("targetStableId", dto.targetStableId());
        if (dto.argumentsJson() != null) n.set("argumentsJson", dto.argumentsJson());
        if (dto.evidenceRef() != null) n.set("evidenceRef", dto.evidenceRef());
        if (dto.confidence() != null) n.put("confidence", dto.confidence());
      }
    }
    return content;
  }

  private static Long extractRangeStart(JsonNode content) {
    return content == null
        ? null
        : (content.path("tickRange").hasNonNull("start")
            ? content.path("tickRange").path("start").asLong()
            : null);
  }

  private static Long extractRangeEnd(JsonNode content) {
    return content == null
        ? null
        : (content.path("tickRange").hasNonNull("end")
            ? content.path("tickRange").path("end").asLong()
            : null);
  }

  private static JsonNode extractOverrides(JsonNode content) {
    return content != null && content.has("initialStateOverrides")
        ? content.get("initialStateOverrides")
        : null;
  }

  private RecordingCuration refetch(UUID id) {
    return curationRepository
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("Curation vanished during save"));
  }

  private CurationResponse toResponse(RecordingCuration c) {
    List<CuratedAction> actions =
        actionRepository.findByCurationIdOrderByTickAscSequenceAsc(c.getId());
    List<CuratedActionDto> actionDtos =
        actions.stream()
            .map(
                a ->
                    new CuratedActionDto(
                        a.getTick(),
                        a.getSequence(),
                        a.getStatus(),
                        a.getSemanticActionType(),
                        a.getTargetStableId(),
                        a.getArgumentsJson(),
                        a.getEvidenceRef(),
                        a.getConfidence()))
            .collect(Collectors.toList());

    return new CurationResponse(
        c.getId(),
        c.getRecordingId(),
        c.getDebugSessionId(),
        c.getVersion(),
        c.getState(),
        c.getRole(),
        c.getSchemaVersion(),
        c.getContentJson(),
        c.getContentChecksum(),
        c.getCreatedBy(),
        c.getCreatedAt(),
        c.getPublishedAt(),
        actionDtos);
  }

  private String computeChecksum(JsonNode content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest =
          md.digest(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hexBuilder = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hexBuilder.append(Character.forDigit((b >> 4) & 0xF, 16));
        hexBuilder.append(Character.forDigit(b & 0xF, 16));
      }
      return "sha256:" + hexBuilder;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
