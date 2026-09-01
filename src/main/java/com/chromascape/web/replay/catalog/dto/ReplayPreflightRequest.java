package com.chromascape.web.replay.catalog.dto;

import com.chromascape.web.replay.api.dto.ReplayMode;

/** Request body for the POST preflight endpoint. */
public record ReplayPreflightRequest(ReplayMode requestedMode, Integer curationVersion) {}
