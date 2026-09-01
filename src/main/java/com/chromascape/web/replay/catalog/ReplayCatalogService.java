package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver.ResolvedRecording;
import com.chromascape.web.replay.catalog.dto.ReplayRecordingCatalogEntry;
import com.chromascape.web.replay.catalog.dto.ReplayRecordingCatalogResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the public replay recording catalog by enumerating recording sessions and computing their
 * eligibility.
 */
@Service
@Transactional(readOnly = true)
public class ReplayCatalogService {

  private final RecordingSessionRepository recordingSessionRepository;
  private final ReplayRecordingResolver resolver;
  private final RecordingIntegrityService integrityService;

  public ReplayCatalogService(
      RecordingSessionRepository recordingSessionRepository,
      ReplayRecordingResolver resolver,
      RecordingIntegrityService integrityService) {
    this.recordingSessionRepository = recordingSessionRepository;
    this.resolver = resolver;
    this.integrityService = integrityService;
  }

  public ReplayRecordingCatalogResponse catalog() {
    List<RecordingSession> recordings = recordingSessionRepository.findAllByOrderByStartTimeDesc();

    List<ReplayRecordingCatalogEntry> entries = new ArrayList<>();
    for (RecordingSession r : recordings) {
      ReplayRecordingCatalogEntry entry = buildEntry(r);
      entries.add(entry);
    }

    return new ReplayRecordingCatalogResponse(
        ReplayRecordingCatalogResponse.CURRENT_VERSION, entries);
  }

  private ReplayRecordingCatalogEntry buildEntry(RecordingSession recording) {
    try {
      ResolvedRecording resolved = resolver.resolve(recording.getRecordingId());
      var eligibility = integrityService.evaluate(resolved);

      DebugSession ds = resolved.debugSession();
      return new ReplayRecordingCatalogEntry(
          recording.getRecordingId(),
          ds != null ? ds.getSessionId() : null,
          recording.getAccountName(),
          recording.getScriptName(),
          recording.getStartTime(),
          recording.getEndTime(),
          eligibility);
    } catch (Exception e) {
      return new ReplayRecordingCatalogEntry(
          recording.getRecordingId(),
          null,
          recording.getAccountName(),
          recording.getScriptName(),
          recording.getStartTime(),
          recording.getEndTime(),
          null);
    }
  }
}
