package com.chromascape.web.aiplayer.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator mutations use the existing /api/v1/ai-routine Bearer interceptor. */
@RestController
@RequestMapping("/api/v1/ai-routine")
public class AiPlayerCatalogController {
  private final AiPlayerCatalogService service;
  private final CatalogStore store;

  /** Creates this component with its explicit dependencies or bounded failure details. */
  public AiPlayerCatalogController(AiPlayerCatalogService service, CatalogStore store) {
    this.service = service;
    this.store = store;
  }

  /** Returns lightweight metadata for an authenticated endpoint reference. */
  @GetMapping("/catalog")
  public ObjectNode catalog(
      @RequestParam String endpointRef, @RequestParam(defaultValue = "false") boolean refresh) {
    return service.catalog(endpointRef, refresh);
  }

  /** Returns the selected canonical task and its lazy-loaded settings schema. */
  @GetMapping("/catalog/tasks/{taskKey}")
  public ObjectNode task(
      @PathVariable String taskKey,
      @RequestParam String endpointRef,
      @RequestParam(required = false) String catalogHash,
      @RequestParam(defaultValue = "false") boolean refresh) {
    return service.detail(endpointRef, taskKey, catalogHash, refresh);
  }

  /** Lists named saved profile versions in reverse creation order. */
  @GetMapping("/profiles")
  public Map<String, Object> profiles() {
    return Map.of("profiles", store.profiles());
  }

  /** Returns an exact historical profile version with its retained snapshots. */
  @GetMapping("/profiles/{profileId}/versions/{version}")
  public ObjectNode profile(@PathVariable String profileId, @PathVariable int version) {
    return store.profile(profileId, version);
  }

  /** Validates the current schema and settings before appending an immutable version. */
  @PostMapping("/profiles")
  public ResponseEntity<ObjectNode> save(@RequestBody JsonNode request) {
    return ResponseEntity.status(201).body(service.save(request));
  }

  /** Returns stable field-level validation failures without upstream data. */
  @ExceptionHandler(CatalogProblem.class)
  public ResponseEntity<Map<String, Object>> problem(CatalogProblem problem) {
    return ResponseEntity.status(problem.status())
        .body(
            Map.of(
                "code",
                problem.code(),
                "message",
                problem.getMessage(),
                "fieldErrors",
                problem.errors()));
  }
}
