package com.chromascape.web.replay.security;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import org.springframework.stereotype.Service;

/**
 * Centralizes caller identity resolution for every replay API. Controllers no longer carry their
 * own {@code requirePrincipal} copies: a missing or blank principal is a uniform 401 everywhere.
 *
 * <p>Identity arrives via the servlet {@code Principal} argument that Spring MVC resolves per
 * request; this project intentionally has no Spring Security context.
 */
@Service
public class ReplayPrincipalService {

  /** Resolves the caller from the injected principal; 401 when absent. */
  public String requirePrincipal(java.security.Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw unauthorized();
    }
    return principal.getName();
  }

  private static ReplayException unauthorized() {
    return new ReplayException(
        ReplayErrorCode.VALIDATION_ERROR,
        org.springframework.http.HttpStatus.UNAUTHORIZED,
        false,
        "Authentication is required",
        "Sign in before calling replay APIs");
  }
}
