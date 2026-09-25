package com.chromascape.web.accounttask.controller;

import com.chromascape.web.accounttask.entity.StabilizationArtifact;
import com.chromascape.web.accounttask.service.StabilizationArtifactStore;
import com.chromascape.web.accounttask.service.StabilizationBundleStreamReader;
import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API surface for stabilization harness ingestion, KPI reporting, and catalog access (SC-572).
 *
 * <p>The verified report-bundle endpoint authenticates in a request filter before any multipart
 * parsing and then streams bounded parts into a unique temporary directory. Duplicate part names
 * and unsafe filenames are rejected; the seven required report parts are mandatory and optional
 * recorder artifacts must be checksum-addressed.
 */
@RestController
@RequestMapping("/api/account-tasks/stabilization")
public class StabilizationController {

  private static final Set<String> REQUIRED_PARTS =
      Set.of(
          "plan", "manifest", "matrix", "transitions", "evidence", "checksums", "completeMarker");
  private static final long MAX_PART_BYTES = 64L * 1024L * 1024L;
  private static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
  private static final int MAX_PARTS = 200;

  private final StabilizationIngestionService ingestionService;
  private final Micro6ExecutorCatalogService catalogService;
  private final StabilizationArtifactStore artifactStore;

  public StabilizationController(
      StabilizationIngestionService ingestionService,
      Micro6ExecutorCatalogService catalogService,
      StabilizationArtifactStore artifactStore) {
    this.ingestionService = ingestionService;
    this.catalogService = catalogService;
    this.artifactStore = artifactStore;
  }

  @PostMapping("/ingest-report")
  public ResponseEntity<Map<String, Object>> ingestReport(@RequestBody JsonNode report) {
    return ResponseEntity.ok(ingestionService.ingestReport(report));
  }

  @PostMapping("/harness-manifest")
  public ResponseEntity<Object> ingestHarnessManifest(@RequestBody JsonNode manifest) {
    try {
      return ResponseEntity.ok(ingestionService.ingestHarnessManifest(manifest));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  /**
   * The certified ingestion path. The authorization filter authenticates before multipart parsing;
   * the {@link StabilizationBundleStreamReader} then reads the boundary from the servlet input
   * stream and streams each part into a unique bounded temporary directory, enforcing part/total
   * limits while bytes are read (including chunked requests), rejecting traversal/separators/
   * duplicate names and preserving original artifact filenames. Conflicts return 409, tampering
   * returns 400, and oversized input returns 413.
   */
  @PostMapping(value = "/report-bundles", consumes = "multipart/form-data")
  public ResponseEntity<Object> ingestReportBundle(HttpServletRequest request) {
    Path tempDir = null;
    try {
      tempDir = Files.createTempDirectory("stabilization-bundle-");
      StabilizationBundleStreamReader.StagedBundle bundle =
          StabilizationBundleStreamReader.read(
              request.getInputStream(), request.getContentType(), tempDir);

      for (String required : REQUIRED_PARTS) {
        if (!bundle.names().contains(required)) {
          throw new IllegalArgumentException(
              "report bundle is missing required part '" + required + "'");
        }
      }

      StabilizationIngestionService.BundleSource source = new StagedBundleSource(bundle);
      Map<String, Object> result = ingestionService.ingestReportBundle(source);
      return ResponseEntity.ok(result);
    } catch (com.chromascape.web.accounttask.service.StabilizationConflictException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (java.io.IOException e) {
      // Multipart size limits and malformed boundaries surface as IOException; an oversized body is
      // a 413, everything else is a malformed 400.
      String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
      if (message.contains("size exceeds")
          || message.contains("total size")
          || message.contains("exceeds its size limit")
          || message.contains("too large")) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(Map.of("error", "report bundle exceeds the size limit"));
      }
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "report bundle ingestion failed: " + e.getMessage()));
    } finally {
      if (tempDir != null) {
        try {
          Files.walk(tempDir)
              .sorted(java.util.Comparator.reverseOrder())
              .forEach(
                  p -> {
                    try {
                      Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                      // best-effort temp cleanup
                    }
                  });
        } catch (IOException ignored) {
          // best-effort temp cleanup
        }
      }
    }
  }

  /** BundleSource backed by the streamed staging files; artifact filenames are preserved. */
  private static final class StagedBundleSource
      implements StabilizationIngestionService.BundleSource {
    private final StabilizationBundleStreamReader.StagedBundle bundle;

    StagedBundleSource(StabilizationBundleStreamReader.StagedBundle bundle) {
      this.bundle = bundle;
    }

    @Override
    public Set<String> names() {
      return bundle.names();
    }

    @Override
    public Path read(String name) {
      return bundle.part(name);
    }

    @Override
    public List<StabilizationIngestionService.ArtifactPart> artifacts() {
      List<StabilizationIngestionService.ArtifactPart> parts = new ArrayList<>();
      for (StabilizationBundleStreamReader.StagedArtifact artifact : bundle.artifacts()) {
        long sizeBytes;
        try {
          sizeBytes = Files.size(artifact.path());
        } catch (java.io.IOException e) {
          throw new IllegalStateException(
              "cannot read staged artifact size for " + artifact.filename(), e);
        }
        parts.add(
            new StabilizationIngestionService.ArtifactPart(
                artifact.filename(), artifact.mediaType(), artifact.path(), sizeBytes));
      }
      return parts;
    }
  }

  @PostMapping("/reliability-matrix")
  public ResponseEntity<Object> ingestReliabilityMatrix(@RequestBody JsonNode matrix) {
    try {
      return ResponseEntity.ok(ingestionService.ingestReliabilityMatrix(matrix));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/runs/{runId}")
  public ResponseEntity<Object> getRunByRunId(@PathVariable("runId") String runId) {
    return ingestionService
        .getRunByRunId(runId)
        .map(run -> ResponseEntity.ok((Object) run))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/runs/{runId}/attempts")
  public ResponseEntity<Object> getRunAttempts(@PathVariable("runId") String runId) {
    return ResponseEntity.ok(ingestionService.getRunAttempts(runId));
  }

  @GetMapping("/runs/{runId}/attempts/{attemptId}")
  public ResponseEntity<Object> getRunByRunIdAndAttemptId(
      @PathVariable("runId") String runId, @PathVariable("attemptId") String attemptId) {
    return ingestionService
        .getRunByRunIdAndAttemptId(runId, attemptId)
        .map(run -> ResponseEntity.ok((Object) run))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/runs/{runId}/transitions")
  public ResponseEntity<Object> getRunTransitions(@PathVariable("runId") String runId) {
    return ingestionService
        .getRunTransitions(runId)
        .map(transitions -> ResponseEntity.ok((Object) transitions))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/runs/{runId}/evidence")
  public ResponseEntity<Object> getRunEvidence(@PathVariable("runId") String runId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("run_id", runId);
    body.put(
        "attempt_ids",
        ingestionService.getRunAttempts(runId).stream()
            .map(com.chromascape.web.accounttask.entity.ScriptStabilizationRun::getAttemptId)
            .toList());
    body.put("artifacts", ingestionService.getRunArtifacts(runId));
    ingestionService.getRunEvidence(runId).ifPresent(evidence -> body.put("evidence", evidence));
    return ResponseEntity.ok(body);
  }

  @GetMapping("/matrices/{matrixHash}")
  public ResponseEntity<Object> getMatrixByMatrixHash(
      @PathVariable("matrixHash") String matrixHash) {
    return ingestionService
        .getMatrixDocumentByHash(matrixHash)
        .map(matrix -> ResponseEntity.ok((Object) matrix))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Streams a stored artifact's bytes; never exposes a filesystem path. */
  @GetMapping("/artifacts/{artifactId}")
  public ResponseEntity<Object> getArtifact(@PathVariable("artifactId") String artifactId) {
    Optional<StabilizationArtifact> artifact = ingestionService.getArtifact(artifactId);
    if (artifact.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    Optional<java.nio.file.Path> resolved;
    try {
      resolved = artifactStore.resolve(UUID.fromString(artifact.get().getArtifactId()));
    } catch (IllegalArgumentException e) {
      // The database mapping is expected to contain an opaque UUID. Treat malformed external
      // input or a corrupt legacy row as a missing artifact rather than leaking a 500 response.
      return ResponseEntity.notFound().build();
    }
    if (resolved.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    try {
      java.nio.file.Path path = resolved.get();
      MediaType mediaType =
          MediaType.parseMediaType(
              artifact.get().getMediaType() == null
                  ? "application/octet-stream"
                  : artifact.get().getMediaType());
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(mediaType);
      headers.setContentLength(Files.size(path));
      headers.set("X-Artifact-Checksum", artifact.get().getSha256());
      headers.setContentDispositionFormData(
          "attachment", artifact.get().getArtifactId() + "." + extensionOf(mediaType));
      return new ResponseEntity<>(
          new InputStreamResource(
              Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)),
          headers,
          HttpStatus.OK);
    } catch (IOException e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "artifact read failed"));
    }
  }

  private static String extensionOf(MediaType mediaType) {
    String t = mediaType == null ? "" : mediaType.toString();
    if (t.contains("ndjson")) {
      return "ndjson";
    }
    if (t.contains("json")) {
      return "json";
    }
    if (t.contains("plain")) {
      return "log";
    }
    if (t.contains("csv")) {
      return "csv";
    }
    return "bin";
  }

  @GetMapping("/kpi")
  public ResponseEntity<Map<String, Object>> kpi() {
    return ResponseEntity.ok(ingestionService.computeKpis());
  }

  @GetMapping("/triage")
  public ResponseEntity<Object> openTriage() {
    return ResponseEntity.ok(ingestionService.listOpenTriage());
  }

  @PostMapping("/triage/{id}/resolve")
  public ResponseEntity<Object> resolveTriage(@PathVariable("id") Long id) {
    try {
      return ResponseEntity.ok(ingestionService.resolveTriage(id));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/catalog")
  public ResponseEntity<JsonNode> catalog(
      @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
    return ResponseEntity.ok(catalogService.getCatalog(refresh));
  }
}
