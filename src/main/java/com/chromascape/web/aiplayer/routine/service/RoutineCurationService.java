package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.RoutineCuration;
import com.chromascape.web.aiplayer.routine.repository.RoutineCurationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutineCurationService {
  private final RoutineCaptureIngestionService captures;
  private final RoutineCurationRepository repository;
  private final ObjectMapper mapper;

  public RoutineCurationService(
      RoutineCaptureIngestionService captures,
      RoutineCurationRepository repository,
      ObjectMapper mapper) {
    this.captures = captures;
    this.repository = repository;
    this.mapper = mapper;
  }

  @Transactional
  public java.util.Map<String, Object> put(
      String recordingId, RoutineApiDtos.CurationRequest request) {
    captures.requireRecordingOperation(recordingId, "semanticLearning");
    try {
      RoutineCuration entity =
          repository.findByRecordingId(recordingId).orElseGet(RoutineCuration::new);
      entity.setRecordingId(recordingId);
      entity.setIncludeRangesJson(
          mapper.writeValueAsString(
              request.includeRanges() == null ? java.util.List.of() : request.includeRanges()));
      entity.setExcludeRangesJson(
          mapper.writeValueAsString(
              request.excludeRanges() == null ? java.util.List.of() : request.excludeRanges()));
      repository.save(entity);
      return java.util.Map.of(
          "recording_id",
          recordingId,
          "curation",
          request,
          "evidence_rewritten",
          false,
          "status",
          "CURATED");
    } catch (Exception e) {
      throw new IllegalArgumentException("curation JSON is invalid", e);
    }
  }

  @Transactional(readOnly = true)
  public RoutineApiDtos.CurationRequest get(String recordingId) {
    return repository.findByRecordingId(recordingId).map(this::read).orElse(null);
  }

  private RoutineApiDtos.CurationRequest read(RoutineCuration entity) {
    try {
      return new RoutineApiDtos.CurationRequest(
          mapper.readValue(
              entity.getIncludeRangesJson(),
              mapper
                  .getTypeFactory()
                  .constructCollectionType(java.util.List.class, java.util.Map.class)),
          mapper.readValue(
              entity.getExcludeRangesJson(),
              mapper
                  .getTypeFactory()
                  .constructCollectionType(java.util.List.class, java.util.Map.class)));
    } catch (Exception e) {
      throw new IllegalStateException("persisted curation is invalid", e);
    }
  }
}
