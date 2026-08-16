package com.chromascape.web.aiplayer.routine.config;

import com.chromascape.web.aiplayer.routine.AiRoutineOperatorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Requires a constant-time-verified {@code Authorization: Bearer <token>} on the candidate workflow
 * endpoints and places the configured operator ID in the request context. A missing, malformed, or
 * wrong token is rejected with 401 before any handler runs.
 */
@Component
public class AiRoutineOperatorInterceptor implements HandlerInterceptor {

  private final AiRoutineOperatorProperties properties;

  public AiRoutineOperatorInterceptor(AiRoutineOperatorProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    // Read-only GET/HEAD/OPTIONS endpoints remain unauthenticated; only human AI-routine mutations
    // require the operator token. Capture endpoints are excluded at registration and keep their
    // separate capture token.
    String method = request.getMethod();
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
        || "OPTIONS".equalsIgnoreCase(method)) {
      return true;
    }
    String expected = properties.getToken();
    if (expected == null || expected.isBlank()) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"OPERATOR_AUTH_NOT_CONFIGURED\",\"message\":\"ai-routine.operator-token is not configured\"}");
      return false;
    }
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      reject(response);
      return false;
    }
    String supplied = authorization.substring("Bearer ".length()).trim();
    if (!constantTimeEquals(expected, supplied)) {
      reject(response);
      return false;
    }
    request.setAttribute(AiRoutineOperatorContext.OPERATOR_ID_ATTRIBUTE, properties.getId());
    return true;
  }

  private static void reject(HttpServletResponse response) throws Exception {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType("application/json");
    response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"operator bearer token is invalid\"}");
  }

  private static boolean constantTimeEquals(String expected, String supplied) {
    byte[] a = expected.getBytes(StandardCharsets.UTF_8);
    byte[] b = supplied.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(a, b);
  }
}
