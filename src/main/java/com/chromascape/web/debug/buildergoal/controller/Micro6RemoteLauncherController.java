package com.chromascape.web.debug.buildergoal.controller;

import com.chromascape.web.debug.buildergoal.service.Micro6RemoteLauncherService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class Micro6RemoteLauncherController {
  private final Micro6RemoteLauncherService launcher;

  public Micro6RemoteLauncherController(Micro6RemoteLauncherService launcher) {
    this.launcher = launcher;
  }

  @PostMapping("/api/builder-goal/remote-launcher/heartbeat")
  public Map<String, Object> heartbeat(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody JsonNode payload) {
    if (!launcher.authenticate(authorization)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    try {
      launcher.heartbeat(payload);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
    return Map.of("accepted", true);
  }
}
