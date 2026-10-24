package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** Each method is selected by its own Gradle Test task and therefore runs in a different JVM. */
@Tag("native-database-restart")
@Import({
  ReplayExpiredAttemptRecoveryTransaction.class,
  ReplayRecoveryReportTransaction.class,
  ReplayRecoveryReportWinnerReader.class,
  ReplayRecoveryReportWriter.class,
  ReplayExpiredAttemptRecoveryService.class
})
class NativeDatabaseRestartTest extends NativeCheckpointPersistenceTest {
  @Autowired StateHashSampleRepository samples;
  @Autowired ReplayExpiredAttemptRecoveryService recovery;
  @Autowired ReplayRecoveryReportWriter recoveryWriter;

  @Test
  void prepareCommittedAnchorsAndQueuedChildren() throws Exception {
    NativeDatabaseContinuation.prepare(
        this, required("SC276_NATIVE_DATABASE_INPUT"), root().resolve("prepare"), runtime());
  }

  @Test
  void restoreCommittedChildrenInDifferentJvm() throws Exception {
    NativeRestartRecovery.verify(this, recovery, recoveryWriter, root());
    NativeDatabaseContinuation.resume(
        this,
        required("SC276_NATIVE_DATABASE_INPUT"),
        root().resolve("prepare/native-database-continuation.json"),
        root().resolve("resume"),
        runtime());
    NativeRestartHttpEvidence.verify(this, samples, root());
  }

  private static Path root() {
    return required("SC276_NATIVE_RESTART_ROOT");
  }

  private static Path runtime() {
    return required("SC274_NATIVE_REPLAY_ROOT");
  }

  private static Path required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Required native restart prerequisite: " + name);
    }
    return Path.of(value).toAbsolutePath();
  }
}
