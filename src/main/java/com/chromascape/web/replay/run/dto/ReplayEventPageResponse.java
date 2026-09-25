package com.chromascape.web.replay.run.dto;

import com.chromascape.web.replay.run.entity.ReplayEvent;
import java.util.List;

/** Cursor-paginated event response. */
public record ReplayEventPageResponse(
    String runId, List<ReplayEvent> events, long nextAfter, boolean hasMore) {}
