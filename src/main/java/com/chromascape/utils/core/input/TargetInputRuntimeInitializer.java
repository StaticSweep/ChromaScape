package com.chromascape.utils.core.input;

import org.springframework.stereotype.Component;

/** Installs the {@link TargetInputRuntime} bridge once the factory bean exists. */
@Component
public final class TargetInputRuntimeInitializer {

  /**
   * Creates the initializer and installs the factory.
   *
   * @param factory the Spring-built factory
   */
  public TargetInputRuntimeInitializer(TargetInputSessionFactory factory) {
    TargetInputRuntime.install(factory);
  }
}
