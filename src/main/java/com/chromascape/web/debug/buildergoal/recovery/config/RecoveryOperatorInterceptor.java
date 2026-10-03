package com.chromascape.web.debug.buildergoal.recovery.config;

import com.chromascape.web.aiplayer.routine.config.AiRoutineOperatorProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires a constant-time-verified {@code Authorization: Bearer <operator-token>} on every Builder
 * Goal compile-recovery mutation. The operator identity is resolved server-side and never accepted
 * from request JSON; GET endpoints keep the debugger's authenticated read policy.
 */
@Component
public class RecoveryOperatorInterceptor implements HandlerInterceptor {

  private final AiRoutineOperatorProperties properties;

  public RecoveryOperatorInterceptor(AiRoutineOperatorProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String method = request.getMethod();
    if ("GET".equalsIgnoreCase(method)
        || "HEAD".equalsIgnoreCase(method)
        || "OPTIONS".equalsIgnoreCase(method)) {
      return true;
    }
    String expected = properties.getToken();
    if (expected == null || expected.isBlank()) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      response.setContentType("application/json");
      response
          .getWriter()
          .write(
              "{\"code\":\"OPERATOR_AUTH_NOT_CONFIGURED\",\"message\":"
                  + "\"builder-goal recovery operator token is not configured\"}");
      return false;
    }
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      reject(response);
      return false;
    }
    String supplied = authorization.substring("Bearer ".length()).trim();
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
      reject(response);
      return false;
    }
    request.setAttribute(RecoveryOperatorContext.OPERATOR_ID_ATTRIBUTE, properties.getId());
    request.setAttribute(
        RecoveryOperatorContext.OPERATOR_ROLE_ATTRIBUTE, RecoveryOperatorContext.ROLE_OPERATOR);
    return true;
  }

  private static void reject(HttpServletResponse response) throws Exception {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType("application/json");
    response
        .getWriter()
        .write("{\"code\":\"UNAUTHORIZED\",\"message\":\"operator bearer token is invalid\"}");
  }
}
