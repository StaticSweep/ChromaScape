package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

class XrspsRealClientConfigTest {
  @TempDir Path directory;
  final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"adapterVersion", "buildCommit", "cacheRevision", "contentRevision"})
  void clientRequiresEveryExactNonemptyRuntimePin(String packagedKey) throws Exception {
    String sessionKey =
        switch (packagedKey) {
          case "adapterVersion" -> "xrspsAdapterVersion";
          case "buildCommit" -> "xrspsBuildCommit";
          default -> packagedKey;
        };
    var assets = Files.createDirectory(directory.resolve("assets"));
    var caches = Files.createDirectory(directory.resolve("caches"));
    Files.writeString(assets.resolve("index.html"), "actual client test asset");
    Files.writeString(caches.resolve("caches.json"), "{}");
    var packaged = mapper.createObjectNode();
    packaged.put("version", 1);
    packaged.put("adapterVersion", "0.1.0");
    packaged.put("buildCommit", "a".repeat(40));
    packaged.put("cacheRevision", "sha256:" + "b".repeat(64));
    packaged.put("contentRevision", "sha256:" + "c".repeat(64));
    packaged.putObject("assets").put("index.html", digest(assets.resolve("index.html")));
    packaged.putObject("cacheAssets").put("caches.json", digest(caches.resolve("caches.json")));
    var runtime = mapper.createObjectNode();
    runtime.set("xrspsAdapterVersion", packaged.get("adapterVersion"));
    runtime.set("xrspsBuildCommit", packaged.get("buildCommit"));
    runtime.set("cacheRevision", packaged.get("cacheRevision"));
    runtime.set("contentRevision", packaged.get("contentRevision"));
    var doc = new XrspsSessionDocument();
    doc.manifest = mapper.createObjectNode().set("runtime", runtime);
    doc.clientBootstrap = mapper.createObjectNode();
    var properties = new ReplayProperties();
    properties.getClient().setEnabled(true);
    properties.getClient().setAssetsDirectory(assets.toString());
    properties.getClient().setCachesDirectory(caches.toString());
    var repository = mock(XrspsSessionRepository.class);
    var principals = mock(ReplayPrincipalService.class);
    Principal principal = () -> "owner";
    var session = UUID.randomUUID();
    when(principals.requirePrincipal(principal)).thenReturn("owner");
    when(repository.getOwned(session, "owner")).thenReturn(doc);
    var controller = new XrspsRealClientConfig(properties, repository, principals, mapper);
    var handlers = mock(ResourceHandlerRegistry.class, RETURNS_DEEP_STUBS);
    writePackage(assets, packaged, controller, handlers);
    assertEquals("forward:/xrsps-client/index.html", controller.client(session, principal));

    var original = runtime.get(sessionKey);
    runtime.put(sessionKey, "different");
    assertThrows(ReplayException.class, () -> controller.client(session, principal));
    runtime.remove(sessionKey);
    assertThrows(ReplayException.class, () -> controller.client(session, principal));
    runtime.set(sessionKey, original);

    for (String invalid : new String[] {"different", "", " "}) {
      packaged.put(packagedKey, invalid);
      writePackage(assets, packaged, controller, handlers);
      assertThrows(ReplayException.class, () -> controller.client(session, principal));
    }
    // Two absent pins must not compare equal through JsonNode.asText().
    packaged.remove(packagedKey);
    runtime.remove(sessionKey);
    writePackage(assets, packaged, controller, handlers);
    assertThrows(ReplayException.class, () -> controller.client(session, principal));
    packaged.put(packagedKey, 123);
    runtime.put(sessionKey, "123");
    writePackage(assets, packaged, controller, handlers);
    assertThrows(ReplayException.class, () -> controller.client(session, principal));
    packaged.set(packagedKey, original);
    runtime.set(sessionKey, original);
    writePackage(assets, packaged, controller, handlers);
    assertEquals("forward:/xrsps-client/index.html", controller.client(session, principal));
  }

  private void writePackage(
      Path assets,
      ObjectNode packaged,
      XrspsRealClientConfig controller,
      ResourceHandlerRegistry handlers)
      throws Exception {
    mapper.writeValue(assets.resolve("replay-client-manifest.json").toFile(), packaged);
    controller.addResourceHandlers(handlers);
  }

  private String digest(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  @Test
  void modifiedCacheAndUndeclaredAssetsRefuseThePackage() throws Exception {
    var file = directory.resolve("cache.dat");
    Files.writeString(file, "original cache");
    var declared =
        mapper
            .createObjectNode()
            .put(
                "cache.dat",
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
    assertDoesNotThrow(() -> XrspsRealClientConfig.verifyFiles(directory, declared, Set.of()));
    Files.writeString(file, "changed cache");
    assertThrows(
        IllegalStateException.class,
        () -> XrspsRealClientConfig.verifyFiles(directory, declared, Set.of()));
    Files.writeString(file, "original cache");
    Files.writeString(directory.resolve("undeclared.txt"), "extra");
    assertThrows(
        IllegalStateException.class,
        () -> XrspsRealClientConfig.verifyFiles(directory, declared, Set.of()));
  }

  @Test
  void missingAndEscapedAssetsRefuseThePackage() throws Exception {
    var declared = mapper.createObjectNode().put("../outside.dat", "0".repeat(64));
    assertThrows(
        IllegalStateException.class,
        () -> XrspsRealClientConfig.verifyFiles(directory, declared, Set.of()));
    Path outside = Files.createTempFile("sc1991-outside-", ".dat");
    try {
      Files.createSymbolicLink(directory.resolve("link.dat"), outside);
      declared.removeAll();
      declared.put("link.dat", "0".repeat(64));
      assertThrows(
          IllegalStateException.class,
          () -> XrspsRealClientConfig.verifyFiles(directory, declared, Set.of()));
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
