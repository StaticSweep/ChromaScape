package com.chromascape.web.debug.tickdebugger.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.capture.TargetFrameRuntime;
import com.chromascape.utils.core.screen.capture.TargetFrameSource;
import com.chromascape.utils.core.screen.capture.ViewOnlyFrameSource;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.web.aiplayer.prototype.FishingShadowDecisionService;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.service.ClientFrameMetrics;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService.SessionStartOutcome;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService.SessionStartRollbackToken;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.chromascape.web.viewport.WebsocketViewport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tests the {@code screen-frame/v1} contract: bound client pixels from the negotiated stream reach
 * the viewport with no input session, while unbound, mismatched, mistyped, stale, rotated,
 * malformed, oversized, and dimension-lying frames are rejected or dropped without touching it.
 * Arbitration drops client frames when an attached input session or an explicit desktop watch owns
 * the pixels.
 */
class TickStreamWebSocketHandlerScreenFrameTest {

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private DebugSessionService sessionService;
  private TickStreamIngestionService ingestionService;
  private DebuggerTickWebSocketHandler debuggerHandler;
  private FishingShadowDecisionService shadowDecisionService;
  private WebsocketViewport viewport;
  private WebSocketSession wsSession;
  private TickStreamWebSocketHandler handler;

  @Test
  void forwardsBoundFrameToViewportWithoutAnInputSession() throws Exception {
    setup("ws-frame-1");
    bind("debug_1");

    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", streamId("debug_1"), 7, framePixels())));

    verify(viewport)
        .publishEncodedFrame(
            any(byte[].class),
            org.mockito.ArgumentMatchers.eq(16),
            org.mockito.ArgumentMatchers.eq(12),
            any());
    assertEquals(1, metrics().getForwarded());
    assertEquals(0, metrics().getDroppedInvalid());
    assertEquals("debug_1", metrics().getLastSessionId());
    assertEquals("client", metrics().getLastSource());
    assertEquals("ACTIVE", metrics().getFeedState());
  }

  @Test
  void rejectsUnboundFrame() throws Exception {
    setup("ws-frame-2");

    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", "stream-x", 1, framePixels())));

    assertError("SESSION_NOT_BOUND");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
    assertEquals(1, metrics().getDroppedInvalid());
  }

  @Test
  void rejectsSessionMismatch() throws Exception {
    setup("ws-frame-3");
    bind("debug_1");

    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_2", streamId("debug_1"), 1, framePixels())));

    assertError("SESSION_ID_MISMATCH");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsNonStringSessionId() throws Exception {
    setup("ws-frame-4");
    bind("debug_1");

    String raw =
        "{\"type\":\"SCREEN_FRAME\",\"sessionId\":123,\"streamId\":\""
            + streamId("debug_1")
            + "\",\"schemaVersion\":1,\"frameSeq\":1,\"capturedAt\":\"2026-09-25T19:00:00Z\","
            + "\"width\":16,\"height\":12,\"mimeType\":\"image/jpeg\",\"jpegBase64\":\""
            + framePixels()
            + "\"}";
    handler.handleTextMessage(wsSession, new TextMessage(raw));

    assertError("SESSION_ID_MISMATCH");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsUnknownStream() throws Exception {
    setup("ws-frame-5");
    bind("debug_1");

    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", "dead-stream", 1, framePixels())));

    assertError("STREAM_ID_MISMATCH");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsMissingStream() throws Exception {
    setup("ws-frame-6");
    bind("debug_1");

    String raw =
        "{\"type\":\"SCREEN_FRAME\",\"schemaVersion\":1,\"sessionId\":\"debug_1\",\"frameSeq\":1,"
            + "\"capturedAt\":\"2026-09-25T19:00:00Z\",\"width\":16,\"height\":12,"
            + "\"mimeType\":\"image/jpeg\",\"jpegBase64\":\""
            + framePixels()
            + "\"}";
    handler.handleTextMessage(wsSession, new TextMessage(raw));

    assertError("STREAM_ID_MISMATCH");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsWrongSchemaVersion() throws Exception {
    setup("ws-frame-7");
    bind("debug_1");

    String raw =
        frameJson("debug_1", streamId("debug_1"), 1, framePixels())
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2");
    handler.handleTextMessage(wsSession, new TextMessage(raw));

    assertError("FRAME_PAYLOAD_INVALID");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsWrongMimeType() throws Exception {
    setup("ws-frame-8");
    bind("debug_1");

    String raw =
        frameJson("debug_1", streamId("debug_1"), 1, framePixels())
            .replace("image/jpeg", "image/png");
    handler.handleTextMessage(wsSession, new TextMessage(raw));

    assertError("FRAME_PAYLOAD_INVALID");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsDuplicateSequenceAsStale() throws Exception {
    setup("ws-frame-9");
    bind("debug_1");
    String stream = streamId("debug_1");

    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", stream, 7, framePixels())));
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", stream, 7, framePixels())));

    verify(viewport, times(1)).publishEncodedFrame(any(), any(int.class), any(int.class), any());
    assertEquals(1, metrics().getForwarded());
    assertEquals(1, metrics().getDroppedInvalid());
    assertEquals("FRAME_STALE", metrics().getLastRejection());
  }

  @Test
  void rejectsOutOfOrderSequenceAsStale() throws Exception {
    setup("ws-frame-10");
    bind("debug_1");
    String stream = streamId("debug_1");

    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", stream, 9, framePixels())));
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", stream, 8, framePixels())));

    verify(viewport, times(1)).publishEncodedFrame(any(), any(int.class), any(int.class), any());
    assertEquals("FRAME_STALE", metrics().getLastRejection());
  }

  @Test
  void rejectsMalformedPixels() throws Exception {
    setup("ws-frame-11");
    bind("debug_1");

    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", streamId("debug_1"), 1, "!!!nope!!!")));

    assertError("FRAME_DECODE_FAILED");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsOversizedPayload() throws Exception {
    setup("ws-frame-12");
    bind("debug_1");

    handler.handleTextMessage(
        wsSession,
        new TextMessage(frameJson("debug_1", streamId("debug_1"), 1, "A".repeat(1_200_001))));

    assertError("FRAME_TOO_LARGE");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsDeclaredDimensionMismatch() throws Exception {
    setup("ws-frame-13");
    bind("debug_1");

    String raw =
        frameJson("debug_1", streamId("debug_1"), 1, framePixels())
            .replace("\"width\":16,\"height\":12", "\"width\":8,\"height\":8");
    handler.handleTextMessage(wsSession, new TextMessage(raw));

    assertError("FRAME_DIMENSION_MISMATCH");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void dropsFrameWhenAnInputSessionOwnsThePixels() throws Exception {
    setup("ws-frame-14");
    bind("debug_1");
    StubSession session = new StubSession();
    TargetFrameRuntime.install(session);
    try {
      handler.handleTextMessage(
          wsSession, new TextMessage(frameJson("debug_1", streamId("debug_1"), 1, framePixels())));

      verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
      assertEquals(1, metrics().getDroppedAttached());
      assertEquals("ATTACHED_SOURCE_WINS", metrics().getLastRejection());
    } finally {
      TargetFrameRuntime.uninstall(session);
    }
  }

  @Test
  void dropsFrameWhenDesktopWatchOwnsThePixels() throws Exception {
    setup("ws-frame-15");
    bind("debug_1");
    StubWatch watch = new StubWatch();
    TargetFrameRuntime.installViewOnly(watch);
    try {
      handler.handleTextMessage(
          wsSession, new TextMessage(frameJson("debug_1", streamId("debug_1"), 1, framePixels())));

      verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
      assertEquals(1, metrics().getDroppedDesktopWatch());
      assertEquals("DESKTOP_WATCH_WINS", metrics().getLastRejection());
    } finally {
      TargetFrameRuntime.uninstall(watch);
    }
  }

  @Test
  void rejectsFrameAfterStop() throws Exception {
    setup("ws-frame-16");
    bind("debug_1");
    String stream = streamId("debug_1");

    handler.handleTextMessage(
        wsSession,
        new TextMessage("{\"type\":\"SESSION_STOP\",\"sessionId\":\"debug_1\",\"endTick\":10}"));
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", stream, 2, framePixels())));

    assertError("SESSION_NOT_BOUND");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void rejectsRotatedStreamAfterRestart() throws Exception {
    setup("ws-frame-17");
    bind("debug_1");
    String oldStream = streamId("debug_1");

    handler.handleTextMessage(
        wsSession,
        new TextMessage("{\"type\":\"SESSION_STOP\",\"sessionId\":\"debug_1\",\"endTick\":10}"));
    bind("debug_1");
    String newStream = streamId("debug_1");
    assertNotEquals(oldStream, newStream);

    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", oldStream, 1, framePixels())));

    assertError("STREAM_ID_MISMATCH");
    verify(viewport, never()).publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  @Test
  void stoppedSessionCannotPublishAlreadyDecodedPixels() throws Exception {
    setup("stop-race");
    bind("debug_1");
    java.util.List<java.util.function.Consumer<Runnable>> guards = new java.util.ArrayList<>();
    org.mockito.Mockito.doAnswer(
            call -> {
              guards.add(call.getArgument(3));
              return true;
            })
        .when(viewport)
        .publishEncodedFrame(any(), any(int.class), any(int.class), any());
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", streamId("debug_1"), 1, framePixels())));
    handler.handleTextMessage(
        wsSession,
        new TextMessage("{\"type\":\"SESSION_STOP\",\"sessionId\":\"debug_1\",\"endTick\":10}"));
    guards
        .get(0)
        .accept(
            () -> {
              throw new AssertionError("Stale frame published");
            });
    assertEquals(0, metrics().getForwarded());
    assertEquals("WAITING_FOR_SESSION", metrics().getFeedState());
  }

  @Test
  void rateAndDisableBoundFrameWork() throws Exception {
    setup("rate");
    bind("debug_1");
    String stream = streamId("debug_1");
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", stream, 1, framePixels())));
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", stream, 2, framePixels())));
    assertEquals("FRAME_RATE_LIMITED", metrics().getLastRejection());
    handler.broadcastFeedControl(false);
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", stream, 3, framePixels())));
    assertEquals("DISABLED", metrics().getFeedState());
    assertEquals(1, metrics().getForwarded());
  }

  @Test
  void sourceSelectedAfterDecodePreventsClientBroadcast() throws Exception {
    setup("source-race");
    bind("debug_1");
    java.util.List<java.util.function.Consumer<Runnable>> guards = new java.util.ArrayList<>();
    org.mockito.Mockito.doAnswer(
            call -> {
              guards.add(call.getArgument(3));
              return true;
            })
        .when(viewport)
        .publishEncodedFrame(any(), any(int.class), any(int.class), any());
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", streamId("debug_1"), 1, framePixels())));
    StubWatch watch = new StubWatch();
    TargetFrameRuntime.installViewOnly(watch);
    try {
      guards
          .get(0)
          .accept(
              () -> {
                throw new AssertionError("Wrong source published");
              });
    } finally {
      TargetFrameRuntime.uninstall(watch);
    }
    assertEquals(1, metrics().getDroppedDesktopWatch());
  }

  @Test
  void reconnectRotatesScreenCapabilityAndRejectsOldSocket() throws Exception {
    setup("original");
    bind("debug_1");
    String old = streamId("debug_1");
    handler.afterConnectionClosed(wsSession, org.springframework.web.socket.CloseStatus.NORMAL);
    assertEquals("WAITING_FOR_SESSION", metrics().getFeedState());
    WebSocketSession prior = wsSession;
    wsSession = mock(WebSocketSession.class);
    when(wsSession.getId()).thenReturn("replacement");
    bind("debug_1");
    assertNotEquals(old, streamId("debug_1"));
    handler.handleTextMessage(prior, new TextMessage(frameJson("debug_1", old, 1, framePixels())));
    assertEquals(0, metrics().getForwarded());
    handler.shutdownReconnectScheduler();
  }

  @Test
  void hugeJpegMetadataIsRejectedBeforeRasterAllocation() throws Exception {
    setup("jpeg-bomb");
    bind("debug_1");
    byte[] jpeg = Base64.getDecoder().decode(framePixels());
    for (int i = 0; i < jpeg.length - 9; i++) {
      if ((jpeg[i] & 255) == 255 && ((jpeg[i + 1] & 255) == 192 || (jpeg[i + 1] & 255) == 194)) {
        // Valid JPEG dimensions, but a raster far above the negotiated bound.
        jpeg[i + 5] = jpeg[i + 7] = 0x7d;
        jpeg[i + 6] = jpeg[i + 8] = 0;
        break;
      }
    }
    handler.handleTextMessage(
        wsSession,
        new TextMessage(
            frameJson(
                "debug_1", streamId("debug_1"), 1, Base64.getEncoder().encodeToString(jpeg))));
    assertError("FRAME_DIMENSION_MISMATCH");
    assertEquals(0, metrics().getForwarded());
  }

  @Test
  void validFramesWithoutViewerAreIdleRatherThanBroken() throws Exception {
    setup("no-viewer");
    bind("debug_1");
    org.mockito.Mockito.doReturn(false)
        .when(viewport)
        .publishEncodedFrame(any(), any(int.class), any(int.class), any());
    handler.handleTextMessage(
        wsSession, new TextMessage(frameJson("debug_1", streamId("debug_1"), 1, framePixels())));
    assertEquals("IDLE_NO_SUBSCRIBERS", metrics().getFeedState());
    assertEquals(0, metrics().getForwarded());
  }

  @Test
  void negotiatedCapabilityMatchesSharedFixture() throws Exception {
    setup("contract");
    bind("debug_1");
    var fixture =
        objectMapper.readTree(getClass().getResourceAsStream("/contracts/screen-frame-v1.json"));
    ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, atLeastOnce()).sendMessage(messages.capture());
    var capability =
        objectMapper.readTree(messages.getAllValues().get(0).getPayload()).get("screenFrame");
    for (String field : List.of("version", "maxJpegBytes", "maxLongEdge", "maxFps", "enabled"))
      assertEquals(fixture.get(field), capability.get(field));
  }

  private void setup(String wsId) {
    sessionService = mock(DebugSessionService.class);
    ingestionService = mock(TickStreamIngestionService.class);
    debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    shadowDecisionService = mock(FishingShadowDecisionService.class);
    when(shadowDecisionService.openSession(any(), any())).thenReturn(true);
    viewport = mock(WebsocketViewport.class);
    wsSession = mock(WebSocketSession.class);
    when(wsSession.getId()).thenReturn(wsId);
    handler =
        new TickStreamWebSocketHandler(
            objectMapper, sessionService, ingestionService, debuggerHandler, shadowDecisionService);
    ReflectionTestUtils.setField(handler, "clientFrameViewport", viewport);
    ReflectionTestUtils.setField(
        handler, "frameExecutor", (java.util.concurrent.Executor) Runnable::run);
    org.mockito.Mockito.doAnswer(
            call -> {
              java.util.function.Consumer<Runnable> guard = call.getArgument(3);
              guard.accept(() -> {});
              return true;
            })
        .when(viewport)
        .publishEncodedFrame(any(), any(int.class), any(int.class), any());
  }

  private void bind(String sessionId) throws Exception {
    SessionStartResponse response = new SessionStartResponse(sessionId, true, "rec_1", 0L, null);
    SessionStartRollbackToken token =
        new SessionStartRollbackToken(sessionId, true, "rec_1", null, null, null);
    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(new SessionStartOutcome(response, token, true, false, true));
    handler.handleTextMessage(
        wsSession,
        new TextMessage(
            "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
                + "\"recordingEnabled\":true,\"preferredSessionId\":\""
                + sessionId
                + "\"}"));
  }

  private String streamId(String sessionId) {
    @SuppressWarnings("unchecked")
    Map<String, TickStreamWebSocketHandler.FrameStreamState> streams =
        (Map<String, TickStreamWebSocketHandler.FrameStreamState>)
            ReflectionTestUtils.getField(handler, "frameStreams");
    return streams.get(sessionId).streamId();
  }

  private ClientFrameMetrics metrics() {
    return (ClientFrameMetrics) ReflectionTestUtils.getField(handler, "clientFrameMetrics");
  }

  private void assertError(String code) throws Exception {
    ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, atLeastOnce()).sendMessage(sent.capture());
    List<String> payloads = sent.getAllValues().stream().map(TextMessage::getPayload).toList();
    assertTrue(
        payloads.stream()
            .anyMatch(
                payload ->
                    payload.contains("\"type\":\"ERROR\"") && payload.contains("\"" + code + "\"")),
        "expected ERROR " + code + " among " + payloads.size() + " sent messages");
  }

  private String frameJson(String sessionId, String streamId, long seq, String base64) {
    return "{\"type\":\"SCREEN_FRAME\",\"schemaVersion\":1,\"sessionId\":\""
        + sessionId
        + "\",\"streamId\":\""
        + streamId
        + "\",\"frameSeq\":"
        + seq
        + ",\"capturedAt\":\"2026-09-25T20:00:00Z\",\"width\":16,\"height\":12,"
        + "\"mimeType\":\"image/jpeg\",\"jpegBase64\":\""
        + base64
        + "\",\"quality\":70}";
  }

  private String framePixels() throws Exception {
    BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_3BYTE_BGR);
    image.setRGB(0, 0, 16, 12, new int[16 * 12], 0, 16);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", bytes);
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  private static final class StubSession implements TargetFrameSource {
    @Override
    public CapturedFrame captureFrame() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessIdentity identity() {
      return null;
    }

    @Override
    public long selectionGeneration() {
      return 0;
    }

    @Override
    public Rectangle targetBounds() {
      return new Rectangle();
    }
  }

  private static final class StubWatch implements ViewOnlyFrameSource {
    @Override
    public CapturedFrame captureFrame() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessIdentity identity() {
      return null;
    }

    @Override
    public long selectionGeneration() {
      return 0;
    }

    @Override
    public Rectangle targetBounds() {
      return new Rectangle();
    }

    @Override
    public void close() {}
  }
}
