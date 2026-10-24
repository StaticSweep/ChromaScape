package com.chromascape.web.artifact;

/** Selects an explicit durability mode for positive fixtures on the executing platform. */
public final class TestDurability {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

  private TestDurability() {}

  /**
   * POSIX platforms exercise the strict contract; Windows positive fixtures must opt in to the
   * explicitly unqualified development root. Refusal behaviour is covered by separate tests.
   */
  public static ImmutableArtifactStore.DurabilityMode positiveMode() {
    return WINDOWS
        ? ImmutableArtifactStore.DurabilityMode.DEVELOPMENT
        : ImmutableArtifactStore.DurabilityMode.STRICT;
  }

  public static boolean directoryForceSupported() {
    return !WINDOWS;
  }
}
