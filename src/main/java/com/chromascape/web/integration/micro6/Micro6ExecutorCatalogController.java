package com.chromascape.web.integration.micro6;

import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes authenticated Micro6 executor selection and catalog access to the debugger. */
@RestController
@RequestMapping("/api/micro6")
public class Micro6ExecutorCatalogController {
  private final Micro6ExecutorCatalogService catalogService;

  public Micro6ExecutorCatalogController(Micro6ExecutorCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  /** Lists authenticated Micro6 executor candidates with safe identity and state. */
  @GetMapping("/executors")
  public ResponseEntity<JsonNode> getExecutors() {
    return ResponseEntity.ok(catalogService.getExecutors());
  }

  /** Fetches a lightweight or full catalog for a resolved authenticated executor. */
  @GetMapping("/executors/{endpointRef}/catalog")
  public ResponseEntity<JsonNode> getExecutorCatalog(
      @PathVariable("endpointRef") String endpointRef,
      @RequestParam(name = "mode", defaultValue = "lightweight") String mode,
      @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
    return ResponseEntity.ok(catalogService.getCatalogForExecutor(endpointRef, mode, refresh));
  }

  /** Legacy fixed-URL catalog endpoint (development fallback only). */
  @GetMapping("/executor/catalog")
  public ResponseEntity<JsonNode> getCatalog(
      @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
    return ResponseEntity.ok(catalogService.getCatalog(refresh));
  }
}
