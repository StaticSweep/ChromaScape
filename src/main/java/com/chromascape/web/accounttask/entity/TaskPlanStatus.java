package com.chromascape.web.accounttask.entity;

/** Status lifecycle for a task plan. */
public enum TaskPlanStatus {
  GENERATED,
  UNDER_REVIEW,
  APPROVED,
  PARTIALLY_APPROVED,
  REJECTED,
  EXECUTING,
  COMPLETED,
  FAILED
}
