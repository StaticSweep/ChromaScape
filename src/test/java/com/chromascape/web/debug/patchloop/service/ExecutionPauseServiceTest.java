package com.chromascape.web.debug.patchloop.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link ExecutionPauseService} focusing on pause lifecycle. */
class ExecutionPauseServiceTest {

  private ExecutionPauseService pauseService;

  private String executionId;

  @BeforeEach
  void setUp() {
    pauseService = new ExecutionPauseService();
    executionId = "exec_" + System.currentTimeMillis();
  }

  @Test
  @DisplayName("setPaused adds execution to paused state")
  void testSetPausedAddsToPauseState() {
    // When: setting execution as paused
    pauseService.setPaused(executionId, true);

    // Then: execution should be paused
    assertThat(pauseService.isPaused(executionId)).isTrue();
    assertThat(pauseService.getPausedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("setPaused with false removes execution from paused state")
  void testSetPausedWithFalseRemovesPauseState() {
    // Given: execution is paused
    pauseService.setPaused(executionId, true);

    // When: unpausing
    pauseService.setPaused(executionId, false);

    // Then: execution should not be paused
    assertThat(pauseService.isPaused(executionId)).isFalse();
    assertThat(pauseService.getPausedCount()).isEqualTo(0);
  }

  @Test
  @DisplayName("clearPaused removes execution from paused state")
  void testClearPausedRemovesPauseState() {
    // Given: execution is paused
    pauseService.setPaused(executionId, true);

    // When: clearing pause
    pauseService.clearPaused(executionId);

    // Then: execution should not be paused
    assertThat(pauseService.isPaused(executionId)).isFalse();
    assertThat(pauseService.getPausedCount()).isEqualTo(0);
  }

  @Test
  @DisplayName("isPaused returns false for non-existent execution")
  void testIsPausedReturnsFalseForNonExistentExecution() {
    // When: checking pause state for unknown execution
    boolean result = pauseService.isPaused("unknown_exec");

    // Then: should return false
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("getPausedCount returns zero when no executions paused")
  void testGetPausedCountReturnsZeroWhenNoExecutionsPaused() {
    // When: no executions are paused
    int count = pauseService.getPausedCount();

    // Then: count should be zero
    assertThat(count).isEqualTo(0);
  }

  @Test
  @DisplayName("getPausedCount returns correct count for multiple paused executions")
  void testGetPausedCountReturnsCorrectCountForMultiplePaused() {
    // Given: multiple executions are paused
    pauseService.setPaused("exec1", true);
    pauseService.setPaused("exec2", true);
    pauseService.setPaused("exec3", true);

    // When: getting paused count
    int count = pauseService.getPausedCount();

    // Then: count should be 3
    assertThat(count).isEqualTo(3);
  }

  @Test
  @DisplayName("clearPaused is idempotent for non-existent execution")
  void testClearPausedIsIdempotentForNonExistentExecution() {
    // When: clearing pause for non-existent execution
    pauseService.clearPaused("unknown_exec");

    // Then: should not throw exception
    assertThat(pauseService.isPaused("unknown_exec")).isFalse();
  }

  @Test
  @DisplayName("setPaused with true overwrites existing pause state")
  void testSetPausedOverwritesExistingState() {
    // Given: execution is paused
    pauseService.setPaused(executionId, true);

    // When: setting paused again
    pauseService.setPaused(executionId, true);

    // Then: count should still be 1 (not 2)
    assertThat(pauseService.getPausedCount()).isEqualTo(1);
  }
}
