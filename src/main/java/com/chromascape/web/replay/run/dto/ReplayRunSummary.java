package com.chromascape.web.replay.run.dto;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Summary row in the runs list.
 */
public record ReplayRunSummary(
        UUID runId,
        ReplayMode mode,
        ReplayRunState state,
        UUID manifestId,
        LocalDateTime createdAt,
        LocalDateTime terminalAt) {}
