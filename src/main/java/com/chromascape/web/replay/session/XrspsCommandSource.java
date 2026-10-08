package com.chromascape.web.replay.session;

/** Origin of a session command. Mode-specific source validation stays explicit per mode. */
public enum XrspsCommandSource {
  RECORDED_ACTION,
  AI_DECISION
}
