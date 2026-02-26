package com.chromascape.web.debug.patchloop.entity;

/**
 * Types of nodes in a patch loop graph workflow.
 */
public enum NodeType {
  /** Execute the target script */
  RUN_SCRIPT,

  /** Gather logs, tick logs, decisions */
  COLLECT_EVIDENCE,

  /** Run AI analysis on evidence */
  ANALYZE,

  /** Generate code patch from analysis */
  GENERATE_PATCH,

  /** Apply patch to local files */
  APPLY_PATCH,

  /** Run tests and checks */
  VALIDATE,

  /** Run script again and compare results */
  RETEST,

  /** Check if loop should stop or continue */
  EVALUATE_SUCCESS,

  /** Terminal state */
  END,

  /** Entry point node */
  START;
}
