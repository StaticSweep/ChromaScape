package com.chromascape.web.debug.patchloop.dto;

/**
 * Confidence level for analysis diagnosis and recommendations.
 *
 * <p>Indicates the analyzer's certainty about the root cause and recommended fixes.
 */
public enum ConfidenceLevel {
  /** High confidence (>80% certainty) - clear evidence and deterministic root cause */
  HIGH,

  /** Medium confidence (50-80% certainty) - probable cause with some ambiguity */
  MEDIUM,

  /** Low confidence (<50% certainty) - multiple hypotheses or insufficient evidence */
  LOW
}
