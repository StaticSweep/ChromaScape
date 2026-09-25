package com.chromascape.web.replay.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalReplayPrincipalFilterTest {

  @Test
  void localDevelopmentLoopbackReceivesPrincipal() throws Exception {
    LocalReplayPrincipalFilter filter = filterFor("local-development");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/replay/recordings");
    request.setRemoteAddr("127.0.0.1");
    AtomicReference<Principal> observed = new AtomicReference<>();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (servletRequest, servletResponse) ->
            observed.set(((HttpServletRequest) servletRequest).getUserPrincipal()));

    assertEquals(System.getProperty("user.name"), observed.get().getName());
  }

  @Test
  void nonLocalProfileDoesNotCreatePrincipal() throws Exception {
    LocalReplayPrincipalFilter filter = filterFor("production");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/replay/recordings");
    request.setRemoteAddr("127.0.0.1");
    AtomicReference<Principal> observed = new AtomicReference<>();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (servletRequest, servletResponse) ->
            observed.set(((HttpServletRequest) servletRequest).getUserPrincipal()));

    assertNull(observed.get());
  }

  @Test
  void existingPrincipalIsPreserved() throws Exception {
    LocalReplayPrincipalFilter filter = filterFor("local-development");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/replay/recordings");
    request.setRemoteAddr("127.0.0.1");
    request.setUserPrincipal(() -> "authenticated-user");
    AtomicReference<Principal> observed = new AtomicReference<>();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (servletRequest, servletResponse) ->
            observed.set(((HttpServletRequest) servletRequest).getUserPrincipal()));

    assertEquals("authenticated-user", observed.get().getName());
  }

  private static LocalReplayPrincipalFilter filterFor(String profile) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.setActiveProfiles(profile);
    return new LocalReplayPrincipalFilter(environment);
  }
}
