package com.chromascape.web.instance;

import com.chromascape.base.BaseScript;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Manages the lifecycle of a script instance.
 *
 * <p>This class dynamically loads and instantiates a script class based on the provided
 * configuration, runs the script in its own thread, and provides control methods to start and stop
 * the script execution.
 */
public class ScriptInstance {

  private final BaseScript instance;
  private volatile Thread thread;
  private final WebSocketStateHandler stateHandler;

  /**
   * Constructs a ScriptInstance by dynamically loading the script class specified in the config.
   *
   * <p>The script class must have a constructor with the signature: {@code (boolean fixed)}.
   *
   * @param config the RunConfig containing script name, duration, and UI layout flag
   * @throws NoSuchMethodException if the expected constructor is not found
   * @throws ClassNotFoundException if the script class cannot be found
   * @throws InvocationTargetException if the constructor throws an exception
   * @throws InstantiationException if the class is abstract or an interface
   * @throws IllegalAccessException if the constructor is not accessible
   */
  public ScriptInstance(RunConfig config, WebSocketStateHandler stateHandler)
      throws NoSuchMethodException,
          ClassNotFoundException,
          InvocationTargetException,
          InstantiationException,
          IllegalAccessException {
    this.stateHandler = stateHandler;

    String fileName = config.getScript();
    String className = fileName.replace(".java", "");

    Class<?> script = Class.forName("com.chromascape.scripts." + className);
    Constructor<?> constructor = script.getDeclaredConstructor(boolean.class);
    instance = (BaseScript) constructor.newInstance(config.isFixed());
  }

  /** Starts the script execution in a new thread. */
  public void start() {
    thread =
        new Thread(
            () -> {
              stateHandler.broadcast(true);
              try {
                instance.run();
              } finally {
                stateHandler.broadcast(false);
              }
            });
    thread.start();
  }

  /**
   * Stops the script execution by requesting the script to stop, interrupting the running thread,
   * and waiting for it to terminate.
   */
  public void stop() {
    instance.stop();
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException ignored) {
        // Thread join interrupted, ignore to proceed with shutdown
      }
    }
    stateHandler.broadcast(false);
  }
}
