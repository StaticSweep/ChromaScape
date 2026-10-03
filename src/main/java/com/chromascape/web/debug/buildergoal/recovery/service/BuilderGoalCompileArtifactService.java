package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryArtifactKinds;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryArtifact;
import com.chromascape.web.debug.buildergoal.recovery.repository.BuilderGoalRecoveryArtifactRepository;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalSensitiveTextSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Captures allowlisted launch/compilation logs, sanitizes before persistence, extracts bounded
 * diagnostics, writes content-addressed objects, and verifies checksums on read. Storage keys are
 * always server-generated; no caller may supply a path.
 */
@Service
public class BuilderGoalCompileArtifactService {

  private static final Logger logger =
      LoggerFactory.getLogger(BuilderGoalCompileArtifactService.class);
  private static final int TAIL_LINES = 4000;
  private static final int TAIL_BYTES = 2 * 1024 * 1024;

  private final BuilderGoalRecoveryProperties properties;
  private final BuilderGoalRecoveryArtifactRepository artifactRepository;

  public BuilderGoalCompileArtifactService(
      BuilderGoalRecoveryProperties properties,
      BuilderGoalRecoveryArtifactRepository artifactRepository) {
    this.properties = properties;
    this.artifactRepository = artifactRepository;
  }

  /** Captures, sanitizes, and stores the sanitized raw log plus structured diagnostics. */
  public record CapturedEvidence(
      BuilderGoalRecoveryArtifact rawLog,
      BuilderGoalRecoveryArtifact diagnostics,
      List<BuilderGoalCompileDiagnosticParser.Diagnostic> parsed,
      int errorCount,
      int warningCount,
      boolean truncated) {}

  public CapturedEvidence captureCompileEvidence(
      String incidentId, JsonNode taskMetadataJson, String repositoryRoot) {
    Path logPath = resolveCompileLogPath(taskMetadataJson);
    if (logPath == null || !Files.isRegularFile(logPath)) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.COMPILE_EVIDENCE_MISSING,
          "No sanitized compilation log was captured for this run");
    }
    TailResult tail;
    try {
      tail = readTailLines(logPath, TAIL_LINES, TAIL_BYTES);
    } catch (IOException e) {
      logger.warn(
          "Could not read bounded compile log tail: incidentId={} (details omitted)", incidentId);
      throw RecoveryException.conflict(
          RecoveryFailureCodes.COMPILE_EVIDENCE_MISSING,
          "Sanitized compilation log could not be read");
    }
    String sanitized = sanitizeArtifact(String.join("\n", tail.lines()));
    BuilderGoalRecoveryArtifact rawLog =
        store(
            RecoveryArtifactKinds.ORIGINAL_COMPILE_LOG,
            incidentId,
            null,
            sanitized,
            tail.truncated());
    BuilderGoalCompileDiagnosticParser.ParseResult parseResult =
        BuilderGoalCompileDiagnosticParser.parse(
            tail.lines(), properties.getDiagnosticLimit(), repositoryRoot);
    String diagnosticsJson = diagnosticsJson(parseResult, incidentId, rawLog);
    BuilderGoalRecoveryArtifact diagnosticsArtifact =
        store(
            RecoveryArtifactKinds.COMPILER_DIAGNOSTICS,
            incidentId,
            null,
            diagnosticsJson,
            parseResult.diagnostics().size() >= properties.getDiagnosticLimit());
    return new CapturedEvidence(
        rawLog,
        diagnosticsArtifact,
        parseResult.diagnostics(),
        parseResult.errorCount(),
        parseResult.warningCount(),
        tail.truncated());
  }

  /** Sanitizes and stores an artifact under a server-generated content-addressed key. */
  public BuilderGoalRecoveryArtifact store(
      String kind, String incidentId, String attemptId, String content, boolean truncated) {
    if (!RecoveryArtifactKinds.isAllowlisted(kind)) {
      throw RecoveryException.badRequest(
          "ARTIFACT_KIND_NOT_ALLOWED", "Artifact kind is not allowed");
    }
    String sanitized = sanitizeArtifact(content);
    byte[] bytes = sanitized.getBytes(StandardCharsets.UTF_8);
    int limit = properties.getRawLogLimitBytes();
    if (bytes.length > limit) {
      sanitized = sanitizeArtifact(new String(bytes, 0, limit, StandardCharsets.UTF_8));
      bytes = sanitized.getBytes(StandardCharsets.UTF_8);
      truncated = true;
    }
    String sha256 = RecoveryHashes.sha256(bytes);
    String storageKey =
        "incidents/"
            + incidentId
            + "/"
            + kind.toLowerCase(java.util.Locale.ROOT)
            + "/"
            + sha256
            + ".txt";
    BuilderGoalRecoveryArtifact existing =
        artifactRepository
            .findByIncidentIdAndKindAndSha256AndSanitizationVersion(
                incidentId, kind, sha256, properties.getSanitizerVersion())
            .orElse(null);
    if (existing != null) {
      verifyIdentity(existing, storageKey, sha256);
      return existing;
    }
    try {
      Path root = Path.of(properties.getArtifactRoot()).toAbsolutePath().normalize();
      Path target = root.resolve(storageKey).normalize();
      if (!target.startsWith(root)) {
        throw new IOException("storage key escapes artifact root");
      }
      if (!Files.isRegularFile(target)) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectories(target.getParent());
        Files.write(temp, bytes);
        try {
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
          Files.move(temp, target);
        } catch (java.nio.file.FileAlreadyExistsException e) {
          Files.deleteIfExists(temp);
        }
      }
    } catch (IOException e) {
      throw new RecoveryException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "ARTIFACT_STORE_UNAVAILABLE",
          "Sanitized artifact could not be persisted",
          true);
    }
    BuilderGoalRecoveryArtifact artifact = new BuilderGoalRecoveryArtifact();
    artifact.setArtifactId(RecoveryHashes.newId("artifact"));
    artifact.setIncidentId(incidentId);
    artifact.setAttemptId(attemptId);
    artifact.setKind(kind);
    artifact.setStorageKey(storageKey);
    artifact.setSha256(sha256);
    artifact.setByteCount(bytes.length);
    artifact.setLineCount(sanitized.isEmpty() ? 0 : sanitized.split("\n", -1).length);
    artifact.setSanitizationVersion(properties.getSanitizerVersion());
    artifact.setTruncated(truncated);
    artifact.setCreatedAt(Instant.now());
    try {
      return artifactRepository.saveAndFlush(artifact);
    } catch (DataIntegrityViolationException conflict) {
      BuilderGoalRecoveryArtifact raced =
          artifactRepository
              .findByIncidentIdAndKindAndSha256AndSanitizationVersion(
                  incidentId, kind, sha256, properties.getSanitizerVersion())
              .orElseThrow(() -> conflict);
      verifyIdentity(raced, storageKey, sha256);
      return raced;
    }
  }

  private static void verifyIdentity(
      BuilderGoalRecoveryArtifact artifact, String storageKey, String sha256) {
    if (!storageKey.equals(artifact.getStorageKey()) || !sha256.equals(artifact.getSha256())) {
      throw RecoveryException.conflict(
          "ARTIFACT_IDENTITY_CONFLICT", "Artifact identity resolved to different stored content");
    }
  }

  /** Reads and checksum-verifies the bounded sanitized artifact content. */
  public String readContent(BuilderGoalRecoveryArtifact artifact) {
    Path root = Path.of(properties.getArtifactRoot()).toAbsolutePath().normalize();
    Path target = root.resolve(artifact.getStorageKey()).normalize();
    if (!target.startsWith(root)) {
      throw RecoveryException.notFound("ARTIFACT_NOT_FOUND", "Artifact is unavailable");
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(target);
    } catch (IOException e) {
      throw RecoveryException.notFound("ARTIFACT_NOT_FOUND", "Artifact is unavailable");
    }
    if (!artifact.getSha256().equals(RecoveryHashes.sha256(bytes))) {
      throw new RecoveryException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "ARTIFACT_CHECKSUM_MISMATCH",
          "Stored artifact failed checksum verification",
          false);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  /** Response-size bounded view of an artifact for the browser. */
  public String readView(BuilderGoalRecoveryArtifact artifact, String view) {
    String content = readContent(artifact);
    int limit = properties.getResponseLimitBytes();
    if (content.length() > limit) {
      content = content.substring(content.length() - limit);
    }
    if ("summary".equals(view)) {
      return content.substring(0, Math.min(content.length(), 4000));
    }
    return content;
  }

  private String sanitizeArtifact(String content) {
    return BuilderGoalSensitiveTextSanitizer.sanitizeArtifact(
        content, BuilderGoalSensitiveTextSanitizer.environmentLiteralSecrets());
  }

  /**
   * Resolves the compile log path from persisted run metadata. Any configured log path is only a
   * hint: the file must exist, and only its bounded sanitized tail is ever read.
   */
  private Path resolveCompileLogPath(JsonNode taskMetadataJson) {
    if (taskMetadataJson == null
        || taskMetadataJson.isNull()
        || !taskMetadataJson.path("launcher_log_file").isTextual()) {
      return null;
    }
    String candidate = taskMetadataJson.path("launcher_log_file").asText();
    // Never accept network/device paths or arbitrary recursive metadata values. The launcher
    // owns this single field and its resolved target must remain within Micro6's configured root.
    if (candidate.startsWith("\\\\") || candidate.startsWith("\\\\.") || candidate.contains("..")) {
      return null;
    }
    try {
      Path root = Path.of(properties.getMicro6ProjectDir()).toRealPath();
      Path path = Path.of(candidate).toRealPath();
      return Files.isRegularFile(path) && path.startsWith(root) ? path : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private record TailResult(List<String> lines, boolean truncated) {}

  /** Reads only a bounded tail; it never materializes or counts the whole source file. */
  private static TailResult readTailLines(Path path, int maxLines, int maxBytes)
      throws IOException {
    long size = Files.size(path);
    int bytesToRead = (int) Math.min(size, maxBytes);
    ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
    try (SeekableByteChannel channel = Files.newByteChannel(path)) {
      channel.position(Math.max(0, size - bytesToRead));
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
        // Fill the bounded buffer without traversing the preceding file contents.
      }
    }
    String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
    boolean byteTruncated = size > bytesToRead;
    if (byteTruncated) {
      int firstNewline = text.indexOf('\n');
      text = firstNewline < 0 ? "" : text.substring(firstNewline + 1);
    }
    String[] split = text.split("\\R", -1);
    int first = Math.max(0, split.length - maxLines);
    List<String> lines = new ArrayList<>(split.length - first);
    for (int i = first; i < split.length; i++) {
      lines.add(split[i]);
    }
    return new TailResult(lines, byteTruncated || first > 0);
  }

  private static String diagnosticsJson(
      BuilderGoalCompileDiagnosticParser.ParseResult parseResult,
      String incidentId,
      BuilderGoalRecoveryArtifact source) {
    StringBuilder json = new StringBuilder();
    json.append("{\"schema\":\"builder-goal-compile-diagnostics/v1\",\"incident_id\":\"")
        .append(incidentId)
        .append("\",\"source_artifact_id\":\"")
        .append(source.getArtifactId())
        .append("\",\"error_count\":")
        .append(parseResult.errorCount())
        .append(",\"warning_count\":")
        .append(parseResult.warningCount())
        .append(",\"diagnostics\":[");
    boolean first = true;
    for (BuilderGoalCompileDiagnosticParser.Diagnostic diagnostic : parseResult.diagnostics()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append("{\"tool\":\"")
          .append(diagnostic.tool())
          .append("\",\"severity\":\"")
          .append(diagnostic.severity())
          .append("\",\"file\":")
          .append(jsonString(diagnostic.relativeFile()))
          .append(",\"line\":")
          .append(diagnostic.line())
          .append(",\"column\":")
          .append(diagnostic.column() == null ? "null" : String.valueOf(diagnostic.column()))
          .append(",\"message\":")
          .append(jsonString(diagnostic.message()))
          .append('}');
    }
    json.append("]}");
    return json.toString();
  }

  private static String jsonString(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder escaped = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (c < 0x20) {
            escaped.append(String.format("\\u%04x", (int) c));
          } else {
            escaped.append(c);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }
}
