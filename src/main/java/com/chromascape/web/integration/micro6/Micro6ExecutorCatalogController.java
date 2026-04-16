package com.chromascape.web.integration.micro6;

import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes Micro6 local executor catalog to dashboard and backend callers. */
@RestController
@RequestMapping("/api/micro6")
public class Micro6ExecutorCatalogController {
  private final Micro6ExecutorCatalogService catalogService;

  public Micro6ExecutorCatalogController(Micro6ExecutorCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @GetMapping("/executor/catalog")
  public ResponseEntity<JsonNode> getCatalog(
      @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
    return ResponseEntity.ok(catalogService.getCatalog(refresh));
  }
}
