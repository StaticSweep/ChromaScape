package com.chromascape.web.debug.buildergoal.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BuilderGoalTopologyGuardTest {
  private final String token = "0123456789abcdef0123456789abcdef";

  private BuilderGoalTopologyGuard guard(String environment, String heartbeat, boolean local) {
    return new BuilderGoalTopologyGuard(
        environment,
        heartbeat,
        token,
        "https://127.0.0.1:8084",
        token,
        "https://127.0.0.1:8084",
        token,
        false,
        local);
  }

  @Test
  void authenticationFailsClosedForMissingWrongAndDefaultTokens() {
    var g = guard("DEV", token, true);
    assertFalse(g.authenticatesHeartbeat(null));
    assertFalse(g.authenticatesHeartbeat("Bearer wrong"));
    assertTrue(g.authenticatesHeartbeat("Bearer " + token));
    assertFalse(guard("DEV", "devtoken123", true).authenticatesHeartbeat("Bearer devtoken123"));
  }

  @Test
  void nonDevelopmentRejectsDirectLocalLaunch() {
    assertThrows(IllegalStateException.class, () -> guard("PRODUCTION", token, true));
    assertThrows(IllegalStateException.class, () -> guard("STAGING", token, true));
  }

  @Test
  void nonDevelopmentRequiresTlsTrustAndClientIdentity() {
    assertThrows(IllegalStateException.class, () -> guard("PRODUCTION", token, false));
  }

  @Test
  void unknownEnvironmentRejected() {
    assertThrows(IllegalStateException.class, () -> guard("anything", token, false));
  }
}
