package com.chromascape.web.replay.session;

/** Execution mode of a ChromaScape-owned xRSPS session. One session owns exactly one mode. */
public enum XrspsSessionMode {
  /** Actions come from a compiled recording, submitted in stable source order at relative ticks. */
  RECORDED_ACTIONS,
  /** Actions come from the AI task runner observing authoritative state. */
  AI_TASK
}
