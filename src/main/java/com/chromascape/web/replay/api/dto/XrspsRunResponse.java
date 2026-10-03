package com.chromascape.web.replay.api.dto;

import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ReplayApproximation;
import com.chromascape.web.replay.conversion.ReplayGap;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Public v2 run projection. It intentionally omits lease and worker internals. */
public record XrspsRunResponse(
    String schema,
    UUID runId,
    String recordingId,
    UUID manifestId,
    ReplayRunState state,
    ReplayRunPhase phase,
    ReplayOutcome outcome,
    String terminalReason,
    String fidelity,
    String xrspsAdapterVersion,
    String xrspsBuildCommit,
    String cacheRevision,
    String contentRevision,
    ConversionQuality conversionQuality,
    double actionCoveragePct,
    int gapCount,
    int approximationCount,
    List<ReplayGap> gaps,
    List<ReplayApproximation> approximations,
    boolean controlAllowed,
    boolean viewerRequested,
    boolean viewerAvailable,
    String viewerTraceChecksum,
    Long viewerTraceFrameCount,
    ReplayStopPolicy stopPolicy,
    LocalDateTime createdAt,
    LocalDateTime startedAt,
    LocalDateTime terminalAt) {

  public static final String SCHEMA = "xrsps-replay-run/v2";

  public XrspsRunResponse {
    schema = schema == null ? SCHEMA : schema;
    gaps = List.copyOf(gaps == null ? List.of() : gaps);
    approximations = List.copyOf(approximations == null ? List.of() : approximations);
  }
}
