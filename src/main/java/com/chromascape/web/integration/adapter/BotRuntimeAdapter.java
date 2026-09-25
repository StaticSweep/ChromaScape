package com.chromascape.web.integration.adapter;

/**
 * Contract for bot runtime adapters.
 *
 * <p>Adapters wrap specific bot runtimes (Micro6 client, Microbot Agent Server, etc.) to provide a
 * unified interface for state snapshots, event streaming, and action dispatch.
 */
public interface BotRuntimeAdapter {

  /** Returns the unique adapter identifier (e.g., "micro6", "microbot-agent"). */
  String getAdapterId();

  /** Returns the display name for UI rendering. */
  String getDisplayName();

  /** Returns true if the adapter is currently connected and operational. */
  boolean isAvailable();

  /** Fetches a current state snapshot from the runtime. */
  RuntimeStateSnapshot fetchStateSnapshot();

  /** Dispatches an action to the runtime for execution. */
  RuntimeAction dispatchAction(RuntimeAction action);

  /** Registers a listener for runtime events. */
  void addEventListener(RuntimeEventListener listener);

  /** Removes a previously registered listener. */
  void removeEventListener(RuntimeEventListener listener);

  /** Listener interface for runtime events. */
  interface RuntimeEventListener {
    void onEvent(RuntimeEventEnvelope event);
  }
}
