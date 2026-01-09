package com.chromascape.web.debug;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for AI inspector diagnostics. */
@RestController
@RequestMapping("/api/debug/ai-inspector")
public class AIInspectorController {

  private final AIInspectorService aiInspectorService;

  @Autowired
  public AIInspectorController(AIInspectorService aiInspectorService) {
    this.aiInspectorService = aiInspectorService;
  }

  /** Returns the latest inspection result. */
  @GetMapping("/latest")
  public ResponseEntity<AIInspectorService.InspectionResult> latest() {
    return ResponseEntity.ok(aiInspectorService.analyze());
  }
}
