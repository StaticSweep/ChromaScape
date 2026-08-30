package com.chromascape.web.gateway.service;

import com.chromascape.web.gateway.config.GatewayCapabilitiesProperties;
import com.chromascape.web.gateway.dto.DebuggerServiceCapabilities;
import com.chromascape.web.gateway.dto.DebuggerServiceCapabilities.Features;
import com.chromascape.web.gateway.dto.DebuggerServiceCapabilities.Health;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.config.ViewportStreamConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Composes capability facts from existing ChromaScape configuration without creating a second
 * domain or session authority.
 */
@Service
public class CapabilityManifestService {

  public static final String SCHEMA_VERSION = "chromascape-service/v1";
  public static final String LEGACY_STREAM_SCHEMA = "chromascape-legacy/v1";

  private static final Pattern SAFE_BUILD_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  private static final List<String> STREAM_KINDS =
      List.of(
          "viewport",
          "overlay",
          "bot-state",
          "logs",
          "state",
          "semantic-state",
          "stats",
          "debugger/tick-stream",
          "debug/patch-loop");

  private final GatewayCapabilitiesProperties properties;
  private final RecordingConfig recordingConfig;
  private final ViewportStreamConfig viewportStreamConfig;
  private final UnifiedCaptureConfig unifiedCaptureConfig;
  private final boolean tickDebuggerEnabled;
  private final boolean patchLoopEnabled;
  private final boolean replayEnabled;

  public CapabilityManifestService(
      GatewayCapabilitiesProperties properties,
      RecordingConfig recordingConfig,
      ViewportStreamConfig viewportStreamConfig,
      UnifiedCaptureConfig unifiedCaptureConfig,
      @Value("${tick-debugger.enabled:true}") boolean tickDebuggerEnabled,
      @Value("${features.patch-loop.autofix-enabled:false}") boolean patchLoopEnabled,
      @Value("${replay.enabled:false}") boolean replayEnabled) {
    this.properties = properties;
    this.recordingConfig = recordingConfig;
    this.viewportStreamConfig = viewportStreamConfig;
    this.unifiedCaptureConfig = unifiedCaptureConfig;
    this.tickDebuggerEnabled = tickDebuggerEnabled;
    this.patchLoopEnabled = patchLoopEnabled;
    this.replayEnabled = replayEnabled;
  }

  /** Build a sanitized snapshot of current service capabilities. */
  public DebuggerServiceCapabilities current() {
    List<String> reasons = new ArrayList<>();
    String buildId = safeBuildId(properties.getServiceBuildId(), reasons);

    boolean recordings = recordingConfig.isEnabled();
    boolean sessions = tickDebuggerEnabled;
    boolean patch = patchLoopEnabled;

    if (!sessions) reasons.add("TICK_DEBUGGER_DISABLED");
    if (!recordings) reasons.add("RECORDING_DISABLED");
    if (!patch) reasons.add("PATCH_DISABLED");
    if (!replayEnabled) reasons.add("REPLAY_DISABLED");

    List<String> restCapabilities = new ArrayList<>(List.of("capabilities", "logs", "viewport"));
    if (sessions) restCapabilities.add("sessions");
    if (recordings) restCapabilities.add("recordings");
    if (replayEnabled) restCapabilities.add("replay");
    if (patch) restCapabilities.add("patch");

    List<String> replayModes = new ArrayList<>(List.of("live", "history"));
    if (replayEnabled) replayModes.add("replay");

    Map<String, String> streamSchemas = new LinkedHashMap<>();
    for (String streamKind : STREAM_KINDS) {
      // Existing producers predate the shared envelope. The Planner adapter accepts this version
      // only when this manifest advertises it; SC-1113 owns the final typed producer migration.
      streamSchemas.put(streamKind, LEGACY_STREAM_SCHEMA);
    }

    String healthStatus = reasons.isEmpty() ? "ok" : "degraded";
    return new DebuggerServiceCapabilities(
        SCHEMA_VERSION,
        buildId,
        List.copyOf(restCapabilities),
        STREAM_KINDS,
        Map.copyOf(streamSchemas),
        properties.getMaxRestBodyBytes(),
        properties.getMaxFrameBytes(),
        List.copyOf(replayModes),
        new Features(sessions, recordings, patch),
        // Live and fleet control remain disabled until the authorization story supplies policy.
        false,
        List.of(),
        new Health(healthStatus, List.copyOf(reasons)));
  }

  /** Exposes the effective capture settings for diagnostics without exposing machine details. */
  public Map<String, Object> captureFacts() {
    return Map.of(
        "unifiedCaptureEnabled", unifiedCaptureConfig.isEnabled(),
        "unifiedCaptureFps", unifiedCaptureConfig.getFps(),
        "viewportTargetFps", viewportStreamConfig.getTargetFps(),
        "viewportMaxLongEdge", viewportStreamConfig.getMaxLongEdge());
  }

  private static String safeBuildId(String configured, List<String> reasons) {
    if (configured != null && SAFE_BUILD_ID.matcher(configured).matches()) {
      return configured;
    }
    reasons.add("CAPABILITY_CONFIGURATION_INVALID");
    return "unknown";
  }
}
