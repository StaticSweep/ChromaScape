package com.chromascape.web.debug.patchloop.entity;

/** Status of a patch application. */
public enum PatchApplyStatus {
  /** Patch generated and staged, ready to apply */
  STAGED,

  /** Patch successfully applied to codebase */
  APPLIED,

  /** Patch application failed */
  FAILED,

  /** Patch was rolled back after validation failure */
  ROLLED_BACK,

  /** Patch was skipped (e.g., duplicate, policy violation) */
  SKIPPED;
}
