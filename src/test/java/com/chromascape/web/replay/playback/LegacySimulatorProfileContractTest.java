package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.simulator.SimulatorReplayControlController;
import com.chromascape.web.simulator.SimulatorReplayControlProperties;
import com.chromascape.web.simulator.SimulatorReplayControlService;
import com.chromascape.web.simulator.SimulatorReplaySourceController;
import com.chromascape.web.simulator.SimulatorReplaySourceService;
import com.chromascape.web.simulator.SimulatorVerifierProperties;
import com.chromascape.web.simulator.SimulatorVerifyController;
import com.chromascape.web.simulator.SimulatorVerifyJobStore;
import com.chromascape.web.simulator.SimulatorVerifyService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/** Guards the opt-in boundary around the retired simulator integration. */
class LegacySimulatorProfileContractTest {

  @Test
  void everyLegacyBeanRequiresExplicitLegacyProfile() {
    for (Class<?> type : ListHolder.TYPES) {
      Profile profile = type.getAnnotation(Profile.class);
      assertTrue(
          profile != null && Arrays.asList(profile.value()).contains("openrune-legacy"),
          () -> type.getName() + " must not load in the default xRSPS profile");
    }
  }

  private static final class ListHolder {
    private static final Class<?>[] TYPES = {
      SimulatorReplayControlController.class,
      SimulatorReplayControlProperties.class,
      SimulatorReplayControlService.class,
      SimulatorReplaySourceController.class,
      SimulatorReplaySourceService.class,
      SimulatorVerifierProperties.class,
      SimulatorVerifyController.class,
      SimulatorVerifyJobStore.class,
      SimulatorVerifyService.class
    };
  }
}
