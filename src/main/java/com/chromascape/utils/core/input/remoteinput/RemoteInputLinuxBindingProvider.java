package com.chromascape.utils.core.input.remoteinput;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production {@link RemoteInputBindingProvider}: resolves and loads the pinned SC-1662 Linux
 * RemoteInput artifact through {@link NativeArtifactResolver} and {@link RemoteInputLibraryLoader}
 * — never any other loading mechanism — and is fail-closed on every failure.
 *
 * <p>The load is performed at most once and cached. Artifact resolution, manifest verification and
 * symbol binding failures are terminal: this provider never falls back to a different library and
 * never returns {@code null}, so a missing artifact surfaces as an explicit {@code
 * RemoteInputArtifactException} instead of a deferred {@code UnsatisfiedLinkError} inside a live
 * session.
 */
@Component
public final class RemoteInputLinuxBindingProvider implements RemoteInputBindingProvider {

  private final Path applicationRoot;
  private final Object lock = new Object();
  private volatile RemoteInputLibraryLoader.LoadedRemoteInput loaded;

  /**
   * Creates the provider.
   *
   * @param configuredApplicationRoot explicit application root containing {@code
   *     native/linux-x86_64}; empty is permitted only when an explicit artifact override exists
   */
  public RemoteInputLinuxBindingProvider(
      @Value("${chromascape.remoteinput.application-root:}") String configuredApplicationRoot) {
    this.applicationRoot =
        configuredApplicationRoot == null || configuredApplicationRoot.isBlank()
            ? null
            : Path.of(configuredApplicationRoot);
  }

  @Override
  public RemoteInputInterface load() {
    RemoteInputLibraryLoader.LoadedRemoteInput cached = loaded;
    if (cached != null) {
      return cached.binding();
    }
    synchronized (lock) {
      if (loaded == null) {
        Path artifact = new NativeArtifactResolver().resolve(applicationRoot);
        loaded = new RemoteInputLibraryLoader().load(artifact);
      }
      return loaded.binding();
    }
  }
}
