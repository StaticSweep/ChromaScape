package com.chromascape.utils.core.screen.capture;

/**
 * A frame source that observes without attaching to, injecting into, or driving the target.
 *
 * <p>View-only sources exist so the debugger viewport can watch a client that is driven elsewhere
 * (for example a self-driving Builder Goal run). They expose no input capability at all: no mouse,
 * no keyboard, no focus changes, no process injection.
 *
 * <p>Lifecycle contract, enforced by {@link TargetFrameRuntime}:
 *
 * <ul>
 *   <li>A view-only source installs only when no source is active; it can never steal or replace an
 *       active attachment.
 *   <li>A view-only source is strictly preemptible: installing a real input session later evicts
 *       it, and the runtime closes it so native pipelines cannot leak.
 *   <li>{@link #close()} is idempotent and safe to call after eviction.
 * </ul>
 */
public interface ViewOnlyFrameSource extends TargetFrameSource {

  /**
   * Releases native resources (capture processes, reader threads). Called on observe-stop and when
   * a real input session preempts this source. Implementations must tolerate being called more than
   * once and after eviction.
   */
  void close();
}
