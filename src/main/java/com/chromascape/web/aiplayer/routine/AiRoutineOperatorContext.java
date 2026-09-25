package com.chromascape.web.aiplayer.routine;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Request-scoped operator identity set by {@code AiRoutineOperatorInterceptor} after a verified
 * Bearer token is accepted. The operator ID is the auditable identity used for approval hashes and
 * audit events; it never comes from a servlet {@code Principal}.
 */
public final class AiRoutineOperatorContext {

  /** Request attribute holding the configured operator ID. */
  public static final String OPERATOR_ID_ATTRIBUTE = "aiRoutineOperatorId";

  private AiRoutineOperatorContext() {}

  /**
   * Reads the verified operator ID for the current request, or {@code null} when unauthenticated.
   */
  public static String operatorId() {
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return null;
    }
    HttpServletRequest request = attributes.getRequest();
    Object value = request == null ? null : request.getAttribute(OPERATOR_ID_ATTRIBUTE);
    return value == null ? null : String.valueOf(value);
  }
}
