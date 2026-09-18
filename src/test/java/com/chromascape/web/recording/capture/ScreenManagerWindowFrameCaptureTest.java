package com.chromascape.web.recording.capture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.utils.core.screen.FrameSourceBinding;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScreenManagerWindowFrameCaptureTest {

  @Test
  void selectionMustRemainStableAcrossWindowsCapture() {
    FrameSourceBinding first = binding(3);

    assertThatCode(() -> ScreenManagerWindowFrameCapture.requireStableSelection(3, first, 3, first))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> ScreenManagerWindowFrameCapture.requireStableSelection(3, first, 4, binding(4)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("selection changed");
  }

  private static FrameSourceBinding binding(long generation) {
    return new FrameSourceBinding(
        ProcessOrigin.LOCAL,
        "windows-host",
        99,
        Instant.parse("2026-09-18T00:00:00Z"),
        "",
        generation);
  }
}
