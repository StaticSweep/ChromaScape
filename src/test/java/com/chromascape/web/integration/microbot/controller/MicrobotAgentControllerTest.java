package com.chromascape.web.integration.microbot.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.integration.adapter.BotRuntimeAdapter;
import com.chromascape.web.integration.adapter.RuntimeAdapterRegistry;
import com.chromascape.web.integration.microbot.config.MicrobotAgentConfig;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class MicrobotAgentControllerTest {

  @Test
  void healthIncludesConfiguredBaseUrlWhenAdapterMissing() {
    RuntimeAdapterRegistry registry = mock(RuntimeAdapterRegistry.class);
    when(registry.getAdapter("microbot-agent")).thenReturn(Optional.empty());

    MicrobotAgentConfig config = new MicrobotAgentConfig();
    config.setBaseUrl("http://127.0.0.1:8082");

    MicrobotAgentController controller = new MicrobotAgentController(registry, config);

    ResponseEntity<Map<String, Object>> response = controller.health();

    assertEquals(200, response.getStatusCode().value());
    assertEquals("http://127.0.0.1:8082", response.getBody().get("configuredBaseUrl"));
    assertEquals("microbot-agent", response.getBody().get("adapterId"));
    assertEquals(false, response.getBody().get("available"));
    assertEquals("Adapter not registered", response.getBody().get("reason"));
  }

  @Test
  void healthIncludesConfiguredBaseUrlWhenAdapterPresent() {
    RuntimeAdapterRegistry registry = mock(RuntimeAdapterRegistry.class);
    BotRuntimeAdapter adapter = mock(BotRuntimeAdapter.class);
    when(adapter.getAdapterId()).thenReturn("microbot-agent");
    when(adapter.getDisplayName()).thenReturn("Microbot Agent Server");
    when(adapter.isAvailable()).thenReturn(true);
    when(registry.getAdapter("microbot-agent")).thenReturn(Optional.of(adapter));

    MicrobotAgentConfig config = new MicrobotAgentConfig();
    config.setBaseUrl("http://127.0.0.1:8083");

    MicrobotAgentController controller = new MicrobotAgentController(registry, config);

    ResponseEntity<Map<String, Object>> response = controller.health();

    assertEquals(200, response.getStatusCode().value());
    assertEquals("http://127.0.0.1:8083", response.getBody().get("configuredBaseUrl"));
    assertEquals("microbot-agent", response.getBody().get("adapterId"));
    assertEquals("Microbot Agent Server", response.getBody().get("displayName"));
    assertTrue((Boolean) response.getBody().get("available"));
    assertFalse(response.getBody().containsKey("reason"));
  }
}
