package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.TaskPlan;
import com.chromascape.web.accounttask.repository.TaskPlanRepository;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskPlanAccountService {
  public static final String UNASSIGNED_FILTER = "__UNASSIGNED__";

  private final TaskPlanRepository planRepository;
  private final DebugSessionRepository debugSessionRepository;
  private final RecordingSessionRepository recordingSessionRepository;

  public TaskPlanAccountService(
      TaskPlanRepository planRepository,
      DebugSessionRepository debugSessionRepository,
      RecordingSessionRepository recordingSessionRepository) {
    this.planRepository = planRepository;
    this.debugSessionRepository = debugSessionRepository;
    this.recordingSessionRepository = recordingSessionRepository;
  }

  public Optional<String> resolveAccountName(String sourceSessionId, String sourceRecordingId) {
    if (sourceSessionId != null && !sourceSessionId.isBlank()) {
      var session = debugSessionRepository.findBySessionId(sourceSessionId).orElse(null);
      if (session != null
          && session.getAccountName() != null
          && !session.getAccountName().isBlank()) {
        return Optional.of(session.getAccountName().trim());
      }
    }
    if (sourceRecordingId != null && !sourceRecordingId.isBlank()) {
      var recording = recordingSessionRepository.findByRecordingId(sourceRecordingId).orElse(null);
      if (recording != null
          && recording.getAccountName() != null
          && !recording.getAccountName().isBlank()) {
        return Optional.of(recording.getAccountName().trim());
      }
    }
    return Optional.empty();
  }

  @Transactional
  public Map<String, Object> backfillMissingAccountNames() {
    List<TaskPlan> unresolved = planRepository.findUnassigned();
    int scanned = unresolved.size();
    int updated = 0;

    for (TaskPlan plan : unresolved) {
      Optional<String> account =
          resolveAccountName(plan.getSourceSessionId(), plan.getSourceRecordingId());
      if (account.isPresent()) {
        plan.setAccountName(account.get());
        planRepository.save(plan);
        updated++;
      }
    }

    Map<String, Object> result = new HashMap<>();
    result.put("scanned", scanned);
    result.put("updated", updated);
    result.put("unresolved", scanned - updated);
    return result;
  }
}
