package com.chromascape.web.instance;

/**
 * Singleton manager class that maintains the current active ScriptInstance.
 *
 * <p>This class provides thread-safe access to a single ScriptInstance, allowing setting and
 * retrieving the currently running script instance.
 */
public class ScriptInstanceManager {
  private static ScriptInstanceManager instance;
  private ScriptInstance currentInstance;

  /** Private constructor to enforce singleton pattern. */
  private ScriptInstanceManager() {}

  /**
   * Returns the singleton instance of ScriptInstanceManager.
   *
   * <p>This method is synchronized to ensure thread-safe lazy initialization.
   *
   * @return the singleton ScriptInstanceManager instance
   */
  public static synchronized ScriptInstanceManager getInstance() {
    if (instance == null) {
      instance = new ScriptInstanceManager();
    }
    return instance;
  }

  /**
   * Sets the current active ScriptInstance.
   *
   * @param scriptInstance the ScriptInstance to set as current
   */
  public void setInstance(ScriptInstance scriptInstance) {
    this.currentInstance = scriptInstance;
  }

  /**
   * Returns a reference to the current active ScriptInstance.
   *
   * @return the current ScriptInstance, or null if none is set
   */
  public ScriptInstance getInstanceRef() {
    return currentInstance;
  }
}
