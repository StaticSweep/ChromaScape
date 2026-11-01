package com.chromascape.web.replay.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

/**
 * The recordings endpoints ({@code /api/recordings/*}) and the SC-2150 external-video endpoints
 * ({@code /api/debug/mouse-videos/*}) require the same local-development principal as the replay
 * endpoints; without registration they fail instead of serving same-host UI calls (SC-609 step 6,
 * SC-2150).
 */
class LocalReplayPrincipalConfigTest {

  @Test
  void coversReplayRecordingsAndExternalVideoApi() {
    LocalReplayPrincipalFilter filter = new LocalReplayPrincipalFilter(new StandardEnvironment());

    var registration =
        new LocalReplayPrincipalConfig().localReplayPrincipalFilterRegistration(filter);

    assertTrue(registration.getUrlPatterns().contains("/api/replay/*"));
    assertTrue(registration.getUrlPatterns().contains("/api/recordings/*"));
    // SC-2150: the owner review workspace and the external-video API are same-host local calls
    // too. When this pattern is missing, main answers 401 for every mouse-video request.
    assertTrue(registration.getUrlPatterns().contains("/api/debug/mouse-videos/*"));
  }
}
