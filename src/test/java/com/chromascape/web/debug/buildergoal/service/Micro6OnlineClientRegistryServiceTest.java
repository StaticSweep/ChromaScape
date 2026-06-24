package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class Micro6OnlineClientRegistryServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void recordHeartbeat_registersRunnableClient() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-1",
              "account_name": "Test Account",
              "account_hash": "abc123",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "executor_bind_addr": "0.0.0.0:8098",
              "source_client_type": "micro6",
              "executor_profile": "accountbuilder_builderpro",
              "status": "IDLE",
              "current_task": null
            }
            """));

    assertThat(service.listClients()).hasSize(1);
    assertThat(service.listClients().get(0).get("accountName")).isEqualTo("Test Account");
    assertThat(service.listClients().get(0).get("advertisedBaseUrl"))
        .isEqualTo("http://192.168.1.50:8098");
    assertThat(service.listClients().get(0).get("runnable")).isEqualTo(true);
    assertThat(service.getRunnableClient("client-1")).isPresent();
  }
}
