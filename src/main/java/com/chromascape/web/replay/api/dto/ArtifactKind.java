package com.chromascape.web.replay.api.dto;

/** Kinds of finalised replay artifacts. */
public enum ArtifactKind {
  MANIFEST,
  SNAPSHOT,
  TRACE,
  REPORT,
  /** SC-275 bound transition-prefix frames for one checkpoint. */
  CHAIN,
  /** Complete versioned causal projection, captured periodically and at boundaries. */
  PROJECTION
}
