package com.chromascape.web.recording.service;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongFunction;

/** FIFO image ownership with atomic count/byte admission and bounded encoder polling. */
final class BoundedFrameBuffer<T> {
  private record Entry<T>(T value, long bytes) {}

  record Admission(boolean accepted, int dropped) {}

  private final int capacity;
  private final long maxBytes;
  private final ToLongFunction<T> bytesOf;
  private final ArrayDeque<Entry<T>> queue = new ArrayDeque<>();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition available = lock.newCondition();
  private long bytes;

  BoundedFrameBuffer(int capacity, long maxBytes, ToLongFunction<T> bytesOf) {
    if (capacity < 1 || maxBytes < 1)
      throw new IllegalArgumentException("Positive buffer limits required");
    this.capacity = capacity;
    this.maxBytes = maxBytes;
    this.bytesOf = bytesOf;
  }

  Admission offer(T value) {
    long weight = bytesOf.applyAsLong(value);
    if (weight < 0) throw new IllegalArgumentException("Negative frame weight");
    lock.lock();
    try {
      if (weight > maxBytes) return new Admission(false, 1);
      int dropped = 0;
      while (queue.size() == capacity || bytes > maxBytes - weight) {
        bytes -= queue.removeFirst().bytes();
        dropped++;
      }
      queue.addLast(new Entry<>(value, weight));
      bytes += weight;
      available.signal();
      return new Admission(true, dropped);
    } finally {
      lock.unlock();
    }
  }

  T poll(long timeout, TimeUnit unit) throws InterruptedException {
    long remaining = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (queue.isEmpty() && remaining > 0) remaining = available.awaitNanos(remaining);
      Entry<T> result = queue.pollFirst();
      if (result == null) return null;
      bytes -= result.bytes();
      return result.value();
    } finally {
      lock.unlock();
    }
  }

  void clear() {
    lock.lock();
    try {
      queue.clear();
      bytes = 0;
    } finally {
      lock.unlock();
    }
  }

  int size() {
    lock.lock();
    try {
      return queue.size();
    } finally {
      lock.unlock();
    }
  }

  long bytes() {
    lock.lock();
    try {
      return bytes;
    } finally {
      lock.unlock();
    }
  }

  boolean isEmpty() {
    return size() == 0;
  }

  int remainingCapacity() {
    return capacity - size();
  }
}
