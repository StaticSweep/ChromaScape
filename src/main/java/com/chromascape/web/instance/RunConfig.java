package com.chromascape.web.instance;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents the configuration settings for running a script instance.
 *
 * <p>Contains the script identifier and optional task plan context for linking script execution to
 * synthesized task plans.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunConfig(String script, String taskPlanId, String taskItemId) {

  /** Constructs a RunConfig with script only (backward compatible). */
  public RunConfig(String script) {
    this(script, null, null);
  }

  /**
   * Constructs a new RunConfig with optional task plan context.
   *
   * @param script the identifier or name of the script to run
   * @param taskPlanId optional task plan ID for plan-linked execution
   * @param taskItemId optional task plan item ID for plan-linked execution
   */
  public RunConfig {}

  /**
   * Returns the identifier or name of the script to run.
   *
   * @return the script name or ID
   */
  @Override
  public String script() {
    return script;
  }

  /** Returns the optional task plan ID. */
  public String taskPlanId() {
    return taskPlanId;
  }

  /** Returns the optional task plan item ID. */
  public String taskItemId() {
    return taskItemId;
  }
}
