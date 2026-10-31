package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Authoritative source coordinate plane pinned by a review session.
 *
 * <p>Correction coordinates are expressed on the displayed source plane. A probe records coded
 * dimensions plus a display rotation; sharing only those loose numbers is not enough, so the plane
 * also fixes its origin and pixel-centre convention. The digest lets the server and converter
 * refuse a review or sealed export whose plane no longer matches the analysis it was created from.
 */
@Component
public class MouseVideoCoordinatePlane {

  public static final String CONTRACT = "coordinate-plane/v1";
  private static final long MAX_MANIFEST_BYTES = 1024L * 1024;
  private static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);

  private final MouseVideoByteStore byteStore;
  private final ObjectMapper mapper;

  public MouseVideoCoordinatePlane(MouseVideoByteStore byteStore, ObjectMapper mapper) {
    this.byteStore = byteStore;
    this.mapper = mapper;
  }

  /** A normalized plane document plus the digest written onto the session. */
  public record Identity(Map<String, Object> document, String digest) {}

  /**
   * Derives the plane from the analysis-owned manifest. Probe analyses publish raw probe facts
   * (coded width/height/rotation); visual analyses publish a nested plane. Unknown or malformed
   * evidence yields an empty result so callers can fail closed rather than guess.
   */
  public Optional<Identity> forAnalysis(MouseVideoAnalysis analysis) {
    if (analysis == null) return Optional.empty();
    String checksum = analysis.getManifestChecksum();
    if (checksum == null || checksum.isBlank()) return Optional.empty();
    Optional<Path> located = byteStore.locate(checksum);
    if (located.isEmpty()) return Optional.empty();
    try {
      Path path = located.get();
      if (!Files.isRegularFile(path) || Files.size(path) > MAX_MANIFEST_BYTES)
        return Optional.empty();
      JsonNode root = mapper.readTree(Files.readAllBytes(path));
      JsonNode declared = root.path("coordinatePlane");
      int width;
      int height;
      int rotation;
      if (declared.isObject()) {
        String contract = declared.path("contract").asText("");
        if (!CONTRACT.equals(contract)) return Optional.empty();
        width = declared.path("codedWidth").asInt(0);
        height = declared.path("codedHeight").asInt(0);
        rotation = declared.path("rotationDegrees").asInt(-1);
      } else {
        width = root.path("width").asInt(0);
        height = root.path("height").asInt(0);
        rotation = root.path("rotationDegrees").asInt(0);
      }
      return normalize(width, height, rotation);
    } catch (Exception failure) {
      return Optional.empty();
    }
  }

  /** Builds the canonical plane used by analysis manifests and probe-only sessions. */
  public static Map<String, Object> fromProbe(int width, int height, int rotation) {
    int normalized = ((rotation % 360) + 360) % 360;
    boolean quarterTurn = normalized == 90 || normalized == 270;
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("contract", CONTRACT);
    document.put("codedWidth", width);
    document.put("codedHeight", height);
    document.put("rotationDegrees", normalized);
    document.put("displayWidth", quarterTurn ? height : width);
    document.put("displayHeight", quarterTurn ? width : height);
    document.put("origin", "top-left");
    document.put("pixelCenter", true);
    return document;
  }

  private Optional<Identity> normalize(int width, int height, int rotation) {
    if (width < 1 || height < 1 || width > 8192 || height > 8192) return Optional.empty();
    int normalized = ((rotation % 360) + 360) % 360;
    if (!ROTATIONS.contains(normalized)) return Optional.empty();
    Map<String, Object> document = fromProbe(width, height, normalized);
    try {
      String canonical = mapper.writeValueAsString(document);
      String digest =
          "sha256:"
              + HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return Optional.of(new Identity(document, digest));
    } catch (Exception failure) {
      return Optional.empty();
    }
  }
}
