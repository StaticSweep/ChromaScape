package com.chromascape.web.debug;

import com.chromascape.web.viewport.ViewportWebSocketHandler;
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

  public ViewportPipelineDebugController(
      UnifiedFrameCaptureService unifiedFrameCaptureService,
      ScreenCaptureService screenCaptureService,
      ViewportWebSocketHandler viewportWebSocketHandler) {
    this.unifiedFrameCaptureService = unifiedFrameCaptureService;
    this.screenCaptureService = screenCaptureService;
    this.viewportWebSocketHandler = viewportWebSocketHandler;
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
                "dispatchAttempts", unifiedFrameCaptureService.getSubscriberDispatchAttempts(),
                "dispatchErrors", unifiedFrameCaptureService.getSubscriberDispatchErrors()),
            "forwarding",
            Map.of(
                "running", screenCaptureService.isRunning(),
                "paused", screenCaptureService.isPaused(),
                "framesReceived", screenCaptureService.getFramesReceived(),
                "framesForwarded", screenCaptureService.getFramesForwarded(),
                "framesDroppedStale", screenCaptureService.getFramesDroppedStale(),
                "frameProcessingErrors", screenCaptureService.getFrameProcessingErrors()),
            "websocket",
            Map.of(
                "activeSessions", viewportWebSocketHandler.getActiveSessionCount(),
                "broadcastRequests", viewportWebSocketHandler.getBroadcastRequests(),
                "flushCycles", viewportWebSocketHandler.getFlushCycles(),
                "payloadsFlushed", viewportWebSocketHandler.getPayloadsFlushed(),
                "sessionSendSuccesses", viewportWebSocketHandler.getSessionSendSuccesses(),
                "sessionSendErrors", viewportWebSocketHandler.getSessionSendErrors()));

    return ResponseEntity.ok(payload);
  }
}
