package com.chromascape.web.replay.curation;

import com.chromascape.web.replay.curation.dto.CreateCurationRequest;
import com.chromascape.web.replay.curation.dto.CuratedActionDto;
import com.chromascape.web.replay.curation.dto.CurationResponse;
import com.chromascape.web.replay.curation.dto.FieldProvenanceDto;
import com.chromascape.web.replay.curation.dto.UpdateCurationRequest;
import com.chromascape.web.replay.curation.entity.CuratedAction;
import com.chromascape.web.replay.curation.entity.CurationRole;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.FieldProvenance;
import com.chromascape.web.replay.curation.entity.FieldProvenanceSource;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
@Transactional
public class ReplayCurationService {

    private final RecordingCurationRepository curationRepository;
    private final CuratedActionRepository actionRepository;
    private final ReplayRecordingResolver resolver;
    private final ObjectMapper objectMapper;

    public ReplayCurationService(RecordingCurationRepository curationRepository,
                                  CuratedActionRepository actionRepository,
                                  ReplayRecordingResolver resolver,
                                  ObjectMapper objectMapper) {
        this.curationRepository = curationRepository;
        this.actionRepository = actionRepository;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<CurationResponse> listCurations(String recordingId) {
        List<RecordingCuration> curations = curationRepository
                .findByRecordingIdOrderByVersionDesc(recordingId);
        return curations.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CurationResponse getCuration(UUID curationId) {
        RecordingCuration curation = curationRepository.findById(curationId)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.CURATION_NOT_PUBLISHED,
                        "Curation " + curationId + " not found"));
        return toResponse(curation);
    }

    public CurationResponse create(String recordingId, CreateCurationRequest request, String createdBy) {
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
        curation.setContentJson(toContentJson(request, request.actionLabels()));
        curation.setContentChecksum(computeChecksum(curation.getContentJson()));
        curation.setCreatedBy(createdBy);
        curation.setCreatedAt(LocalDateTime.now());

        curation = curationRepository.save(curation);

        if (request.actionLabels() != null) {
            for (CuratedActionDto actionDto : request.actionLabels()) {
                CuratedAction action = new CuratedAction();
                action.setCurationId(curation.getId());
                action.setTick(actionDto.tick());
                action.setSequence(actionDto.sequence());
                action.setStatus(actionDto.status() != null ? actionDto.status() : "ACCEPTED_LABEL");
                action.setSemanticActionType(actionDto.semanticActionType());
                action.setTargetStableId(actionDto.targetStableId());
                action.setArgumentsJson(actionDto.argumentsJson());
                if (actionDto.evidenceRef() != null) {
                    action.setEvidenceRef(actionDto.evidenceRef());
                }
                if (actionDto.confidence() != null) {
                    action.setConfidence(actionDto.confidence());
                }
                actionRepository.save(action);
            }
        }

        return toResponse(curation);
    }

    public CurationResponse update(UUID curationId, UpdateCurationRequest request, String updatedBy) {
        RecordingCuration curation = curationRepository.findById(curationId)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.CURATION_NOT_PUBLISHED,
                        "Curation " + curationId + " not found"));

        if (curation.getState() != CurationState.DRAFT) {
            throw ReplayException.conflict(ReplayErrorCode.CURATION_NOT_PUBLISHED,
                    "Only DRAFT curations can be updated");
        }

        if (request.role() != null) {
            curation.setRole(request.role());
        }
        if (request.initialStateOverrides() != null) {
            curation.setContentJson(toContentJsonForUpdate(request));
            curation.setContentChecksum(computeChecksum(curation.getContentJson()));
        }

        curation = curationRepository.save(curation);
        return toResponse(curation);
    }

    public CurationResponse publish(UUID curationId, String publishedBy) {
        RecordingCuration curation = curationRepository.findById(curationId)
                .orElseThrow(() -> ReplayException.notFound(ReplayErrorCode.CURATION_NOT_PUBLISHED,
                        "Curation " + curationId + " not found"));

        if (curation.getState() != CurationState.DRAFT) {
            throw ReplayException.conflict(ReplayErrorCode.CURATION_NOT_PUBLISHED,
                    "Only DRAFT curations can be published");
        }
        if (curation.getContentChecksum() == null || curation.getContentChecksum().isBlank()) {
            throw ReplayException.invalid(ReplayErrorCode.CURATION_NOT_PUBLISHED,
                    "Curation content is empty");
        }

        curation.setState(CurationState.PUBLISHED);
        curation.setPublishedAt(LocalDateTime.now());
        curation = curationRepository.save(curation);
        return toResponse(curation);
    }

    private CurationResponse toResponse(RecordingCuration c) {
        List<CuratedAction> actions = actionRepository
                .findByCurationIdOrderByTickAscSequenceAsc(c.getId());
        List<CuratedActionDto> actionDtos = actions.stream()
                .map(a -> new CuratedActionDto(a.getTick(), a.getSequence(), a.getStatus(),
                        a.getSemanticActionType(), a.getTargetStableId(), a.getArgumentsJson(),
                        a.getEvidenceRef(), a.getConfidence()))
                .collect(Collectors.toList());

        return new CurationResponse(
                c.getId(), c.getRecordingId(), c.getDebugSessionId(), c.getVersion(),
                c.getState(), c.getRole(), c.getSchemaVersion(), c.getContentJson(),
                c.getContentChecksum(), c.getCreatedBy(), c.getCreatedAt(),
                c.getPublishedAt(), actionDtos
        );
    }

    private JsonNode toContentJson(CreateCurationRequest request, List<CuratedActionDto> labels) {
        ObjectNode content = objectMapper.createObjectNode();
        ObjectNode range = content.putObject("tickRange");
        range.put("start", request.tickRangeStart());
        range.put("end", request.tickRangeEnd());
        if (labels != null) {
            ArrayNode arr = content.putArray("actionLabels");
            for (CuratedActionDto dto : labels) {
                ObjectNode n = arr.addObject();
                n.put("tick", dto.tick());
                n.put("sequence", dto.sequence());
                n.put("status", dto.status());
                if (dto.semanticActionType() != null) n.put("semanticActionType", dto.semanticActionType());
                if (dto.targetStableId() != null) n.put("targetStableId", dto.targetStableId());
            }
        }
        return content;
    }

    private JsonNode toContentJsonForUpdate(UpdateCurationRequest request) {
        ObjectNode content = objectMapper.createObjectNode();
        content.set("initialStateOverrides",
                request.initialStateOverrides() != null ? request.initialStateOverrides()
                        : objectMapper.createObjectNode());
        return content;
    }

    private String computeChecksum(JsonNode content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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