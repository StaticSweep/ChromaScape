package com.chromascape.web.recording.entity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link TaskRecordingPreference} persistence. */
public interface TaskRecordingPreferenceRepository
    extends JpaRepository<TaskRecordingPreference, Long> {

  /** Find preference by taskType name (lower-case). */
  Optional<TaskRecordingPreference> findByTaskType(String taskType);
}
