package com.chromascape.utils.core.state;

/** Interface for listening to changes in the bot's semantic state. */
public interface BotStateListener {

  /**
   * Called when the bot transitions to a new state.
   *
   * @param state The new {@link BotState}.
   */
  void onStateChange(BotState state);
}
