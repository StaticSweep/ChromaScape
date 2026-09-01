/**
 * Pure lifecycle helpers for the debugger's recording/session synchronization.
 *
 * The browser page uses these helpers for generation and identity decisions; keeping them free of
 * fetch, DOM, and timers makes the reconnect-storm behavior testable without a browser.
 */
(function (global) {
  'use strict';

  function normalizeAccount(value) {
    return value == null ? '' : String(value).trim().toLowerCase();
  }

  function sessionCreatedKey(windowId, accountName, sessionId, shadowRunId) {
    return [
      windowId == null ? '' : String(windowId),
      normalizeAccount(accountName),
      sessionId == null ? '' : String(sessionId),
      shadowRunId == null ? '' : String(shadowRunId)
    ].join('|');
  }

  function isDuplicateSessionCreated(lastKey, nextKey) {
    return Boolean(nextKey) && lastKey === nextKey;
  }

  function acceptsGeneration(capturedGeneration, currentGeneration, signal, requestedSession, trackedSession, requestedRun, trackedRun) {
    if (signal && signal.aborted) return false;
    if (capturedGeneration !== currentGeneration) return false;
    if (requestedSession && requestedSession !== trackedSession) return false;
    if (requestedRun && requestedRun !== trackedRun) return false;
    return true;
  }

  function recordingTransition(previousId, previousActive, nextId, nextActive) {
    const changed = previousId !== nextId || Boolean(previousActive) !== Boolean(nextActive);
    return {
      changed,
      recordingChanged: previousId !== nextId,
      becameActive: Boolean(nextActive) && !Boolean(previousActive),
      becameInactive: !Boolean(nextActive) && Boolean(previousActive),
      nextId: nextId || null,
      nextActive: Boolean(nextActive)
    };
  }

  function shouldNotifyRecording(previousNotificationId, recordingId) {
    return Boolean(recordingId) && previousNotificationId !== recordingId;
  }

  const api = {
    normalizeAccount,
    sessionCreatedKey,
    isDuplicateSessionCreated,
    acceptsGeneration,
    recordingTransition,
    shouldNotifyRecording
  };

  global.DebuggerSessionSyncCore = api;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})(typeof window !== 'undefined' ? window : globalThis);
