package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.debug.patchloop.entity.PatchLoopEvent;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEventRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEventType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable event recording for the SC-260 unified run timeline.
 *
 * <p>Every event is stamped with the owning task/script identity (iteration id, task key, display
 * name, run session id) so a failure reason can always be traced back to the task and script that
 * produced it. Recording never throws: a timeline event must not be able to break run control or
 * status sync.
 */
@Service
public class BuilderGoalRunEventService {

  private static final Logger logger = LoggerFactory.getLogger(BuilderGoalRunEventService.class);

  private final PatchLoopEventRepository eventRepository;
  private final PatchLoopIterationRepository iterationRepository;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate writeTransaction;

  /** Compatibility constructor for focused unit tests that do not start a Spring transaction. */
  public BuilderGoalRunEventService(
      PatchLoopEventRepository eventRepository,
      PatchLoopIterationRepository iterationRepository,
      ObjectMapper objectMapper) {
    this(eventRepository, iterationRepository, objectMapper, null);
  }

  @Autowired
  public BuilderGoalRunEventService(
      PatchLoopEventRepository eventRepository,
      PatchLoopIterationRepository iterationRepository,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.eventRepository = eventRepository;
    this.iterationRepository = iterationRepository;
    this.objectMapper = objectMapper;
    if (transactionManager == null) {
      this.writeTransaction = null;
    } else {
      this.writeTransaction = new TransactionTemplate(transactionManager);
      this.writeTransaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }
  }

  /**
   * Records a timeline event for a run. The data map is merged into the durable identity stamp so
   * callers can attach extra fields without losing the contract fields. Idempotent for repeated
   * identical (type, message) events so the periodic status sync cannot spam the timeline.
   *
   * <p>Runs in its own transaction (REQUIRES_NEW). Event persistence must never participate in —
   * and therefore never mark rollback-only — the caller’s run-control or status-sync transaction; a
   * deferred flush failure surfaces at the inner commit and is swallowed here.
   */
  public void recordRunEvent(
      String iterationId,
      PatchLoopEventType eventType,
      String message,
      String severity,
      Map<String, Object> data) {
    try {
      executeWrite(
          () -> {
            if (isDuplicateOfLatestEvent(iterationId, eventType, message)) {
              return;
            }
            PatchLoopEvent event =
                new PatchLoopEvent(
                    iterationId, eventType, firstNonBlank(message, eventType.name()), severity);
            event.setData(toJson(buildStampedData(iterationId, data)));
            eventRepository.save(event);
          });
    } catch (Exception e) {
      logger.warn(
          "Failed to record SC-260 timeline event {} for iteration {}: {}",
          eventType,
          iterationId,
          e.getMessage());
    }
  }

  /**
   * Records a completed/failure outcome event carrying the SC-260 failure contract fields: {@code
   * failure_code} and {@code failure_reason} plus the owning task/script identity.
   *
   * <p>Runs in its own transaction (REQUIRES_NEW). Event persistence must never participate in —
   * and therefore never mark rollback-only — the caller’s run-control or status-sync transaction; a
   * deferred flush failure surfaces at the inner commit and is swallowed here.
   */
  public void recordFailureEvent(
      String iterationId,
      Integer entryId,
      boolean success,
      String failureCode,
      String failureReason) {
    try {
      executeWrite(
          () -> {
            PatchLoopEventType eventType =
                success ? PatchLoopEventType.SCRIPT_COMPLETED : PatchLoopEventType.SCRIPT_FAILED;
            String message =
                success ? "Run completed successfully" : firstNonBlank(failureReason, "Run failed");
            if (isDuplicateOfLatestEvent(iterationId, eventType, message)) {
              return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            if (entryId != null) {
              data.put("entry_id", entryId);
            }
            if (!success) {
              data.put("failure_code", firstNonBlank(failureCode, "UNKNOWN"));
              data.put("failure_reason", firstNonBlank(failureReason, "Run failed"));
            }
            PatchLoopEvent event =
                new PatchLoopEvent(iterationId, eventType, message, success ? "INFO" : "ERROR");
            event.setData(toJson(buildStampedData(iterationId, data)));
            eventRepository.save(event);
          });
    } catch (Exception e) {
      logger.warn(
          "Failed to record SC-260 outcome event for iteration {}: {}",
          iterationId,
          e.getMessage());
    }
  }

  /** Returns the chronological timeline for a run, ordered by timestamp then insertion id. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> getTimelineEvents(String iterationId) {
    List<Map<String, Object>> timeline = new java.util.ArrayList<>();
    for (PatchLoopEvent event :
        eventRepository.findByIterationIdOrderByTimestampAscIdAsc(iterationId)) {
      Map<String, Object> dto = new LinkedHashMap<>();
      dto.put("id", event.getId());
      dto.put("iterationId", event.getIterationId());
      dto.put("eventType", event.getEventType() != null ? event.getEventType().name() : null);
      dto.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : null);
      dto.put("message", event.getMessage());
      dto.put("severity", event.getSeverity());
      dto.put(
          "data",
          event.getData() != null ? objectMapper.convertValue(event.getData(), Map.class) : null);
      timeline.add(dto);
    }
    return timeline;
  }

  private static String firstNonBlank(String value, String fallback) {
    return value != null && !value.isBlank() ? value : fallback;
  }

  /**
   * Records a risk-trigger event from a Micro6 executor status payload. Risk data is stored exactly
   * as the executor reported it (raw JSON), never synthesized.
   *
   * <p>Runs in its own transaction (REQUIRES_NEW). Event persistence must never participate in —
   * and therefore never mark rollback-only — the caller’s run-control or status-sync transaction; a
   * deferred flush failure surfaces at the inner commit and is swallowed here.
   */
  public void recordRiskEvent(String iterationId, Integer entryId, JsonNode riskData) {
    try {
      executeWrite(
          () -> {
            if (riskData == null || riskData.isNull() || !riskData.isObject()) {
              return;
            }
            // Do not repeat an identical consecutive risk event (the executor reports the risk
            // flag on every status poll once it is raised), but a CHANGED risk payload must still
            // be recorded so a new risk is never collapsed into the previous one.
            Optional<PatchLoopEvent> latest =
                eventRepository.findTopByIterationIdOrderByIdDesc(iterationId);
            if (latest.isPresent()
                && latest.get().getEventType() == PatchLoopEventType.RUN_RISK_TRIGGERED
                && latest.get().getData() != null
                && Objects.equals(latest.get().getData().path("risk"), riskData)) {
              return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            if (entryId != null) {
              data.put("entry_id", entryId);
            }
            data.put("risk", objectMapper.convertValue(riskData, Map.class));
            PatchLoopEvent event =
                new PatchLoopEvent(
                    iterationId,
                    PatchLoopEventType.RUN_RISK_TRIGGERED,
                    "Executor reported a risk trigger",
                    "WARN");
            event.setData(toJson(buildStampedData(iterationId, data)));
            eventRepository.save(event);
          });
    } catch (Exception e) {
      logger.warn(
          "Failed to record SC-260 risk event for iteration {}: {}", iterationId, e.getMessage());
    }
  }

  /**
   * Executes the complete write, including commit, inside the guarded call. TransactionTemplate
   * ensures a deferred commit failure is caught by the public record method instead of escaping
   * from a Spring proxy after that method has returned.
   */
  private void executeWrite(Runnable write) {
    if (writeTransaction == null) {
      write.run();
      return;
    }
    writeTransaction.executeWithoutResult(ignored -> write.run());
  }

  /**
   * Returns true when the iteration has an existing event with the same type and message as the one
   * about to be recorded (simple idempotency for repeated sync observations).
   */
  private boolean isDuplicateOfLatestEvent(
      String iterationId, PatchLoopEventType eventType, String message) {
    try {
      Optional<PatchLoopEvent> latest =
          eventRepository.findTopByIterationIdOrderByIdDesc(iterationId);
      return latest.isPresent()
          && latest.get().getEventType() == eventType
          && Objects.equals(latest.get().getMessage(), message);
    } catch (Exception e) {
      // Never block event recording on the dedupe lookup.
      return false;
    }
  }

  /**
   * Builds the durable identity stamp merged into every event's jsonb data: iteration id, task key
   * (owning script), display name, and run session id.
   */
  private Map<String, Object> buildStampedData(String iterationId, Map<String, Object> extra) {
    Map<String, Object> stamped = new LinkedHashMap<>();
    stamped.put("iteration_id", iterationId);
    try {
      Optional<PatchLoopIteration> iteration = iterationRepository.findByIterationId(iterationId);
      if (iteration.isPresent()) {
        PatchLoopIteration it = iteration.get();
        if (it.getScriptName() != null && !it.getScriptName().isBlank()) {
          stamped.put("task_key", it.getScriptName());
        }
        if (it.getPrompt() != null && !it.getPrompt().isBlank()) {
          stamped.put("display_name", it.getPrompt());
        }
        if (it.getRunSessionId() != null && !it.getRunSessionId().isBlank()) {
          stamped.put("run_session_id", it.getRunSessionId());
        }
      }
    } catch (Exception e) {
      // Identity lookup is best-effort; the iteration_id stamp always remains.
    }
    if (extra != null) {
      stamped.putAll(extra);
    }
    return stamped;
  }

  /** Converts a plain map to the jsonb payload stored on {@link PatchLoopEvent#getData()}. */
  private JsonNode toJson(Map<String, Object> data) {
    if (data == null) {
      return null;
    }
    return objectMapper.valueToTree(data);
  }
}
