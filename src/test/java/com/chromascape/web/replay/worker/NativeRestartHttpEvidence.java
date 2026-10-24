package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.replay.api.ReplayV2Controller;
import com.chromascape.web.replay.error.ReplayExceptionHandler;
import com.chromascape.web.replay.run.ReplayV2RunService;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real loopback HTTP and production export path; the principal injector is test-only. */
final class NativeRestartHttpEvidence {
  private NativeRestartHttpEvidence() {}

  static void verify(
      NativeCheckpointPersistenceTest t, StateHashSampleRepository samples, Path restartRoot)
      throws Exception {
    Path resultFile = restartRoot.resolve("resume/native-database-continuation.json");
    JsonNode continuation = t.json.readTree(Files.readAllBytes(resultFile));
    assertThat(continuation.path("freshJvmQualified").asBoolean()).isTrue();
    Path output = restartRoot.resolve("http");
    Files.createDirectory(output);
    ObjectNode report =
        t.json
            .createObjectNode()
            .put("origin", "AUTHORED_TEST_ONLY")
            .put("jvmPid", ProcessHandle.current().pid())
            .put("authenticationStackQualified", false)
            .put("allDownloadsPassed", false);
    var downloads = report.putArray("downloads");
    // Export needs real immutable repositories/artifacts only. The unselected preflight,
    // compiler and viewer dependencies are absent so accidental calls fail immediately.
    var service =
        new ReplayV2RunService(
            null,
            null,
            t.runService,
            t.manifests,
            null,
            t.refs,
            t.artifacts,
            t.reports,
            t.mismatches,
            samples,
            t.hashes,
            t.json,
            t.properties);
    try (var web = new GenericWebApplicationContext()) {
      new AnnotatedBeanDefinitionReader(web).register(Mvc.class);
      web.registerBean(ReplayV2Controller.class, () -> new ReplayV2Controller(service));
      web.registerBean(ReplayExceptionHandler.class, ReplayExceptionHandler::new);
      var factory = new TomcatServletWebServerFactory(0);
      factory.setAddress(InetAddress.getByName("127.0.0.1"));
      var server =
          factory.getWebServer(
              context -> {
                web.setServletContext(context);
                web.refresh();
                var dispatcher =
                    context.addServlet("native-restart-export", new DispatcherServlet(web));
                dispatcher.setLoadOnStartup(1);
                dispatcher.addMapping("/");
                Filter principals =
                    (request, response, chain) -> {
                      HttpServletRequest http = (HttpServletRequest) request;
                      String owner = http.getHeader("X-Test-Replay-Owner");
                      chain.doFilter(
                          new HttpServletRequestWrapper(http) {
                            @Override
                            public Principal getUserPrincipal() {
                              return owner == null ? null : () -> owner;
                            }
                          },
                          response);
                    };
                context
                    .addFilter("test-principal-only", principals)
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
              });
      try {
        server.start();
        report.put("loopbackPort", server.getPort());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (JsonNode variant : continuation.path("variants")) {
          UUID id = UUID.fromString(variant.path("childRunId").asText());
          for (String kind : List.of("manifest", "trace", "hashes", "report", "outcome")) {
            URI uri =
                URI.create(
                    "http://127.0.0.1:"
                        + server.getPort()
                        + "/api/replay/v2/runs/"
                        + id
                        + "/artifacts/"
                        + kind);
            assertThat(download(client, uri, null).statusCode()).isEqualTo(401);
            assertThat(download(client, uri, "foreign-owner").statusCode()).isEqualTo(404);
            HttpResponse<byte[]> response = download(client, uri, "authored-native-database-owner");
            assertThat(response.statusCode()).isEqualTo(200);
            String checksum =
                response.headers().firstValue("X-Replay-Artifact-Checksum").orElseThrow();
            assertThat(checksum).isEqualTo(t.hashes.sha256Hex(response.body()));
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                .contains("replay-" + id + "-" + kind + ".json");
            JsonNode content = t.json.readTree(response.body());
            if (kind.equals("trace")) assertThat(content).isEqualTo(variant.path("restoredTrace"));
            if (kind.equals("report")) {
              assertThat(content.path("executedActionCount").asInt())
                  .isEqualTo(variant.path("finalActions").asInt());
              assertThat(content.path("controlAllowed").asBoolean()).isFalse();
            }
            if (kind.equals("hashes")) {
              var logical = ((ObjectNode) content).deepCopy();
              String embedded = logical.remove("bundleChecksum").asText();
              assertThat(embedded).isEqualTo(t.hashes.stateHash(logical));
              assertThat(content.path("sampleCount").asInt())
                  .isEqualTo(content.path("samples").size());
            }
            Path saved = output.resolve(id + "-" + kind + ".json");
            Files.write(saved, response.body(), StandardOpenOption.CREATE_NEW);
            downloads
                .addObject()
                .put("runId", id.toString())
                .put("kind", kind)
                .put("checksum", checksum)
                .put("bytes", response.body().length);
          }
        }
        var recoveredCases = new ArrayList<JsonNode>();
        continuation
            .path("variants")
            .forEach(variant -> recoveredCases.add(variant.path("abandonedRestore")));
        recoveredCases.add(continuation.path("abandonedAdvance"));
        for (JsonNode abandoned : recoveredCases) {
          UUID id = UUID.fromString(abandoned.path("runId").asText());
          for (String kind : List.of("report", "hashes")) {
            URI uri =
                URI.create(
                    "http://127.0.0.1:"
                        + server.getPort()
                        + "/api/replay/v2/runs/"
                        + id
                        + "/artifacts/"
                        + kind);
            assertThat(download(client, uri, null).statusCode()).isEqualTo(401);
            assertThat(download(client, uri, "foreign-owner").statusCode()).isEqualTo(404);
            var response = download(client, uri, "authored-native-database-owner");
            assertThat(response.statusCode()).isEqualTo(200);
            String checksum =
                response.headers().firstValue("X-Replay-Artifact-Checksum").orElseThrow();
            assertThat(checksum).isEqualTo(t.hashes.sha256Hex(response.body()));
            JsonNode content = t.json.readTree(response.body());
            if (kind.equals("report")) {
              assertThat(content.path("executedActionCount").asInt())
                  .isEqualTo(abandoned.path("sourceActions").asInt());
              var logical = ((ObjectNode) content).deepCopy();
              String embedded = logical.remove("reportChecksum").asText();
              assertThat(embedded).isEqualTo(t.hashes.stateHash(logical));
            } else {
              assertThat(content.path("samples")).hasSize(abandoned.path("hashSamples").asInt());
            }
            Files.write(
                output.resolve(id + "-" + kind + ".json"),
                response.body(),
                StandardOpenOption.CREATE_NEW);
            downloads
                .addObject()
                .put("runId", id.toString())
                .put("kind", kind)
                .put("checksum", checksum)
                .put("bytes", response.body().length)
                .put("recovered", true);
          }
        }
        assertThat(downloads).hasSize(23);
        report.put("allDownloadsPassed", true);
      } finally {
        server.stop();
      }
    } finally {
      Files.writeString(
          output.resolve("http-export.json"),
          t.json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
          StandardOpenOption.CREATE_NEW);
    }
  }

  private static HttpResponse<byte[]> download(HttpClient client, URI uri, String owner)
      throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
    if (owner != null) request.header("X-Test-Replay-Owner", owner);
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class Mvc {}
}
