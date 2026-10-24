package com.chromascape.web.replay.session;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.*;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;

/** Serves the full compiled OsrsClient, never the diagnostic entity observer. */
@Controller
public class XrspsRealClientConfig implements WebMvcConfigurer {
  private final ReplayProperties properties;
  private final XrspsSessionRepository repository;
  private final ReplayPrincipalService principals;
  private final ObjectMapper mapper;
  private JsonNode packaged;

  public XrspsRealClientConfig(
      ReplayProperties properties,
      XrspsSessionRepository repository,
      ReplayPrincipalService principals,
      ObjectMapper mapper) {
    this.properties = properties;
    this.repository = repository;
    this.principals = principals;
    this.mapper = mapper;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (!properties.getClient().isEnabled()) return;
    var config = properties.getClient();
    try {
      Path assets = Path.of(config.getAssetsDirectory()).toRealPath(),
          caches = Path.of(config.getCachesDirectory()).toRealPath();
      packaged = mapper.readTree(assets.resolve("replay-client-manifest.json").toFile());
      if (packaged.path("version").asInt() != 1
          || !Files.isRegularFile(caches.resolve("caches.json")))
        throw new IllegalStateException("Invalid client/cache package");
      verifyFiles(
          assets,
          packaged.path("assets"),
          java.util.Set.of("replay-client-manifest.json", "worker-capabilities.json"));
      verifyFiles(caches, packaged.path("cacheAssets"), java.util.Set.of());
      if (!packaged.path("assets").has("index.html")
          || !packaged.path("cacheAssets").has("caches.json"))
        throw new IllegalStateException("Actual client index missing");
      registry
          .addResourceHandler("/xrsps-client/caches/**")
          .addResourceLocations(caches.toUri().toString());
      registry
          .addResourceHandler("/xrsps-client/**")
          .addResourceLocations(assets.toUri().toString());
    } catch (Exception error) {
      throw new IllegalStateException(
          "Configured actual xRSPS client package could not be verified", error);
    }
  }

  static void verifyFiles(Path root, JsonNode declared, java.util.Set<String> exempt)
      throws Exception {
    // Compare physical paths on both sides, including Windows short-name aliases.
    root = root.toRealPath();
    if (!declared.isObject() || declared.isEmpty())
      throw new IllegalStateException("Package file manifest missing");
    var remaining = new java.util.HashSet<String>();
    declared.fieldNames().forEachRemaining(remaining::add);
    try (var files = Files.walk(root)) {
      for (Path file : files.toList()) {
        if (Files.isSymbolicLink(file))
          throw new IllegalStateException("Package contains a symlink");
        if (Files.isDirectory(file)) continue;
        String key = root.relativize(file).toString().replace('\\', '/');
        if (exempt.contains(key)) continue;
        if (!remaining.remove(key)
            || !Files.isRegularFile(file)
            || !file.toRealPath().startsWith(root))
          throw new IllegalStateException("Undeclared or escaped package asset: " + key);
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
          byte[] buffer = new byte[65536];
          int read;
          while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(declared.path(key).asText()))
          throw new IllegalStateException("Package asset checksum differs: " + key);
      }
    }
    if (!remaining.isEmpty())
      throw new IllegalStateException("Package assets missing: " + remaining.iterator().next());
  }

  @GetMapping({"/xrsps-client", "/xrsps-client/"})
  public String client(@RequestParam UUID session, Principal principal) {
    var doc = repository.getOwned(session, principals.requirePrincipal(principal));
    var pins = doc.manifest.path("runtime");
    if (packaged == null
        || doc.clientBootstrap == null
        || !doc.clientBootstrap.isObject()
        || !samePin(packaged.path("adapterVersion"), pins.path("xrspsAdapterVersion"))
        || !samePin(packaged.path("buildCommit"), pins.path("xrspsBuildCommit"))
        || !samePin(packaged.path("cacheRevision"), pins.path("cacheRevision"))
        || !samePin(packaged.path("contentRevision"), pins.path("contentRevision")))
      throw ReplayException.unavailable(
          ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
          "Actual client package does not match this isolated session",
          "Build the client from the configured certified worker package");
    return "forward:/xrsps-client/index.html";
  }

  private static boolean samePin(JsonNode packagedPin, JsonNode sessionPin) {
    return packagedPin.isTextual()
        && !packagedPin.textValue().isBlank()
        && sessionPin.isTextual()
        && packagedPin.textValue().equals(sessionPin.textValue());
  }
}
