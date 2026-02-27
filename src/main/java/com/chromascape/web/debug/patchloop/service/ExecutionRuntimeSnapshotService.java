package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.PatchProposal;
import com.chromascape.web.debug.patchloop.entity.ExecutionRuntimeSnapshot;
import com.chromascape.web.debug.patchloop.entity.ExecutionRuntimeSnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for persisting and restoring execution runtime snapshots.
 *
 * <p>Enables resume/retry functionality by saving runtime data after each node execution.
 */
@Service
public class ExecutionRuntimeSnapshotService {

  private static final Logger logger =
      LoggerFactory.getLogger(ExecutionRuntimeSnapshotService.class);

  private final ExecutionRuntimeSnapshotRepository repository;
  private final ObjectMapper objectMapper;

  public ExecutionRuntimeSnapshotService(
      ExecutionRuntimeSnapshotRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  /**
   * Saves a runtime snapshot for the execution.
   *
   * @param executionId the execution ID
   * @param runtimeData the runtime data to persist
   * @param context the graph execution context
   */
  @Transactional
  public void saveSnapshot(
      String executionId, Map<String, Object> runtimeData, GraphExecutionContext context) {
    try {
      ExecutionRuntimeSnapshot snapshot =
          repository
              .findByExecutionId(executionId)
              .orElse(new ExecutionRuntimeSnapshot());

      snapshot.setExecutionId(executionId);
      snapshot.setRuntimeDataJson(objectMapper.writeValueAsString(runtimeData));

      // Also save key context data for restore
      Map<String, Object> contextData = new HashMap<>();
      contextData.put("iterationId", context.getIterationId());
      contextData.put("scriptName", context.getScriptName());
      contextData.put("goalPrompt", context.getGoalPrompt());
      contextData.put("context", context.getContext());
      contextData.put("maxAttempts", context.getMaxAttempts());
      snapshot.setContextDataJson(objectMapper.writeValueAsString(contextData));

      snapshot.setLastUpdatedAt(LocalDateTime.now());

      // Increment version if this is an update
      if (snapshot.getId() != null) {
        snapshot.incrementVersion();
      }

      repository.save(snapshot);

      logger.debug("Saved runtime snapshot for execution: {}", executionId);

    } catch (Exception e) {
      logger.error("Failed to save runtime snapshot for execution: {}", executionId, e);
      // Don't throw - snapshot failures shouldn't block execution
    }
  }

  /**
   * Loads runtime data for an execution.
   *
   * @param executionId the execution ID
   * @return the runtime data, or empty map if not found
   */
  public Map<String, Object> loadRuntimeData(String executionId) {
    return repository
        .findByExecutionId(executionId)
        .<Map<String, Object>>map(
            snapshot -> {
              try {
                return parseJson(snapshot.getRuntimeDataJson());
              } catch (Exception e) {
                logger.error("Failed to parse runtime data for execution: {}", executionId, e);
                return new HashMap<>();
              }
            })
        .orElseGet(
            () -> {
              logger.debug("No runtime snapshot found for execution: {}", executionId);
              return new HashMap<>();
            });
  }

  /**
   * Loads context data for an execution.
   *
   * @param executionId the execution ID
   * @return the context data map, or empty map if not found
   */
  public Map<String, Object> loadContextData(String executionId) {
    return repository
        .findByExecutionId(executionId)
        .<Map<String, Object>>map(
            snapshot -> {
              try {
                return parseJson(snapshot.getContextDataJson());
              } catch (Exception e) {
                logger.error("Failed to parse context data for execution: {}", executionId, e);
                return new HashMap<>();
              }
            })
        .orElseGet(
            () -> {
              logger.debug("No runtime snapshot found for execution: {}", executionId);
              return new HashMap<>();
            });
  }

  /**
   * Gets a type-safe runtime value from the snapshot.
   *
   * <p>Rehydrates the value from Map to the requested type using Jackson conversion.
   *
   * @param <T> the type of the value
   * @param executionId the execution ID
   * @param key the runtime data key
   * @param type the class of the type
   * @return the rehydrated value, or null if not found
   */
  public <T> T getRuntimeValue(String executionId, String key, Class<T> type) {
    Map<String, Object> data = loadRuntimeData(executionId);
    Object value = data.get(key);

    if (value == null) {
      return null;
    }

    // Rehydrate from Map to specific types using Jackson conversion
    // This handles all complex types that were serialized to JSON
    if (value instanceof Map) {
      try {
        return objectMapper.convertValue((Map) value, type);
      } catch (IllegalArgumentException e) {
        logger.warn(
            "Failed to convert runtime value {} to type {} via Jackson",
            key,
            type.getSimpleName());
        return null;
      }
    }

    // Try direct cast for simple types
    try {
      return type.cast(value);
    } catch (ClassCastException e) {
      logger.warn(
          "Failed to cast runtime value {} to type {}, attempting Jackson conversion",
          key,
          type.getSimpleName());
      // Fallback to Jackson conversion for non-Map values
      return objectMapper.convertValue(value, type);
    }
  }

  /**
   * Deletes the snapshot for an execution.
   *
   * @param executionId the execution ID
   */
  @Transactional
  public void deleteSnapshot(String executionId) {
    repository
        .findByExecutionId(executionId)
        .ifPresent(
            snapshot -> {
              repository.delete(snapshot);
              logger.debug("Deleted runtime snapshot for execution: {}", executionId);
            });
  }

  /**
   * Gets the snapshot version for conflict detection.
   *
   * @param executionId the execution ID
   * @return the snapshot version, or 0 if not found
   */
  public int getSnapshotVersion(String executionId) {
    return repository
        .findByExecutionId(executionId)
        .map(snapshot -> snapshot.getSnapshotVersion() != null ? snapshot.getSnapshotVersion() : 0)
        .orElse(0);
  }

  /**
   * Parses JSON string into a Map.
   *
   * @param json the JSON string
   * @return the parsed map
   */
  private Map<String, Object> parseJson(String json) {
    if (json == null || json.isBlank()) {
      return new HashMap<>();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      logger.error("Failed to parse JSON: {}", json, e);
      return new HashMap<>();
    }
  }
}
