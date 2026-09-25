package com.chromascape.web.replay.catalog.dto;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.error.ReplayErrorCode;

/** A single blocking reason for a recording's ineligibility. */
public record ReplayBlockingReason(
    ReplayMode mode, ReplayErrorCode code, String message, String remediation) {}
