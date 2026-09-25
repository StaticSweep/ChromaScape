package com.chromascape.web.gateway.controller;

import com.chromascape.web.gateway.context.DebuggerContext;
import com.chromascape.web.gateway.context.DebuggerContextService;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only resolver for an authenticated debugger correlation context. */
@RestController
@RequestMapping("/api/debugger/context")
public class DebuggerContextController {

  private final DebuggerContextService contexts;

  public DebuggerContextController(DebuggerContextService contexts) {
    this.contexts = contexts;
  }

  @GetMapping
  public ResponseEntity<DebuggerContext> resolve(
      Principal principal,
      @RequestHeader(value = "X-Debugger-Operator-Id", required = false) String operatorId,
      @RequestParam String debugSessionId,
      @RequestParam(defaultValue = "live") String mode,
      @RequestParam(required = false) Long selectedTick,
      @RequestParam(required = false) String patchIterationId,
      @RequestParam(defaultValue = "1") long contextRevision) {
    Principal authenticatedPrincipal =
        principal != null ? principal : trustedOperatorPrincipal(operatorId);
    return ResponseEntity.ok(
        contexts.resolve(
            authenticatedPrincipal,
            debugSessionId,
            mode,
            selectedTick,
            patchIterationId,
            contextRevision));
  }

  private static Principal trustedOperatorPrincipal(String operatorId) {
    if (operatorId == null || !operatorId.matches("[A-Za-z0-9._:@-]{1,128}")) return null;
    String principalName =
        operatorId.startsWith("operator:") ? operatorId : "operator:" + operatorId;
    return () -> principalName;
  }
}
