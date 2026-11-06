package com.chromascape.web.debug.tickdebugger.video.store;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Store-wide publication/collection exclusion shared by threads and JVMs on the same media volume.
 * A transactional caller retains the lease until commit/rollback, including after method return.
 * Reference writers and collectors additionally take the PostgreSQL publication fence so a JVM
 * dying during COMMIT cannot expose an older reference snapshot to a collector.
 */
final class MouseVideoPublicationLock {
  private static final ConcurrentHashMap<Path, State> LOCKS = new ConcurrentHashMap<>();

  private MouseVideoPublicationLock() {}

  private static final class State {
    private final ReentrantLock mutex = new ReentrantLock(true);
    private FileChannel channel;
    private FileLock file;
  }

  static Lease acquire(Path root) {
    State state = LOCKS.computeIfAbsent(root, ignored -> new State());
    boolean acquired = false;
    try {
      if (!state.mutex.tryLock(10, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new MouseVideoException(
            MouseVideoException.CODE_QUOTA_EXCEEDED,
            "Publication capacity is busy; retry shortly",
            true);
      }
      acquired = true;
      if (state.mutex.getHoldCount() == 1) {
        state.channel =
            FileChannel.open(
                root.resolve(".publication.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while ((state.file = state.channel.tryLock()) == null) {
          if (System.nanoTime() >= deadline) throw new IOException("Publication lock timed out");
          Thread.sleep(20);
        }
      }
      return new Lease(state);
    } catch (IOException | InterruptedException failure) {
      if (acquired) {
        if (state.channel != null) {
          try {
            state.channel.close();
          } catch (IOException ignored) {
          }
        }
        state.mutex.unlock();
      }
      if (failure instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        throw new MouseVideoException(
            MouseVideoException.CODE_INTERRUPTED, "Publication wait was interrupted", failure);
      }
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED,
          "Shared media publication guard is unavailable",
          failure);
    }
  }

  static final class Lease implements MouseVideoByteStore.PublicationLease {
    private final State state;
    private boolean closed;
    private boolean deferred;

    private Lease(State state) {
      this.state = state;
    }

    @Override
    public void close() {
      if (closed || deferred) return;
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        deferred = true;
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                release();
              }
            });
      } else {
        release();
      }
    }

    private void release() {
      if (closed) return;
      closed = true;
      try {
        if (state.mutex.getHoldCount() == 1) {
          try {
            state.file.release();
          } finally {
            state.channel.close();
          }
        }
      } catch (IOException failure) {
        org.slf4j.LoggerFactory.getLogger(MouseVideoPublicationLock.class)
            .warn("Media publication guard release failed", failure);
      } finally {
        state.mutex.unlock();
      }
    }
  }
}
