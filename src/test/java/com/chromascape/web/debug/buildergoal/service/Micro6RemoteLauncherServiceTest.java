package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class Micro6RemoteLauncherServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final String token = "a".repeat(40);
  private final String machine = "bf864e28-9e8c-48fd-91f4-f29a6f6b72cb";

  private Micro6RemoteLauncherService service() {
    return new Micro6RemoteLauncherService(mapper, new Micro6OnlineClientRegistryService(),
        machine, "http://192.168.1.21:8101", "http://192.168.1.21:8098", token, 30);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode heartbeat() {
    var body = mapper.createObjectNode().put("machine_uuid", machine)
        .put("contract_version", 2).put("agent_version", "1.0.0")
        .put("launcher_endpoint", "http://192.168.1.21:8101")
        .put("executor_url", "http://192.168.1.21:8098");
    body.putArray("capabilities").add(Micro6RemoteLauncherService.CAPABILITY);
    return body;
  }

  @Test
  void agentIsOfflineUntilCompatibleHeartbeatArrives() {
    var service = service();
    assertThat(service.machines().get(0).online()).isFalse();
    service.heartbeat(heartbeat());
    assertThat(service.supports(service.machines().get(0))).isTrue();
  }

  @Test
  void heartbeatCannotChangePinnedDestinationOrIdentity() {
    var service = service();
    assertThatThrownBy(() -> service.heartbeat(heartbeat().put("launcher_endpoint", "http://localhost:8090")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.heartbeat(heartbeat().put("machine_uuid", "another-host")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.heartbeat(heartbeat().put("contract_version", 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(service.machines().get(0).online()).isFalse();
  }

  @Test
  void authenticationFailsClosed() {
    var service = service();
    assertThat(service.authenticate(null)).isFalse();
    assertThat(service.authenticate("Bearer wrong")).isFalse();
    assertThat(service.authenticate("Bearer " + token)).isTrue();
    assertThatThrownBy(() -> new Micro6RemoteLauncherService(mapper,
        new Micro6OnlineClientRegistryService(), machine, "http://192.168.1.21:8101",
        "http://192.168.1.21:8098", "", 30)).isInstanceOf(IllegalArgumentException.class);
  }
}
