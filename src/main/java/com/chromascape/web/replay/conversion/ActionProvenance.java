package com.chromascape.web.replay.conversion;

/** Provenance carried into the immutable manifest for every converted action/gap. */
public enum ActionProvenance {
  CAPTURED,
  INFERRED,
  OPERATOR_SUPPLIED,
  DEFAULTED
}
