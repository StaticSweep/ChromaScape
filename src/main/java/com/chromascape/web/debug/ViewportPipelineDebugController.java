package com.chromascape.web.debug;

import com.chromascape.web.viewport.ViewportWebSocketHandler;
import com.chromascape.web.viewport.WebsocketViewport;
import com.chromascape.web.viewport.config.ViewportStreamConfig;
import com.chromascape.web.viewport.service.ScreenCaptureService;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
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

  @GetMapping("/pipeline")
  public ResponseEntity<Map<String, Object>> getPipelineMetrics() {
    Map<String, Object> payload =
        Map.of(
            "capture",
            Map.of(
                "running", unifiedFrameCaptureService.isCapturing(),
                "subscribers", unifiedFrameCaptureService.getSubscriberCount(),
                "attempts", unifiedFrameCaptureService.getCaptureAttempts(),
                "successes", unifiedFrameCaptureService.getCaptureSuccesses(),
                "errors", unifiedFrameCaptureService.getCaptureErrors(),
                "avgCaptureMs", unifiedFrameCaptureService.getAverageCaptureDurationMs(),
                "maxCaptureMs", unifiedFrameCaptureService.getMaxCaptureDurationMs(),
                "dispatchAttempts", unifiedFrameCaptureService.getSubscriberDispatchAttempts(),
                "dispatchErrors", unifiedFrameCaptureService.getSubscriberDispatchErrors()),
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
                Map.entry(
                    "activeSessions", viewportWebSocketHandler.getActiveSessionCount()),
                Map.entry(
                    "broadcastRequests", viewportWebSocketHandler.getBroadcastRequests()),
                Map.entry("flushCycles", viewportWebSocketHandler.getFlushCycles()),
                Map.entry(
                    "payloadsFlushed", viewportWebSocketHandler.getPayloadsFlushed()),
                Map.entry(
                    "sessionSendSuccesses", viewportWebSocketHandler.getSessionSendSuccesses()),
                Map.entry(
                    "sessionSendErrors", viewportWebSocketHandler.getSessionSendErrors()),
                Map.entry(
                    "framesSkippedNoSessions", websocketViewport.getFramesSkippedNoSessions()),
                Map.entry(
                    "framesDroppedEncodeBackpressure",
                    websocketViewport.getFramesDroppedEncodeBackpressure()),
                Map.entry("encodeCount", websocketViewport.getEncodeCount()),
                Map.entry("avgEncodeMs", websocketViewport.getAverageEncodeTimeMs()),
                Map.entry(
                    "maxEncodeMs", websocketViewport.getMaxEncodeTimeMs()),
                Map.entry(
                    "avgPayloadBytes", websocketViewport.getAveragePayloadBytes()),
                Map.entry("targetFps", viewportStreamConfig.getTargetFps()),
                Map.entry("jpegQuality", viewportStreamConfig.getJpegQuality()),
                Map.entry("maxLongEdge", viewportStreamConfig.getMaxLongEdge())));

    return ResponseEntity.ok(payload);
  }
}
