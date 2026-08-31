package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IsolatedPatchWorkerTest {

  @Test
  void excludesOnlyGradleCoordinationLocksFromThePrivateCacheSeed() {
    assertTrue(IsolatedPatchWorker.isGradleCoordinationFile(
        Path.of("caches", "8.14.2", "fileContent", "fileContent.lock")));
    assertTrue(IsolatedPatchWorker.isGradleCoordinationFile(Path.of("modules-2.lock")));
    assertFalse(IsolatedPatchWorker.isGradleCoordinationFile(
        Path.of("caches", "modules-2", "files-2.1", "dependency.jar")));
    assertFalse(IsolatedPatchWorker.isGradleCoordinationFile(Path.of("gc.properties")));
  }
}
