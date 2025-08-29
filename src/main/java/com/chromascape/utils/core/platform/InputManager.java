package com.chromascape.utils.core.platform;

/**
 * Abstract interface for input injection operations across different platforms. This allows
 * platform-specific implementations while maintaining a consistent API.
 */
public interface InputManager {

  /**
   * Creates an input instance for the specified process ID.
   *
   * @param processId The target process ID
   * @return true if creation succeeded, false otherwise
   */
  boolean createInputInstance(int processId);

  /**
   * Deletes an input instance for the specified process ID.
   *
   * @param processId The target process ID
   * @return true if deletion succeeded, false otherwise
   */
  boolean deleteInputInstance(int processId);

  /**
   * Sends a keyboard event to the target process.
   *
   * @param processId The target process ID
   * @param eventId The keyboard event ID
   * @param when Event timestamp for queuing
   * @param modifiers Key modifiers
   * @param keyCode Virtual key code
   * @param keyChar The character associated
   * @param keyLocation Key location
   * @return true if the event was successfully sent, false otherwise
   */
  boolean sendKeyEvent(
      int processId,
      int eventId,
      long when,
      int modifiers,
      int keyCode,
      short keyChar,
      int keyLocation);

  /**
   * Sends a mouse event to the target process.
   *
   * @param processId The target process ID
   * @param eventId The mouse event type ID
   * @param when Event timestamp for queuing
   * @param modifiers Mouse modifiers
   * @param x The x-coordinate of the mouse event
   * @param y The y-coordinate of the mouse event
   * @param clickCount Number of clicks
   * @param popupTrigger Whether this event should pop up
   * @param button Mouse button ID
   * @return true if the event was successfully sent, false otherwise
   */
  boolean sendMouseEvent(
      int processId,
      int eventId,
      long when,
      int modifiers,
      int x,
      int y,
      int clickCount,
      boolean popupTrigger,
      int button);

  /**
   * Sends a focus event to the target process.
   *
   * @param processId The target process ID
   * @param eventId The focus event type (e.g., gain or loss)
   * @return true if event succeeded, false otherwise
   */
  boolean sendFocusEvent(int processId, int eventId);

  /**
   * Checks if the platform supports this input manager implementation.
   *
   * @return true if supported, false otherwise
   */
  boolean isSupported();

  /**
   * Gets a description of the input manager implementation.
   *
   * @return A string describing the implementation
   */
  String getImplementationDescription();
}
