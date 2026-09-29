package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsproxLaunchSessionTest {
  @TempDir Path temp;

  @Test void missingDescriptorFailsClearly() {
    assertThatThrownBy(() -> new RsproxLaunchSession(temp.resolve("missing")))
        .isInstanceOf(IOException.class).hasMessageContaining("running compatible local RSProx");
  }

  @Test void unavailableProxyFailsBeforeExecutorInspectionOrCleanup() throws Exception {
    String originalHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", temp.toString());
      var service = new Micro6TargetRelaunchServiceTest.ControlledPlatformService(
          true, true, temp.toString(), "", "");
      // Even an invalid workspace is not examined: connection preflight fails first.
      assertThatThrownBy(() -> service.restartTarget("a", "0123456789abcdef", "test@example.invalid",
          "dummy", 360, null, null, BuilderGoalLaunchCancellation.NONE, false, true))
          .isInstanceOf(IOException.class).hasMessageContaining("running compatible local RSProx");
      try (var files = Files.list(temp)) { assertThat(files.count()).isZero(); }
    } finally { System.setProperty("user.home", originalHome); }
  }

  @Test void rejectsNonLoopbackAndCredentials() {
    for (String url : new String[] {"https://127.0.0.1:80/worlds.js", "http://example.com:80/worlds.js",
        "http://secret@127.0.0.1:80/worlds.js", "http://127.0.0.1:80/worlds.js?token=x"}) {
      assertThatThrownBy(() -> RsproxLaunchSession.validateLocalUrl(url)).isInstanceOf(IOException.class);
    }
    assertThatCode(() -> RsproxLaunchSession.validateLocalUrl("http://127.0.0.1:43600/worlds.js")).doesNotThrowAnyException();
  }

  @Test void authenticatesOnlyToLocalDescriptorPort() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String token = "a".repeat(64);
    java.util.concurrent.atomic.AtomicReference<String> auth = new java.util.concurrent.atomic.AtomicReference<>();
    server.createContext("/v1/launch", exchange -> {
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] response = "{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      Path descriptor = temp.resolve("descriptor.json");
      Files.writeString(descriptor, "{\"version\":1,\"port\":" + server.getAddress().getPort() + ",\"token\":\"" + token + "\"}");
      try (var ignored = new RsproxLaunchSession(descriptor)) {
        assertThat(auth.get()).isEqualTo("Bearer " + token);
      }
    } finally { server.stop(0); }
  }

  @Test void activeDirectLaunchCannotDeduplicateCaptureRequest() {
    var operations = new BuilderGoalLaunchOperationService();
    var direct = operations.createOrReuse("a", "a", "A", "local", 360);
    assertThatThrownBy(() -> operations.createOrReuse("a", "a", "A", "local", 360, true))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("different Packet Capture");
    assertThat(operations.toResponse(direct.operation().getOperationId(), false)).containsEntry("packetCapture", false);
    var capture = operations.createOrReuse("b", "b", "B", "local", 360, true);
    assertThat(operations.createOrReuse("b", "b", "B", "local", 360, true).deduplicated()).isTrue();
    assertThat(operations.toResponse(capture.operation().getOperationId(), false)).containsEntry("packetCapture", true);
  }
}
