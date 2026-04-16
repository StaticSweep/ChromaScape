package com.chromascape.web.accounttask.controller;

import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
