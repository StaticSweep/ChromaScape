package com.chromascape.web.replay.run.dto;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReplayRunResponse(
    UUID runId,
    ReplayMode mode,
    UUID manifestId,
    String ownerId,
    ReplayRunState state,
    ReplayRunPhase phase,
    ReplayStopPolicy stopPolicy,
    boolean controlAllowed,
    LocalDateTime createdAt,
    LocalDateTime startedAt,
    LocalDateTime terminalAt,
    List<ReplayAttempt> attempts) {}
