package com.chromascape.web.replay.catalog.dto;

import java.util.List;

/**
 * Wrapper for the recording catalog endpoint.
 */
public record ReplayRecordingCatalogResponse(
        int schemaVersion,
        List<ReplayRecordingCatalogEntry> entries) {

    public static final int CURRENT_VERSION = 1;
}
