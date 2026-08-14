package com.chromascape.web.debug;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  /**
   * Analyze a specific tick for potential issues.
   *
   * @param sessionId the debug session ID
   * @param tick the tick number to analyze
   * @param contextWindow optional context window size (default: 50)
   * @return tick inspection result
   */
  @GetMapping("/tick/{sessionId}/{tick}")
  public ResponseEntity<AIInspectorService.TickInspectionResult> inspectTick(
      @PathVariable String sessionId,
      @PathVariable long tick,
      @RequestParam(defaultValue = "50") int contextWindow) {
    return ResponseEntity.ok(aiInspectorService.inspectTick(sessionId, tick, contextWindow));
  }
}
