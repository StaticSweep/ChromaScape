package com.chromascape.web.aiplayer.contract;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Requires staged original producer inputs. Run explicitly with sc663ContractTest. */
class CurrentContractProducerRoundTripTest {
  @TempDir Path output;

  @Test
  void originalRuneLiteCaptureProducerAndConsumerRoundTripExactly() throws Exception {
    Path sources = Path.of(System.getProperty("sc663.capture.sources"));
    Path identity = sources.resolve("com/chromascape/contracts/capture/CaptureIdentity.java");
    Path codec = sources.resolve("com/chromascape/contracts/capture/JsonCaptureCodec.java");
    assertThat(identity).isRegularFile();
    assertThat(codec).isRegularFile();
    String gsonJar =
        Path.of(
                com.google.gson.Gson.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
            .toString();
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("Producer gate needs a JDK").isNotNull();
    assertThat(
            compiler.run(
                null,
                null,
                null,
                "--release",
                "11",
                "-cp",
                gsonJar,
                "-d",
                output.toString(),
                identity.toString(),
                codec.toString()))
        .isZero();
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {output.toUri().toURL()}, getClass().getClassLoader())) {
      var type = loader.loadClass("com.chromascape.contracts.capture.CaptureIdentity");
      String json;
      try (var stream = getClass().getResourceAsStream("/sc663/runelite-identity.json")) {
        json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      Object producer = type.getMethod("forCreation", String.class).invoke(null, json);
      Object emitted = type.getMethod("json").invoke(producer);
      var codecType = loader.loadClass("com.chromascape.contracts.capture.JsonCaptureCodec");
      String wire =
          (String)
              codecType
                  .getMethod("encode", com.google.gson.JsonElement.class)
                  .invoke(null, emitted);
      var mapper = new ObjectMapper();
      var consumer = mapper.readValue(wire, CurrentSliceIdentity.class);
      consumer.validate();
      String response = mapper.writeValueAsString(consumer);
      Object returned = type.getMethod("forCreation", String.class).invoke(null, response);
      String returnedWire =
          (String)
              codecType
                  .getMethod("encode", com.google.gson.JsonElement.class)
                  .invoke(null, type.getMethod("json").invoke(returned));
      assertThat(CanonicalJsonHasher.parse(returnedWire.getBytes(StandardCharsets.UTF_8)))
          .isEqualTo(CanonicalJsonHasher.parse(wire.getBytes(StandardCharsets.UTF_8)));
      assertThat(type.getMethod("clientBuildId").invoke(returned)).isEqualTo("fixture-runelite");
    }
  }

  @Test
  void originalMicro6PublishedFixtureBytesMatchConsumerInputs() throws Exception {
    Path producer = Path.of(System.getProperty("sc663.micro6.fixtures"));
    for (String name :
        List.of(
            "catalog-lightweight.json",
            "catalog-full-ready.json",
            "catalog-full-not-applicable.json",
            "catalog-full-error.json",
            "health-bootstrap.json",
            "health-generating.json",
            "health-available.json",
            "fixtures-manifest.sha256")) {
      Path file = producer.resolve(name);
      assertThat(file).isRegularFile();
      try (var stream = getClass().getResourceAsStream("/micro6-fixtures/" + name)) {
        assertThat(Files.readAllBytes(file))
            .as("Producer bytes: %s", name)
            .isEqualTo(stream.readAllBytes());
      }
    }
  }

  @Test
  void originalMicro6HashesRoundTripThroughConsumerValidation() throws Exception {
    Path sources = Path.of(System.getProperty("sc663.micro6.sources"));
    String base = "net/runelite/client/plugins/microbot/accountbuilder/";
    var files =
        List.of(
            sources.resolve(base + "harness/CanonicalJson.java"),
            sources.resolve(base + "harness/CanonicalCatalogModel.java"),
            sources.resolve(base + "integration/builder/CatalogV2Publication.java"));
    for (Path file : files) assertThat(file).isRegularFile();
    var dependencyTypes =
        List.of(
            ObjectMapper.class,
            com.fasterxml.jackson.core.JsonFactory.class,
            com.fasterxml.jackson.annotation.JsonProperty.class);
    var dependencies = new java.util.ArrayList<String>();
    for (Class<?> type : dependencyTypes)
      dependencies.add(
          Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    var arguments =
        new java.util.ArrayList<String>(
            List.of(
                "--release",
                "11",
                "-cp",
                String.join(java.io.File.pathSeparator, dependencies),
                "-d",
                output.toString()));
    files.forEach(file -> arguments.add(file.toString()));
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).isNotNull();
    assertThat(compiler.run(null, null, null, arguments.toArray(String[]::new))).isZero();
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {output.toUri().toURL()}, getClass().getClassLoader())) {
      var model =
          loader.loadClass(
              "net.runelite.client.plugins.microbot.accountbuilder.harness.CanonicalCatalogModel");
      var publication =
          loader.loadClass(
              "net.runelite.client.plugins.microbot.accountbuilder.integration.builder.CatalogV2Publication");
      var bundle = publication.getDeclaredMethod("schemaBundleHash", List.class, String.class);
      bundle.setAccessible(true);
      var mapper = new ObjectMapper();
      for (String name :
          List.of(
              "catalog-lightweight.json",
              "catalog-full-ready.json",
              "catalog-full-not-applicable.json",
              "catalog-full-error.json")) {
        try (var stream = getClass().getResourceAsStream("/micro6-fixtures/" + name)) {
          var input = mapper.readTree(stream);
          boolean full = !name.equals("catalog-lightweight.json");
          var verdict =
              full
                  ? com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract
                      .validateFull(input)
                  : com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract
                      .validateLightweight(input);
          assertThat(verdict.valid()).as("%s: %s", name, verdict.reasons()).isTrue();
          var returned = mapper.readTree(mapper.writeValueAsBytes(input));
          assertThat(returned).isEqualTo(input);
          String catalogHash =
              (String)
                  model
                      .getMethod(
                          "computeCatalogHash", com.fasterxml.jackson.databind.JsonNode.class)
                      .invoke(null, returned);
          assertThat(catalogHash)
              .isEqualTo(verdict.catalogHash())
              .isEqualTo(input.path("catalog_hash").asText());
          if (full) {
            var tasks = mapper.convertValue(returned.path("tasks"), List.class);
            assertThat(bundle.invoke(null, tasks, catalogHash))
                .isEqualTo(verdict.schemaBundleHash())
                .isEqualTo(input.path("schema_bundle_hash").asText());
          }
        }
      }
    }
  }
}
