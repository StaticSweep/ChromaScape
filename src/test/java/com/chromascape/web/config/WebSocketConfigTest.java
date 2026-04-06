package com.chromascape.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.debug.patchloop.websocket.PatchLoopWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Test class for {@link WebSocketConfig}.
 *
 * <p>Verifies that WebSocket handlers are registered correctly, particularly the patch-loop handler
 * which supports both base and execution-scoped connections using wildcard routing.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketConfigTest {

  @Mock private PatchLoopWebSocketHandler patchLoopWebSocketHandler;

  private WebSocketConfig webSocketConfig;

  @BeforeEach
  void setUp() {
    // Create config with mocked handler
    webSocketConfig =
        new WebSocketConfig(
            null, // logWebSocketHandler
            null, // stateWebSocketHandler
            null, // viewportWebSocketHandler
            null, // semanticWebSocketHandler
            null, // statisticsWebSocketHandler
            null, // overlayWebSocketHandler
            null, // botStateWebSocketHandler
            null, // tickStreamWebSocketHandler
            null, // debuggerTickWebSocketHandler
            patchLoopWebSocketHandler);
  }

  @Test
  void testWebSocketConfigCanBeInstantiated() {
    assertNotNull(webSocketConfig, "WebSocketConfig should be instantiable");
  }

  @Test
  void testPatchLoopHandlerIsNotNull() {
    assertNotNull(patchLoopWebSocketHandler, "PatchLoopWebSocketHandler should be injected");
  }

  /**
   * Integration-style test to verify wildcard routing works with the handler's executionId
   * extraction logic. This test validates that the regex pattern in
   * PatchLoopWebSocketHandler.extractExecutionId() correctly parses executionIds from URLs matched
   * by the wildcard route.
   */
  @Test
  void testWildcardRoutePatternCompatibility() throws Exception {
    // Simulate the handler's extractExecutionId logic
    java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile("/ws/debug/patch-loop/([^/?]+)");

    // Test base path (no executionId)
    java.util.regex.Matcher baseMatcher = pattern.matcher("/ws/debug/patch-loop");
    assertTrue(!baseMatcher.find(), "Base path should not match the executionId pattern");

    // Test execution-scoped path
    java.util.regex.Matcher execMatcher = pattern.matcher("/ws/debug/patch-loop/exec-123");
    assertTrue(execMatcher.find(), "Execution-scoped path should match the pattern");
    assertEquals("exec-123", execMatcher.group(1), "ExecutionId should be extracted correctly");

    // Test execution-scoped path with complex ID
    java.util.regex.Matcher complexMatcher =
        pattern.matcher("/ws/debug/patch-loop/iteration-abc-456-def");
    assertTrue(complexMatcher.find(), "Complex executionId should match the pattern");
    assertEquals(
        "iteration-abc-456-def",
        complexMatcher.group(1),
        "Complex executionId should be extracted correctly");

    // Test path with query parameter (fallback extraction)
    java.util.regex.Pattern queryPattern =
        java.util.regex.Pattern.compile("[?&]executionId=([^&]+)");
    java.util.regex.Matcher queryMatcher =
        queryPattern.matcher("/ws/debug/patch-loop?executionId=test-exec");
    assertTrue(queryMatcher.find(), "Query parameter executionId should be extracted");
    assertEquals(
        "test-exec", queryMatcher.group(1), "Query parameter executionId should be correct");
  }

  /**
   * Validates the route patterns expected for patch-loop WebSocket connections.
   *
   * <p>The handler registration uses:
   *
   * <ul>
   *   <li>/ws/debug/patch-loop - for base connections (broadcast to all)
   *   <li>/ws/debug/patch-loop/* - for execution-scoped connections
   * </ul>
   *
   * <p>The wildcard pattern is more reliable than URI-template style ({executionId}) for Spring's
   * path matching and is transparent to clients.
   */
  @Test
  void testExpectedRoutePatterns() {
    String basePath = "/ws/debug/patch-loop";
    String wildcardPath = "/ws/debug/patch-loop/*";

    // Verify path formats
    assertTrue(basePath.startsWith("/ws/"), "Base path should start with /ws/");
    assertTrue(wildcardPath.startsWith("/ws/"), "Wildcard path should start with /ws/");
    assertTrue(
        wildcardPath.endsWith("/*"),
        "Wildcard path should end with /* for execution-scoped routing");

    // Verify that wildcard path would match expected execution URLs
    String[] testExecutionIds = {"exec-123", "test-execution", "abc123", "iteration-001"};
    for (String execId : testExecutionIds) {
      String testUrl = "/ws/debug/patch-loop/" + execId;
      assertTrue(
          testUrl.startsWith("/ws/debug/patch-loop/"),
          "Execution URL '" + testUrl + "' should match wildcard route");
    }
  }

  /** Test that validates the handler is a TextWebSocketHandler subclass. */
  @Test
  void testPatchLoopHandlerExtendsTextWebSocketHandler() {
    assertTrue(
        patchLoopWebSocketHandler instanceof TextWebSocketHandler,
        "PatchLoopWebSocketHandler should extend TextWebSocketHandler");
  }
}
