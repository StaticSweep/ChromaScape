package com.chromascape.web.replay.manifest.contract;

/**
 * Curation reference within a manifest.
 */
public record CurationIdentity(
        String curationId, int version, String checksum) {}