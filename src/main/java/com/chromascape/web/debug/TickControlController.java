package com.chromascape.web.debug;

import com.chromascape.utils.core.statistics.StatisticsManager;
import com.chromascape.web.instance.ScriptInstance;
import com.chromascape.web.instance.ScriptInstanceManager;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for exposing tick state and simple replay controls. */
@RestController
@RequestMapping("/api/debug/tick")
public class TickControlController {

  @GetMapping("/current")
  public ResponseEntity<Map<String, Object>> getCurrentTick() {
    ScriptInstance instance = ScriptInstanceManager.getInstance().getInstanceRef();
    Map<String, Object> response = new HashMap<>();
    response.put("tick", StatisticsManager.getCycles());
    response.put("running", instance != null && instance.isRunning());
    return ResponseEntity.ok(response);
  }

  @GetMapping("/info")
  public ResponseEntity<Map<String, Object>> getTickInfo() {
    long elapsed = StatisticsManager.getElapsedTime();
    int cycles = StatisticsManager.getCycles();
    double fps = elapsed > 0 ? (cycles * 1000.0) / elapsed : 0.0;

    Map<String, Object> response = new HashMap<>();
    response.put("tick", cycles);
    response.put("fps", fps);
    response.put("timestamp", System.currentTimeMillis());
    response.put("elapsedMs", elapsed);
    return ResponseEntity.ok(response);
  }

  @PostMapping("/jump")
  public ResponseEntity<Map<String, Object>> jumpToTick(@RequestBody TickJumpRequest request) {
    Map<String, Object> response = new HashMap<>();
    response.put("requestedTick", request.tick());
    response.put("status", "accepted");
    return ResponseEntity.ok(response);
  }

  /** Request body for tick jump. */
  public record TickJumpRequest(int tick) {}
}
