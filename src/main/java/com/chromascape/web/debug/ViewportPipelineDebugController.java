package com.chromascape.web.debug;

import com.chromascape.utils.core.screen.FrameSourceBinding;
import com.chromascape.web.viewport.ViewportWebSocketHandler;
import com.chromascape.web.viewport.WebsocketViewport;
import com.chromascape.web.viewport.config.ViewportStreamConfig;
import com.chromascape.web.viewport.service.ScreenCaptureService;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoint for diagnosing viewport pipeline health.
 *
 * <p>Exposes counters for each stage in the frame path:
 *
 * <ul>
 *   <li>Window capture from native screen
 *   <li>Forwarding to viewport manager
 *   <li>WebSocket broadcast to connected browser sessions
 * </ul>
 */
@RestController
@RequestMapping("/api/debug/viewport")
public class ViewportPipelineDebugController {

  private final UnifiedFrameCaptureService unifiedFrameCaptureService;
  private final ScreenCaptureService screenCaptureService;
  private final ViewportWebSocketHandler viewportWebSocketHandler;
  private final WebsocketViewport websocketViewport;
  private final ViewportStreamConfig viewportStreamConfig;

  /** Creates the diagnostics endpoint over every stage of the viewport pipeline. */
  public ViewportPipelineDebugController(
      UnifiedFrameCaptureService unifiedFrameCaptureService,
      ScreenCaptureService screenCaptureService,
      ViewportWebSocketHandler viewportWebSocketHandler,
      WebsocketViewport websocketViewport,
      ViewportStreamConfig viewportStreamConfig) {
    this.unifiedFrameCaptureService = unifiedFrameCaptureService;
    this.screenCaptureService = screenCaptureService;
    this.viewportWebSocketHandler = viewportWebSocketHandler;
    this.websocketViewport = websocketViewport;
    this.viewportStreamConfig = viewportStreamConfig;
  }

  /** Returns capture, forwarding, and WebSocket counters plus sanitized source identity. */
  @GetMapping("/pipeline")
  public ResponseEntity<Map<String, Object>> getPipelineMetrics() {
    Map<String, Object> payload =
        Map.of(
            "capture",
            captureMetrics(),
            "forwarding",
            Map.of(
                "running", screenCaptureService.isRunning(),
                "paused", screenCaptureService.isPaused(),
                "activeViewportSessions", screenCaptureService.getActiveViewportSessions(),
                "streamActivations", screenCaptureService.getStreamActivations(),
                "streamDeactivations", screenCaptureService.getStreamDeactivations(),
                "framesReceived", screenCaptureService.getFramesReceived(),
                "framesForwarded", screenCaptureService.getFramesForwarded(),
                "framesDroppedStale", screenCaptureService.getFramesDroppedStale(),
                "framesSkippedThrottle", screenCaptureService.getFramesSkippedThrottle(),
                "frameProcessingErrors", screenCaptureService.getFrameProcessingErrors()),
            "websocket",
            Map.ofEntries(
                Map.entry("activeSessions", viewportWebSocketHandler.getActiveSessionCount()),
                Map.entry("broadcastRequests", viewportWebSocketHandler.getBroadcastRequests()),
                Map.entry("flushCycles", viewportWebSocketHandler.getFlushCycles()),
                Map.entry("payloadsFlushed", viewportWebSocketHandler.getPayloadsFlushed()),
                Map.entry(
                    "sessionSendSuccesses", viewportWebSocketHandler.getSessionSendSuccesses()),
                Map.entry("sessionSendErrors", viewportWebSocketHandler.getSessionSendErrors()),
                Map.entry(
                    "framesSkippedNoSessions", websocketViewport.getFramesSkippedNoSessions()),
                Map.entry(
                    "framesDroppedEncodeBackpressure",
                    websocketViewport.getFramesDroppedEncodeBackpressure()),
                Map.entry("encodeCount", websocketViewport.getEncodeCount()),
                Map.entry("avgEncodeMs", websocketViewport.getAverageEncodeTimeMs()),
                Map.entry("maxEncodeMs", websocketViewport.getMaxEncodeTimeMs()),
                Map.entry("avgPayloadBytes", websocketViewport.getAveragePayloadBytes()),
                Map.entry("targetFps", viewportStreamConfig.getTargetFps()),
                Map.entry("jpegQuality", viewportStreamConfig.getJpegQuality()),
                Map.entry("maxLongEdge", viewportStreamConfig.getMaxLongEdge())));

    return ResponseEntity.ok(payload);
  }

  private Map<String, Object> captureMetrics() {
    Map<String, Object> capture = new LinkedHashMap<>();
    capture.put("running", unifiedFrameCaptureService.isCapturing());
    capture.put("subscribers", unifiedFrameCaptureService.getSubscriberCount());
    capture.put("attempts", unifiedFrameCaptureService.getCaptureAttempts());
    capture.put("successes", unifiedFrameCaptureService.getCaptureSuccesses());
    capture.put("errors", unifiedFrameCaptureService.getCaptureErrors());
    capture.put("avgCaptureMs", unifiedFrameCaptureService.getAverageCaptureDurationMs());
    capture.put("maxCaptureMs", unifiedFrameCaptureService.getMaxCaptureDurationMs());
    capture.put("dispatchAttempts", unifiedFrameCaptureService.getSubscriberDispatchAttempts());
    capture.put("dispatchErrors", unifiedFrameCaptureService.getSubscriberDispatchErrors());
    capture.put("sourceTransitions", unifiedFrameCaptureService.getSourceTransitions());
    capture.put("staleSourceDispatches", unifiedFrameCaptureService.getStaleSourceDispatches());
    FrameSourceBinding binding = unifiedFrameCaptureService.getActiveSourceBinding();
    capture.put("sourceBinding", binding != null ? binding.toDisplayString() : null);
    return capture;
  }
}
