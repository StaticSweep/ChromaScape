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
    assertTrue(IsolatedPatchWorker.isGradleCoordinationFile(Path.of("gradle-8.8-all.zip.lck")));
    assertFalse(IsolatedPatchWorker.isGradleCoordinationFile(
        Path.of("caches", "modules-2", "files-2.1", "dependency.jar")));
    assertFalse(IsolatedPatchWorker.isGradleCoordinationFile(Path.of("gc.properties")));
  }

  @Test
  void copiesOnlyTheExecutableGradleDistributionIntoThePrivateHome() {
    assertTrue(IsolatedPatchWorker.isGradleRuntimePath(Path.of("hash")));
    assertTrue(IsolatedPatchWorker.isGradleRuntimePath(Path.of("hash", "gradle.zip.ok")));
    assertTrue(IsolatedPatchWorker.isGradleRuntimePath(
        Path.of("hash", "gradle-8.8", "bin", "gradle.bat")));
    assertTrue(IsolatedPatchWorker.isGradleRuntimePath(
        Path.of("hash", "gradle-8.8", "lib", "gradle-launcher-8.8.jar")));
    assertFalse(IsolatedPatchWorker.isGradleRuntimePath(
        Path.of("hash", "gradle-8.8", "docs", "long-generated-name.html")));
    assertFalse(IsolatedPatchWorker.isGradleRuntimePath(
        Path.of("hash", "gradle-8.8", "src", "core", "Source.java")));
  }
}
