package com.chromascape.web.integration.adapter;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;

/** Registers all discovered BotRuntimeAdapter beans into RuntimeAdapterRegistry at startup. */
@Component
public class RuntimeAdapterRegistrar {

  private final RuntimeAdapterRegistry registry;
  private final List<BotRuntimeAdapter> adapters;

  public RuntimeAdapterRegistrar(RuntimeAdapterRegistry registry, List<BotRuntimeAdapter> adapters) {
    this.registry = registry;
    this.adapters = adapters;
  }

  @PostConstruct
  public void registerAll() {
    for (BotRuntimeAdapter adapter : adapters) {
      registry.register(adapter);
    }
  }
}
