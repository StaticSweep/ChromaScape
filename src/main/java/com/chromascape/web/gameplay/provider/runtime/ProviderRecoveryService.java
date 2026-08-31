package com.chromascape.web.gameplay.provider.runtime;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Performs proof-conservative restart classification after persistence is available. */
@Component
public final class ProviderRecoveryService {
  private final GameplayProviderRuntime runtime;
  private final ProviderRuntimeProperties properties;

  public ProviderRecoveryService(
      GameplayProviderRuntime runtime, ProviderRuntimeProperties properties) {
    this.runtime = runtime;
    this.properties = properties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    if (properties.isEnabled()) {
      runtime.recoverAtStartup();
    }
  }
}
