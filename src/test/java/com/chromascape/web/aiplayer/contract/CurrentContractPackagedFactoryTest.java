package com.chromascape.web.aiplayer.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Loads the selected production capture factories from their exact packaged JARs, with no source
 * classpath shadowing, and proves the advertised running artifact digest equals those bytes.
 * Requires the packaged distributions; run explicitly with sc663PackageTest.
 */
class CurrentContractPackagedFactoryTest {
  private static final String MICRO6 =
      "net.runelite.client.plugins.airoutinecapture.Micro6CaptureIdentityFactory";
  private static final String RUNELITE =
      "net.runelite.client.plugins.airoutinecapture.RuneLiteCaptureIdentityFactory";
  private static final String TASK = "task";
  private static final String SETTINGS = "sha256:" + "a".repeat(64);
  private static final String PROFILE = "profile";
  private static final String PROFILE_HASH = "sha256:" + "b".repeat(64);
  private static final String CATALOG = "sha256:" + "c".repeat(64);
  private static final String SNAPSHOT = "srcsnap_" + "d".repeat(24);
  private static final String MANIFEST = "sha256:" + "e".repeat(64);
  private static final String CLIENT = "client";

  private static Path property(String name) {
    String value = System.getProperty(name, "");
    if (value.isBlank())
      throw new IllegalStateException(name + " must name a packaged client artifact");
    Path path = Path.of(value).toAbsolutePath();
    if (!Files.isRegularFile(path))
      throw new IllegalStateException(name + " is not a file: " + path);
    return path;
  }

  private static String sha256(Path path) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  private static String invoke(
      ClassLoader loader, String factory, String method, Object... arguments) throws Exception {
    Class<?> type = Class.forName(factory, false, loader);
    Object instance = type.getDeclaredConstructor().newInstance();
    Method target =
        java.util.Arrays.stream(type.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(method))
            .filter(candidate -> candidate.getParameterCount() == arguments.length)
            .findFirst()
            .orElseThrow();
    target.setAccessible(true);
    return String.valueOf(target.invoke(instance, arguments));
  }

  private static Throwable refusal(ClassLoader loader, String factory, Object... arguments) {
    try {
      invoke(loader, factory, "initial", arguments);
      return null;
    } catch (InvocationTargetException exception) {
      return exception.getCause();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static Object context(ClassLoader loader, String runId, String snapshot, String manifest)
      throws Exception {
    Class<?> type = Class.forName(MICRO6 + "$CaptureContext", false, loader);
    Constructor<?> constructor =
        type.getDeclaredConstructor(
            String.class, String.class, String.class, String.class, String.class, String.class);
    constructor.setAccessible(true);
    boolean managed = runId != null;
    return constructor.newInstance(
        runId,
        managed ? runId : null,
        managed ? "1" : null,
        managed ? runId : null,
        snapshot,
        manifest);
  }

  private static URLClassLoader loader(Path artifact) throws Exception {
    return new URLClassLoader(
        new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
  }

  @Test
  void packagedMicro6FactoryLoadsFromJarAndAdvertisesMeasuredDigest() throws Exception {
    Path artifact = property("sc663.packaged.micro6");
    try (var loader = loader(artifact)) {
      Class<?> factory = Class.forName(MICRO6, false, loader);
      assertThat(Path.of(factory.getProtectionDomain().getCodeSource().getLocation().toURI()))
          .isEqualTo(artifact);
      String digest = invoke(loader, MICRO6, "runningArtifactDigest").replace("\"", "").trim();
      assertThat(digest).isEqualTo(sha256(artifact));
    }
  }

  @Test
  void packagedRuneLiteFactoryLoadsFromJarAndAdvertisesMeasuredDigest() throws Exception {
    Path artifact = property("sc663.packaged.runelite");
    try (var loader = loader(artifact)) {
      Class<?> factory = Class.forName(RUNELITE, false, loader);
      assertThat(Path.of(factory.getProtectionDomain().getCodeSource().getLocation().toURI()))
          .isEqualTo(artifact);
      String digest = invoke(loader, RUNELITE, "artifactDigest").replace("\"", "").trim();
      assertThat(digest).isEqualTo(sha256(artifact));
    }
  }

  @Test
  void packagedMicro6FactoryRefusesInventedSourceAndUnmanagedProvenance() throws Exception {
    Path artifact = property("sc663.packaged.micro6");
    try (var loader = loader(artifact)) {
      Throwable humanWithSource =
          refusal(
              loader,
              MICRO6,
              TASK,
              SETTINGS,
              PROFILE,
              PROFILE_HASH,
              CATALOG,
              CLIENT,
              context(loader, null, SNAPSHOT, MANIFEST));
      assertThat(humanWithSource).hasMessage("RUNELITE_CAPTURE_TASK_SOURCE_UNSUPPORTED");
      Throwable managedWithoutSource =
          refusal(
              loader,
              MICRO6,
              TASK,
              SETTINGS,
              PROFILE,
              PROFILE_HASH,
              CATALOG,
              CLIENT,
              context(loader, "run", null, null));
      assertThat(managedWithoutSource).hasMessage("IDENTITY_SOURCE_CONTEXT_REQUIRED");
      Throwable managedWithoutBundle =
          refusal(
              loader,
              MICRO6,
              TASK,
              SETTINGS,
              PROFILE,
              PROFILE_HASH,
              CATALOG,
              CLIENT,
              context(loader, "run", SNAPSHOT, MANIFEST));
      assertThat(managedWithoutBundle).hasMessage("IDENTITY_SOURCE_PROVENANCE_UNAVAILABLE");
    }
  }

  @Test
  void packagedRuneLiteFactoryRefusesInventedTaskSource() throws Exception {
    Path artifact = property("sc663.packaged.runelite");
    try (var loader = loader(artifact)) {
      assertThat(
              invoke(
                  loader,
                  RUNELITE,
                  "initial",
                  TASK,
                  SETTINGS,
                  PROFILE,
                  PROFILE_HASH,
                  CATALOG,
                  CLIENT,
                  null,
                  null))
          .isNotBlank();
      Throwable refusal =
          refusal(
              loader,
              RUNELITE,
              TASK,
              SETTINGS,
              PROFILE,
              PROFILE_HASH,
              CATALOG,
              CLIENT,
              SNAPSHOT,
              MANIFEST);
      assertThat(refusal).hasMessage("RUNELITE_CAPTURE_TASK_SOURCE_UNSUPPORTED");
    }
  }

  @Test
  void staleMicro6JarLacksTheCurrentSourceRefusal() throws Exception {
    Path stale = property("sc663.stale.micro6");
    assertThat(stale).isNotEqualTo(property("sc663.packaged.micro6"));
    try (var loader = loader(stale)) {
      Throwable staleRefusal =
          refusal(
              loader,
              MICRO6,
              TASK,
              SETTINGS,
              PROFILE,
              PROFILE_HASH,
              CATALOG,
              CLIENT,
              context(loader, null, SNAPSHOT, MANIFEST));
      assertThat(staleRefusal)
          .as("stale Micro6 JAR must not satisfy the current provenance gate")
          .isNull();
    }
    try (var loader = loader(property("sc663.packaged.micro6"))) {
      Throwable currentRefusal =
          refusal(
              loader,
              MICRO6,
              TASK,
              SETTINGS,
              PROFILE,
              PROFILE_HASH,
              CATALOG,
              CLIENT,
              context(loader, null, SNAPSHOT, MANIFEST));
      assertThat(currentRefusal).hasMessage("RUNELITE_CAPTURE_TASK_SOURCE_UNSUPPORTED");
    }
  }
}
