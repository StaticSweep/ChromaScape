package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Protects state-changing external-video requests. A mutation must be same-origin and must carry a
 * server-issued write nonce bound to the authenticated owner; cross-origin browser requests are
 * rejected outright and no permissive CORS policy is installed for this API.
 */
@Component
public class MouseVideoRequestGuard {

  private final MouseVideoWriteNonceService nonces;

  public MouseVideoRequestGuard(MouseVideoWriteNonceService nonces) {
    this.nonces = nonces;
  }

  public void requireWrite(HttpServletRequest request, MouseVideoAccess.Actor actor) {
    requireSameOrigin(request);
    nonces.verify(actor.owner(), request.getHeader(MouseVideoWriteNonceService.HEADER));
  }

  public void requireSameOrigin(HttpServletRequest request) {
    String fetchSite = request.getHeader("Sec-Fetch-Site");
    if (fetchSite != null && !fetchSite.isBlank()) {
      String normalized = fetchSite.trim().toLowerCase(Locale.ROOT);
      if (!"same-origin".equals(normalized) && !"none".equals(normalized)) {
        throw new MouseVideoException(
            MouseVideoException.CODE_FORBIDDEN, "Cross-origin browser requests are not permitted");
      }
    }
    String origin = request.getHeader("Origin");
    if (origin == null || origin.isBlank()) {
      return;
    }
    if (!originMatches(origin, request)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_FORBIDDEN, "Cross-origin browser requests are not permitted");
    }
  }

  private static boolean originMatches(String origin, HttpServletRequest request) {
    URI uri;
    try {
      uri = new URI(origin.trim());
    } catch (URISyntaxException malformed) {
      return false;
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      return false;
    }
    String requestScheme = request.getScheme();
    String requestHost = request.getServerName();
    int requestPort = request.getServerPort();
    if (!uri.getScheme().equalsIgnoreCase(requestScheme)) {
      return false;
    }
    if (!uri.getHost().equalsIgnoreCase(requestHost)) {
      return false;
    }
    int originPort = uri.getPort() < 0 ? defaultPort(uri.getScheme()) : uri.getPort();
    return originPort == requestPort;
  }

  private static int defaultPort(String scheme) {
    return "https".equalsIgnoreCase(scheme) ? 443 : 80;
  }
}
