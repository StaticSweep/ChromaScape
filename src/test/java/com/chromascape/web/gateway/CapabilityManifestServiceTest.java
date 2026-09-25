package com.chromascape.web.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.gateway.config.GatewayCapabilitiesProperties;
import com.chromascape.web.gateway.dto.DebuggerServiceCapabilities;
import com.chromascape.web.gateway.service.CapabilityManifestService;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.config.ViewportStreamConfig;
import org.junit.jupiter.api.Test;

class CapabilityManifestServiceTest {

  @Test
  void publishesSanitizedVersionedFactsAndKeepsControlDisabled() {
    CapabilityManifestService service = service(true, true, true, "cs-test");

    DebuggerServiceCapabilities capabilities = service.current();

    assertEquals("chromascape-service/v1", capabilities.schemaVersion());
    assertEquals("cs-test", capabilities.serviceBuildId());
    assertTrue(capabilities.restCapabilities().contains("context"));
    assertTrue(capabilities.restCapabilities().contains("sessions"));
    assertTrue(capabilities.streamKinds().contains("viewport"));
    assertEquals("chromascape-legacy/v1", capabilities.streamSchemaVersions().get("viewport"));
    assertEquals(2_097_152, capabilities.maxFrameBytes());
    assertEquals("ok", capabilities.health().status());
    assertFalse(capabilities.controlAllowed());
    assertTrue(capabilities.allowedMutations().isEmpty());
  }

  @Test
  void reportsDegradedFeaturesWithoutLeakingConfiguration() {
    CapabilityManifestService service = service(false, false, false, "C:\\secret\\build");

    DebuggerServiceCapabilities capabilities = service.current();

    assertEquals("unknown", capabilities.serviceBuildId());
    assertEquals("degraded", capabilities.health().status());
    assertTrue(capabilities.health().reasons().contains("CAPABILITY_CONFIGURATION_INVALID"));
    assertTrue(capabilities.health().reasons().contains("TICK_DEBUGGER_DISABLED"));
    assertTrue(capabilities.health().reasons().contains("RECORDING_DISABLED"));
    assertTrue(capabilities.health().reasons().contains("PATCH_DISABLED"));
    assertTrue(capabilities.health().reasons().contains("REPLAY_DISABLED"));
    assertFalse(capabilities.features().sessions());
    assertFalse(capabilities.features().recordings());
    assertFalse(capabilities.features().patch());
    assertFalse(capabilities.serviceBuildId().contains("secret"));
  }

  @Test
  void rejectsTransportLimitsOutsideTheBoundedConfigurationRange() {
    GatewayCapabilitiesProperties properties = new GatewayCapabilitiesProperties();

    assertThrows(IllegalArgumentException.class, () -> properties.setMaxRestBodyBytes(0));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setMaxRestBodyBytes(16 * 1024 * 1024 + 1));
    assertThrows(IllegalArgumentException.class, () -> properties.setMaxFrameBytes(1_048_575));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setMaxFrameBytes(16 * 1024 * 1024 + 1));
  }

  private static CapabilityManifestService service(
      boolean sessions, boolean recordings, boolean patch, String buildId) {
    GatewayCapabilitiesProperties properties = new GatewayCapabilitiesProperties();
    properties.setServiceBuildId(buildId);
    RecordingConfig recordingConfig = new RecordingConfig();
    recordingConfig.setEnabled(recordings);
    return new CapabilityManifestService(
        properties,
        recordingConfig,
        new ViewportStreamConfig(),
        new UnifiedCaptureConfig(),
        sessions,
        patch,
        sessions);
  }
}
