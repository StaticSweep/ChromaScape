package com.chromascape.web.replay.manifest.contract;

/**
 * Pinned runtime version information within a manifest.
 */
public record RuntimeIdentity(
        String xrspsAdapterVersion,
        String xrspsBuildCommit,
        String cacheRevision,
        String contentRevision) {}