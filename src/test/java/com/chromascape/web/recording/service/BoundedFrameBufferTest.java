package com.chromascape.web.recording.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedFrameBufferTest {
  @Test
  void dropsOldestUntilBothLimitsHold() throws Exception {
    var buffer = new BoundedFrameBuffer<Long>(3, 10, Long::longValue);
    buffer.offer(4L);
    buffer.offer(4L);
    assertEquals(1, buffer.offer(5L).dropped());
    assertEquals(9, buffer.bytes());
    assertEquals(4L, buffer.poll(0, TimeUnit.MILLISECONDS));
    assertEquals(5L, buffer.poll(0, TimeUnit.MILLISECONDS));
    assertEquals(0, buffer.bytes());
    assertNull(buffer.poll(1, TimeUnit.MILLISECONDS));
  }

  @Test
  void oversizedImageCannotEvictOrOverflowExistingFrames() {
    var buffer = new BoundedFrameBuffer<Long>(2, 10, Long::longValue);
    buffer.offer(5L);
    assertFalse(buffer.offer(11L).accepted());
    assertEquals(5, buffer.bytes());
    assertEquals(1, buffer.size());
    buffer.clear();
    assertEquals(0, buffer.bytes());
    assertTrue(buffer.isEmpty());
  }

  @Test
  void concurrentProducerConsumerAndClearNeverCorruptByteAccounting() throws Exception {
    var buffer = new BoundedFrameBuffer<Integer>(8, 32, Integer::longValue);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    var producer =
        new Thread(
            () -> {
              try {
                for (int i = 0; i < 10_000; i++) buffer.offer(4);
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    var consumer =
        new Thread(
            () -> {
              try {
                for (int i = 0; i < 10_000; i++) {
                  buffer.poll(0, TimeUnit.MILLISECONDS);
                  if (i % 17 == 0) buffer.clear();
                  long bytes = buffer.bytes();
                  if (bytes < 0 || bytes > 32)
                    throw new AssertionError("Unbounded bytes: " + bytes);
                }
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    producer.start();
    consumer.start();
    producer.join(5000);
    consumer.join(5000);
    assertFalse(producer.isAlive());
    assertFalse(consumer.isAlive());
    assertNull(failure.get());
    int count = buffer.size();
    assertEquals(count * 4L, buffer.bytes());
    buffer.clear();
    assertEquals(0, buffer.bytes());
  }

  @Test
  void waitsAreInterruptible() throws Exception {
    var buffer = new BoundedFrameBuffer<Integer>(1, 4, Integer::longValue);
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> buffer.poll(10, TimeUnit.SECONDS));
    } finally {
      Thread.interrupted();
    }
  }
}
