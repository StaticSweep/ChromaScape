package com.chromascape.web.debug.patchloop.dto;

/**
 * Types of evidence that can be included in an analysis request.
 *
 * <p>This enum defines the categories of evidence that can be collected and analyzed by the BMAD
 * (Bot Metric Analysis & Diagnostics) system.
 */
public enum EvidenceType {
  /** General application logs (LogEntity) */
  GENERAL_LOGS,

  /** Game tick-specific logs from debug sessions (TickLog) */
  TICK_LOGS,

  /** Decision records from bot decision-making (DecisionEntity) */
  DECISIONS
}
