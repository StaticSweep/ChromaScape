package com.chromascape.web.replay.conversion;

/** Selects which immutable recording evidence may be used for a replay manifest. */
public enum ConversionMode {
  /** Use the typed client stream when present and otherwise infer from legacy evidence. */
  AUTO,
  /** Require at least one complete typed action stream; never inspect legacy logs. */
  CAPTURED_ONLY,
  /** Ignore typed actions and make the limitations of legacy inference explicit. */
  LEGACY_BEST_EFFORT
}
