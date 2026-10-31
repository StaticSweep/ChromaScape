package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MouseVideoYouTubeTest {

  @Test
  void acceptsReviewedSingleVideoForms() {
    assertThat(MouseVideoYouTube.canonicalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        .contains(
            new MouseVideoYouTube.Canonical(
                "dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    assertThat(MouseVideoYouTube.canonicalize("https://youtu.be/dQw4w9WgXcQ")).isPresent();
    assertThat(
            MouseVideoYouTube.canonicalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s")
                .orElseThrow()
                .providerId())
        .isEqualTo("dQw4w9WgXcQ");
  }

  @Test
  void rejectsUnsupportedOrUnsafeForms() {
    assertThat(MouseVideoYouTube.canonicalize(null)).isEmpty();
    assertThat(MouseVideoYouTube.canonicalize("")).isEmpty();
    assertThat(MouseVideoYouTube.canonicalize("http://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        .isEmpty();
    assertThat(
            MouseVideoYouTube.canonicalize("https://user:pass@www.youtube.com/watch?v=dQw4w9WgXcQ"))
        .isEmpty();
    assertThat(MouseVideoYouTube.canonicalize("https://www.youtube.com:8443/watch?v=dQw4w9WgXcQ"))
        .isEmpty();
    assertThat(MouseVideoYouTube.canonicalize("https://www.youtube.com/playlist?list=PL123"))
        .isEmpty();
    assertThat(MouseVideoYouTube.canonicalize("https://www.youtube.com/live/dQw4w9WgXcQ"))
        .isEmpty();
    assertThat(MouseVideoYouTube.canonicalize("https://evil.example.com/watch?v=dQw4w9WgXcQ"))
        .isEmpty();
    assertThat(MouseVideoYouTube.canonicalize("https://www.youtube.com/watch?v=short")).isEmpty();
    assertThat(MouseVideoYouTube.canonicalize("file:///etc/passwd")).isEmpty();
  }
}
