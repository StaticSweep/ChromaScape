package com.chromascape.web.replay.artifact;

import com.chromascape.web.replay.artifact.entity.ArtifactRef;

/**
 * Result of fenced artifact-reference registration.
 *
 * @param reference the live reference row (existing or newly inserted)
 * @param publicationRequired false ONLY when an already-FINALIZED reference was reused: its bytes
 *     are published content-addressed evidence and must neither be republished nor listed in the
 *     next publication receipt (it still belongs in report evidence). The redundant deterministic
 *     staged copy should be discarded by the caller.
 */
public record ArtifactRegistration(ArtifactRef reference, boolean publicationRequired) {}
