package com.chromascape.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BaseScriptFirstCycleTest {
  @Test
  void firstCycleHookRunsExactlyOnceBeforeCycles() {
    RecordingScript script = new RecordingScript(false);

    script.executeCycle();
    script.executeCycle();

    assertThat(script.events).hasToString("ICC");
  }

  @Test
  void failedFirstCycleHookDoesNotRunCycleOrMarkInitializationComplete() {
    RecordingScript script = new RecordingScript(true);

    assertThatThrownBy(script::executeCycle).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(script::executeCycle).isInstanceOf(IllegalStateException.class);

    assertThat(script.events).hasToString("II");
  }

  private static final class RecordingScript extends BaseScript {
    private final StringBuilder events = new StringBuilder();
    private final boolean failInitialization;

    private RecordingScript(boolean failInitialization) {
      this.failInitialization = failInitialization;
    }

    @Override
    protected void onFirstCycle() {
      events.append('I');
      if (failInitialization) {
        throw new IllegalStateException("initialization failed");
      }
    }

    @Override
    protected void cycle() {
      events.append('C');
    }
  }
}
