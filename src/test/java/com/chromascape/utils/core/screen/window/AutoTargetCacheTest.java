package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AutoTargetCacheTest {

  @Test
  void contextChangeDiscardsFreshEntry() {
    var cache = new AutoTargetCache<String>(1_000, clock::get, v -> true);
    assertEquals("a", cache.get("account-a", () -> "a"));
    assertEquals("b", cache.get("account-b", () -> "b"));
  }

  @Test
  void invalidationDuringResolutionCannotPublishTheDisplacedResult() {
    var cache = new AutoTargetCache<String>(1_000, clock::get, v -> true);
    assertNull(
        cache.get(
            () -> {
              cache.invalidate();
              return "stale";
            }));
    assertEquals("fresh", cache.get(() -> "fresh"));
  }

  @Test
  void concurrentMissesShareOneResolution() throws Exception {
    var cache = new AutoTargetCache<String>(1_000, clock::get, v -> true);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  cache.get(
                      () -> {
                        resolutions.incrementAndGet();
                        entered.countDown();
                        try {
                          if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("timeout");
                        } catch (InterruptedException e) {
                          throw new AssertionError(e);
                        }
                        return "canvas";
                      }));
      org.junit.jupiter.api.Assertions.assertTrue(
          entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      var second = executor.submit(() -> resolve(cache, "other"));
      release.countDown();
      assertEquals("canvas", first.get(3, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("canvas", second.get(3, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, resolutions.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void completedInvalidationDoesNotResurrectAnOldEntry() throws Exception {
    var cache = new AutoTargetCache<String>(1_000, clock::get, v -> true);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var lookup =
          executor.submit(
              () ->
                  cache.get(
                      () -> {
                        entered.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException e) {
                          throw new AssertionError(e);
                        }
                        return "old";
                      }));
      org.junit.jupiter.api.Assertions.assertTrue(
          entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      var invalidate = executor.submit(cache::invalidate);
      release.countDown();
      lookup.get(3, java.util.concurrent.TimeUnit.SECONDS);
      invalidate.get(3, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals("new", cache.get(() -> "new"));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  private final AtomicLong clock = new AtomicLong();
  private final AtomicInteger resolutions = new AtomicInteger();

  private String resolve(AutoTargetCache<String> cache, String value) {
    return cache.get(
        () -> {
          resolutions.incrementAndGet();
          return value;
        });
  }

  @Test
  void repeatedLookupsWithinTtlResolveOnce() {
    var cache = new AutoTargetCache<String>(1_000, clock::get, v -> true);
    for (int i = 0; i < 100; i++) {
      clock.set(i);
      assertEquals("canvas", resolve(cache, "canvas"));
    }
    assertEquals(1, resolutions.get());
  }

  @Test
  void expiredEntryIsResolvedAgain() {
    var cache = new AutoTargetCache<String>(1_000, clock::get, v -> true);
    resolve(cache, "a");
    clock.set(1_000);
    assertEquals("b", resolve(cache, "b"));
    assertEquals(2, resolutions.get());
  }

  @Test
  void invalidEntryIsResolvedAgainBeforeExpiry() {
    AtomicReference<String> alive = new AtomicReference<>("a");
    var cache2 = new AutoTargetCache<String>(1_000, clock::get, v -> v.equals(alive.get()));
    resolve(cache2, "a");
    alive.set("gone");
    assertEquals("b", resolve(cache2, "b"));
  }

  @Test
  void failedLookupIsNotCached() {
    var cache = new AutoTargetCache<String>(1_000, clock::get, v -> true);
    assertNull(resolve(cache, null));
    assertEquals("found", resolve(cache, "found"));
    assertEquals(2, resolutions.get());
  }
}
