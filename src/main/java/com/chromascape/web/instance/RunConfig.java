package com.chromascape.web.instance;

/**
 * Represents the configuration settings for running a script instance.
 *
 * <p>Contains the duration the script should run, the script identifier, and a flag indicating
 * whether the client UI is fixed or resizable.
 */
public record RunConfig(String script) {

  /**
   * Constructs a new RunConfig with the specified duration, script, and fixed flag.
   *
   * @param script the identifier or name of the script to run
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
}
