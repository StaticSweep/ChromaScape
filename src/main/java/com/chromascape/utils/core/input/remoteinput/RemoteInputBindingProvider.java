package com.chromascape.utils.core.input.remoteinput;

/**
 * Seam that supplies the audited SC-1662 {@link RemoteInputInterface} JNA binding.
 *
 * <p>Implementations must be <b>fail-closed</b>: when the binding cannot be produced they throw
 * instead of returning {@code null}, so attachment can never proceed against an unspecified
 * library. Loading happens at most once; the binding is cached.
 */
public interface RemoteInputBindingProvider {

  /**
   * Returns the audited RemoteInput binding.
   *
   * @return the bound {@link RemoteInputInterface}
   * @throws RemoteInputArtifactException when the native artifact cannot be resolved, verified, or
   *     loaded
   */
  RemoteInputInterface load();
}
