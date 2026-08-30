package com.chromascape.web.gateway.controller;

import com.chromascape.web.gateway.dto.DebuggerServiceCapabilities;
import com.chromascape.web.gateway.service.CapabilityManifestService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only service capability endpoint consumed by same-origin dashboard gateways. */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityManifestController {

  private final CapabilityManifestService capabilityManifestService;

  public CapabilityManifestController(CapabilityManifestService capabilityManifestService) {
    this.capabilityManifestService = capabilityManifestService;
  }

  @GetMapping
  public ResponseEntity<DebuggerServiceCapabilities> getCapabilities() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(capabilityManifestService.current());
  }
}
