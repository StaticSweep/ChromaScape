package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import java.security.Principal;
import org.springframework.stereotype.Component;

/**
 * Resolves the owning actor for external-video requests. Ownership and the audit actor are derived
 * exclusively from a server-established servlet {@link Principal}; caller headers never
 * authenticate and there is no anonymous or local-operator fallback. A missing principal is refused
 * before any request body is consumed.
 */
@Component
public class MouseVideoAccess {

  public static final int MAX_LABEL = 160;

  /** Authenticated owner plus the audit actor recorded on review revisions. */
  public record Actor(String owner, String auditActor) {}

  public Actor require(Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_UNAUTHENTICATED,
          "An authenticated principal is required for external video operations");
    }
    String owner = clamp(principal.getName());
    return new Actor(owner, owner);
  }

  private static String clamp(String value) {
    String trimmed = value.trim();
    return trimmed.length() <= MAX_LABEL ? trimmed : trimmed.substring(0, MAX_LABEL);
  }
}
