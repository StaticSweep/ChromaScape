package com.chromascape.web.recording.service;

import com.chromascape.web.recording.config.TaskType;
import com.chromascape.web.recording.entity.TaskRecordingPreference;
import com.chromascape.web.recording.entity.TaskRecordingPreferenceRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for persisting and querying per-task recording preferences.
 *
 * <p>On startup the service bootstraps rows for every {@link TaskType} that does not yet exist,
 * defaulting all of them to {@code enabled=false}. Changes are immediately persisted to the
 * database, so they survive restarts.
 */
@Service
public class TaskRecordingPreferenceService {

  private static final Logger log = LoggerFactory.getLogger(TaskRecordingPreferenceService.class);

  private final TaskRecordingPreferenceRepository repository;

  public TaskRecordingPreferenceService(TaskRecordingPreferenceRepository repository) {
    this.repository = repository;
  }

  /**
   * Ensure every {@link TaskType} has a persisted row. Missing entries are created with {@code
   * enabled=false}. Runs after the full application context is ready so JPA/transactions are fully
   * available.
   */
  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void bootstrap() {
    for (TaskType type : TaskType.values()) {
      String key = type.name().toLowerCase();
      if (repository.findByTaskType(key).isEmpty()) {
        repository.save(new TaskRecordingPreference(key, false));
        log.debug("Bootstrapped task recording preference: {} -> false", key);
      }
    }
    log.info("Task recording preferences bootstrapped (all missing entries defaulted to false)");
  }

  /**
   * Return a full map of {@code taskType -> enabled} for all known task types.
   *
   * <p>Guaranteed to include every {@link TaskType} key (lower-case).
   */
  @Transactional(readOnly = true)
  public Map<String, Boolean> getEffectiveTaskMap() {
    Map<String, Boolean> result = new LinkedHashMap<>();
    // Seed with all known types defaulting to false
    for (TaskType type : TaskType.values()) {
      result.put(type.name().toLowerCase(), false);
    }
    // Overwrite with persisted values
    for (TaskRecordingPreference pref : repository.findAll()) {
      result.put(pref.getTaskType(), pref.isEnabled());
    }
    return result;
  }

  /**
   * Check whether auto-recording is enabled for the given task type.
   *
   * @param taskType the task type to check
   * @return {@code true} if recording should start automatically for this task
   */
  @Transactional(readOnly = true)
  public boolean isTaskEnabled(TaskType taskType) {
    String key = taskType.name().toLowerCase();
    return repository.findByTaskType(key).map(TaskRecordingPreference::isEnabled).orElse(false);
  }

  /**
   * Persist the enabled state for a task type.
   *
   * @param taskType the task type to update
   * @param enabled whether auto-recording should be enabled
   */
  @Transactional
  public void setTaskEnabled(TaskType taskType, boolean enabled) {
    String key = taskType.name().toLowerCase();
    TaskRecordingPreference pref =
        repository.findByTaskType(key).orElseGet(() -> new TaskRecordingPreference(key, false));
    pref.setEnabled(enabled);
    repository.save(pref);
    log.info("Task recording preference updated: {} -> {}", key, enabled);
  }

  /**
   * Set all task recording preferences to {@code false}.
   *
   * @return the updated task map (all false)
   */
  @Transactional
  public Map<String, Boolean> resetAllOff() {
    for (TaskRecordingPreference pref : repository.findAll()) {
      pref.setEnabled(false);
      repository.save(pref);
    }
    log.info("All task recording preferences reset to false");
    return getEffectiveTaskMap();
  }
}
