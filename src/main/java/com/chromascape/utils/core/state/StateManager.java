package com.chromascape.utils.core.state;

/**
 * Singleton manager for tracking and broadcasting the bot's semantic state.
 *
 * <p>This class serves as the bridge between core bot logic (which triggers state changes) and the
 * presentation layer (which listens for them), without introducing direct dependencies.
 */
public class StateManager {

  private static BotStateListener listener = state -> {}; // Default No-Op
  private static BotState currentState = BotState.WAITING;

  private StateManager() {}

  /**
   * Sets the listener that will receive state change updates.
   *
   * @param newListener The listener implementation (e.g. a websocket bridge).
   */
  public static void setListener(BotStateListener newListener) {
    listener = newListener;
  }

  /**
   * Transitions the bot to a new semantic state.
   *
   * <p>If the new state is different from the current state, the registered listener is notified.
   *
   * @param newState The state to transition to.
   */
  public static void setState(BotState newState) {
    if (currentState != newState) {
      currentState = newState;
      listener.onStateChange(newState);
    }
  }

  /**
   * Gets the current state of the bot.
   *
   * @return The active {@link BotState}.
   */
  public static BotState getState() {
    return currentState;
  }
}
