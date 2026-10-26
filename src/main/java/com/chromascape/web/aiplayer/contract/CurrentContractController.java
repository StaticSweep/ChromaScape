package com.chromascape.web.aiplayer.contract;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only reviewed expectations; no endpoint here can grant control or assert runtime readiness.
 */
@RestController
public class CurrentContractController {
  private final CurrentContractGate gate;

  public CurrentContractController(CurrentContractGate gate) {
    this.gate = gate;
  }

  @GetMapping("/api/v1/ai-routine/contract")
  public Map<String, Object> contract() {
    return gate.diagnostics();
  }
}
