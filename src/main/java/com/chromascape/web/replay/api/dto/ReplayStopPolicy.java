package com.chromascape.web.replay.api.dto;

/** Stop policies control when the mismatch engine halts a run. */
public enum ReplayStopPolicy {
  STOP_ON_FIRST_HARD,
  COLLECT_UNTIL_CHECKPOINT,
  COLLECT_ALL,
  /** Execute every action and collect comparison results without an early stop. */
  RUN_TO_END
}
