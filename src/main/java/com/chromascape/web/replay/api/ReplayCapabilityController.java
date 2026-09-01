package com.chromascape.web.replay.api;

import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/replay")
public class ReplayCapabilityController {

  private final ReplayCapabilityService capabilityService;

  public ReplayCapabilityController(ReplayCapabilityService capabilityService) {
    this.capabilityService = capabilityService;
  }

  @GetMapping("/capabilities")
  public ResponseEntity<CapabilityManifest> capabilities() {
    return ResponseEntity.ok(capabilityService.getManifest());
  }
}
