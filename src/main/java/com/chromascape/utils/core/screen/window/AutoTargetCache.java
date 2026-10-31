package com.chromascape.utils.core.screen.window;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Short-lived cache for the auto-detected capture target.
 *
 * <p>Auto-detection enumerates every desktop window and probes child windows, which is far too
 * expensive to repeat for every captured frame. The cached value is reused until it expires or
 * stops being valid, then resolved again. Failed lookups (null) are never cached.
 */
final class AutoTargetCache<T> {

  private record Entry<T>(T value, long resolvedAtNanos) {}

  private final long ttlNanos;
  private final LongSupplier clockNanos;
  private final Predicate<T> stillValid;
  private Entry<T> entry;
  private Object context;
  private long generation;

  AutoTargetCache(long ttlNanos, LongSupplier clockNanos, Predicate<T> stillValid) {
    this.ttlNanos = ttlNanos;
    this.clockNanos = clockNanos;
    this.stillValid = stillValid;
  }

  /** Returns the cached value while fresh and valid, otherwise resolves and caches a new one. */
  synchronized T get(Supplier<T> resolver) {
    return get(null, resolver);
  }

  synchronized T get(Object requestedContext, Supplier<T> resolver) {
    if (!Objects.equals(context, requestedContext)) {
      invalidate();
      context = requestedContext;
    }
    long now = clockNanos.getAsLong();
    Entry<T> current = entry;
    if (current != null
        && now - current.resolvedAtNanos() < ttlNanos
        && stillValid.test(current.value())) {
      return current.value();
    }
    long resolvingGeneration = generation;
    T resolved = resolver.get();
    // A resolver can reenter invalidation; never republish its displaced result.
    if (generation != resolvingGeneration) return null;
    entry = resolved == null ? null : new Entry<>(resolved, clockNanos.getAsLong());
    return resolved;
  }

  synchronized void invalidate() {
    entry = null;
    generation++;
  }
}
