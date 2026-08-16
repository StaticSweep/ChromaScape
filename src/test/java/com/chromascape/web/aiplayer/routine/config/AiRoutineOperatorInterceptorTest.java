package com.chromascape.web.aiplayer.routine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.AiRoutineOperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class AiRoutineOperatorInterceptorTest {

  private AiRoutineOperatorProperties properties(String token, String id) {
    AiRoutineOperatorProperties props = new AiRoutineOperatorProperties();
    props.setToken(token);
    props.setId(id);
    return props;
  }

  @Test
  void validTokenPlacesConfiguredOperatorIdInRequest() throws Exception {
    AiRoutineOperatorInterceptor interceptor =
        new AiRoutineOperatorInterceptor(properties("op-secret", "operator-42"));
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer op-secret");

    assertTrue(interceptor.preHandle(request, response, null));
    verify(request).setAttribute(AiRoutineOperatorContext.OPERATOR_ID_ATTRIBUTE, "operator-42");
  }

  @Test
  void wrongTokenIsRejectedWith401() throws Exception {
    AiRoutineOperatorInterceptor interceptor =
        new AiRoutineOperatorInterceptor(properties("op-secret", "operator-42"));
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter writer = new StringWriter();
    when(request.getHeader("Authorization")).thenReturn("Bearer wrong");
    when(response.getWriter()).thenReturn(new PrintWriter(writer));

    assertFalse(interceptor.preHandle(request, response, null));
    verify(response).setStatus(401);
  }

  @Test
  void missingAuthorizationIsRejectedWith401() throws Exception {
    AiRoutineOperatorInterceptor interceptor =
        new AiRoutineOperatorInterceptor(properties("op-secret", "operator-42"));
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter writer = new StringWriter();
    when(request.getHeader("Authorization")).thenReturn(null);
    when(response.getWriter()).thenReturn(new PrintWriter(writer));

    assertFalse(interceptor.preHandle(request, response, null));
    verify(response).setStatus(401);
  }

  @Test
  void unconfiguredTokenFailsClosedWith503() throws Exception {
    AiRoutineOperatorInterceptor interceptor =
        new AiRoutineOperatorInterceptor(properties("", "operator-42"));
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter writer = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(writer));

    assertFalse(interceptor.preHandle(request, response, null));
    verify(response).setStatus(503);
    assertEquals("operator", properties("", "operator").getId());
  }

  @Test
  void readOnlyGetRequestsRemainUnauthenticated() throws Exception {
    // Read-only GET/HEAD/OPTIONS AI-routine endpoints must not require the operator token even when
    // the token is unconfigured; only human mutations are authenticated.
    AiRoutineOperatorInterceptor interceptor =
        new AiRoutineOperatorInterceptor(properties("", "operator-42"));
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getMethod()).thenReturn("GET");

    assertTrue(interceptor.preHandle(request, response, null),
        "GET endpoints must remain unauthenticated");
  }

  @Test
  void mutationRequiresOperatorToken() throws Exception {
    AiRoutineOperatorInterceptor interceptor =
        new AiRoutineOperatorInterceptor(properties("op-secret", "operator-42"));
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter writer = new StringWriter();
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeader("Authorization")).thenReturn("Bearer wrong");
    when(response.getWriter()).thenReturn(new PrintWriter(writer));

    assertFalse(interceptor.preHandle(request, response, null),
        "mutations without the operator token must be rejected");
    verify(response).setStatus(401);
  }
}
