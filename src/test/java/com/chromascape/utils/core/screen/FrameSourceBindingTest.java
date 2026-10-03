package com.chromascape.utils.core.screen;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.capture.FrameFreshness;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import java.awt.image.BufferedImage;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FrameSourceBindingTest {

  @Test
  void hostAndSelectionGenerationParticipateInFrameRoutingIdentity() {
    Instant started = Instant.parse("2026-09-18T00:00:00Z");
    ProcessIdentity hostA = new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 42, started, "boot");
    ProcessIdentity hostB = new ProcessIdentity(ProcessOrigin.LOCAL, "host-b", 42, started, "boot");

    assertThat(hostA).isEqualTo(hostB);
    assertThat(FrameSourceBinding.from(hostA, 7))
        .isNotEqualTo(FrameSourceBinding.from(hostB, 7))
        .isNotEqualTo(FrameSourceBinding.from(hostA, 8));
  }

  @Test
  void capturedFrameMetadataSurvivesLegacyFrameEventBoundary() {
    ProcessIdentity identity =
        new ProcessIdentity(
            ProcessOrigin.LOCAL, "fedora-a", 123, Instant.parse("2026-09-18T00:00:00Z"), "boot");
    Instant observed = Instant.parse("2026-09-18T00:01:00Z");
    Instant changed = Instant.parse("2026-09-18T00:00:59Z");
    BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_3BYTE_BGR);

    FrameEvent event =
        FrameEvent.from(
            new CapturedFrame(image, FrameFreshness.PAUSED, observed, changed, identity, 9), 456);

    assertThat(event.getFrame()).isSameAs(image);
    assertThat(event.getCaptureTimeNanos()).isEqualTo(456);
    assertThat(event.getSourceBinding()).isEqualTo(FrameSourceBinding.from(identity, 9));
    assertThat(event.getFreshness()).isEqualTo(FrameFreshness.PAUSED);
    assertThat(event.getObservedAt()).isEqualTo(observed);
    assertThat(event.getContentChangedAt()).isEqualTo(changed);
  }
}
