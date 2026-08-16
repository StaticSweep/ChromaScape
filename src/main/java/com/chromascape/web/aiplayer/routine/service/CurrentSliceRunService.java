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
    CurrentSliceRun existing = repository.findWithLockByRunId(runId).orElse(null);
    if (existing != null) {
      // Semantic idempotency: a retried upstream create succeeds when the submitted identity is a
      // valid predecessor of the persisted (possibly enriched) identity. Raw serialized JSON is
      // never compared; a regression (missing or changed group) is rejected.
      CurrentSliceIdentity persisted = read(existing.getIdentityJson());
      try {
        identity.requireSameIdentity(persisted);
      } catch (RuntimeException e) {
        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH", "run identity differs from persisted run");
      }
      return response(existing, persisted);
    }
    CurrentSliceRun entity = new CurrentSliceRun();
    entity.setRunId(runId.isBlank() ? "run_" + UUID.randomUUID().toString().replace("-", "") : runId);
    entity.setAttemptId(micro6 ? attemptId : null);
    entity.setIdentityJson(identityJson);
    entity.setStage("BOUND");
    return response(repository.save(entity), identity);
  }

  /**
   * Advances the persisted run identity through the locked path.
   */
  @Transactional
  public CurrentSliceRun advanceIdentity(String runId, CurrentSliceIdentity enriched, String stage) {
    if (runId == null || runId.isBlank()) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_FIELD_MISSING", "run_id is required");
    }
    CurrentSliceRun entity = repository.findWithLockByRunId(runId)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "run not found"));
    CurrentSliceIdentity persisted = read(entity.getIdentityJson());
    try {
      persisted.requireSameIdentity(enriched);
    } catch (RuntimeException e) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH", "identity is not a valid progressive enrichment of the persisted run");
    }
    entity.setIdentityJson(write(enriched));
    entity.setStage(stage);
    return repository.save(entity);
  }

  /**
   * Locked, non-throwing identity advancement for asynchronous result application. Returning false
   * lets the caller commit durable UNCERTAIN evidence in its own transaction instead of catching a
   * runtime identity conflict after Spring has already marked the surrounding transaction rollback
   * only.
   */
  @Transactional
  public boolean tryAdvanceIdentity(String runId, CurrentSliceIdentity enriched, String stage) {
    if (runId == null || runId.isBlank() || enriched == null) return false;
    CurrentSliceRun entity = repository.findWithLockByRunId(runId).orElse(null);
    if (entity == null) return false;
    try {
      CurrentSliceIdentity persisted = read(entity.getIdentityJson());
      persisted.requireSameIdentity(enriched);
      entity.setIdentityJson(write(enriched));
      entity.setStage(stage);
      repository.save(entity);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  /**
   * Replaces the run graph group ONLY through an explicit supersession edge: the run must currently
   * reference {@code supersededGraphId} (or have no graph yet). This is the single sanctioned path
   * for a published assistant successor to replace the immutable core graph identity; it never
   * allows an arbitrary graph overwrite.
   */
  @Transactional
  public CurrentSliceRun advanceIdentityGraph(String runId, String supersededGraphId, CurrentSliceIdentity.Graph successor, String stage) {
    if (runId == null || runId.isBlank() || successor == null) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_FIELD_MISSING", "run_id and successor graph are required");
    }
    CurrentSliceRun entity = repository.findWithLockByRunId(runId)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "run not found"));
    CurrentSliceIdentity persisted = read(entity.getIdentityJson());
    CurrentSliceIdentity.Graph current = persisted.graph();
    if (current != null && !supersededGraphId.equals(current.graphId())) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH", "run graph is not the superseded graph");
    }
    if (current == null && supersededGraphId != null && !supersededGraphId.isBlank()) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH", "run has no graph to supersede");
    }
    CurrentSliceIdentity enriched = new CurrentSliceIdentity(
        persisted.schema(), persisted.task(), persisted.run(), persisted.client(), persisted.capture(),
        persisted.source(), successor, persisted.model(), persisted.candidate(), false);
    entity.setIdentityJson(write(enriched));
    entity.setStage(stage);
    return repository.save(entity);
  }

  /**
   * Publishes a root (generated) graph identity onto the run. This is the sanctioned path for
   * publishing a generated graph: it advances the run graph ONLY when the run currently has no
   * graph, and state update plus run-identity update happen in one transaction. An existing graph
   * is never overwritten here — assistant successors must go through {@link #advanceIdentityGraph}.
   */
  @Transactional
  public CurrentSliceRun advanceIdentityRootGraph(String runId, CurrentSliceIdentity.Graph rootGraph, String stage) {
    if (runId == null || runId.isBlank() || rootGraph == null) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_FIELD_MISSING", "run_id and root graph are required");
    }
    CurrentSliceRun entity = repository.findWithLockByRunId(runId)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "run not found"));
    CurrentSliceIdentity persisted = read(entity.getIdentityJson());
    if (persisted.graph() != null) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_MISMATCH", "run already has a graph; use the supersession path");
    }
    CurrentSliceIdentity enriched = new CurrentSliceIdentity(
        persisted.schema(), persisted.task(), persisted.run(), persisted.client(), persisted.capture(),
        persisted.source(), rootGraph, persisted.model(), persisted.candidate(), false);
    entity.setIdentityJson(write(enriched));
    entity.setStage(stage);
    return repository.save(entity);
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
