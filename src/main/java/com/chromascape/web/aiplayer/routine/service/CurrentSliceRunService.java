package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentSliceRunService {
  private final CurrentSliceIdentityValidator validator;
  private final CurrentSliceRunRepository repository;
  private final ObjectMapper mapper;
  private final StabilizationIngestionService stabilization;

  public CurrentSliceRunService(
      CurrentSliceIdentityValidator validator,
      CurrentSliceRunRepository repository,
      ObjectMapper mapper,
      StabilizationIngestionService stabilization) {
    this.validator = validator;
    this.repository = repository;
    this.mapper = mapper;
    this.stabilization = stabilization;
  }

  @Transactional
  public Map<String, Object> createRunelite(CurrentSliceIdentity identity) {
    if (identity == null || identity.client() == null || !"RUNELITE_CAPTURE".equals(identity.client().clientKind())) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CLIENT_CAPABILITY_INVALID", "RuneLite capture client identity is required");
    String runId = identity.run() == null || identity.run().runId() == null || identity.run().runId().isBlank() ? "run_" + UUID.randomUUID().toString().replace("-", "") : identity.run().runId();
    CurrentSliceIdentity.Run run = new CurrentSliceIdentity.Run(runId, null, null, null);
    CurrentSliceIdentity enriched = new CurrentSliceIdentity(identity.schema(), identity.task(), run, identity.client(), identity.capture(), identity.source(), identity.graph(), identity.model(), identity.candidate(), false);
    return create(null, enriched);
  }

  @Transactional
  public Map<String, Object> create(String attemptId, CurrentSliceIdentity identity) {
    validator.validate(identity);
    boolean micro6 = identity.client() != null && "MICRO6_MANAGED".equals(identity.client().clientKind());
    if (micro6 && (attemptId == null || attemptId.isBlank())) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_FIELD_MISSING", "attemptId is required for MICRO6_MANAGED");
    }
    if (!micro6 && attemptId != null && !attemptId.isBlank()) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CLIENT_CAPABILITY_INVALID", "RUNELITE_CAPTURE cannot claim a Micro6 attempt");
    }
    if (identity.run() == null || identity.run().runId() == null) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_GROUP_MISSING", "run.run_id is required");
    }
    if (micro6) {
      if (identity.run().attemptId() == null || !attemptId.equals(identity.run().attemptId())) {
        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH", "path attemptId does not match identity.run.attempt_id");
      }
      stabilization.getRunByRunIdAndAttemptId(identity.run().runId(), attemptId)
          .filter(run -> "PASS".equalsIgnoreCase(run.getOutcome()))
          .orElseThrow(() -> error(HttpStatus.UNPROCESSABLE_ENTITY, "STABILIZATION_NOT_VERIFIED", "SC-572 PASS attempt is not verified"));
    }

    String runId = identity.run().runId();
    String identityJson = write(identity);
    CurrentSliceRun existing = repository.findByRunId(runId).orElse(null);
    if (existing != null) {
      if (!identityJson.equals(existing.getIdentityJson())) {
        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH", "run identity differs from persisted run");
      }
      return response(existing, identity);
    }
    CurrentSliceRun entity = new CurrentSliceRun();
    entity.setRunId(runId.isBlank() ? "run_" + UUID.randomUUID().toString().replace("-", "") : runId);
    entity.setAttemptId(micro6 ? attemptId : null);
    entity.setIdentityJson(identityJson);
    entity.setStage("BOUND");
    return response(repository.save(entity), identity);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String runId) {
    CurrentSliceRun entity = repository.findByRunId(runId)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "run not found"));
    return response(entity, read(entity.getIdentityJson()));
  }

  private Map<String, Object> response(CurrentSliceRun entity, CurrentSliceIdentity identity) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "current-slice-run/v1");
    result.put("run_id", entity.getRunId());
    result.put("attempt_id", entity.getAttemptId());
    result.put("identity", identity);
    result.put("stage", entity.getStage());
    result.put("created_at", entity.getCreatedAt() == null ? Instant.now().toString() : entity.getCreatedAt().toString());
    result.put("control_allowed", false);
    return result;
  }

  private String write(CurrentSliceIdentity identity) {
    try {
      return mapper.writeValueAsString(identity);
    } catch (Exception e) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH", "identity cannot be serialized");
    }
  }

  private CurrentSliceIdentity read(String json) {
    try {
      return validator.validate(mapper.readValue(json, CurrentSliceIdentity.class));
    } catch (Exception e) {
      throw error(HttpStatus.INTERNAL_SERVER_ERROR, "IDENTITY_MISMATCH", "persisted identity is invalid");
    }
  }

  private static RoutineCaptureIngestionService.RoutineCaptureException error(HttpStatus status, String code, String message) {
    return new RoutineCaptureIngestionService.RoutineCaptureException(status, code, message);
  }
}
