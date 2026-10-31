package com.chromascape.web.debug.tickdebugger.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * Canonical request identity for external-video intake. Identity is bound to the authenticated
 * owner, the caller UUID, the source kind, the exact SHA-256 and length of the uploaded bytes (when
 * applicable), the normalized semantic metadata and the ingestion contract version. Caller MIME is
 * metadata, not identity. Fields are serialized as a sorted canonical JSON object and hashed with
 * SHA-256 so identical retries collide and any content or metadata change conflicts.
 */
public final class MouseVideoRequestIdentity {

  public static final String CONTRACT = "sc2150-ingest/v1";

  private MouseVideoRequestIdentity() {}

  public static String forUpload(
      ObjectMapper mapper,
      String owner,
      MouseVideoImportMetadata metadata,
      String fileChecksum,
      long fileSizeBytes) {
    TreeMap<String, Object> canonical = base(owner, metadata, "UPLOAD");
    canonical.put("contract", CONTRACT);
    canonical.put("fileChecksum", fileChecksum == null ? "" : fileChecksum);
    canonical.put("fileSizeBytes", fileSizeBytes);
    canonical.put("providerId", "");
    return hash(mapper, canonical);
  }

  public static String forYouTube(
      ObjectMapper mapper, String owner, MouseVideoImportMetadata metadata, String providerId) {
    TreeMap<String, Object> canonical = base(owner, metadata, "YOUTUBE");
    canonical.put("contract", CONTRACT);
    canonical.put("fileChecksum", "");
    canonical.put("fileSizeBytes", -1L);
    canonical.put("providerId", providerId == null ? "" : providerId);
    return hash(mapper, canonical);
  }

  private static TreeMap<String, Object> base(
      String owner, MouseVideoImportMetadata metadata, String kind) {
    TreeMap<String, Object> canonical = new TreeMap<>();
    canonical.put("owner", owner);
    canonical.put("requestId", metadata.requestId());
    canonical.put("kind", kind);
    canonical.put("declaredOrigin", metadata.declaredOrigin());
    canonical.put("taskKey", orEmpty(metadata.taskKey()));
    canonical.put("skill", orEmpty(metadata.skill()));
    canonical.put("context", orEmpty(metadata.contextLabel()));
    canonical.put("place", orEmpty(metadata.placeLabel()));
    return canonical;
  }

  private static String hash(ObjectMapper mapper, TreeMap<String, Object> canonical) {
    try {
      byte[] json = mapper.writeValueAsBytes(canonical);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().formatHex(digest.digest(json));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "Canonical request identity could not be computed", impossible);
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
