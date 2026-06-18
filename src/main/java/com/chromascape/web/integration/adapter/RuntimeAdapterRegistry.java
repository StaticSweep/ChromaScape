package com.chromascape.web.integration.adapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registry of all available {@link BotRuntimeAdapter} instances.
 *
 * <p>Adapters self-register at startup via Spring injection. Consumers look up adapters by ID or
 * list all available adapters.
 */
@Component
public class RuntimeAdapterRegistry {

  private static final Logger logger = LoggerFactory.getLogger(RuntimeAdapterRegistry.class);

  private final Map<String, BotRuntimeAdapter> adapters = new ConcurrentHashMap<>();

  /** Registers an adapter. Called automatically by Spring for all BotRuntimeAdapter beans. */
  public void register(BotRuntimeAdapter adapter) {
    adapters.put(adapter.getAdapterId(), adapter);
    logger.info(
        "Registered runtime adapter: {} ({})", adapter.getAdapterId(), adapter.getDisplayName());
  }

  /** Unregisters an adapter. */
  public void unregister(String adapterId) {
    adapters.remove(adapterId);
    logger.info("Unregistered runtime adapter: {}", adapterId);
  }

  /** Returns the adapter with the given ID, if available. */
  public Optional<BotRuntimeAdapter> getAdapter(String adapterId) {
    return Optional.ofNullable(adapters.get(adapterId));
  }

  /** Returns all registered adapters. */
  public List<BotRuntimeAdapter> getAllAdapters() {
    return List.copyOf(adapters.values());
  }

  /** Returns all currently available adapters. */
  public List<BotRuntimeAdapter> getAvailableAdapters() {
    return adapters.values().stream().filter(BotRuntimeAdapter::isAvailable).toList();
  }
}
