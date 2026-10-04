package com.chromascape.web.replay.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

/**
 * The recordings endpoints ({@code /api/recordings/*}) require the same local-development
 * principal as the replay endpoints; without registration they fail with 500 instead of
 * serving same-host UI calls (SC-609 step 6).
 */
class LocalReplayPrincipalConfigTest {

  @Test
  void coversReplayAndRecordingsApi() {
    LocalReplayPrincipalFilter filter = new LocalReplayPrincipalFilter(new StandardEnvironment());

    var registration =
        new LocalReplayPrincipalConfig().localReplayPrincipalFilterRegistration(filter);

    assertTrue(registration.getUrlPatterns().contains("/api/replay/*"));
    assertTrue(registration.getUrlPatterns().contains("/api/recordings/*"));
  }
}
