package com.chromascape.web.replay.worker;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Explicit authored integration lane; ordinary and certification tasks do not select this class. */
@Tag("native-database-continuation")
class NativeDatabaseContinuationTest extends NativeCheckpointPersistenceTest {
  @Test
  void finalizedDatabaseAnchorsRestoreInSixFreshNativeWorkers() throws Exception {
    NativeDatabaseContinuation.verify(
        this,
        requiredPath("SC276_NATIVE_DATABASE_INPUT"),
        requiredPath("SC276_NATIVE_DATABASE_OUTPUT"),
        requiredPath("SC274_NATIVE_REPLAY_ROOT"));
  }

  private static Path requiredPath(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Required native/database verification prerequisite: " + name);
    }
    return Path.of(value).toAbsolutePath();
  }
}
