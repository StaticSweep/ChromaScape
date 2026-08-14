package com.chromascape.web.accounttask.controller;

import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** API surface for stabilization harness ingestion, KPI reporting, and catalog access. */
@RestController
@RequestMapping("/api/account-tasks/stabilization")
public class StabilizationController {
  private final StabilizationIngestionService ingestionService;
  private final Micro6ExecutorCatalogService catalogService;

  public StabilizationController(
      StabilizationIngestionService ingestionService, Micro6ExecutorCatalogService catalogService) {
    this.ingestionService = ingestionService;
    this.catalogService = catalogService;
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
   * The certified ingestion path. Multipart processing occurs only after the stabilization
   * authorization interceptor has approved the write request. Files are bounded here as a second
   * line of defence in addition to Spring's multipart limits.
   */
  @PostMapping(value = "/report-bundles", consumes = "multipart/form-data")
  public ResponseEntity<Object> ingestReportBundle(
      @RequestPart("plan") MultipartFile plan,
      @RequestPart("manifest") MultipartFile manifest,
      @RequestPart("matrix") MultipartFile matrix,
      @RequestPart("transitions") MultipartFile transitions,
      @RequestPart("evidence") MultipartFile evidence,
      @RequestPart("checksums") MultipartFile checksums,
      @RequestPart("completeMarker") MultipartFile completeMarker) {
    try {
      Map<String, MultipartFile> uploaded =
          Map.of(
              "plan",
              plan,
              "manifest",
              manifest,
              "matrix",
              matrix,
              "transitions",
              transitions,
              "evidence",
              evidence,
              "checksums",
              checksums,
              "completeMarker",
              completeMarker);
      Map<String, byte[]> parts = new LinkedHashMap<>();
      for (Map.Entry<String, MultipartFile> entry : uploaded.entrySet()) {
        MultipartFile file = entry.getValue();
        if (file == null || file.isEmpty() || file.getSize() > 10L * 1024L * 1024L) {
          return ResponseEntity.badRequest()
              .body(Map.of("error", "invalid or oversized report artifact: " + entry.getKey()));
        }
        parts.put(entry.getKey(), file.getBytes());
      }
      return ResponseEntity.ok(ingestionService.ingestReportBundle(parts));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "report bundle ingestion failed"));
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

  @GetMapping("/matrices/{matrixHash}")
  public ResponseEntity<Object> getMatrixByMatrixHash(
      @PathVariable("matrixHash") String matrixHash) {
    return ingestionService
        .getMatrixDocumentByHash(matrixHash)
        .map(matrix -> ResponseEntity.ok((Object) matrix))
        .orElseGet(() -> ResponseEntity.notFound().build());
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
