package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryAuditEvent;
import com.chromascape.web.debug.buildergoal.recovery.repository.BuilderGoalRecoveryAuditEventRepository;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalSensitiveTextSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Append-only audit event writer for every compile recovery action and denial. Only stable IDs,
 * hashes, states, codes, and safe correlation data are recorded — never diff text, raw compiler
 * output, credentials, or request headers.
 */
@Service
public class RecoveryAuditService {

  private static final Logger logger = LoggerFactory.getLogger(RecoveryAuditService.class);

  private final BuilderGoalRecoveryAuditEventRepository repository;
  private final ObjectMapper objectMapper;

  public RecoveryAuditService(
      BuilderGoalRecoveryAuditEventRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public void record(
      String action,
      String actorId,
      String actorRole,
      String iterationId,
      String incidentId,
      String subjectType,
      String subjectId,
      String outcome,
      String errorCode,
      Map<String, Object> detail) {
    try {
      BuilderGoalRecoveryAuditEvent event = new BuilderGoalRecoveryAuditEvent();
      event.setAuditEventId(RecoveryHashes.newId("audit"));
      event.setOccurredAt(Instant.now());
      event.setActorId(actorId);
      event.setActorRole(actorRole);
      event.setAction(action);
      event.setIterationId(iterationId);
      event.setIncidentId(incidentId);
      event.setSubjectType(subjectType);
      event.setSubjectId(subjectId);
      event.setOutcome(outcome);
      event.setErrorCode(errorCode);
      event.setDetailJson(detail == null ? null : write(detail));
      repository.save(event);
    } catch (Exception e) {
      // Audit must never break the business action, but the failure is always logged.
      logger.warn(
          "Recovery audit write failed: action={} incidentId={} (details omitted)",
          action,
          incidentId);
    }
  }

  public void denied(
      String action, String actorId, String iterationId, String incidentId, String errorCode) {
    record(
        action,
        actorId,
        RecoveryOperatorContext_role(actorId),
        iterationId,
        incidentId,
        null,
        null,
        "DENIED",
        errorCode,
        null);
  }

  private static String RecoveryOperatorContext_role(String actorId) {
    return actorId == null ? null : "OPERATOR";
  }

  private String write(Map<String, Object> detail) {
    try {
      Map<String, Object> sanitized = new LinkedHashMap<>();
      detail.forEach(
          (key, value) ->
              sanitized.put(
                  key,
                  value instanceof String text
                      ? BuilderGoalSensitiveTextSanitizer.sanitize(text)
                      : value));
      return objectMapper.writeValueAsString(sanitized);
    } catch (Exception e) {
      return null;
    }
  }
}
