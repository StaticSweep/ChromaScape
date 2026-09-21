package com.chromascape.utils.core.input;

import java.util.concurrent.atomic.AtomicReference;

/** Narrow runtime bridge for controllers created outside Spring. */
public final class TargetInputRuntime {
  private static final AtomicReference<TargetInputSessionFactory> FACTORY = new AtomicReference<>();

  private TargetInputRuntime() {}

  /**
   * Installs the Spring-built factory; called once by {@link TargetInputRuntimeInitializer}.
   *
   * @param factory the factory to install
   */
  public static void install(TargetInputSessionFactory factory) {
    FACTORY.set(factory);
  }

  /**
   * Returns the installed factory.
   *
   * @return the factory
   * @throws IllegalStateException when Spring startup has not completed yet
   */
  public static TargetInputSessionFactory factory() {
    TargetInputSessionFactory factory = FACTORY.get();
    if (factory == null) {
      throw new IllegalStateException(
          "Target input runtime is not initialized; wait for Spring startup");
    }
    return factory;
  }

  /** Test-only reset. */
  static void resetForTests() {
    FACTORY.set(null);
  }
}
